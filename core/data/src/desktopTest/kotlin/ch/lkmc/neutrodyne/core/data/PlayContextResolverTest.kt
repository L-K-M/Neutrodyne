// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.data.playback.PlayContextResolverImpl
import ch.lkmc.neutrodyne.core.database.PlaySessionEntity
import ch.lkmc.neutrodyne.core.database.QueueEntryEntity
import ch.lkmc.neutrodyne.core.domain.ContextEligibility
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.FilterFlagBits
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.YouTubeVariantBits
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.core.testing.database.downloadEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeStateEntity
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.memberEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PlayContextResolverTest {
    private val db = newDb()
    private val capabilities = FakeYouTubeCapabilitiesSource()
    private val resolver = PlayContextResolverImpl(db, capabilities)

    @AfterTest fun close() = db.close()

    @Test fun specsCarryFiltersAndApplyEntryPointRules() =
        runTest {
            val filters =
                FeedFilters(downloadedOnly = true, inProgressOnly = true, media = MediaFilter.AUDIO, minSortDate = 42)
            for ((source, type, id) in listOf(
                Triple(FeedSource.All, ContextType.ALL, null),
                Triple(FeedSource.Ungrouped, ContextType.UNGROUPED, null),
                Triple(FeedSource.Group(7), ContextType.GROUP, 7L),
                Triple(FeedSource.Podcast(9), ContextType.PODCAST, 9L),
            )) {
                val spec = resolver.spec(source, filters, FeedOrder.OLDEST_FIRST)
                assertEquals(type, spec.type)
                assertEquals(id, spec.contextId)
                assertEquals(filters.toFlags() or FilterFlagBits.UNPLAYED, spec.filterFlags)
                assertEquals(MediaFilter.AUDIO, spec.mediaFilter)
                assertEquals(if (type == ContextType.PODCAST) null else 42L, spec.minSortDate)
                val ordered = type == ContextType.GROUP || type == ContextType.PODCAST
                assertEquals(if (ordered) FeedOrder.OLDEST_FIRST else FeedOrder.NEWEST_FIRST, spec.order)
                assertEquals(type == ContextType.GROUP, spec.boundStartBySubscription)
            }
            assertFalse(resolver.spec(FeedSource.Group(7), filters, FeedOrder.NEWEST_FIRST).boundStartBySubscription)
        }

    @Test fun startSelectionExcludesQueuedPlayedAndCurrentRows() =
        runTest {
            val podcast = db.podcastDao().insertPodcast(podcastEntity())
            val ids =
                (3 downTo 1).map {
                    db.episodeDao().insertStub(episodeEntity(podcastId = podcast, sortDate = it * 100L))
                }
            db.playSessionDao().write(PlaySessionEntity(currentEpisodeId = ids[0], updatedAt = 1))
            val spec = resolver.spec(FeedSource.Podcast(podcast), FeedFilters(), FeedOrder.NEWEST_FIRST)
            assertEquals(ids[1], resolver.startItem(spec))
            db.queueDao().insert(QueueEntryEntity(episodeId = ids[1], orderKey = "a0", addedAt = 1))
            assertEquals(ids[2], resolver.startItem(spec))
            db.episodeStateDao().upsert(episodeStateEntity(ids[2], playedAt = 1))
            assertNull(resolver.startItem(spec))
            assertNull(resolver.startItem(spec.copy(type = ContextType.EXTERNAL)))
        }

    @Test fun bothYouTubeSourcesRequeryCapabilitiesAndRespectHiddenShorts() =
        runTest {
            for (source in listOf(SourceType.YOUTUBE_CHANNEL, SourceType.YOUTUBE_PLAYLIST)) {
                val podcast = db.podcastDao().insertPodcast(podcastEntity { copy(sourceType = source) })
                val video = db.episodeDao().insertStub(episodeEntity(podcastId = podcast, sortDate = 100))
                db.episodeDao().insertStub(episodeEntity(podcastId = podcast, sortDate = 200) { copy(isShort = true) })
                val spec = resolver.spec(FeedSource.Podcast(podcast), FeedFilters(), FeedOrder.NEWEST_FIRST)
                capabilities.capabilities.value = capabilities.capabilities.value.copy(inAppPlayback = false)
                assertNull(resolver.startItem(spec))
                assertEquals(video, resolver.startItem(spec, ContextEligibility.INCLUDE_EXTERNAL_YOUTUBE))
                capabilities.capabilities.value = capabilities.capabilities.value.copy(inAppPlayback = true)
                assertEquals(video, resolver.startItem(spec))
            }
            val showing =
                db.podcastDao().insertPodcast(
                    podcastEntity {
                        copy(
                            sourceType = SourceType.YOUTUBE_CHANNEL,
                            youtubeVariants = YouTubeVariantBits.LONG_FORM or YouTubeVariantBits.SHORTS,
                        )
                    },
                )
            val short = db.episodeDao().insertStub(episodeEntity(podcastId = showing) { copy(isShort = true) })
            val shortSpec = resolver.spec(FeedSource.Podcast(showing), FeedFilters(), FeedOrder.NEWEST_FIRST)
            assertEquals(short, resolver.startItem(shortSpec))
        }

    @Test fun oldestGroupPrefersSubscriptionBoundThenFallsBackButPodcastDoesNot() =
        runTest {
            val podcast = db.podcastDao().insertPodcast(podcastEntity(subscribedAt = 1_000))
            val group = db.groupDao().insert(groupEntity(orderKey = "a0"))
            db.groupDao().insertMember(memberEntity(group, podcast, "a0"))
            val old = db.episodeDao().insertStub(episodeEntity(podcastId = podcast, sortDate = 500))
            val recent = db.episodeDao().insertStub(episodeEntity(podcastId = podcast, sortDate = 1_500))
            val spec = resolver.spec(FeedSource.Group(group), FeedFilters(), FeedOrder.OLDEST_FIRST)
            assertEquals(recent, resolver.startItem(spec))
            db.episodeStateDao().upsert(episodeStateEntity(recent, playedAt = 1))
            assertEquals(old, resolver.startItem(spec))
            val podcastSpec =
                resolver.spec(FeedSource.Podcast(podcast), FeedFilters(minSortDate = 1_200), FeedOrder.OLDEST_FIRST)
            assertNull(podcastSpec.minSortDate)
            assertEquals(old, resolver.startItem(podcastSpec))
        }
}
