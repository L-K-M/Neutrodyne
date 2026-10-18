// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.importing

import ch.lkmc.neutrodyne.core.common.JobLanePoker
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.requestImportRestore
import ch.lkmc.neutrodyne.core.data.seedImportSession
import ch.lkmc.neutrodyne.core.model.ImportFormat
import ch.lkmc.neutrodyne.core.model.ImportOptions
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The desktop `import-backup` scheduler over a real Room database (05 §7's platform column):
 * every schedule call is one coalesced poke, `scheduleRestore` acknowledges truthfully even
 * when it can already see the request's own durable intent, `hasRestoreWork` reads real lane
 * facts and `cancel` drops only the named session's tracked child job.
 */
class LaneImportWorkSchedulerTest {
    private val clock = TestClock()
    private val db = TestDb.inMemory(clock = clock)
    private val pokes = CopyOnWriteArrayList<String>()
    private val scheduler =
        LaneImportWorkScheduler(db, Provider { JobLanePoker { name -> pokes += name } })

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun bothFetchPoliciesAndRestoreRequestsPokeTheLane() =
        runTest {
            scheduler.schedule(1L, ImportWorkPolicy.KEEP)
            scheduler.schedule(2L, ImportWorkPolicy.APPEND_OR_REPLACE)
            assertEquals(
                RestoreEnqueue.ENQUEUED,
                scheduler.scheduleRestore(3L, auto = false),
            )

            assertEquals(
                listOf(
                    DesktopImportBackupLane.NAME,
                    DesktopImportBackupLane.NAME,
                    DesktopImportBackupLane.NAME,
                ),
                pokes,
            )
        }

    @Test
    fun scheduleRestoreReportsEnqueuedWhileItsOwnIntentIsVisible() =
        runTest {
            // `BackupRepositoryImpl.restore` checks `hasRestoreWork` *before* stamping, then
            // writes the durable intent and calls `scheduleRestore`. By then the row plainly
            // carries the intent — an ALREADY_RUNNING answer would roll the stamp back and
            // deny work that is in fact queued, so ENQUEUED is the only truthful answer.
            val sessionId = backupSession()
            assertIs<Outcome.Success<*>>(
                db.requestImportRestore(sessionId, clock.now()),
            )

            assertEquals(
                RestoreEnqueue.ENQUEUED,
                scheduler.scheduleRestore(sessionId, auto = false),
            )
            assertEquals(listOf(DesktopImportBackupLane.NAME), pokes)
        }

    @Test
    fun hasRestoreWorkReadsDurableIntentsAndInFlightRuns() =
        runTest {
            assertFalse(scheduler.hasRestoreWork())

            // A backup session still in PREVIEW carries no intent by itself.
            val idle = backupSession()
            assertFalse(scheduler.hasRestoreWork())

            // The durable request is restore work whether or not the lane has started it.
            assertIs<Outcome.Success<*>>(
                db.requestImportRestore(idle, clock.now()),
            )
            assertTrue(scheduler.hasRestoreWork())

            // An undecodable options row could hide an intent — fail closed, like the cleaner.
            val corrupt = backupSession()
            db.importDao().setOptionsJson(corrupt, "{not json")
            assertTrue(scheduler.hasRestoreWork())

            // The lane's in-flight restore counts even with every durable row consumed.
            db.importDao().deleteSession(idle)
            db.importDao().deleteSession(corrupt)
            assertFalse(scheduler.hasRestoreWork())
            scheduler.restoreStarted(idle)
            assertTrue(scheduler.hasRestoreWork())
            scheduler.restoreFinished(idle)
            assertFalse(scheduler.hasRestoreWork())
        }

    @Test
    fun cancelDropsOnlyTheNamedSessionsTrackedJob() =
        runTest {
            val running = Job()
            val sibling = Job()
            scheduler.trackJob(7, running)
            scheduler.trackJob(9, sibling)

            scheduler.cancel(7)

            assertTrue(running.isCancelled, "the tracked run was dropped")
            assertTrue(sibling.isActive, "another session's run is untouched")
            assertEquals(listOf(DesktopImportBackupLane.NAME), pokes)

            // An untracked session is a no-op beyond the wake: nothing to stop, still pokes.
            scheduler.cancel(8)
            assertEquals(2, pokes.size)
            assertTrue(sibling.isActive)
        }

    @Test
    fun untrackReleasesOnlyTheSameJob() =
        runTest {
            // The lane untracks with the job it launched: a finished run must not unregister
            // a *newer* run of the same session that already claimed the slot.
            val stale = Job()
            val current = Job()
            scheduler.trackJob(7, stale)
            scheduler.untrackJob(7, stale)
            scheduler.trackJob(7, current)

            scheduler.cancel(7)

            assertFalse(stale.isCancelled, "the stale job was already untracked")
            assertTrue(current.isCancelled)
        }

    private suspend fun backupSession(state: ImportState = ImportState.PREVIEW): Long =
        db.seedImportSession(
            options = ImportOptions(restoreRequestedAt = null),
            format = ImportFormat.NEUTRODYNE_BACKUP,
            state = state,
            payloadPath = "import/test.bin",
        )
}
