// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.fetch

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.common.HttpClientKind
import ch.lkmc.neutrodyne.core.model.NetError
import ch.lkmc.neutrodyne.core.network.NetErrorClassifier
import ch.lkmc.neutrodyne.core.network.NeutrodyneHttpClients
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import dev.zacsweers.metro.Inject
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.HashingSink
import okio.buffer

/**
 * The 03 fetch pipeline's transport half (internal to `:core:data`): the manual redirect chain
 * (Ktor's `HttpRedirect` stays off for `HttpClientKind.FEED` so status codes reach the caller),
 * conditional GETs, temp-file bodies, SHA-256, the 32 MB cap and the byte-level sniff. Every
 * outcome is a value — HTTP status codes are data, never exceptions.
 */
@Inject
internal class FeedFetcher(
    httpClients: NeutrodyneHttpClients,
    private val tempFiles: FeedTempFiles,
    private val fileSystem: FileSystem,
    private val classifier: NetErrorClassifier,
    private val credentialLookup: CredentialLookup,
    private val clock: Clock,
) {
    private val client = httpClients.client(HttpClientKind.FEED)

    /** Fetches [req]; follows the redirect chain by hand and records every hop. */
    suspend fun fetch(req: FeedRequest): FetchOutcome {
        credentialLookup.awaitLoaded()
        var url = req.url
        val visited = mutableSetOf(url)
        val hops = mutableListOf<RedirectHop>()
        var permanentUrl: String? = null

        while (true) {
            val result =
                try {
                    oneRequest(req, url, hops, permanentUrl)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    return FetchOutcome.Network(classifier.classify(e))
                }

            // A 3xx without a usable http(s) Location is the final response (03 Request rules).
            val location = result.location ?: return result.outcome
            val next = FeedHttpHeaders.resolveLocation(url, location) ?: return result.outcome
            if (!next.startsWith("http://") && !next.startsWith("https://")) return result.outcome

            hops += RedirectHop(url, result.status)
            // `permanentUrl` is the URL reached by the leading run of 301/308 hops only.
            if ((result.status == 301 || result.status == 308) &&
                hops.all { it.status == 301 || it.status == 308 }
            ) {
                permanentUrl = next
            }

            if (hops.size > MAX_REDIRECT_FOLLOWUPS || !visited.add(next)) {
                return FetchOutcome.RedirectLoop
            }
            url = next
        }
    }

    /** One request of the chain; [location] is set only for a followable 3xx. */
    private suspend fun oneRequest(
        req: FeedRequest,
        url: String,
        hops: List<RedirectHop>,
        permanentUrl: String?,
    ): HopResult {
        var outcome: FetchOutcome = FetchOutcome.Network(NetError.Other("unassigned"))
        var location: String? = null
        var status = 0
        // 03 Request rules: not-yet-stored credentials are sent only on hops whose origin equals
        // the first request's (a cross-scheme/host/port hop never carries them).
        val firstOrigin = UrlNormalizer.origin(req.url)
        val hopOrigin = UrlNormalizer.origin(url)
        val validatorsSent = req.conditional && (req.etag != null || req.lastModified != null)
        client
            .prepareGet(url) {
                header(HttpHeaders.Accept, FEED_ACCEPT)
                if (req.conditional) {
                    req.etag?.let { header(HttpHeaders.IfNoneMatch, it) }
                    req.lastModified?.let { header(HttpHeaders.IfModifiedSince, it) }
                }
                if (req.credentials != null && hopOrigin != null && hopOrigin == firstOrigin) {
                    val it = req.credentials
                    header(
                        HttpHeaders.Authorization,
                        "Basic " +
                            "${it.username}:${it.password}".encodeToByteArray().toByteString().base64(),
                    )
                }
            }.execute { response ->
                status = response.status.value
                when {
                    // A 304 counts only when this request actually sent validators (03 Response
                    // handling); an unsolicited 304 is a client-visible HTTP error below.
                    status == 304 && validatorsSent -> {
                        response.discardBody()
                        outcome =
                            FetchOutcome.NotModified(
                                etag = response.headers[FeedHttpHeaders.ETAG],
                                lastModified = response.headers[FeedHttpHeaders.LAST_MODIFIED],
                                maxAgeSec =
                                    FeedHttpHeaders.maxAgeSec(
                                        response.headers[FeedHttpHeaders.CACHE_CONTROL],
                                    ),
                                serverDateMs =
                                    FeedHttpHeaders.serverDateMs(response.headers[FeedHttpHeaders.DATE]),
                            )
                    }

                    status in FOLLOWABLE_STATUSES && response.headers[FeedHttpHeaders.LOCATION] != null -> {
                        response.discardBody()
                        location = response.headers[FeedHttpHeaders.LOCATION]
                        outcome =
                            FetchOutcome.Http(
                                code = status,
                                retryAfterMs = null,
                                basicChallenge = false,
                                realm = null,
                            )
                    }

                    response.status.isSuccess() -> {
                        outcome = readBody(req, url, hops, permanentUrl, response)
                    }

                    else -> {
                        response.discardBody()
                        val (basic, realm) =
                            FeedHttpHeaders.basicChallenge(
                                response.headers.getAll(FeedHttpHeaders.WWW_AUTHENTICATE),
                            )
                        outcome =
                            FetchOutcome.Http(
                                code = status,
                                retryAfterMs =
                                    FeedHttpHeaders.retryAfterMs(
                                        response.headers[FeedHttpHeaders.RETRY_AFTER],
                                        clock.now(),
                                    ),
                                basicChallenge = basic,
                                realm = realm,
                            )
                    }
                }
            }
        return HopResult(outcome, location, status)
    }

    /**
     * Streams a 200 body to a temp file under the cap, hashing and sniffing on the way. The temp
     * file is owned only by a returned [FetchOutcome.Body]; every failure and cancellation path
     * closes the sink and deletes it, and sink/disk faults are [FetchOutcome.Storage], not
     * transport failures (03 Response handling).
     */
    private suspend fun readBody(
        req: FeedRequest,
        url: String,
        hops: List<RedirectHop>,
        permanentUrl: String?,
        response: HttpResponse,
    ): FetchOutcome {
        val path =
            try {
                tempFiles.create()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return FetchOutcome.Storage(storageDetail(e))
            }
        val hashing =
            try {
                HashingSink.sha256(fileSystem.sink(path))
            } catch (e: CancellationException) {
                tempFiles.delete(path)
                throw e
            } catch (e: Throwable) {
                tempFiles.delete(path)
                return FetchOutcome.Storage(storageDetail(e))
            }
        val sink = hashing.buffer()
        val probe = Buffer()
        var probeBytes = 0L
        var total = 0L
        val sniffTarget = req.sniffOnlyBytes?.toLong() ?: FeedSniffer.PROBE_BYTES.toLong()
        val channel = response.bodyAsChannel()
        val chunk = ByteArray(CHUNK_BYTES)
        var tooLarge = false

        try {
            while (true) {
                val read =
                    try {
                        channel.readAvailable(chunk, 0, chunk.size)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        throw StreamFailure(FetchOutcome.Network(classifier.classify(e)))
                    }
                if (read == -1) break
                if (read == 0) {
                    try {
                        channel.awaitContent()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        throw StreamFailure(FetchOutcome.Network(classifier.classify(e)))
                    }
                    continue
                }
                try {
                    sink.write(chunk, 0, read)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    throw StreamFailure(FetchOutcome.Storage(storageDetail(e)))
                }
                total += read
                if (probeBytes < sniffTarget) {
                    val take = minOf(read.toLong(), sniffTarget - probeBytes).toInt()
                    probe.write(chunk, 0, take)
                    probeBytes += take
                }
                if (total > req.maxBytes) {
                    tooLarge = true
                    break
                }
                if (req.sniffOnlyBytes != null && total >= req.sniffOnlyBytes) break
            }
            try {
                sink.close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw StreamFailure(FetchOutcome.Storage(storageDetail(e)))
            }
        } catch (e: CancellationException) {
            closeAndDelete(sink, path)
            throw e
        } catch (e: StreamFailure) {
            closeAndDelete(sink, path)
            return e.outcome
        }

        if (tooLarge) {
            tempFiles.delete(path)
            return FetchOutcome.TooLarge
        }

        val sniff = FeedSniffer.sniff(probe.readByteArray())
        return FetchOutcome.Body(
            file = path,
            sha256Hex = hashing.hash.hex(),
            requestedUrl = req.url,
            finalUrl = url,
            permanentUrl = permanentUrl,
            hops = hops,
            etag = response.headers[FeedHttpHeaders.ETAG],
            lastModified = response.headers[FeedHttpHeaders.LAST_MODIFIED],
            charset =
                FeedHttpHeaders.contentTypeCharset(response.headers[FeedHttpHeaders.CONTENT_TYPE]),
            maxAgeSec =
                FeedHttpHeaders.maxAgeSec(response.headers[FeedHttpHeaders.CACHE_CONTROL]),
            serverDateMs = FeedHttpHeaders.serverDateMs(response.headers[FeedHttpHeaders.DATE]),
            sniff = sniff,
        )
    }

    /** Releases a response body we do not stream (3xx, 304, errors). */
    private suspend fun HttpResponse.discardBody() {
        val channel = bodyAsChannel()
        val chunk = ByteArray(CHUNK_BYTES)
        while (!channel.isClosedForRead) {
            if (channel.readAvailable(chunk, 0, chunk.size) == -1) break
            channel.awaitContent()
        }
    }

    /** Best-effort sink close plus temp-file delete for the failure/cancellation exits. */
    private fun closeAndDelete(
        sink: okio.BufferedSink,
        path: okio.Path,
    ) {
        try {
            sink.close()
        } catch (_: Exception) {
            // Closing a half-failed or cancelled sink can throw; the file is deleted anyway.
        }
        tempFiles.delete(path)
    }

    private fun storageDetail(e: Throwable): String = e::class.simpleName ?: "storage"

    /** Carries the outcome of an aborted body stream out of the read loop. */
    private class StreamFailure(
        val outcome: FetchOutcome,
    ) : Exception()

    private class HopResult(
        val outcome: FetchOutcome,
        val location: String?,
        val status: Int,
    )

    private companion object {
        const val CHUNK_BYTES = 64 * 1024

        /** The only 3xx codes the manual chain follows (03 Request rules). */
        val FOLLOWABLE_STATUSES = setOf(301, 302, 303, 307, 308)
    }
}
