// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.importing

import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.HttpClientKind
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.data.fetch.FOLLOWABLE_STATUSES
import ch.lkmc.neutrodyne.core.data.fetch.FeedHttpHeaders
import ch.lkmc.neutrodyne.core.data.fetch.MAX_REDIRECT_FOLLOWUPS
import ch.lkmc.neutrodyne.core.domain.ImportError
import ch.lkmc.neutrodyne.core.model.ImportSource
import ch.lkmc.neutrodyne.core.model.NetError
import ch.lkmc.neutrodyne.core.network.NetErrorClassifier
import ch.lkmc.neutrodyne.core.network.NeutrodyneHttpClients
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import dev.zacsweers.metro.Inject
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.FileSystem
import okio.ForwardingSource
import okio.Path
import okio.Path.Companion.toPath
import okio.Source
import okio.buffer
import kotlin.random.Random

/**
 * Payload staging for import sessions (05 OPML import §1). Every source is copied into
 * `<cache>/import/` before sniffing — the provider's content lives only as long as the caller's
 * URI grant, and the same file is re-opened per parse pass — through a counting stream that
 * aborts past [MAX_PAYLOAD_BYTES] because declared sizes lie.
 *
 * [acquire] runs on [io] inside the caller's coroutine, so an Android grant stays valid; the
 * sniff/parse/persist half is `ImportSessions`'s application-scope job. [publish] runs inside
 * the session's write transaction so a failed insert never strands the renamed payload.
 */
@Inject
internal class PayloadStore(
    storagePaths: StoragePaths,
    private val fileSystem: FileSystem,
    private val payloads: PayloadSource,
    private val httpClients: NeutrodyneHttpClients,
    private val netErrors: NetErrorClassifier,
    @Dispatcher(NeutrodyneDispatchers.IO) private val io: CoroutineDispatcher,
) {
    /** A copied payload parked at `<cache>/import/incoming-*.tmp`, awaiting a session id. */
    class Staged
        internal constructor(
            internal val path: Path,
            /** `OpenableColumns.DISPLAY_NAME` / file name learned at acquire; feeds `sourceName`. */
            val displayName: String?,
        )

    private val cacheDir: Path = storagePaths.cacheDir.toPath()
    private val dir: Path = cacheDir / IMPORT_DIR
    private val client = httpClients.client(HttpClientKind.FEED)

    /**
     * Copies [source]'s payload to a staged file. `http(s)://` goes through the feed client
     * (03's timeouts, no credentials; `LocalNetworkGuardDns` applies on Android) following a
     * bounded, validated redirect chain; everything else through the platform [PayloadSource].
     */
    suspend fun acquire(source: ImportSource): Outcome<Staged, ImportError> =
        withContext(io) {
            val uri = source.uri
            when {
                uri.startsWith("http://") || uri.startsWith("https://") -> fetch(uri, source.displayName)
                else -> copyFrom(uri, source.displayName)
            }
        }

    /**
     * 05 §1 step 6: an app-private file (the auto-snapshot, `files/backup/auto-snapshot.zip`)
     * is copied the same counted way. `ImportSource` never points at app-private files, so this
     * takes a [Path] instead.
     */
    suspend fun adopt(
        path: Path,
        displayName: String? = null,
    ): Outcome<Staged, ImportError> =
        withContext(io) {
            val meta =
                try {
                    fileSystem.metadataOrNull(path)
                } catch (_: Exception) {
                    null
                }
            if (meta?.isRegularFile != true) return@withContext Outcome.Failure(ImportError.Unreadable)
            val size = meta.size
            if (size != null && size > MAX_PAYLOAD_BYTES) {
                return@withContext Outcome.Failure(ImportError.TooLarge)
            }
            stage({ fileSystem.source(path) }, displayName ?: path.name)
        }

    /**
     * 05 §1 step 3: the staged payload becomes `import/{sessionId}.bin`; the returned
     * cache-relative string is what `import_session.payloadPath` stores. Runs inside the
     * session's write transaction — a rolled-back insert must not strand the renamed file, so
     * the caller sweeps [payloadFile] of its result on failure.
     */
    fun publish(
        staged: Staged,
        sessionId: Long,
    ): String {
        val name = "$sessionId$PAYLOAD_SUFFIX"
        fileSystem.createDirectories(dir)
        fileSystem.atomicMove(staged.path, dir / name)
        return "$IMPORT_DIR/$name"
    }

    /** The file a session's `payloadPath` (cache-relative) resolves to. */
    fun payloadFile(payloadPath: String): Path = cacheDir / payloadPath

    /** Fresh read over a staged or published payload; every parse pass re-opens it. */
    fun open(path: Path): Source = fileSystem.source(path)

    /** Best-effort removal — a vanished cache file is not an error. */
    fun delete(path: Path) {
        try {
            fileSystem.delete(path)
        } catch (_: Exception) {
            // The next sweep or a later delete retries.
        }
    }

    private suspend fun copyFrom(
        uri: String,
        fallbackName: String?,
    ): Outcome<Staged, ImportError> {
        val opened =
            when (val result = payloads.open(uri)) {
                is Outcome.Failure -> return result
                is Outcome.Success -> result.value
            }
        val declared = opened.declaredSize
        if (declared != null && declared > MAX_PAYLOAD_BYTES) {
            return Outcome.Failure(ImportError.TooLarge)
        }
        return stage(opened.open, opened.displayName ?: fallbackName)
    }

    /**
     * The `http(s)` acquire path (05 §1 step 2). The FEED client's own redirect support is off
     * (03), so the chain is followed by hand: every resolved `Location` must stay a
     * credential-free http(s) target inside [MAX_REDIRECT_FOLLOWUPS] follow-ups and must not
     * revisit a URL — a 3xx whose `Location` is missing or unusable is the final response.
     * Only the final response's body is streamed; the 50 MiB cap applies to it alone.
     */
    private suspend fun fetch(
        uri: String,
        displayName: String?,
    ): Outcome<Staged, ImportError> {
        val temp = tempPath() ?: return Outcome.Failure(ImportError.Storage)
        var url = uri
        val visited = mutableSetOf(uri)
        var followups = 0
        while (true) {
            val hop =
                try {
                    oneRequest(url, temp)
                } catch (e: CancellationException) {
                    delete(temp)
                    throw e
                } catch (e: Exception) {
                    delete(temp)
                    return Outcome.Failure(ImportError.Network(netErrors.classify(e)))
                }
            if (hop is Hop.Final) {
                if (hop.outcome is Outcome.Failure) delete(temp)
                return when (hop.outcome) {
                    is Outcome.Failure -> hop.outcome
                    is Outcome.Success -> Outcome.Success(Staged(temp, displayName))
                }
            }
            val redirect = hop as Hop.Redirect
            val next =
                FeedHttpHeaders
                    .resolveLocation(url, redirect.location)
                    ?.takeIf(::isFollowableTarget)
            if (next == null) {
                delete(temp)
                return Outcome.Failure(
                    ImportError.Network(NetError.Other("http_${redirect.status}")),
                )
            }
            followups += 1
            if (followups > MAX_REDIRECT_FOLLOWUPS || !visited.add(next)) {
                delete(temp)
                return Outcome.Failure(
                    ImportError.Network(NetError.Other("redirect_loop")),
                )
            }
            url = next
        }
    }

    /** One request of the chain; [Hop.Redirect] only for a followable-status 3xx with `Location`. */
    private suspend fun oneRequest(
        url: String,
        temp: Path,
    ): Hop =
        client.prepareGet(url).execute { response ->
            val status = response.status.value
            val location = response.headers[FeedHttpHeaders.LOCATION]
            when {
                status in FOLLOWABLE_STATUSES && location != null -> {
                    response.discardBody()
                    Hop.Redirect(status, location)
                }

                !response.status.isSuccess() -> {
                    response.discardBody()
                    Hop.Final(
                        Outcome.Failure(ImportError.Network(NetError.Other("http_$status"))),
                    )
                }

                (response.contentLength() ?: -1L) > MAX_PAYLOAD_BYTES -> {
                    response.discardBody()
                    Hop.Final(Outcome.Failure(ImportError.TooLarge))
                }

                else -> {
                    Hop.Final(copyChannel(response.bodyAsChannel(), temp))
                }
            }
        }

    /** A redirect target stays http(s) and carries no embedded credentials (03 Request rules). */
    private fun isFollowableTarget(url: String): Boolean =
        (url.startsWith("http://") || url.startsWith("https://")) &&
            UrlNormalizer.splitUserInfo(url).second == null

    /**
     * Drains a body the chain does not stream (redirects, errors) so the connection frees
     * promptly; bounded — an error body can legally be unbounded and its bytes are never used.
     */
    private suspend fun HttpResponse.discardBody() {
        val channel = bodyAsChannel()
        val chunk = ByteArray(CHUNK_BYTES)
        var remaining = DISCARD_MAX_BYTES
        while (remaining > 0 && !channel.isClosedForRead) {
            val read = channel.readAvailable(chunk, 0, minOf(chunk.size.toLong(), remaining).toInt())
            if (read == -1) break
            if (read == 0) {
                channel.awaitContent()
                continue
            }
            remaining -= read
        }
        if (remaining <= 0) channel.cancel(null)
    }

    /** The outcome of one request: a followable 3xx, or the final response reduced to a result. */
    private sealed interface Hop {
        data class Redirect(
            val status: Int,
            val location: String,
        ) : Hop

        data class Final(
            val outcome: Outcome<Unit, ImportError>,
        ) : Hop
    }

    private fun stage(
        open: () -> Source,
        displayName: String?,
    ): Outcome<Staged, ImportError> {
        val temp = tempPath() ?: return Outcome.Failure(ImportError.Storage)
        val copied = copyCounted(open, temp)
        if (copied is Outcome.Failure) {
            delete(temp)
            return copied
        }
        return Outcome.Success(Staged(temp, displayName))
    }

    /**
     * Counted copy of one provider stream to [temp]. Read-side failures are [ImportError.Unreadable]
     * (05 §1 step 4), write-side ones [ImportError.Storage]; [CountingSource] aborts past the cap.
     */
    private fun copyCounted(
        open: () -> Source,
        temp: Path,
    ): Outcome<Unit, ImportError> {
        val source =
            try {
                open()
            } catch (_: Exception) {
                // "Couldn't open this file" surfaces at the provider's open (05 §1 step 4).
                return Outcome.Failure(ImportError.Unreadable)
            }
        try {
            source.use { src ->
                fileSystem.sink(temp).buffer().use { sink ->
                    val counting = CountingSource(src).buffer()
                    val chunk = Buffer()
                    while (true) {
                        val read =
                            try {
                                counting.read(chunk, CHUNK_BYTES.toLong())
                            } catch (_: PayloadTooLarge) {
                                return Outcome.Failure(ImportError.TooLarge)
                            } catch (_: Exception) {
                                // A provider can fail mid-read (grant revoked, stream cut).
                                return Outcome.Failure(ImportError.Unreadable)
                            }
                        if (read == -1L) break
                        try {
                            sink.write(chunk, read)
                        } catch (_: Exception) {
                            return Outcome.Failure(ImportError.Storage)
                        }
                    }
                    sink.flush()
                }
            }
        } catch (_: Exception) {
            // What is left to fail here: opening the temp file or closing either stream.
            return Outcome.Failure(ImportError.Storage)
        }
        return Outcome.Success(Unit)
    }

    /**
     * The `http(s)` half of the counted copy (05 §1 step 2), shaped after `FeedFetcher`'s
     * body streaming: channel reads map through [netErrors], writes are storage.
     */
    private suspend fun copyChannel(
        channel: ByteReadChannel,
        temp: Path,
    ): Outcome<Unit, ImportError> {
        val sink =
            try {
                fileSystem.sink(temp).buffer()
            } catch (_: Exception) {
                return Outcome.Failure(ImportError.Storage)
            }
        var failure: ImportError? = null
        try {
            val chunk = ByteArray(CHUNK_BYTES)
            var total = 0L
            while (failure == null) {
                val read =
                    try {
                        channel.readAvailable(chunk, 0, chunk.size)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failure = ImportError.Network(netErrors.classify(e))
                        break
                    }
                if (read == -1) break
                if (read == 0) {
                    try {
                        channel.awaitContent()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failure = ImportError.Network(netErrors.classify(e))
                    }
                    continue
                }
                total += read
                if (total > MAX_PAYLOAD_BYTES) {
                    failure = ImportError.TooLarge
                    break
                }
                try {
                    sink.write(chunk, 0, read)
                } catch (_: Exception) {
                    failure = ImportError.Storage
                    break
                }
            }
            try {
                sink.flush()
            } catch (_: Exception) {
                if (failure == null) failure = ImportError.Storage
            }
        } finally {
            try {
                sink.close()
            } catch (_: Exception) {
                // A close failure after a recorded error is not a second error.
            }
        }
        return failure?.let { Outcome.Failure(it) } ?: Outcome.Success(Unit)
    }

    /** `<cache>/import/incoming-*.tmp`; null when the staging directory cannot be created. */
    private fun tempPath(): Path? {
        try {
            fileSystem.createDirectories(dir)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        return dir / "incoming-${Random.nextBits(32).toULong().toString(16)}.tmp"
    }

    internal companion object {
        const val IMPORT_DIR = "import"
        const val PAYLOAD_SUFFIX = ".bin"
        const val CHUNK_BYTES = 16 * 1024

        /** Redirect/error bodies are discarded after this many bytes (same rule as `FeedFetcher`). */
        const val DISCARD_MAX_BYTES = 256L * 1024

        /** 05 §1: the import payload cap — declared or counted, whichever trips first. */
        const val MAX_PAYLOAD_BYTES = 50L * 1024 * 1024
    }
}

/** Counts bytes read and fails once the import payload cap is crossed (05 §1: sizes lie). */
private class CountingSource(
    delegate: Source,
) : ForwardingSource(delegate) {
    private var total = 0L

    override fun read(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        val read = super.read(sink, byteCount)
        if (read > 0) {
            total += read
            if (total > PayloadStore.MAX_PAYLOAD_BYTES) throw PayloadTooLarge()
        }
        return read
    }
}

private class PayloadTooLarge : Exception()
