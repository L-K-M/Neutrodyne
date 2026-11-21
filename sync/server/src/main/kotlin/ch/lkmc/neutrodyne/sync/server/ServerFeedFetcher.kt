// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

/**
 * The server's upstream feed transport. The server owns subscription enumeration, the cache and
 * invalidation; an implementation only performs one conditional GET and reports the outcome as a
 * value. `null` means the fetch failed (transport error or a non-success status), leaving any
 * existing cache entry untouched.
 */
internal fun interface ServerFeedFetcher {
    suspend fun fetch(
        url: String,
        authorization: String?,
        etag: String?,
        lastModified: String?,
    ): ServerFeedFetch?

    sealed interface ServerFeedFetch {
        data class Changed(
            val body: ByteArray,
            val finalUrl: String,
            val etag: String?,
            val lastModified: String?,
            val permanentUrl: String? = null,
            val httpCharset: String? = null,
        ) : ServerFeedFetch

        data object Unchanged : ServerFeedFetch
    }
}
