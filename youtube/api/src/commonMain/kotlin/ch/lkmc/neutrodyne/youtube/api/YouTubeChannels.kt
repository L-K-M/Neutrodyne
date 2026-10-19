// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.youtube.api

/** Main-safe, cancellable Layer A resolution. Query is invalid; known identity survives missing art. */
interface YouTubeChannelResolver {
    suspend fun resolve(
        ref: YtRef,
        depth: MetadataDepth = MetadataDepth.AVATAR,
    ): ChannelResolution
}

enum class MetadataDepth { ID_ONLY, AVATAR, FULL }

enum class ResolvedVia { STATIC, USER_FEED, EXTRACTOR, HTML, OEMBED }

enum class FailReason { NETWORK, RATE_LIMITED, CONSENT_WALL, PAGE_UNREADABLE, NOT_A_CHANNEL_LINK }

sealed interface ChannelResolution {
    data class Resolved(
        val channelId: String,
        val title: String?,
        val avatarUrl: String?,
        val bannerUrl: String?,
        val description: String?,
        val via: ResolvedVia,
    ) : ChannelResolution {
        init {
            require(YouTubeIds.CHANNEL.matches(channelId))
        }
    }

    data class PlaylistUnsupported(
        val playlistId: String,
        val ownerChannelId: String?,
        val ownerTitle: String?,
    ) : ChannelResolution

    data object NotFound : ChannelResolution

    data class Failed(
        val retryable: Boolean,
        val reason: FailReason,
        val cause: Throwable?,
    ) : ChannelResolution
}

/** null means extractor unavailable, allowing Layer A fallback. NotFound is authoritative. */
interface ExtractorChannelLookup {
    suspend fun lookup(
        ref: YtRef,
        depth: MetadataDepth,
    ): ChannelResolution?
}

interface YouTubeChannelSearch {
    suspend fun search(
        query: String,
        cursor: SearchCursor? = null,
    ): ChannelSearchResult
}

/** Opaque, host-owned and never persisted; expires with its result generator. */
data class SearchCursor(
    val token: String,
)

data class ChannelHit(
    val channelId: String,
    val title: String,
    val avatarUrl: String?,
    val subscriberCount: Long?,
    val description: String?,
    val verified: Boolean,
)

sealed interface ChannelSearchResult {
    data class Ok(
        val hits: List<ChannelHit>,
        val next: SearchCursor?,
    ) : ChannelSearchResult

    data object Unsupported : ChannelSearchResult

    data class Failed(
        val kind: TransientKind,
    ) : ChannelSearchResult
}

/** Core data parses the bounded Atom body; HTTP status, body limits and closing stay with the
 * resolver's FEED client. null means unreadable metadata; cancellation propagates. */
interface YouTubeFeedMetadataReader {
    suspend fun read(body: ByteArray): FeedChannelMetadata?
}

data class FeedChannelMetadata(
    val channelId: String?,
    val authorName: String?,
    val authorUrl: String?,
    val entryCount: Int,
)
