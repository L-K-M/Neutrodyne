// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.youtube.impl

import ch.lkmc.neutrodyne.core.model.ExternalReason
import ch.lkmc.neutrodyne.youtube.api.AudioPref
import ch.lkmc.neutrodyne.youtube.api.ChannelResolution
import ch.lkmc.neutrodyne.youtube.api.ChannelSearchResult
import ch.lkmc.neutrodyne.youtube.api.EngineAvailability
import ch.lkmc.neutrodyne.youtube.api.EngineStatus
import ch.lkmc.neutrodyne.youtube.api.EnrichResult
import ch.lkmc.neutrodyne.youtube.api.ExtractorChannelLookup
import ch.lkmc.neutrodyne.youtube.api.MetadataDepth
import ch.lkmc.neutrodyne.youtube.api.PrewarmReason
import ch.lkmc.neutrodyne.youtube.api.ResolvePurpose
import ch.lkmc.neutrodyne.youtube.api.ResolveResult
import ch.lkmc.neutrodyne.youtube.api.SearchCursor
import ch.lkmc.neutrodyne.youtube.api.UploadsCursor
import ch.lkmc.neutrodyne.youtube.api.UploadsPageResult
import ch.lkmc.neutrodyne.youtube.api.VideoFactsResult
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilities
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.youtube.api.YouTubeChannelSearch
import ch.lkmc.neutrodyne.youtube.api.YouTubeEngine
import ch.lkmc.neutrodyne.youtube.api.YouTubeEnricher
import ch.lkmc.neutrodyne.youtube.api.YouTubeStreamResolver
import ch.lkmc.neutrodyne.youtube.api.YtRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Portable consumers are installed; the ordinary shells have no activated engine owner yet. */
class DeferredYouTubeEngine : YouTubeEngine {
    override val status = MutableStateFlow(EngineStatus(EngineAvailability.NOT_YET_AVAILABLE)).asStateFlow()

    override fun prewarm(reason: PrewarmReason) = Unit

    override fun retryStart() = Unit
}

/** Dependency-free external bindings: constructing or calling these never starts a host or IO. */
class StaticYouTubeCapabilitiesSource : YouTubeCapabilitiesSource {
    override val capabilities =
        MutableStateFlow(
            YouTubeCapabilities(false, false, false, false, false, ExternalReason.NOT_YET_AVAILABLE),
        ).asStateFlow()
}

class ExternalOnlyYouTubeStreamResolver : YouTubeStreamResolver {
    override suspend fun resolveAudio(
        videoId: String,
        pref: AudioPref,
        purpose: ResolvePurpose,
    ): ResolveResult = ResolveResult.Unsupported

    override fun invalidate(videoId: String) = Unit

    override fun invalidateAll() = Unit
}

class NoOpYouTubeEnricher : YouTubeEnricher {
    override suspend fun facts(videoId: String): VideoFactsResult = VideoFactsResult.Unsupported

    override suspend fun enrich(
        channelId: String,
        videoIds: Set<String>,
        variants: Int,
    ): EnrichResult = EnrichResult.Unsupported

    override suspend fun uploadsPage(
        channelId: String,
        variant: Int,
        cursor: UploadsCursor?,
    ): UploadsPageResult = UploadsPageResult.Unsupported
}

class UnsupportedYouTubeChannelSearch : YouTubeChannelSearch {
    override suspend fun search(
        query: String,
        cursor: SearchCursor?,
    ): ChannelSearchResult = ChannelSearchResult.Unsupported
}

class NoExtractorChannelLookup : ExtractorChannelLookup {
    override suspend fun lookup(
        ref: YtRef,
        depth: MetadataDepth,
    ): ChannelResolution? = null
}
