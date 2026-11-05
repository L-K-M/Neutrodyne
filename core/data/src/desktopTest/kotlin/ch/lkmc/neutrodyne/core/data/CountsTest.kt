// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.room3.useReaderConnection
import app.cash.turbine.test
import ch.lkmc.neutrodyne.core.data.repo.EpisodeRepositoryImpl
import ch.lkmc.neutrodyne.core.data.repo.FeedRepositoryImpl
import ch.lkmc.neutrodyne.core.database.EpisodeEntity
import ch.lkmc.neutrodyne.core.database.EpisodePositionEntity
import ch.lkmc.neutrodyne.core.database.FeedQueryBuilder
import ch.lkmc.neutrodyne.core.domain.MarkFeedPlayedResult
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.FeedCounts
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedPrefs
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.GroupCounts
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.model.VirtualCounts
import ch.lkmc.neutrodyne.core.model.settings.GroupsSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakePlayContextResolver
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeStateEntity
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.memberEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.core.testing.database.queueEntryEntity
import ch.lkmc.neutrodyne.feeds.jvm.html.JsoupShowNotesSanitizer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M2 AC9/AC11 over the bundled driver; no A2 seed is present on the A1/B2 stack. */
@OptIn(ExperimentalCoroutinesApi::class)
class CountsTest {
    private val db = TestDb.inMemory()
    private val settings = FakeSettingsRepository()
    private val clock = TestClock()
    private val feeds =
        FeedRepositoryImpl(db, settings, clock, FakePlayContextResolver(), FakeYouTubeCapabilitiesSource())
    private val episodes = EpisodeRepositoryImpl(db, JsoupShowNotesSanitizer(), clock)
    private val now = clock.now()
    private val since = now - GroupCounts.WINDOW_DAYS * GroupCounts.DAY_MS

    @AfterTest fun close() = db.close()

    @Test fun windowEdgesOverlapAndEmptyGroups() =
        runTest {
            val a = group("A")
            val b = group("B")
            val empty = group("Empty")
            val noEpisodes = group("No episodes")
            val shared = podcast(a, b)
            podcast(noEpisodes)
            episode(shared, since - 1)
            episode(shared, since)
            episode(shared, now)
            val played = episode(shared, now)
            db.episodeStateDao().upsert(episodeStateEntity(played, playedAt = now))
            val hiddenFromAll = db.podcastDao().insertPodcast(podcastEntity { copy(includeInAll = false) })
            db.groupDao().insertMember(memberEntity(a, hiddenFromAll, "b"))
            episode(hiddenFromAll, now)
            val ungrouped = db.podcastDao().insertPodcast(podcastEntity { copy(includeInAll = false) })
            episode(ungrouped, now)

            assertEquals(
                mapOf(a to FeedCounts(3, 0), b to FeedCounts(2, 0), empty to FeedCounts(), noEpisodes to FeedCounts()),
                feeds.observeGroupCounts(since).first(),
            )
            assertEquals(VirtualCounts(FeedCounts(2, 0), FeedCounts(1, 0)), feeds.observeVirtualCounts(since).first())
            // Passing an older window cannot reintroduce an imported back catalogue.
            assertEquals(feeds.observeGroupCounts(since).first(), feeds.observeGroupCounts(0).first())
        }

    @Test fun badgesExcludeAllUnavailableStatesAndOnlyHiddenShorts() =
        runTest {
            val g = group("G")
            val hidden = podcast(g)
            val shown = db.podcastDao().insertPodcast(podcastEntity { copy(youtubeVariants = 3) })
            db.groupDao().insertMember(memberEntity(g, shown, "b"))
            episode(hidden) { copy(isShort = true) }
            episode(shown) { copy(isShort = true) }
            for (availability in Availability.entries) {
                episode(hidden) { copy(availability = availability) }
            }
            assertEquals(mapOf(g to FeedCounts(2, 0)), feeds.observeGroupCounts(since).first())
            assertEquals(FeedCounts(2, 0), feeds.observeVirtualCounts(since).first().all)
        }

    @Test fun newRequiresUnplayedIsNewAndStrictVisitOrCreationBaseline() =
        runTest {
            val g = group("G", createdAt = now - 10)
            val p = podcast(g)
            episode(p) { copy(isNew = true, firstSeenAt = now - 11) }
            episode(p) { copy(isNew = true, firstSeenAt = now - 10) }
            episode(p) { copy(isNew = true, firstSeenAt = now - 9) }
            episode(p) { copy(isNew = false, firstSeenAt = now) }
            val played = episode(p) { copy(isNew = true, firstSeenAt = now) }
            db.episodeStateDao().upsert(episodeStateEntity(played, playedAt = now))
            feeds.observeGroupCounts(since).test {
                assertEquals(FeedCounts(4, 1), awaitItem()[g])
                feeds.markVisited(FeedSource.Group(g), now - 9)
                assertEquals(FeedCounts(4, 0), awaitItem()[g])
            }
        }

    @Test fun virtualBaselinesInitializeAndVisitsRemainIndependent() =
        runTest {
            val p = podcast()
            episode(p) { copy(isNew = true, firstSeenAt = now) }
            episode(p) { copy(isNew = true, firstSeenAt = now + 1) }
            feeds.observeVirtualCounts(since).test {
                assertEquals(VirtualCounts(FeedCounts(2, 1), FeedCounts(2, 1)), awaitItem())
                assertEquals(now, settings.get(GroupsSettingKeys.ALL_LAST_VIEWED_AT))
                assertEquals(now, settings.get(GroupsSettingKeys.UNGROUPED_LAST_VIEWED_AT))
                feeds.markVisited(FeedSource.All, now + 1)
                assertEquals(VirtualCounts(FeedCounts(2, 0), FeedCounts(2, 1)), awaitItem())
                feeds.markVisited(FeedSource.Ungrouped, now + 1)
                assertEquals(VirtualCounts(FeedCounts(2, 0), FeedCounts(2, 0)), awaitItem())
            }
        }

    @Test fun ageBoundsNarrowBadgesAndViewFiltersDoNot() =
        runTest {
            val g = group("G")
            val p = podcast(g)
            val u = podcast()
            for (podcast in listOf(p, u)) {
                episode(podcast, now - 7 * GroupCounts.DAY_MS - 1)
                episode(podcast, now - 7 * GroupCounts.DAY_MS)
                episode(podcast, now)
            }
            feeds.setHideOlderThanDays(FeedSource.Group(g), 7)
            feeds.setHideOlderThanDays(FeedSource.All, 7)
            feeds.setHideOlderThanDays(FeedSource.Ungrouped, 1)
            feeds.setFilters(FeedSource.Group(g), FeedFilters(downloadedOnly = true, media = MediaFilter.VIDEO))
            assertEquals(FeedCounts(2, 0), feeds.observeGroupCounts(since).first()[g])
            assertEquals(VirtualCounts(FeedCounts(4, 0), FeedCounts(1, 0)), feeds.observeVirtualCounts(since).first())
            feeds.setHideOlderThanDays(FeedSource.Group(g), 90)
            assertEquals(FeedCounts(3, 0), feeds.observeGroupCounts(since).first()[g])
        }

    @Test fun hourlyWindowMovesWithoutDatabaseWritesAndRoundsDown() =
        runTest {
            // Keep SQL on virtual time so awaiting Room cannot advance the hourly clock.
            val timedDb = TestDb.inMemory(queryContext = StandardTestDispatcher(testScheduler))
            try {
                val movingClock = TestClock.from(testScheduler, now + FeedPrefs.HOUR_MS / 2)
                val repo =
                    FeedRepositoryImpl(
                        timedDb,
                        settings,
                        movingClock,
                        FakePlayContextResolver(),
                        FakeYouTubeCapabilitiesSource(),
                    )
                val g = timedDb.groupDao().insert(groupEntity(name = "G", orderKey = "a", createdAt = now))
                val p = timedDb.podcastDao().insertPodcast(podcastEntity())
                timedDb.groupDao().insertMember(memberEntity(g, p, "a"))
                timedDb.episodeDao().insertStub(episodeEntity(podcastId = p, sortDate = since))
                val window = movingClock.now() - GroupCounts.WINDOW_DAYS * GroupCounts.DAY_MS
                repo.observeGroupCounts(window).test {
                    assertEquals(FeedCounts(1, 0), awaitItem()[g])
                    advanceTimeBy(FeedPrefs.HOUR_MS / 2 - 1)
                    runCurrent()
                    expectNoEvents()
                    advanceTimeBy(1)
                    runCurrent()
                    assertEquals(FeedCounts(), awaitItem()[g])
                }
                timedDb.episodeDao().insertStub(
                    episodeEntity(podcastId = p, sortDate = now - GroupCounts.DAY_MS + FeedPrefs.HOUR_MS),
                )
                repo.setHideOlderThanDays(FeedSource.All, 1)
                repo.observeVirtualCounts(window).test {
                    assertEquals(FeedCounts(1, 0), awaitItem().all)
                    advanceTimeBy(FeedPrefs.HOUR_MS)
                    runCurrent()
                    assertEquals(FeedCounts(), awaitItem().all)
                }
            } finally {
                timedDb.close()
            }
        }

    @Test fun membershipAndEpisodeChangesInvalidateCounts() =
        runTest {
            val g = group("G")
            val p = podcast()
            episode(p)
            feeds.observeGroupCounts(since).test {
                assertEquals(FeedCounts(), awaitItem()[g])
                db.groupDao().insertMember(memberEntity(g, p, "a"))
                assertEquals(FeedCounts(1, 0), awaitItem()[g])
                episode(p)
                assertEquals(FeedCounts(2, 0), awaitItem()[g])
            }
            feeds.observeVirtualCounts(since).test {
                assertEquals(VirtualCounts(FeedCounts(2, 0), FeedCounts()), awaitItem())
                episode(p)
                assertEquals(VirtualCounts(FeedCounts(3, 0), FeedCounts()), awaitItem())
            }
        }

    @Test fun countMatchesChangedRowsForEverySourceAndCutoff() =
        runTest {
            val a = group("A")
            val b = group("B")
            val shared = podcast(a, b)
            val ungrouped = podcast()
            val excluded = db.podcastDao().insertPodcast(podcastEntity { copy(includeInAll = false) })
            val allIds = mutableListOf<Long>()
            for (p in listOf(shared, ungrouped, excluded)) {
                for (date in listOf(since - 1, now - 1, now, now + 1)) allIds += episode(p, date)
                allIds += episode(p) { copy(isShort = true) }
                for (availability in Availability.entries) allIds += episode(p) { copy(availability = availability) }
            }
            val alreadyPlayed = allIds.first()
            episodes.setPlayed(listOf(alreadyPlayed), true)
            val sources =
                listOf(
                    FeedSource.All,
                    FeedSource.Ungrouped,
                    FeedSource.Group(a),
                    FeedSource.Group(b),
                    FeedSource.Group(-1),
                    FeedSource.Podcast(shared),
                    FeedSource.Podcast(excluded),
                )
            for (source in sources) {
                for (cutoff in listOf(null, since, now, now + 1, 0L)) {
                    episodes.setPlayed(allIds.filter { it != alreadyPlayed }, false)
                    val expected = feeds.countUnplayed(source, cutoff)
                    val before = allIds.associateWith { db.episodeStateDao().byEpisode(it) }
                    assertEquals(
                        MarkFeedPlayedResult.Played(expected),
                        episodes.markFeedPlayed(source, cutoff, expected),
                    )
                    val changed = allIds.count { db.episodeStateDao().byEpisode(it) != before[it] }
                    assertEquals(expected, changed, "$source before $cutoff")
                    assertEquals(0, feeds.countUnplayed(source, cutoff))
                }
            }
        }

    @Test fun bulkSelectionHasStrictCutoffAndIgnoresBadgeWindowAndAgeLimit() =
        runTest {
            val g = group("G")
            val p = podcast(g)
            val old = episode(p, since - 1)
            episode(p, now)
            episode(p) { copy(isShort = true) }
            feeds.setHideOlderThanDays(FeedSource.Group(g), 1)
            assertEquals(1, feeds.countUnplayed(FeedSource.Group(g), now))
            assertEquals(MarkFeedPlayedResult.Played(1), episodes.markFeedPlayed(FeedSource.Group(g), now, 1))
            assertNotNull(db.episodeStateDao().byEpisode(old)?.playedAt)
            assertEquals(1, feeds.countUnplayed(FeedSource.Group(g), null))
        }

    @Test fun staleConfirmationWritesNothingIncludingStatePositionsAndQueue() =
        runTest {
            val p = podcast()
            val first = episode(p)
            db.positionDao().upsert(
                EpisodePositionEntity(first, 500, positionSource = PositionSource.STREAM, updatedAt = 1),
            )
            db.queueDao().insert(queueEntryEntity(first, "a"))
            val count = feeds.countUnplayed(FeedSource.All, null)
            val added = episode(p)
            val position = db.positionDao().byEpisode(first)
            val queue = db.queueDao().entries()
            assertEquals(MarkFeedPlayedResult.SelectionChanged(2), episodes.markFeedPlayed(FeedSource.All, null, count))
            assertNull(db.episodeStateDao().byEpisode(first))
            assertNull(db.episodeStateDao().byEpisode(added))
            assertEquals(position, db.positionDao().byEpisode(first))
            assertEquals(queue, db.queueDao().entries())
            assertEquals(MarkFeedPlayedResult.Played(2), episodes.markFeedPlayed(FeedSource.All, null, 2))
            assertEquals(0L, db.positionDao().byEpisode(first)?.positionMs)
            assertTrue(db.queueDao().entries().isEmpty())
        }

    @Test fun decreasedSelectionIsStaleAndNegativeExpectedCountIsRejected() =
        runTest {
            val p = podcast()
            val id = episode(p)
            val count = feeds.countUnplayed(FeedSource.All, null)
            episodes.setPlayed(listOf(id), true)
            val before = db.episodeStateDao().byEpisode(id)
            assertEquals(MarkFeedPlayedResult.SelectionChanged(0), episodes.markFeedPlayed(FeedSource.All, null, count))
            assertEquals(before, db.episodeStateDao().byEpisode(id))
            assertFailsWith<IllegalArgumentException> { episodes.markFeedPlayed(FeedSource.All, null, -1) }
        }

    @Test fun markFeedPlayedChainsAcrossChunkBoundaries() =
        runTest {
            // IN lists chunk at 500 bound ids; 1001 forces three chunks through one transaction.
            val p = podcast()
            val ids = List(1001) { episode(p, now - 1 - it) }
            val outside = episode(p, now)
            for ((i, id) in ids.withIndex()) {
                db.positionDao().upsert(
                    EpisodePositionEntity(id, i + 1L, positionSource = PositionSource.STREAM, updatedAt = 1),
                )
                db.queueDao().insert(queueEntryEntity(id, i.toString()))
            }
            db.positionDao().upsert(
                EpisodePositionEntity(outside, 7, positionSource = PositionSource.STREAM, updatedAt = 1),
            )
            db.queueDao().insert(queueEntryEntity(outside, "z"))

            val source = FeedSource.Podcast(p)
            val expected = feeds.countUnplayed(source, now)
            assertEquals(ids.size, expected)
            assertEquals(MarkFeedPlayedResult.Played(ids.size), episodes.markFeedPlayed(source, now, expected))

            assertEquals(0, feeds.countUnplayed(source, now))
            assertEquals(1, feeds.countUnplayed(source, null))
            for (id in ids) {
                val state = db.episodeStateDao().byEpisode(id)
                assertEquals(now, state?.playedAt, "id $id")
                assertEquals(1, state?.playCount, "id $id")
                assertEquals(0L, db.positionDao().byEpisode(id)?.positionMs, "id $id")
            }
            assertNull(db.episodeStateDao().byEpisode(outside))
            assertEquals(7L, db.positionDao().byEpisode(outside)?.positionMs)
            assertEquals(listOf(outside), db.queueDao().entries().map { it.episodeId })
        }

    @Test fun groupCountPlanSearchesPodcastDateIndex() =
        runTest {
            val g = group("G")
            episode(podcast(g))
            val query = FeedQueryBuilder.groupCounts(since, now)
            val plan =
                db.useReaderConnection { connection ->
                    connection.usePrepared("EXPLAIN QUERY PLAN ${query.sql}") { statement ->
                        statement.bindLong(1, since)
                        statement.bindLong(2, now)
                        buildList { while (statement.step()) add(statement.getText(3)) }
                    }
                }
            assertTrue(
                plan.any {
                    "SEARCH e USING INDEX index_episode_podcastId_sortDate (podcastId=? AND sortDate>?)" in it
                },
                plan.toString(),
            )
            assertTrue(plan.none { it.startsWith("SCAN e") }, plan.toString())
        }

    private suspend fun group(
        name: String,
        createdAt: Long = now,
    ): Long = db.groupDao().insert(groupEntity(name = name, orderKey = name, createdAt = createdAt))

    private suspend fun podcast(vararg groups: Long): Long {
        val id = db.podcastDao().insertPodcast(podcastEntity { copy(youtubeVariants = 1) })
        groups.forEach { db.groupDao().insertMember(memberEntity(it, id, "a")) }
        return id
    }

    private suspend fun episode(
        podcast: Long,
        date: Long = now,
        block: EpisodeEntity.() -> EpisodeEntity = { this },
    ): Long = db.episodeDao().insertStub(episodeEntity(podcastId = podcast, sortDate = date, block = block))
}
