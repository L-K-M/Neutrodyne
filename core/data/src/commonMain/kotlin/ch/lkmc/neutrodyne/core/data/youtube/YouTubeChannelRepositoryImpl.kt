// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data.youtube

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Nfc
import ch.lkmc.neutrodyne.core.data.refresh.RefreshOrigin
import ch.lkmc.neutrodyne.core.data.refresh.RefreshScheduler
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.ChannelMetadataReason
import ch.lkmc.neutrodyne.core.domain.LoadOlderResult
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.domain.RssAlternative
import ch.lkmc.neutrodyne.core.domain.SearchRepository
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.domain.YouTubeAvailabilityRecorder
import ch.lkmc.neutrodyne.core.domain.YouTubeChannelMetadata
import ch.lkmc.neutrodyne.core.domain.YouTubeChannelRepository
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.YouTubeVariantBits
import ch.lkmc.neutrodyne.core.model.settings.YouTubeSettingKeys
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.youtube.api.VideoFactsResult
import ch.lkmc.neutrodyne.youtube.api.YouTubeEnricher
import ch.lkmc.neutrodyne.youtube.api.YouTubeFeedUrls
import ch.lkmc.neutrodyne.youtube.api.YouTubeUrlClassifier
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class YouTubeChannelRepositoryImpl(
    private val db: NeutrodyneDatabase,
    private val metadata: YouTubeChannelMetadata,
    private val scheduler: RefreshScheduler,
    private val search: SearchRepository,
    private val settings: SettingsRepository,
    private val older: YouTubeOlderLoader,
    private val enricher: YouTubeEnricher,
    private val availability: YouTubeAvailabilityRecorder,
    private val enrichment: YouTubeEnrichment,
) : YouTubeChannelRepository {
    override suspend fun subscribedPodcastId(channelId: String): Long? =
        db.podcastDao().byFeedKey(checkNotNull(UrlNormalizer.forIdentity(YouTubeFeedUrls.canonical(channelId))))?.id

    override suspend fun setVariants(
        podcastId: Long,
        variants: Int,
    ) {
        require(YouTubeVariantBits.isValidForSettings(variants))
        if (!older.setVariants(podcastId, variants)) return
        enrichment.cancel(podcastId)
        scheduler.enqueueNow(RefreshScope.Podcasts(listOf(podcastId)), true, false, RefreshOrigin.MANUAL)
    }

    override suspend fun ensureChannelArt(podcastId: Long) {
        metadata.refresh(podcastId, ChannelMetadataReason.DETAIL)
    }

    override suspend fun findRssAlternative(channelTitle: String): RssAlternative? {
        if (!settings.get(YouTubeSettingKeys.SUGGEST_RSS)) return null
        val wanted = matchName(channelTitle)
        if (wanted.isEmpty()) return null
        return withTimeoutOrNull(RSS_TIMEOUT_MS) {
            search
                .search(channelTitle)
                .map { result ->
                    result.hits
                        .firstOrNull { matchName(it.title) == wanted || it.author?.let(::matchName) == wanted }
                        ?.let { hit ->
                            RssAlternative(
                                hit.feedUrl,
                                hit.title,
                                hit.artworkUrl,
                                hit.providers.joinToString { it.host },
                            )
                        } to result.complete
                }.first { (hit, complete) -> hit != null || complete }
                .first
        }
    }

    override suspend fun loadOlder(podcastId: Long): LoadOlderResult = older.load(podcastId)

    override suspend fun recheckAvailability(episodeId: Long): Availability? =
        withTimeoutOrNull(45_000) {
            val row = db.episodeDao().byId(episodeId) ?: return@withTimeoutOrNull null
            if (db.podcastDao().byId(row.podcastId)?.sourceType != SourceType.YOUTUBE_CHANNEL) {
                return@withTimeoutOrNull null
            }
            val video = row.externalMediaId ?: return@withTimeoutOrNull null
            val result = enricher.facts(video) as? VideoFactsResult.Ok ?: return@withTimeoutOrNull null
            val reason = result.facts.availability ?: return@withTimeoutOrNull null
            if (result.facts.videoId != video) return@withTimeoutOrNull null
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            db.withWriteTransaction {
                val current = db.episodeDao().byId(episodeId) ?: return@withWriteTransaction null
                if (current.podcastId != row.podcastId || current.externalMediaId != video) {
                    return@withWriteTransaction null
                }
                availability.record(episodeId, reason)
                reason
            }
        }

    private companion object {
        const val RSS_TIMEOUT_MS = 8_000L

        fun matchName(text: String): String = Nfc.compatibility(text).lowercase().filter(Char::isLetterOrDigit)
    }
}
