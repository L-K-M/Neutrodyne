// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.paging.testing.asSnapshot
import androidx.room3.useWriterConnection
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.repo.FeedRepositoryImpl
import ch.lkmc.neutrodyne.core.data.repo.GroupRepositoryImpl
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastGroupSettingsEntity
import ch.lkmc.neutrodyne.core.database.ScopeOverrides
import ch.lkmc.neutrodyne.core.domain.DeletedGroupToken
import ch.lkmc.neutrodyne.core.domain.GroupError
import ch.lkmc.neutrodyne.core.domain.GroupRepository
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.model.GroupEdit
import ch.lkmc.neutrodyne.core.testing.FakeGroupChannelSync
import ch.lkmc.neutrodyne.core.testing.FakeRefreshController
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 05 Delete and undo over the bundled driver — PLAN M2 AC4: a podcast in two groups keeps its
 * other memberships and appears once in All when one group goes; the channel delete waits out the
 * 10 s window; undo restores the snapshot (id, uuid, orderKey, settings, surviving members and the
 * cleared play context while its generation is untouched).
 */
@OptIn(ExperimentalCoroutinesApi::class) // advanceTimeBy, runCurrent, TestClock.from(scheduler)
class GroupDeleteTest {
    private val channelSync = FakeGroupChannelSync()
    private val refresh = FakeRefreshController()

    private fun repository(
        db: NeutrodyneDatabase,
        clock: Clock,
        appScope: CoroutineScope,
    ) = GroupRepositoryImpl(db, clock, TestOrderKeys, channelSync, refresh, appScope)

    /**
     * Tests that merely need the token live must NOT pass `backgroundScope`: [runTest] skips
     * forward over virtual time whenever the body suspends on off-scheduler work (every Room
     * call), so the 10 s expiry job would consume the token before `undoDelete` is reached. A
     * real-dispatcher scope keeps the window at a wall-clock 10 s, far past the test's own run.
     */
    private fun realExpiryScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test
    fun deleteDropsMembershipsClearsContextAndKeepsThePodcast() =
        runTest {
            val clock = TestClock.from(testScheduler)
            val db = TestDb.inMemory(clock = clock)
            val scope = realExpiryScope()
            try {
                val repo = repository(db, clock, scope)
                val feeds = FeedRepositoryImpl(db, FakeSettingsRepository(), clock)
                val shared = db.podcastDao().insertPodcast(podcastEntity(feedKey = "k-shared"))
                val techOnly = db.podcastDao().insertPodcast(podcastEntity(feedKey = "k-tech"))
                val episode = insertEpisode(db, shared, "g:ac4", sortDate = 100)
                val tech = created(repo, GroupDraft("Tech"), setOf(shared, techOnly))
                val news = created(repo, GroupDraft("News"), setOf(shared))
                val techUuid = assertNotNull(db.groupDao().groupById(tech)).uuid

                // AC4: the overlap shows in both group feeds and once in All.
                assertEquals(listOf(episode), pageIds(feeds, FeedSource.Group(tech)))
                assertEquals(listOf(episode), pageIds(feeds, FeedSource.Group(news)))
                assertEquals(listOf(episode), pageIds(feeds, FeedSource.All))

                db.playSessionDao().ensureNow(clock.now())
                db.useWriterConnection {
                    it.execSql(
                        "UPDATE play_session SET contextType = 'GROUP', contextId = $tech," +
                            " contextAnchorEpisodeId = $episode, contextAnchorSortDate = 100," +
                            " generation = 7 WHERE id = 0",
                    )
                }

                val token = assertIs<Outcome.Success<DeletedGroupToken>>(repo.delete(tech)).value

                // One transaction: row, members and the GROUP context are gone; podcasts stand.
                assertNull(db.groupDao().groupById(tech))
                assertEquals(emptyList(), db.groupDao().membersOf(tech))
                assertEquals(setOf(shared), db.groupDao().membersOf(news).mapTo(HashSet()) { it.podcastId })
                assertNotNull(db.podcastDao().byId(shared))
                val session = assertNotNull(db.playSessionDao().get())
                assertNull(session.contextType)
                assertNull(session.contextId)
                assertNull(session.contextAnchorEpisodeId)
                assertEquals(8, session.generation, "the clear bumps 06's foreign-write generation")

                // Feeds re-resolve at once: tech's page is empty, news and All keep the episode.
                assertEquals(emptyList(), pageIds(feeds, FeedSource.Group(tech)))
                assertEquals(listOf(episode), pageIds(feeds, FeedSource.Group(news)))
                assertEquals(listOf(episode), pageIds(feeds, FeedSource.All))

                // The uuid stays live for the channel sweep; nothing deleted a channel yet.
                assertEquals(setOf(techUuid), repo.pendingUndoUuids())
                assertEquals(emptyList(), channelSync.calls)
                assertEquals(listOf("reschedulePeriodic()"), refresh.calls)

                // A second independent delete would hand back a distinct token (05 step 3).
                assertEquals(Outcome.Failure(GroupError.NotFound), repo.delete(-1))
            } finally {
                scope.cancel()
                db.close()
            }
        }

    @Test
    fun channelDeleteWaitsForTheFullUndoWindow() =
        runTest {
            val clock = TestClock.from(testScheduler)
            val db = TestDb.inMemory(clock = clock)
            try {
                val repo = repository(db, clock, backgroundScope)
                val tech = created(repo, GroupDraft("Tech"))
                val uuid = assertNotNull(db.groupDao().groupById(tech)).uuid

                assertIs<Outcome.Success<DeletedGroupToken>>(repo.delete(tech))
                runCurrent()
                assertEquals(emptyList(), channelSync.calls)

                testScheduler.advanceTimeBy(GroupRepository.UNDO_WINDOW_MS - 1)
                runCurrent()
                assertEquals(emptyList(), channelSync.calls)

                testScheduler.advanceTimeBy(1)
                runCurrent()
                assertEquals(listOf("onDeleted($uuid)"), channelSync.calls)
                assertEquals(emptySet(), repo.pendingUndoUuids())
            } finally {
                db.close()
            }
        }

    /** 05 step 2 in reverse: the restored group may lower the least refresh interval again. */
    @Test
    fun successfulUndoReschedulesAndAFailedUndoDoesNot() =
        runTest {
            val clock = TestClock.from(testScheduler)
            val db = TestDb.inMemory(clock = clock)
            val scope = realExpiryScope()
            try {
                val repo = repository(db, clock, scope)
                val tech = created(repo, GroupDraft("Tech"))
                val token = assertIs<Outcome.Success<DeletedGroupToken>>(repo.delete(tech)).value
                val replacement = created(repo, GroupDraft("tech"))
                refresh.calls.clear()

                assertEquals(Outcome.Failure(GroupError.NameTaken(replacement)), repo.undoDelete(token))
                assertEquals(emptyList(), refresh.calls)

                assertEquals(Outcome.Success(Unit), repo.update(replacement, GroupEdit.Rename("Other")))
                refresh.calls.clear()
                assertEquals(Outcome.Success(tech), repo.undoDelete(token))
                assertEquals(listOf("reschedulePeriodic()"), refresh.calls)
            } finally {
                scope.cancel()
                db.close()
            }
        }

    @Test
    fun undoRestoresIdentitySettingsSurvivingMembersAndContext() =
        runTest {
            val clock = TestClock.from(testScheduler)
            val db = TestDb.inMemory(clock = clock)
            val scope = realExpiryScope()
            try {
                val repo = repository(db, clock, scope)
                val gone = db.podcastDao().insertPodcast(podcastEntity(feedKey = "k-gone"))
                val kept = db.podcastDao().insertPodcast(podcastEntity(feedKey = "k-kept"))
                val tech =
                    created(
                        repo,
                        GroupDraft("Tech", colorArgb = 0x123456, iconKey = "ic_news"),
                        setOf(gone, kept),
                    )
                val settings =
                    PodcastGroupSettingsEntity(tech, ScopeOverrides(notifyNewEpisodes = true))
                db.scopeSettingsDao().upsertGroup(settings)
                db.playSessionDao().ensureNow(clock.now())
                db.useWriterConnection {
                    it.execSql(
                        "UPDATE play_session SET contextType = 'GROUP', contextId = $tech," +
                            " contextAnchorSortDate = 50, generation = 3 WHERE id = 0",
                    )
                }
                val original = assertNotNull(db.groupDao().groupById(tech))
                val originalMember = db.groupDao().membersOf(tech).single { it.podcastId == kept }

                val token = assertIs<Outcome.Success<DeletedGroupToken>>(repo.delete(tech)).value
                // The unsubscribe inside the window cascades the member row; undo skips it.
                db.useWriterConnection { it.execSql("DELETE FROM podcast WHERE id = $gone") }

                assertEquals(Outcome.Success(tech), repo.undoDelete(token))
                assertEquals(original, assertNotNull(db.groupDao().groupById(tech)))
                assertEquals(settings, db.scopeSettingsDao().forGroup(tech))
                val members = db.groupDao().membersOf(tech)
                assertEquals(listOf(originalMember), members, "only the surviving podcast returns")

                val session = assertNotNull(db.playSessionDao().get())
                assertEquals(ContextType.GROUP, session.contextType)
                assertEquals(tech, session.contextId)
                assertEquals(50, session.contextAnchorSortDate)
                assertEquals(5, session.generation, "clear and restore each bumped once off 3")

                assertEquals(emptyList(), channelSync.calls, "undo cancels the channel delete")
                assertEquals(Outcome.Failure(GroupError.UndoExpired), repo.undoDelete(token))
            } finally {
                scope.cancel()
                db.close()
            }
        }

    @Test
    fun undoAfterTheWindowIsRejectedAndTheDeleteStands() =
        runTest {
            val clock = TestClock.from(testScheduler)
            val db = TestDb.inMemory(clock = clock)
            try {
                val repo = repository(db, clock, backgroundScope)
                val tech = created(repo, GroupDraft("Tech"), setOf(seedPodcast(db, "k-a")))
                val uuid = assertNotNull(db.groupDao().groupById(tech)).uuid
                val token = assertIs<Outcome.Success<DeletedGroupToken>>(repo.delete(tech)).value

                testScheduler.advanceTimeBy(GroupRepository.UNDO_WINDOW_MS)
                runCurrent()

                assertEquals(Outcome.Failure(GroupError.UndoExpired), repo.undoDelete(token))
                assertNull(db.groupDao().groupById(tech))
                assertEquals(listOf("onDeleted($uuid)"), channelSync.calls)
            } finally {
                db.close()
            }
        }

    @Test
    fun undoFailsWhileTheNameIsTakenAndSucceedsAfterRename() =
        runTest {
            val clock = TestClock.from(testScheduler)
            val db = TestDb.inMemory(clock = clock)
            val scope = realExpiryScope()
            try {
                val repo = repository(db, clock, scope)
                val tech = created(repo, GroupDraft("Tech"))
                val techUuid = assertNotNull(db.groupDao().groupById(tech)).uuid
                val token = assertIs<Outcome.Success<DeletedGroupToken>>(repo.delete(tech)).value
                val replacement = created(repo, GroupDraft("tech"))

                // The nameKey went to a new group: nothing is written and the token stays live.
                assertEquals(Outcome.Failure(GroupError.NameTaken(replacement)), repo.undoDelete(token))
                assertNull(db.groupDao().groupById(tech))
                assertEquals(listOf(replacement), repo.observeGroups().first().map { it.id })
                assertEquals(setOf(techUuid), repo.pendingUndoUuids())

                assertEquals(Outcome.Success(Unit), repo.update(replacement, GroupEdit.Rename("Renamed")))
                assertEquals(Outcome.Success(tech), repo.undoDelete(token))
                assertEquals("Tech", assertNotNull(db.groupDao().groupById(tech)).name)
            } finally {
                scope.cancel()
                db.close()
            }
        }

    @Test
    fun undoLeavesASessionWrittenMeanwhileAlone() =
        runTest {
            val clock = TestClock.from(testScheduler)
            val db = TestDb.inMemory(clock = clock)
            val scope = realExpiryScope()
            try {
                val repo = repository(db, clock, scope)
                val podcast = seedPodcast(db, "k-p")
                val tech = created(repo, GroupDraft("Tech"), setOf(podcast))
                db.playSessionDao().ensureNow(clock.now())
                db.useWriterConnection {
                    it.execSql(
                        "UPDATE play_session SET contextType = 'GROUP', contextId = $tech WHERE id = 0",
                    )
                }
                val token = assertIs<Outcome.Success<DeletedGroupToken>>(repo.delete(tech)).value

                // A new context start bumps the generation; the stale snapshot must not win.
                db.useWriterConnection {
                    it.execSql(
                        "UPDATE play_session SET contextType = 'PODCAST', contextId = $podcast," +
                            " generation = generation + 1 WHERE id = 0",
                    )
                }
                assertEquals(Outcome.Success(tech), repo.undoDelete(token))
                val session = assertNotNull(db.playSessionDao().get())
                assertEquals(ContextType.PODCAST, session.contextType)
                assertEquals(podcast, session.contextId)
            } finally {
                scope.cancel()
                db.close()
            }
        }

    private suspend fun created(
        repo: GroupRepositoryImpl,
        draft: GroupDraft,
        memberIds: Set<Long> = emptySet(),
    ): Long = assertIs<Outcome.Success<Long>>(repo.create(draft, memberIds)).value

    private suspend fun seedPodcast(
        db: NeutrodyneDatabase,
        feedKey: String,
    ): Long = db.podcastDao().insertPodcast(podcastEntity(feedKey = feedKey))

    private suspend fun insertEpisode(
        db: NeutrodyneDatabase,
        podcastId: Long,
        key: String,
        sortDate: Long,
    ): Long =
        db
            .ingestDao()
            .insertEpisodes(
                listOf(
                    episodeEntity(podcastId = podcastId, identityKey = key, title = key, sortDate = sortDate),
                ),
            ).single()

    private suspend fun pageIds(
        feeds: FeedRepositoryImpl,
        source: FeedSource,
    ): List<Long> =
        feeds
            .pagedFeed(source, FeedFilters(), FeedOrder.NEWEST_FIRST)
            .asSnapshot()
            .map { it.id }
}
