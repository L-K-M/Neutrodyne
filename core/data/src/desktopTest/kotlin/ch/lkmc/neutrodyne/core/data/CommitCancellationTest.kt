// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.refresh.RefreshScheduler
import ch.lkmc.neutrodyne.core.data.repo.GroupRepositoryImpl
import ch.lkmc.neutrodyne.core.data.settings.ScopeSettingsRepositoryImpl
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.testing.FakeGroupChannelSync
import ch.lkmc.neutrodyne.core.testing.FakeNewEpisodePoster
import ch.lkmc.neutrodyne.core.testing.FakeRefreshController
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.FakeSnapshotScheduler
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Room re-checks cancellation when a write transaction returns, so a caller cancelled right after
 * COMMIT sees a `CancellationException` for a write that landed. The effects owed to that write
 * (an undo token, a channel sweep, a reschedule, notification cleanup) must still run
 * (main review at 4919b3a7).
 */
class CommitCancellationTest {
    private val driver = CommitCancellingDriver()
    private val db = TestDb.inMemory(driver = driver)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val channels = FakeGroupChannelSync()
    private val refresh = FakeRefreshController()
    private val snapshots = FakeSnapshotScheduler()
    private val groups = GroupRepositoryImpl(db, TestClock(), TestOrderKeys, channels, refresh, snapshots, appScope)

    @AfterTest
    fun tearDown() {
        appScope.cancel()
        db.close()
    }

    @Test
    fun aDeleteCancelledAtCommitKeepsItsUndoTokenAndEffects() =
        runTest {
            val podcast = db.podcastDao().insertPodcast(podcastEntity())
            val id = assertIs<Outcome.Success<Long>>(groups.create(GroupDraft("Tech"), setOf(podcast))).value
            val uuid = assertNotNull(db.groupDao().groupById(id)).uuid
            channels.calls.clear()
            refresh.calls.clear()

            cancelAtCommit("DELETE FROM podcast_group WHERE") { groups.delete(id) }

            assertNull(db.groupDao().groupById(id))
            assertEquals(setOf(uuid), groups.pendingUndoUuids())
            assertEquals(listOf("sync()"), channels.calls)
            assertEquals(listOf("reschedulePeriodic()"), refresh.calls)
            assertEquals(2, snapshots.requestSoonCalls, "create and the cancelled-at-commit delete both poke")
        }

    @Test
    fun aMembershipEditCancelledAtCommitStillReschedules() =
        runTest {
            val podcast = db.podcastDao().insertPodcast(podcastEntity())
            val id = assertIs<Outcome.Success<Long>>(groups.create(GroupDraft("Tech"))).value
            refresh.calls.clear()

            cancelAtCommit("INSERT OR IGNORE INTO `podcast_group_member`") { groups.setMembers(id, setOf(podcast)) }

            assertEquals(listOf(podcast), db.groupDao().membersOf(id).map { it.podcastId })
            assertEquals(listOf("reschedulePeriodic()"), refresh.calls)
            assertEquals(2, snapshots.requestSoonCalls, "create and the cancelled-at-commit edit both poke")
        }

    @Test
    fun aGroupSettingCancelledAtCommitStillReschedulesAndSweeps() =
        runTest {
            val settings =
                ScopeSettingsRepositoryImpl(
                    db,
                    FakeSettingsRepository(),
                    { refresh },
                    channels,
                    snapshots,
                    capabilities =
                        ch.lkmc.neutrodyne.core.testing
                            .FakeYouTubeCapabilitiesSource(),
                )
            val id = assertIs<Outcome.Success<Long>>(groups.create(GroupDraft("Tech"))).value
            channels.calls.clear()
            refresh.calls.clear()

            cancelAtCommit("INSERT INTO `podcast_group_settings`") {
                settings.updateGroup(id) { it.copy(refreshIntervalMinutes = 0, notifyNewEpisodes = true) }
            }

            assertEquals(
                0,
                db
                    .scopeSettingsDao()
                    .forGroup(id)
                    ?.o
                    ?.refreshIntervalMinutes,
            )
            assertEquals(listOf("reschedulePeriodic()"), refresh.calls)
            assertEquals(listOf("sync()"), channels.calls)
            assertEquals(2, snapshots.requestSoonCalls, "create and the cancelled-at-commit write both poke")
        }

    @Test
    fun anUnsubscribeCancelledWhileReschedulingStillCleansNotifications() =
        runTest {
            val podcast = seedPodcast(db, "https://a.example.com/f")
            val poster = FakeNewEpisodePoster()
            val rescheduling = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val scheduler =
                object : RefreshScheduler by FakeRefreshScheduler() {
                    override suspend fun reschedulePeriodic() {
                        rescheduling.complete(Unit)
                        release.await()
                    }
                }
            val caller = launch { newUnsubscribe(db, scheduler, TestClock(), poster = poster)(listOf(podcast)) }
            rescheduling.await()

            caller.cancel()
            release.complete(Unit)
            caller.join()

            assertNull(db.podcastDao().byId(podcast))
            assertEquals(listOf(listOf(podcast)), poster.removals)
        }

    @Test
    fun aFailedRescheduleNeitherFailsTheUnsubscribeNorSkipsCleanup() =
        runTest {
            val podcast = seedPodcast(db, "https://a.example.com/f")
            val poster = FakeNewEpisodePoster()
            val scheduler =
                object : RefreshScheduler by FakeRefreshScheduler() {
                    override suspend fun reschedulePeriodic(): Unit = error("WorkManager unavailable")
                }

            val removed = newUnsubscribe(db, scheduler, TestClock(), poster = poster)(listOf(podcast))

            assertEquals(listOf(podcast), removed)
            assertEquals(listOf(listOf(podcast)), poster.removals)
        }

    /** Runs [write] and cancels it right after the COMMIT of a transaction that ran [statement]. */
    private suspend fun CoroutineScope.cancelAtCommit(
        statement: String,
        write: suspend () -> Unit,
    ) {
        lateinit var caller: Job
        caller = launch(start = CoroutineStart.LAZY) { write() }
        driver.arm(statement) { caller.cancel() }
        caller.start()
        caller.join()
        assertEquals(1, driver.cancellations, "the commit hook fired")
    }
}
