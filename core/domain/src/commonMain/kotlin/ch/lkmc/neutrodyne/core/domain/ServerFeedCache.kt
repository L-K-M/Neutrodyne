// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

/**
 * The client half of the linked server's batched feed refresh (03 Refresh, 10 Sync): one
 * authenticated call answers every due feed's current publisher document, so a device that was
 * asleep does not fetch each feed itself. `:sync:impl` binds the HTTP client on both platforms.
 *
 * [lookup] maps a feed URL to the SHA-256 hex of the document this device last ingested, or null
 * when it has none. The returned map is keyed by the requested URL. A snapshot with a body is a
 * changed document to parse and ingest locally; a snapshot without a body means the device already
 * holds that exact document. A URL absent from the result has no usable snapshot and the caller
 * fetches it directly.
 */
fun interface ServerFeedCache {
    /** True when this device can use a linked server for foreground refresh. */
    suspend fun isLinked(): Boolean = false

    suspend fun lookup(feeds: Map<String, String?>): Map<String, ServerFeedSnapshot>
}

/**
 * One cached publisher document. [sha256] is the hex SHA-256 of the decompressed body; [finalUrl]
 * is the URL at the end of the redirect chain; [checkedAt] is the server's last check in Unix
 * milliseconds; [body] is null when the requester's known hash already equals [sha256].
 */
data class ServerFeedSnapshot(
    val finalUrl: String,
    val sha256: String,
    val checkedAt: Long,
    val body: ByteArray?,
    val permanentUrl: String? = null,
    val httpCharset: String? = null,
)
