// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.domain.ServerFeedCache
import ch.lkmc.neutrodyne.core.domain.ServerFeedSnapshot
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.sync.protocol.ProtocolLimits
import ch.lkmc.neutrodyne.sync.protocol.dto.CachedFeed
import ch.lkmc.neutrodyne.sync.protocol.dto.FeedCacheQuery
import kotlinx.coroutines.CancellationException
import okio.ByteString.Companion.toByteString
import kotlin.io.encoding.Base64

/**
 * [ServerFeedCache] over the authenticated `/api/v1/feed-cache` endpoint (10 Batched feed
 * refresh). The lookup is best effort: an unlinked device, an old server without the endpoint
 * and a network failure all return an empty map, so the caller falls back to its own fetch.
 *
 * Every entry is re-validated before it reaches the caller: the URL must be one this call asked
 * for, the final URL must be a usable HTTP(S) URL, the hash must be well formed and (when a body
 * is present) match the body's SHA-256, the body must fit the server's stored-entry bound, and
 * `checkedAt` must be fresh and not past the protocol's clock-skew allowance. A matching known
 * hash legitimately carries no body; a changed hash without a body is rejected.
 *
 * The server accepts at most [MAX_QUERIES_PER_REQUEST] URLs per call, so a larger library is
 * requested in chunks. The client retains at most 8 MiB of document bodies across the whole
 * lookup. If a chunk fails, earlier validated entries remain usable. Missing entries fall back
 * to the publisher when the subscription is due.
 */
internal class KtorServerFeedCache(
    private val clock: Clock,
    private val linked: suspend () -> Boolean,
    private val capture: suspend () -> FeedCacheSession?,
) : ServerFeedCache {
    override suspend fun isLinked(): Boolean = linked()

    override suspend fun lookup(feeds: Map<String, String?>): Map<String, ServerFeedSnapshot> {
        val requested = feeds.filterKeys { it.isNotBlank() }
        if (requested.isEmpty()) return emptyMap()
        val session = capture() ?: return emptyMap()
        val now = clock.now()
        val queries =
            requested.map { (url, sha) ->
                FeedCacheQuery(url, sha?.takeIf(::isValidSha256Hex)?.lowercase())
            }
        var remainingBytes = ProtocolLimits.MAX_FEED_BYTES
        val snapshots = mutableMapOf<String, ServerFeedSnapshot>()
        for (chunk in queries.chunked(MAX_QUERIES_PER_REQUEST)) {
            val response =
                try {
                    if (!session.current()) return emptyMap()
                    session.client.feedCache(chunk)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (unavailable: SyncTransportException) {
                    // An old server answers 404/405; any other transport status is equally a
                    // "no cache for you" answer. Neither is worth a warning on every refresh.
                    Log.d(TAG) { "feed cache endpoint unavailable (${unavailable.status})" }
                    break
                } catch (failure: Exception) {
                    Log.d(TAG) { "feed cache lookup failed (${failure::class.simpleName})" }
                    break
                }
            if (response.feeds.size > chunk.size) break
            val asked = chunk.associate { it.url to it.sha256 }
            for (entry in response.feeds) {
                val snapshot = entry.toSnapshot(asked, now) ?: continue
                val size = snapshot.body?.size ?: 0
                if (size > remainingBytes || entry.url in snapshots) continue
                remainingBytes -= size
                snapshots[entry.url] = snapshot
            }
        }
        return if (session.current()) snapshots else emptyMap()
    }

    private fun CachedFeed.toSnapshot(
        requested: Map<String, String?>,
        now: Long,
    ): ServerFeedSnapshot? {
        if (url.length > ProtocolLimits.MAX_URL) return null
        if (UrlNormalizer.forIdentity(url) == null) return null
        // The server keys entries by the exact requested string; an unsolicited URL is refused.
        if (!requested.containsKey(url)) return null
        val knownSha = requested[url]
        if (finalUrl.length > ProtocolLimits.MAX_URL) return null
        if (UrlNormalizer.forIdentity(finalUrl) == null) return null
        permanentUrl?.let {
            if (it.length > ProtocolLimits.MAX_URL ||
                UrlNormalizer.forIdentity(it) == null
            ) {
                return null
            }
        }
        if ((httpCharset?.length ?: 0) > 64) return null
        if (checkedAt <= 0L || checkedAt > now + ProtocolLimits.MAX_SKEW_MS) return null
        if (checkedAt < now - MAX_FRESH_AGE_MS - ProtocolLimits.MAX_SKEW_MS) return null
        if (!isValidSha256Hex(sha256)) return null
        val body = bodyBase64?.let { decodeBody(it) ?: return null }
        if (body == null) {
            // No body is only legitimate when the caller already holds this exact document.
            if (knownSha == null || !sha256.equals(knownSha, ignoreCase = true)) return null
        } else {
            if (body.size > MAX_BODY_BYTES) return null
            if (body.toByteString().sha256().hex() != sha256.lowercase()) return null
        }
        return ServerFeedSnapshot(
            finalUrl = finalUrl,
            sha256 = sha256.lowercase(),
            checkedAt = checkedAt,
            body = body,
            permanentUrl = permanentUrl,
            httpCharset = httpCharset,
        )
    }

    /** Decodes a body, refusing an obviously oversized payload before allocating it. */
    private fun decodeBody(encoded: String): ByteArray? {
        if (encoded.length > MAX_BODY_BYTES * 4 / 3 + 8) return null
        return try {
            Base64.decode(encoded)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun isValidSha256Hex(value: String): Boolean =
        value.length == SHA256_HEX_LENGTH &&
            value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    private companion object {
        const val TAG = "ServerFeedCache"

        /** The endpoint's default per-call URL bound; a larger library is asked in chunks. */
        const val MAX_QUERIES_PER_REQUEST = ProtocolLimits.MAX_FEED_BATCH

        /** The server's stored-entry bound; a cached body never legitimately exceeds it. */
        const val MAX_BODY_BYTES = ProtocolLimits.MAX_FEED_BYTES

        /** The server's default cache TTL; older entries are left to the client's own fetch. */
        const val MAX_FRESH_AGE_MS = 30 * 60_000L

        const val SHA256_HEX_LENGTH = 64
    }
}

internal class FeedCacheSession(
    val client: SyncClient,
    val current: suspend () -> Boolean,
)
