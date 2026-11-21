// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.protocol.dto

import kotlinx.serialization.Serializable

@Serializable
data class FeedCacheRequest(
    val feeds: List<FeedCacheQuery>,
)

@Serializable
data class FeedCacheQuery(
    val url: String,
    val sha256: String? = null,
)

@Serializable
data class FeedCacheResponse(
    val feeds: List<CachedFeed>,
)

/** The hash covers the decompressed publisher document. A matching client already has its contents. */
@Serializable
data class CachedFeed(
    val url: String,
    val finalUrl: String,
    val sha256: String,
    val checkedAt: Long,
    val bodyBase64: String? = null,
    val permanentUrl: String? = null,
    val httpCharset: String? = null,
)
