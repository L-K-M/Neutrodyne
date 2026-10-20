// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.HttpClientKind
import ch.lkmc.neutrodyne.core.common.PublisherAuthPolicy
import ch.lkmc.neutrodyne.core.database.DownloadEntity
import ch.lkmc.neutrodyne.core.database.DownloadSourceQueries
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.DownloadError
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.ExternalReason
import ch.lkmc.neutrodyne.core.model.SourceKind
import ch.lkmc.neutrodyne.core.model.WaitReason
import ch.lkmc.neutrodyne.core.model.settings.YouTubeSettingKeys
import ch.lkmc.neutrodyne.core.network.NeutrodyneHttpClients
import ch.lkmc.neutrodyne.core.network.publisherAuth
import ch.lkmc.neutrodyne.youtube.api.AudioPref
import ch.lkmc.neutrodyne.youtube.api.AudioQuality
import ch.lkmc.neutrodyne.youtube.api.ExtractionGate
import ch.lkmc.neutrodyne.youtube.api.ExtractionOutcome
import ch.lkmc.neutrodyne.youtube.api.PrewarmReason
import ch.lkmc.neutrodyne.youtube.api.ResolvePurpose
import ch.lkmc.neutrodyne.youtube.api.ResolveResult
import ch.lkmc.neutrodyne.youtube.api.ResolvedAudio
import ch.lkmc.neutrodyne.youtube.api.TransientKind
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.youtube.api.YouTubeEngine
import ch.lkmc.neutrodyne.youtube.api.YouTubeHealth
import ch.lkmc.neutrodyne.youtube.api.YouTubeRepresentationKey
import ch.lkmc.neutrodyne.youtube.api.YouTubeStreamResolver
import ch.lkmc.neutrodyne.youtube.api.YouTubeTransferSnapshot
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okio.IOException
import kotlin.random.Random

/** Uses the one DOWNLOAD client for bytes and the existing resolver owner for every chunk URL. */
internal class YouTubeTransferSource(
    clients: NeutrodyneHttpClients,
    private val sources: DownloadSourceQueries,
    private val settings: SettingsRepository,
    private val language: () -> String,
    private val streams: YouTubeStreamResolver,
    private val control: YouTubeEngine,
    private val capabilities: YouTubeCapabilitiesSource,
    private val health: YouTubeHealth,
    private val pacer: YouTubeAutoPacer,
    private val roots: StorageRoots,
    private val files: DownloadFileSystem,
    private val verification: DownloadFiles,
    private val clock: Clock,
    private val jitterMs: () -> Long = { Random.nextLong(500, 2_001) },
) : TransferSource {
    override val kind = SourceKind.YOUTUBE
    private val client = clients.client(HttpClientKind.DOWNLOAD)

    override suspend fun prepare(
        row: DownloadEntity,
        part: PartFile,
        attempt: TransferAttempt,
    ): Prepared {
        external()?.let { return it }
        val source =
            sources.downloadSources(listOf(row.episodeId)).singleOrNull()
                ?: return Prepared.Fail(DownloadError.HTTP_NOT_FOUND, null)
        val identity = source.youtubeIdentity()
        if (identity == null || !source.matches(row) || (attempt.identity != null && attempt.identity != identity)) {
            return Prepared.Fail(DownloadError.UNSUPPORTED_STREAM, null)
        }
        attempt.identity = identity
        if (attempt.pref == null) {
            // The first durable emission, never the warm-up StateFlow defaults, owns this attempt.
            val snapshot = settings.observePortableSnapshot().first()
            val savedQuality = snapshot[YouTubeSettingKeys.AUDIO_QUALITY.name] as? AudioQuality
            val levelling = snapshot[YouTubeSettingKeys.VOLUME_LEVELLING.name] as? Boolean
            attempt.pref =
                AudioPref(
                    quality =
                        AudioQuality.entries.firstOrNull { it.name == row.formatPref }
                            ?: savedQuality ?: AudioQuality.STANDARD,
                    preferDrc = levelling ?: YouTubeSettingKeys.VOLUME_LEVELLING.default,
                    preferredLanguage = language(),
                )
        }
        gate()?.let { return it }
        if (!attempt.admitted) {
            if (row.lane == DownloadLane.AUTO) {
                pacer.admit()?.let {
                    return Prepared.Wait(WaitReason.BACKOFF, null, it, false)
                }
            }
            attempt.admitted = true
            control.prewarm(PrewarmReason.DOWNLOAD)
        }
        val result =
            streams.resolveAudio(
                identity.videoId,
                checkNotNull(attempt.youtube?.pref ?: attempt.pref),
                ResolvePurpose.BACKGROUND,
            )
        val audio = (result as? ResolveResult.Ok)?.audio ?: return outcome(result, attempt)
        readiness(audio)?.let { return it }
        val previous = attempt.youtube
        val next = snapshot(audio, checkNotNull(attempt.pref))
        attempt.youtube = next
        var failureRoot = part.rootId
        try {
            val offset = if (roots.resolve(part.rootId) == null) 0 else part.length()
            val changed = previous != null && previous != next
            val proof =
                row.resolvedItag == audio.itag &&
                    row.totalBytes != null && row.totalBytes == audio.contentLength &&
                    audio.lastModifiedMicros?.takeIf { it > 0 }?.let { row.lastModified == it.toString() } == true
            if (offset > 0 && (changed || (previous == null && !proof))) part.truncate(0)
            if (row.lastStopReason == DownloadStopReasons.PROCESS_DEATH) {
                part.truncate((part.length() - TORN_TAIL).coerceAtLeast(0))
            }
            // A renamed file is only adoptable with the same complete durable representation proof.
            if (proof) verification.adopt(row, source, null)?.let { return it }
            val root = roots.current()
            failureRoot = root
            if (roots.resolve(root) == null) return storage(StorageFailure.UNAVAILABLE)
            val length = if (roots.resolve(part.rootId) == null) 0 else part.length()
            val total = audio.contentLength
            val maximum = roots.maximumFileBytes(root)
            if (total != null && maximum != null && total > maximum) return storage(StorageFailure.TOO_LARGE)
            val estimate = (total ?: row.estimatedBytes ?: UNKNOWN_SIZE).coerceAtLeast(0)
            val space = estimate.coerceAtMost(Long.MAX_VALUE - HEADROOM) + HEADROOM
            roots
                .ensureSpace(root, if (root == part.rootId) (space - length).coerceAtLeast(0) else space)
                ?.let { return storage(it) }
            val temp = DownloadPaths.part(row.episodeId)
            if (root != part.rootId) copyPart(part, root, temp, length)
            return Prepared.Go(
                TransferPlan(row.episodeId, audio.url, length, total, null, audio.itag, audio.mimeType, youtube = next),
                root,
                temp,
                identity.videoId,
            )
        } catch (failure: IOException) {
            return storage(roots.classify(failure, failureRoot))
        }
    }

    override suspend fun transfer(
        plan: TransferPlan,
        part: PartFile,
        sink: ProgressSink,
        attempt: TransferAttempt,
    ): TransferResult {
        var offset = part.length()
        var total = plan.expectedTotal
        var audio: ResolvedAudio? = null
        var first = true
        try {
            while (first || total == null || offset < total) {
                first = false
                currentCoroutineContext().ensureActive()
                external()?.let { return transferResult(it) }
                gate()?.let { return transferResult(it) }
                val identity = checkNotNull(attempt.identity)
                val result =
                    streams.resolveAudio(
                        identity.videoId,
                        checkNotNull(attempt.youtube).pref,
                        ResolvePurpose.BACKGROUND,
                    )
                audio = (result as? ResolveResult.Ok)?.audio ?: return transferResult(outcome(result, attempt))
                readiness(audio)?.let { return transferResult(it) }
                val next = snapshot(audio, checkNotNull(attempt.pref))
                if (next != attempt.youtube) {
                    attempt.youtube = next
                    return TransferResult.Restart("youtube_representation_changed")
                }
                if (audio.contentLength != null && total != null && audio.contentLength != total) {
                    return TransferResult.Restart("youtube_length_changed")
                }
                if (total != null && offset == total) break
                val end = offset + minOf(CHUNK_BYTES, (total ?: Long.MAX_VALUE) - offset) - 1
                var acceptedTotal: Long? = null
                val failure =
                    client
                        .prepareGet(audio.url) {
                            publisherAuth(PublisherAuthPolicy.ANONYMOUS)
                            header("Range", "bytes=$offset-$end")
                        }.execute { response ->
                            readChunk(response, audio, part, sink, offset, end, total) { acceptedTotal = it }
                        }
                if (failure != null) {
                    if (failure is TransferResult.Transient && failure.httpStatus in setOf(403, 410)) {
                        if (attempt.refusedUrls.add(audio.url) && clock.now() - audio.resolvedAtMs in 0 until 120_000) {
                            health.reportExtraction(ExtractionOutcome.ForbiddenFreshUrl(identity.videoId))
                        }
                        if (attempt.invalidations >= 2) return failure
                        attempt.invalidations++
                        streams.invalidate(identity.videoId)
                        continue
                    }
                    return failure
                }
                total = checkNotNull(acceptedTotal)
                offset = part.length()
                if (offset < total) delay(jitterMs().coerceIn(500, 2_000))
            }
            external()?.let { return transferResult(it) }
            val resolved = checkNotNull(audio)
            return TransferResult.Complete(
                checkNotNull(total),
                resolved.mimeType,
                null,
                resolved.lastModifiedMicros?.toString(),
                if (resolved.mimeType == "audio/mp4") MediaKind.MP4 else MediaKind.WEBM,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: StorageIo) {
            return transferResult(storage(roots.classify(checkNotNull(failure.cause), part.rootId)))
        } catch (_: IOException) {
            return TransferResult.Transient(DownloadError.NETWORK_IO, null, null)
        }
    }

    private suspend fun readChunk(
        response: HttpResponse,
        audio: ResolvedAudio,
        part: PartFile,
        sink: ProgressSink,
        offset: Long,
        requestedEnd: Long,
        knownTotal: Long?,
        accepted: (Long) -> Unit,
    ): TransferResult? {
        val status = response.status.value
        if (status == 403 || status == 410) return TransferResult.Transient(DownloadError.YT_FORBIDDEN, status, null)
        if (status == 429) {
            health.reportRateLimited(clock.now())
            return TransferResult.Wait(
                WaitReason.BACKOFF,
                DownloadError.HTTP_RATE_LIMITED,
                maxOf(clock.now() + RATE_WAIT, health.state.value.rateLimitedUntil ?: 0),
                httpStatus = status,
            )
        }
        if (status >= 500) return TransferResult.Transient(DownloadError.HTTP_SERVER, status, null)
        if (status !in setOf(200, 206)) return TransferResult.Permanent(DownloadError.HTTP_CLIENT, status)
        val headers = response.headers
        val mime = headers["Content-Type"]?.substringBefore(';')?.trim()?.lowercase()
        if (mime != audio.mimeType || mime !in AUDIO_TYPES ||
            headers["Content-Encoding"]?.lowercase()?.let { it != "identity" } == true ||
            (headers.getAll("Content-Length")?.size ?: 0) > 1 || (headers.getAll("Content-Range")?.size ?: 0) > 1
        ) {
            return TransferResult.Permanent(DownloadError.NOT_MEDIA, status)
        }
        val lengthText = headers["Content-Length"]
        val length = lengthText?.toLongOrNull()?.takeIf { it > 0 }
        if (lengthText != null && length == null) return TransferResult.Permanent(DownloadError.HTTP_CLIENT, status)
        val total: Long
        val bytes: Long
        if (status == 206) {
            val match =
                RANGE.matchEntire(headers["Content-Range"].orEmpty())
                    ?: return TransferResult.Transient(DownloadError.NETWORK_IO, status, null)
            val start = match.groupValues[1].toLongOrNull()
            val end = match.groupValues[2].toLongOrNull()
            total = match.groupValues[3].toLongOrNull()?.takeIf { it > 0 }
                ?: return TransferResult.Transient(DownloadError.NETWORK_IO, status, null)
            if (start != offset || end != minOf(requestedEnd, total - 1) || end < offset ||
                (knownTotal != null && total != knownTotal) ||
                (audio.contentLength != null && total != audio.contentLength)
            ) {
                return TransferResult.Transient(DownloadError.NETWORK_IO, status, null)
            }
            bytes = end - offset + 1
            if (length != null && length != bytes) {
                return TransferResult.Transient(DownloadError.NETWORK_IO, status, null)
            }
        } else {
            total = audio.contentLength?.takeIf { it > 0 }
                ?: return TransferResult.Transient(DownloadError.NETWORK_IO, status, null)
            if (offset != 0L || length != total || (knownTotal != null && knownTotal != total)) {
                return TransferResult.Transient(DownloadError.NETWORK_IO, status, null)
            }
            bytes = total
        }
        var received = 0L
        try {
            val channel = response.bodyAsChannel()
            val head = ByteArray(minOf(MediaSniffer.HEAD_BYTES.toLong(), bytes).toInt())
            var n = 0
            while (n < head.size) {
                val count = channel.readAvailable(head, n, head.size - n)
                if (count == -1) return TransferResult.Transient(DownloadError.NETWORK_IO, status, null)
                n += count
            }
            if (offset == 0L && !MediaSniffer.youtubeAudio(head, mime)) {
                return TransferResult.Permanent(DownloadError.NOT_MEDIA, status)
            }
            sink.started(TransferMetadata(offset, total, mime, null, audio.lastModifiedMicros?.toString(), audio.itag))
            disk { part.append(head, n) }
            received = n.toLong()
            val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = channel.readAvailable(buffer, 0, buffer.size)
                if (count == -1) break
                if (count > bytes - received) return TransferResult.Transient(DownloadError.NETWORK_IO, status, null)
                disk { part.append(buffer, count) }
                received += count
                sink.bytes(offset + received, total)
            }
            if (received != bytes) return TransferResult.Transient(DownloadError.NETWORK_IO, status, null)
            disk { part.sync() }
            accepted(total)
            return null
        } finally {
            // Every non-crash failure retains only fully validated chunks. The runner retries that boundary.
            // Cancellation after the last append must keep the complete chunk; only a short read rolls back.
            if (received != bytes) withContext(NonCancellable) { disk { part.truncate(offset) } }
        }
    }

    private fun snapshot(
        audio: ResolvedAudio,
        pref: AudioPref,
    ) = YouTubeTransferSnapshot(
        pref.copy(pinnedItag = audio.itag),
        YouTubeRepresentationKey(audio.formatId, audio.contentLength, audio.lastModifiedMicros),
    )

    private fun readiness(audio: ResolvedAudio): Prepared? =
        when {
            audio.mimeType !in AUDIO_TYPES || audio.contentLength?.let { it <= 0 } == true -> {
                Prepared.Fail(DownloadError.UNSUPPORTED_STREAM, null)
            }

            audio.availableAtMs?.let { it > clock.now() } == true -> {
                Prepared.Wait(WaitReason.BACKOFF, null, audio.availableAtMs, false)
            }

            else -> {
                null
            }
        }

    private fun outcome(
        result: ResolveResult,
        attempt: TransferAttempt,
    ): Prepared =
        when (result) {
            is ResolveResult.Ok -> {
                error("Audio was handled")
            }

            is ResolveResult.Unavailable -> {
                attempt.unavailable = result.reason
                Prepared.Fail(DownloadError.YT_UNAVAILABLE, null)
            }

            ResolveResult.Unsupported -> {
                external() ?: Prepared.Fail(DownloadError.UNSUPPORTED_STREAM, null)
            }

            is ResolveResult.Transient -> {
                when (result.kind) {
                    TransientKind.EXTRACTION -> {
                        Prepared.Retry(TransferResult.Transient(DownloadError.YT_EXTRACTION, null, null))
                    }

                    TransientKind.BREAKER_OPEN, TransientKind.RATE_LIMITED -> {
                        gate()
                            ?: Prepared.Wait(WaitReason.BACKOFF, null, clock.now() + RATE_WAIT, false)
                    }

                    else -> {
                        Prepared.Retry(TransferResult.Transient(DownloadError.NETWORK_IO, null, null))
                    }
                }
            }
        }

    private fun external(): Prepared? =
        if (capabilities.capabilities.value.downloads) {
            null
        } else {
            if (capabilities.capabilities.value.externalReason == ExternalReason.NOT_IN_THIS_APK) {
                Prepared.Fail(DownloadError.UNSUPPORTED_STREAM, null)
            } else {
                Prepared.Wait(WaitReason.YOUTUBE_ENGINE_OFF, null, null, false)
            }
        }

    private fun gate(): Prepared.Wait? =
        (health.extractionGate(clock.now()) as? ExtractionGate.Deny)?.let {
            Prepared.Wait(WaitReason.BACKOFF, null, minOf(it.untilMs, clock.now() + RATE_WAIT), false)
        }

    private fun transferResult(prepared: Prepared): TransferResult =
        when (prepared) {
            is Prepared.Fail -> {
                TransferResult.Permanent(prepared.error, prepared.httpStatus)
            }

            is Prepared.Wait -> {
                TransferResult.Wait(prepared.reason, prepared.error, prepared.untilMs, prepared.countsAsAttempt)
            }

            is Prepared.Retry -> {
                prepared.result
            }

            else -> {
                error("Expected a terminal prepare outcome")
            }
        }

    private fun storage(failure: StorageFailure): Prepared =
        when (failure) {
            StorageFailure.TOO_LARGE -> {
                Prepared.Fail(DownloadError.STORAGE_UNAVAILABLE, null)
            }

            StorageFailure.IO -> {
                Prepared.Retry(TransferResult.Transient(DownloadError.NETWORK_IO, null, null))
            }

            else -> {
                Prepared.Wait(
                    WaitReason.STORAGE,
                    if (failure ==
                        StorageFailure.FULL
                    ) {
                        DownloadError.STORAGE_FULL
                    } else {
                        DownloadError.STORAGE_UNAVAILABLE
                    },
                    clock.now() + RATE_WAIT,
                    false,
                )
            }
        }

    private suspend fun copyPart(
        part: PartFile,
        root: String,
        temp: String,
        length: Long,
    ) {
        val target = files.openPart(root, temp)
        try {
            target.truncate(0)
            val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
            var offset = 0L
            while (offset < length) {
                val count = part.read(offset, buffer, minOf(buffer.size.toLong(), length - offset).toInt())
                if (count <= 0) throw IOException("Partial copy ended early")
                target.append(buffer, count)
                offset += count
            }
            target.sync()
            if (target.length() != length) throw IOException("Partial copy length mismatch")
        } finally {
            target.close()
        }
        if (roots.resolve(part.rootId) != null) part.delete()
    }

    private suspend fun <T> disk(block: suspend () -> T): T =
        try {
            block()
        } catch (failure: IOException) {
            throw StorageIo(failure)
        }

    private class StorageIo(
        cause: IOException,
    ) : Exception(cause)

    private companion object {
        const val CHUNK_BYTES = 10L * 1024 * 1024
        const val TORN_TAIL = 64L * 1024
        const val UNKNOWN_SIZE = 150L * 1024 * 1024
        const val HEADROOM = 200L * 1024 * 1024
        const val RATE_WAIT = 30L * 60 * 1000
        val AUDIO_TYPES = setOf("audio/mp4", "audio/webm")
        val RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
    }
}
