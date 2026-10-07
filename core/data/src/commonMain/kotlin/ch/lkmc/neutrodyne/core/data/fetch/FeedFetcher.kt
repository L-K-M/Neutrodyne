// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.fetch

import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.common.HttpClientKind
import ch.lkmc.neutrodyne.core.model.NetError
import ch.lkmc.neutrodyne.core.network.NeutrodyneHttpClients
import ch.lkmc.neutrodyne.core.network.NetErrorClassifier
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
        client.prepareGet(url) {
            header(HttpHeaders.Accept, FEED_ACCEPT)
            if (req.conditional) {
                req.etag?.let { header(HttpHeaders.IfNoneMatch, it) }
                req.lastModified?.let { header(HttpHeaders.IfModifiedSince, it) }
            }
            req.credentials?.let {
                header(
                    HttpHeaders.Authorization,
                    "Basic " +
                        "${it.username}:${it.password}".encodeToByteArray().toByteString().base64(),
                )
            }
        }.execute { response ->
            status = response.status.value
            when {
                status == 304 -> {
                    response.discardBody()
                    outcome =
                        FetchOutcome.NotModified(
                            maxAgeSec =
                                FeedHttpHeaders.maxAgeSec(
                                    response.headers[FeedHttpHeaders.CACHE_CONTROL],
                                ),
                            serverDateMs =
                                FeedHttpHeaders.serverDateMs(response.headers[FeedHttpHeaders.DATE]),
                        )
                }

                status in 300..399 && response.headers[FeedHttpHeaders.LOCATION] != null -> {
                    response.discardBody()
                    location = response.headers[FeedHttpHeaders.LOCATION]
                }

                response.status.isSuccess() -> outcome = readBody(req, url, hops, permanentUrl, response)

                else -> {
                    response.discardBody()
                    val (basic, realm) =
                        FeedHttpHeaders.basicChallenge(
                            response.headers[FeedHttpHeaders.WWW_AUTHENTICATE],
                        )
                    outcome =
                        FetchOutcome.Http(
                            code = status,
                            retryAfterMs =
                                FeedHttpHeaders.retryAfterMs(
                                    response.headers[FeedHttpHeaders.RETRY_AFTER],
                                ),
                            basicChallenge = basic,
                            realm = realm,
                        )
                }
            }
        }
        return HopResult(outcome, location, status)
    }

    /** Streams a 200 body to a temp file under the cap, hashing and sniffing on the way. */
    private suspend fun readBody(
        req: FeedRequest,
        url: String,
        hops: List<RedirectHop>,
        permanentUrl: String?,
        response: HttpResponse,
    ): FetchOutcome {
        val path = tempFiles.create()
        val hashing = HashingSink.sha256(fileSystem.sink(path))
        val sink = hashing.buffer()
        val probe = Buffer()
        var probeBytes = 0L
        var total = 0L
        val sniffTarget = req.sniffOnlyBytes?.toLong() ?: FeedSniffer.PROBE_BYTES.toLong()
        val channel = response.bodyAsChannel()
        val chunk = ByteArray(CHUNK_BYTES)
        var tooLarge = false

        while (true) {
            val read =
                try {
                    channel.readAvailable(chunk, 0, chunk.size)
                } catch (e: CancellationException) {
                    tempFiles.delete(path)
                    throw e
                }
            if (read == -1) break
            if (read == 0) {
                channel.awaitContent()
                continue
            }
            sink.write(chunk, 0, read)
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
        sink.close()

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

    private class HopResult(
        val outcome: FetchOutcome,
        val location: String?,
        val status: Int,
    )

    private companion object {
        const val CHUNK_BYTES = 64 * 1024
    }
}
