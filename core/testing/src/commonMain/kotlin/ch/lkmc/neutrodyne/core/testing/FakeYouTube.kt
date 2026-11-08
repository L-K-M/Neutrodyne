// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.ChannelMetadataReason
import ch.lkmc.neutrodyne.core.domain.ChannelMetadataResult
import ch.lkmc.neutrodyne.core.domain.LoadOlderResult
import ch.lkmc.neutrodyne.core.domain.RssAlternative
import ch.lkmc.neutrodyne.core.domain.SubscribeError
import ch.lkmc.neutrodyne.core.domain.YouTubeChannelMetadata
import ch.lkmc.neutrodyne.core.domain.YouTubeChannelRepository
import ch.lkmc.neutrodyne.core.domain.YouTubeDuplicatePolicy
import ch.lkmc.neutrodyne.core.domain.YouTubeExternalOpen
import ch.lkmc.neutrodyne.core.domain.YouTubeOpenContext
import ch.lkmc.neutrodyne.core.domain.YouTubeSubscription
import ch.lkmc.neutrodyne.core.domain.YouTubeSubscriptionWriter
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.YouTubeVariantBits
import ch.lkmc.neutrodyne.youtube.api.ChannelResolution
import ch.lkmc.neutrodyne.youtube.api.ChannelSearchResult
import ch.lkmc.neutrodyne.youtube.api.EnrichResult
import ch.lkmc.neutrodyne.youtube.api.ExtractionGate
import ch.lkmc.neutrodyne.youtube.api.ExtractionOutcome
import ch.lkmc.neutrodyne.youtube.api.ExtractorChannelLookup
import ch.lkmc.neutrodyne.youtube.api.FeedChannelMetadata
import ch.lkmc.neutrodyne.youtube.api.FeedGate
import ch.lkmc.neutrodyne.youtube.api.FeedOutcome
import ch.lkmc.neutrodyne.youtube.api.MetadataDepth
import ch.lkmc.neutrodyne.youtube.api.SearchCursor
import ch.lkmc.neutrodyne.youtube.api.UploadsCursor
import ch.lkmc.neutrodyne.youtube.api.UploadsPageResult
import ch.lkmc.neutrodyne.youtube.api.YouTubeChannelResolver
import ch.lkmc.neutrodyne.youtube.api.YouTubeChannelSearch
import ch.lkmc.neutrodyne.youtube.api.YouTubeEnricher
import ch.lkmc.neutrodyne.youtube.api.YouTubeFeedMetadataReader
import ch.lkmc.neutrodyne.youtube.api.YouTubeFeedOutageMonitor
import ch.lkmc.neutrodyne.youtube.api.YouTubeHealth
import ch.lkmc.neutrodyne.youtube.api.YouTubeHealthState
import ch.lkmc.neutrodyne.youtube.api.YtRef
import kotlinx.coroutines.flow.MutableStateFlow

/** Scriptable main-safe fakes. Suspending handlers allow failure, cancellation and coalescing tests. */
class FakeYouTubeChannelResolver : YouTubeChannelResolver {
    val calls = mutableListOf<Pair<YtRef, MetadataDepth>>()
    var resolve: suspend (YtRef, MetadataDepth) -> ChannelResolution = { _, _ -> ChannelResolution.NotFound }

    override suspend fun resolve(
        ref: YtRef,
        depth: MetadataDepth,
    ): ChannelResolution {
        calls += ref to depth
        return resolve.invoke(ref, depth)
    }
}

class FakeExtractorChannelLookup : ExtractorChannelLookup {
    var result: ChannelResolution? = null
    val calls = mutableListOf<Pair<YtRef, MetadataDepth>>()

    override suspend fun lookup(
        ref: YtRef,
        depth: MetadataDepth,
    ): ChannelResolution? {
        calls += ref to depth
        return result
    }
}

class FakeYouTubeChannelSearch : YouTubeChannelSearch {
    var result: ChannelSearchResult = ChannelSearchResult.Unsupported
    val calls = mutableListOf<Pair<String, SearchCursor?>>()

    override suspend fun search(
        query: String,
        cursor: SearchCursor?,
    ): ChannelSearchResult {
        calls += query to cursor
        return result
    }
}

class FakeYouTubeFeedMetadataReader : YouTubeFeedMetadataReader {
    var read: suspend (ByteArray) -> FeedChannelMetadata? = { null }
    val bodies = mutableListOf<ByteArray>()

    override suspend fun read(body: ByteArray): FeedChannelMetadata? {
        bodies += body.copyOf()
        return read.invoke(body)
    }
}

class FakeYouTubeEnricher : YouTubeEnricher {
    var result: EnrichResult = EnrichResult.Unsupported
    var page: UploadsPageResult = UploadsPageResult.Unsupported
    val calls = mutableListOf<Triple<String, Set<String>, Int>>()
    val pages = mutableListOf<Triple<String, Int, UploadsCursor?>>()

    override suspend fun enrich(
        channelId: String,
        videoIds: Set<String>,
        variants: Int,
    ): EnrichResult {
        calls += Triple(channelId, videoIds.toSet(), variants)
        return result
    }

    override suspend fun uploadsPage(
        channelId: String,
        variant: Int,
        cursor: UploadsCursor?,
    ): UploadsPageResult {
        pages += Triple(channelId, variant, cursor)
        return page
    }
}

class FakeYouTubeHealth : YouTubeHealth {
    override val state = MutableStateFlow(YouTubeHealthState())
    var gate: ExtractionGate = ExtractionGate.Allow
    val reports = mutableListOf<Any>()
    var loads = 0
    var retries = 0

    override suspend fun awaitLoaded() {
        loads++
    }

    override fun extractionGate(nowMs: Long): ExtractionGate = gate

    override fun reportExtraction(outcome: ExtractionOutcome) {
        reports += outcome
    }

    override fun reportRateLimited(nowMs: Long) {
        reports += "rate" to nowMs
    }

    override fun reportFeedOutage(nowMs: Long) {
        reports += "outage" to nowMs
    }

    override fun reportFeedRecovered() {
        reports += "recovered"
    }

    override fun reportFeedRateLimited(
        nowMs: Long,
        retryAfterMs: Long?,
    ) {
        reports += nowMs to retryAfterMs
    }

    override fun reportEngineVersion(version: String?) {
        reports += "version" to version
    }

    override fun retryNow() {
        retries++
    }
}

class FakeYouTubeFeedOutageMonitor : YouTubeFeedOutageMonitor {
    var gate: suspend (Long, Long) -> FeedGate = { _, _ -> FeedGate.Fetch }
    val gates = mutableListOf<Pair<Long, Long>>()
    val reports = mutableListOf<Triple<Long, FeedOutcome, Long>>()
    val finished = mutableListOf<Pair<Int, Long>>()

    override suspend fun gate(
        podcastId: Long,
        nowMs: Long,
    ): FeedGate {
        gates += podcastId to nowMs
        return gate.invoke(podcastId, nowMs)
    }

    override suspend fun report(
        podcastId: Long,
        outcome: FeedOutcome,
        nowMs: Long,
    ) {
        reports += Triple(podcastId, outcome, nowMs)
    }

    override suspend fun onRunFinished(
        totalSubscribed: Int,
        nowMs: Long,
    ) {
        finished += totalSubscribed to nowMs
    }
}

class FakeYouTubeChannelRepository : YouTubeChannelRepository {
    var subscribedId: Long? = null

    override suspend fun subscribedPodcastId(channelId: String): Long? = subscribedId

    val variants = mutableMapOf<Long, Int>()
    val art = mutableListOf<Long>()
    val older = mutableListOf<Long>()
    val searches = mutableListOf<String>()
    val rechecks = mutableListOf<Long>()
    var olderResult: LoadOlderResult = LoadOlderResult.Unsupported
    var alternative: RssAlternative? = null
    var availability: Availability? = null

    override suspend fun setVariants(
        podcastId: Long,
        variants: Int,
    ) {
        require(
            YouTubeVariantBits
                .isValid(variants),
        )
        this.variants[podcastId] = variants
    }

    override suspend fun ensureChannelArt(podcastId: Long) {
        art += podcastId
    }

    override suspend fun loadOlder(podcastId: Long): LoadOlderResult {
        older += podcastId
        return olderResult
    }

    override suspend fun findRssAlternative(channelTitle: String): RssAlternative? {
        searches += channelTitle
        return alternative
    }

    override suspend fun recheckAvailability(episodeId: Long): Availability? {
        rechecks += episodeId
        return availability
    }
}

class FakeYouTubeSubscriptionWriter : YouTubeSubscriptionWriter {
    data class Call(
        val channel: ChannelResolution.Resolved,
        val variants: Int,
        val groupIds: Set<Long>,
        val policy: YouTubeDuplicatePolicy,
        val customTitle: String?,
    )

    val calls = mutableListOf<Call>()
    var write: suspend (
        Call,
    ) -> Outcome<YouTubeSubscription, SubscribeError> = { Outcome.Failure(SubscribeError.YouTubeNotYetAvailable) }

    override suspend fun write(
        channel: ChannelResolution.Resolved,
        variants: Int,
        groupIds: Set<Long>,
        duplicatePolicy: YouTubeDuplicatePolicy,
        customTitle: String?,
    ): Outcome<YouTubeSubscription, SubscribeError> {
        if (!YouTubeVariantBits.isValid(variants)) return Outcome.Failure(SubscribeError.InvalidUrl)
        val call = Call(channel, variants, groupIds.toSet(), duplicatePolicy, customTitle)
        calls += call
        return write.invoke(call)
    }
}

class FakeYouTubeChannelMetadata : YouTubeChannelMetadata {
    val calls = mutableListOf<Pair<Long, ChannelMetadataReason>>()
    var refresh: suspend (
        Long,
        ChannelMetadataReason,
    ) -> ChannelMetadataResult = { _, _ -> ChannelMetadataResult.DEFERRED }

    override suspend fun refresh(
        podcastId: Long,
        reason: ChannelMetadataReason,
    ): ChannelMetadataResult {
        calls += podcastId to reason
        return refresh.invoke(podcastId, reason)
    }
}

class FakeYouTubeExternalOpen : YouTubeExternalOpen {
    val undos = mutableListOf<Long>()

    override suspend fun undo(episodeId: Long) {
        undos += episodeId
    }

    val calls = mutableListOf<Pair<Long, YouTubeOpenContext>>()
    var onOpened: suspend (Long, YouTubeOpenContext) -> Boolean = { _, _ -> false }

    override suspend fun onOpened(
        episodeId: Long,
        context: YouTubeOpenContext,
    ): Boolean {
        calls += episodeId to context
        return onOpened.invoke(episodeId, context)
    }
}
