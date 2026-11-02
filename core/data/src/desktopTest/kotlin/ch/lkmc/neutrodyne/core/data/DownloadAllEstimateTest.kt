// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.data.playback.PlayContextResolverImpl
import ch.lkmc.neutrodyne.core.data.repo.FeedRepositoryImpl
import ch.lkmc.neutrodyne.core.database.EpisodeEntity
import ch.lkmc.neutrodyne.core.database.FeedQueryBuilder
import ch.lkmc.neutrodyne.core.database.PlaySessionEntity
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.DownloadAllEstimate
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedPrefs
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.YouTubeVariantBits
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.downloadEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeStateEntity
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.memberEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.core.testing.database.queueEntryEntity
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real Room queries and repository estimates; no engine, network or queue writes are involved. */
class DownloadAllEstimateTest {
    private val db = newDb()
    private val clock = TestClock(nowMs = TestClock.DEFAULT_NOW + 123_456)
    private val settings = FakeSettingsRepository()
    private val capabilities = FakeYouTubeCapabilitiesSource()
    private val contexts = PlayContextResolverImpl(db, capabilities)
    private val repo = FeedRepositoryImpl(db, settings, clock, contexts, capabilities)

    @AfterTest fun close() = db.close()

    @Test fun capKeepsNewestTwoHundredAndSizesOnlyThoseIds() =
        runTest {
            val podcast = podcast()
            val ids =
                (1..205).map { index ->
                    episode(podcast, sortDate = index.toLong()) {
                        // The five oldest sizes must not enter the capped confirmation.
                        copy(enclosureLength = if (index <= 5) 9_000_000 else 100_000)
                    }
                }
            val estimate = repo.downloadAllEstimate(FeedSource.Podcast(podcast))
            assertEquals(ids.takeLast(200).reversed(), estimate.episodeIds)
            assertEquals(205, estimate.totalCandidates)
            assertEquals(20_000_000, estimate.knownBytes)
            assertEquals(0, estimate.unknownSizeCount)
            assertTrue(estimate.capped)
            assertTrue(db.downloadDao().allRows().isEmpty())
        }

    @Test fun exactlyTwoHundredIsNotCappedAndTiesUseDescendingId() =
        runTest {
            val podcast = podcast()
            val ids = (1..200).map { episode(podcast, sortDate = 50) }
            val estimate = repo.downloadAllEstimate(FeedSource.Podcast(podcast))
            assertEquals(ids.reversed(), estimate.episodeIds)
            assertEquals(200, estimate.totalCandidates)
            assertEquals(200, estimate.unknownSizeCount)
            assertFalse(estimate.capped)
        }

    @Test fun missingSourcesAndEmptyFeedsHaveZeroEstimates() =
        runTest {
            val sources = listOf(FeedSource.All, FeedSource.Ungrouped, FeedSource.Group(987), FeedSource.Podcast(987))
            for (source in sources) {
                assertEquals(DownloadAllEstimate(emptyList(), 0, 0, 0, false), repo.downloadAllEstimate(source))
            }
        }

    @Test fun failedAndMissingAreCandidatesInBothLanesOtherStatesAreExcluded() =
        runTest {
            val podcast = podcast()
            val expected = mutableListOf<Long>()
            expected += episode(podcast, sortDate = 1)
            for (lane in DownloadLane.entries) {
                for (state in DownloadState.entries) {
                    val id = episode(podcast, sortDate = 1)
                    db.downloadDao().insert(downloadEntity(id, state = state, lane = lane))
                    if (state == DownloadState.FAILED || state == DownloadState.MISSING) expected += id
                }
            }
            val estimate = repo.downloadAllEstimate(FeedSource.Podcast(podcast))
            assertEquals(expected.reversed(), estimate.episodeIds)
            assertEquals(expected.size, estimate.totalCandidates)
        }

    @Test fun tombstonesAndPlayedRowsStayExcludedEvenWhenFailedOrMissing() =
        runTest {
            val podcast = podcast()
            val wanted = episode(podcast)
            for (state in listOf(null, DownloadState.FAILED, DownloadState.MISSING)) {
                for (played in listOf(false, true)) {
                    val id = episode(podcast)
                    db.episodeStateDao().upsert(
                        episodeStateEntity(id) {
                            if (played) copy(playedAt = 1) else copy(downloadDismissedAt = 1)
                        },
                    )
                    if (state != null) db.downloadDao().insert(downloadEntity(id, state = state))
                }
            }
            assertEquals(listOf(wanted), repo.downloadAllEstimate(FeedSource.Podcast(podcast)).episodeIds)
        }

    @Test fun currentAndUpNextRemainCandidatesAndEstimateDoesNotMutateThem() =
        runTest {
            val podcast = podcast()
            val current = episode(podcast, sortDate = 10)
            val queued = episode(podcast, sortDate = 20)
            val session = PlaySessionEntity(currentEpisodeId = current, updatedAt = 1)
            db.playSessionDao().write(session)
            db.queueDao().insert(queueEntryEntity(queued, "a0"))
            db.episodeStateDao().upsert(episodeStateEntity(current) { copy(startedAt = 1) })
            assertEquals(listOf(queued, current), repo.downloadAllEstimate(FeedSource.Podcast(podcast)).episodeIds)
            assertEquals(session, db.playSessionDao().get())
            assertEquals(listOf(queued), db.queueDao().entries().map { it.episodeId })
            assertTrue(db.downloadDao().allRows().isEmpty())
        }

    @Test fun sourcePredicatesRespectMembershipAndIncludeInAllWithoutDuplicates() =
        runTest {
            val included = podcast()
            val hidden = db.podcastDao().insertPodcast(podcastEntity { copy(includeInAll = false) })
            val ungrouped = db.podcastDao().insertPodcast(podcastEntity { copy(includeInAll = false) })
            val group = db.groupDao().insert(groupEntity(name = "One", orderKey = "a"))
            val other = db.groupDao().insert(groupEntity(name = "Two", orderKey = "b"))
            db.groupDao().insertMember(memberEntity(group, included, "a"))
            db.groupDao().insertMember(memberEntity(group, hidden, "b"))
            db.groupDao().insertMember(memberEntity(other, included, "a"))
            val a = episode(included, sortDate = 1)
            val b = episode(hidden, sortDate = 2)
            val c = episode(ungrouped, sortDate = 3)
            assertEquals(listOf(a), repo.downloadAllEstimate(FeedSource.All).episodeIds)
            assertEquals(listOf(b, a), repo.downloadAllEstimate(FeedSource.Group(group)).episodeIds)
            assertEquals(listOf(a), repo.downloadAllEstimate(FeedSource.Group(other)).episodeIds)
            assertEquals(listOf(c), repo.downloadAllEstimate(FeedSource.Ungrouped).episodeIds)
            assertEquals(listOf(b), repo.downloadAllEstimate(FeedSource.Podcast(hidden)).episodeIds)
            db.groupDao().insertMember(memberEntity(group, ungrouped, "c"))
            assertEquals(emptyList(), repo.downloadAllEstimate(FeedSource.Ungrouped).episodeIds)
            assertEquals(listOf(c, b, a), repo.downloadAllEstimate(FeedSource.Group(group)).episodeIds)
        }

    @Test fun groupViewFiltersAndRoundedAgeBoundConstrainCandidatesAndCount() =
        runTest {
            val podcast = podcast()
            val group = db.groupDao().insert(groupEntity(orderKey = "a"))
            db.groupDao().insertMember(memberEntity(group, podcast, "a"))
            val source = FeedSource.Group(group)
            val bound = clock.now() - clock.now().mod(FeedPrefs.HOUR_MS) - DAY_MS
            val old = episode(podcast, sortDate = bound - 1)
            val wanted = episode(podcast, sortDate = bound)
            val video = episode(podcast, sortDate = bound + 1) { copy(isVideo = true) }
            episode(podcast, sortDate = bound + 2) // Not started, so not in the selected view.
            for (id in listOf(old, wanted, video)) {
                db.episodeStateDao().upsert(episodeStateEntity(id) { copy(startedAt = 1) })
            }
            repo.setHideOlderThanDays(source, 1)
            repo.setFilters(source, FeedFilters(inProgressOnly = true, media = MediaFilter.AUDIO))
            var estimate = repo.downloadAllEstimate(source)
            assertEquals(listOf(wanted), estimate.episodeIds)
            assertEquals(1, estimate.totalCandidates)
            repo.setFilters(source, FeedFilters(inProgressOnly = true, media = MediaFilter.VIDEO))
            assertEquals(listOf(video), repo.downloadAllEstimate(source).episodeIds)
            repo.setHideOlderThanDays(source, null)
            repo.setFilters(source, FeedFilters(inProgressOnly = true, media = MediaFilter.AUDIO))
            estimate = repo.downloadAllEstimate(source)
            assertEquals(listOf(wanted, old), estimate.episodeIds)
            assertEquals(2, estimate.totalCandidates)
        }

    @Test fun virtualFeedsUseTheirOwnPersistedViewFilters() =
        runTest {
            val podcast = podcast()
            val bound = clock.now() - clock.now().mod(FeedPrefs.HOUR_MS) - DAY_MS
            episode(podcast, sortDate = bound - 1)
            val audio = episode(podcast, sortDate = bound)
            val video = episode(podcast, sortDate = bound + 1) { copy(isVideo = true) }
            repo.setHideOlderThanDays(FeedSource.All, 1)
            repo.setHideOlderThanDays(FeedSource.Ungrouped, 1)
            repo.setFilters(FeedSource.All, FeedFilters(media = MediaFilter.AUDIO))
            repo.setFilters(FeedSource.Ungrouped, FeedFilters(media = MediaFilter.VIDEO))
            assertEquals(listOf(audio), repo.downloadAllEstimate(FeedSource.All).episodeIds)
            assertEquals(listOf(video), repo.downloadAllEstimate(FeedSource.Ungrouped).episodeIds)
        }

    @Test fun downloadedFilterHasNoNewCandidatesEvenForCompletedUnplayedRows() =
        runTest {
            val podcast = podcast()
            val id = episode(podcast)
            db.downloadDao().insert(downloadEntity(id, state = DownloadState.COMPLETED))
            repo.setFilters(FeedSource.All, FeedFilters(downloadedOnly = true))
            assertEquals(DownloadAllEstimate(emptyList(), 0, 0, 0, false), repo.downloadAllEstimate(FeedSource.All))
        }

    @Test fun oldestFirstGroupStillOffersNewestFirstWithoutSubscriptionBound() =
        runTest {
            val podcast = db.podcastDao().insertPodcast(podcastEntity(subscribedAt = 1_000))
            val group = db.groupDao().insert(groupEntity(orderKey = "a").copy(playOrder = FeedOrder.OLDEST_FIRST))
            db.groupDao().insertMember(memberEntity(group, podcast, "a"))
            val old = episode(podcast, sortDate = 100)
            val recent = episode(podcast, sortDate = 1_100)
            repo.setFeedOrder(FeedSource.Group(group), FeedOrder.OLDEST_FIRST)
            assertEquals(listOf(recent, old), repo.downloadAllEstimate(FeedSource.Group(group)).episodeIds)
        }

    @Test fun unavailableAndHiddenShortsAreNotCandidates() =
        runTest {
            val podcast = podcast()
            val wanted = episode(podcast)
            for (availability in Availability.entries.filter { it != Availability.AVAILABLE }) {
                episode(podcast) { copy(availability = availability) }
            }
            episode(podcast) { copy(isShort = true) }
            assertEquals(listOf(wanted), repo.downloadAllEstimate(FeedSource.Podcast(podcast)).episodeIds)
            val shorts =
                db.podcastDao().insertPodcast(
                    podcastEntity { copy(youtubeVariants = YouTubeVariantBits.SHORTS) },
                )
            val visible = episode(shorts) { copy(isShort = true) }
            assertEquals(listOf(visible), repo.downloadAllEstimate(FeedSource.Podcast(shorts)).episodeIds)
        }

    @Test fun youtubeUsesLiveDownloadsCapabilityIndependentlyOfPlayback() =
        runTest {
            val rss = podcast()
            val audio = episode(rss, sortDate = 1)
            val expected = mutableListOf(audio)
            for (type in listOf(SourceType.YOUTUBE_CHANNEL, SourceType.YOUTUBE_PLAYLIST)) {
                val channel = db.podcastDao().insertPodcast(podcastEntity { copy(sourceType = type) })
                expected +=
                    episode(channel, sortDate = 1) {
                        copy(enclosureUrl = null, externalMediaId = "video-$channel")
                    }
                // Even a legacy enclosure must not bypass the external-mode capability gate.
                expected += episode(channel, sortDate = 1) { copy(externalMediaId = "legacy-$channel") }
                episode(channel) { copy(enclosureUrl = null, externalMediaId = null) }
            }
            capabilities.capabilities.value =
                capabilities.capabilities.value.copy(
                    inAppPlayback = true,
                    downloads = false,
                )
            assertEquals(listOf(audio), repo.downloadAllEstimate(FeedSource.All).episodeIds)
            capabilities.capabilities.value =
                capabilities.capabilities.value.copy(
                    inAppPlayback = false,
                    downloads = true,
                )
            val estimate = repo.downloadAllEstimate(FeedSource.All)
            assertEquals(expected.reversed(), estimate.episodeIds)
            assertEquals(expected.size, estimate.totalCandidates)
            capabilities.capabilities.value = capabilities.capabilities.value.copy(downloads = false)
            assertEquals(listOf(audio), repo.downloadAllEstimate(FeedSource.All).episodeIds)
        }

    @Test fun rssWithoutEnclosureIsExcludedWhenYoutubeDownloadsAreUnavailable() =
        runTest {
            val podcast = podcast()
            episode(podcast) { copy(enclosureUrl = null) }
            assertEquals(0, repo.downloadAllEstimate(FeedSource.Podcast(podcast)).totalCandidates)
        }

    @Test fun enclosureThresholdAndDurationFallbackUseBytesPerMillisecond() =
        runTest {
            val podcast = podcast()
            episode(podcast) { copy(enclosureLength = 99_999) }
            episode(podcast) { copy(enclosureLength = 100_000, durationMs = 10_000) }
            episode(podcast) { copy(enclosureLength = 123_456, durationMs = 10_000) }
            episode(podcast) { copy(enclosureLength = 1, durationMs = 1_500) }
            episode(podcast) { copy(durationMs = 2_000) }
            val measured = episode(podcast) { copy(durationMs = 9_000) }
            db.episodeStateDao().upsert(episodeStateEntity(measured) { copy(measuredDurationMs = 3_000) })
            episode(podcast) { copy(enclosureLength = 0, durationMs = 0) }
            episode(podcast) { copy(enclosureLength = -1, durationMs = -10) }
            val estimate = repo.downloadAllEstimate(FeedSource.Podcast(podcast))
            assertEquals(8, estimate.totalCandidates)
            assertEquals(3, estimate.unknownSizeCount)
            assertEquals(100_000L + 123_456L + (1_500L + 2_000L + 3_000L) * 16, estimate.knownBytes)
        }

    @Test fun veryLargeDurationAndAggregateSizesDoNotOverflow() =
        runTest {
            val podcast = podcast()
            val id = episode(podcast) { copy(durationMs = Long.MAX_VALUE) }
            val durationEstimate = repo.downloadAllEstimate(FeedSource.Podcast(podcast))
            assertEquals(Long.MAX_VALUE / 16 * 16, durationEstimate.knownBytes)
            assertEquals(listOf(id), durationEstimate.episodeIds)
            episode(podcast) { copy(enclosureLength = Long.MAX_VALUE) }
            assertEquals(Long.MAX_VALUE, repo.downloadAllEstimate(FeedSource.Podcast(podcast)).knownBytes)
        }

    @Test fun queryBuilderRejectsExternalContextAndMissingSourceIds() =
        runTest {
            val spec = contexts.spec(FeedSource.All, FeedFilters(), FeedOrder.NEWEST_FIRST)
            for (invalid in listOf(ContextType.EXTERNAL, ContextType.GROUP, ContextType.PODCAST)) {
                assertFailsWith<IllegalArgumentException> {
                    FeedQueryBuilder.downloadAll(spec.copy(type = invalid), false)
                }
            }
        }

    private suspend fun podcast(): Long = db.podcastDao().insertPodcast(podcastEntity())

    private suspend fun episode(
        podcast: Long,
        sortDate: Long = clock.now(),
        block: EpisodeEntity.() -> EpisodeEntity = { this },
    ): Long =
        db.episodeDao().insertStub(
            episodeEntity(podcastId = podcast, sortDate = sortDate) {
                copy(enclosureUrl = "https://example.com/media.mp3").block()
            },
        )

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
