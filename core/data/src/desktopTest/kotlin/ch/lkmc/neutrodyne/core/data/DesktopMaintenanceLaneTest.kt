// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.data.maintenance.DesktopMaintenanceLane
import ch.lkmc.neutrodyne.core.model.settings.DiagnosticsSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeDbMaintenance
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.FakeSyncActivity
import ch.lkmc.neutrodyne.core.testing.TestClock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The `maintenance` lane's own schedule (11's lane table): ten-minute start guard, then one
 * shared `db-maintenance` pass per 24 h stamped through `maintenance_last_run_at`. The pass
 * itself is covered by `DbMaintenanceTest`/`RetentionTest`; this only proves the lane calls
 * it at the right times and survives a thrown step.
 */
class DesktopMaintenanceLaneTest {
    private val clock = TestClock()
    private val settings = FakeSettingsRepository()
    private val maintenance = FakeDbMaintenance()
    private val syncActivity = FakeSyncActivity()
    private val lane = DesktopMaintenanceLane(maintenance, settings, syncActivity)

    @Test
    fun nothingRunsInsideTheTenMinuteStartGuard() =
        runTest {
            lane.run(now())
            clock.nowMs += 9.minutes.inWholeMilliseconds
            lane.run(now())
            assertTrue(maintenance.calls.isEmpty(), "the start guard holds the first pass back")

            clock.nowMs += 2.minutes.inWholeMilliseconds
            lane.run(now())
            assertEquals(1, maintenance.calls.size)
        }

    @Test
    fun thePassRepeatsOncePer24hFromItsOwnStamp() =
        runTest {
            lane.run(now()) // the first tick only arms the start guard
            clock.nowMs += DesktopMaintenanceLane.START_GUARD.inWholeMilliseconds
            lane.run(now())
            assertEquals(1, maintenance.calls.size)
            assertEquals(clock.now(), settings.get(DiagnosticsSettingKeys.MAINTENANCE_LAST_RUN_AT))
            assertEquals(null, maintenance.calls.single().deadlineElapsedMs, "the desktop has no deadline")

            clock.nowMs += 23.hours.inWholeMilliseconds
            lane.run(now())
            assertEquals(1, maintenance.calls.size, "inside the window the tick returns early")

            clock.nowMs += 2.hours.inWholeMilliseconds
            lane.run(now())
            assertEquals(2, maintenance.calls.size)
        }

    @Test
    fun aRecordedStampSurvivesANewLaneInstance() =
        runTest {
            lane.run(now())
            clock.nowMs += DesktopMaintenanceLane.START_GUARD.inWholeMilliseconds
            lane.run(now())
            assertEquals(1, maintenance.calls.size)

            // The relaunched process arms its own start guard again but honours the stamp.
            val restarted = DesktopMaintenanceLane(maintenance, settings, syncActivity)
            clock.nowMs += 30.minutes.inWholeMilliseconds
            restarted.run(now())
            // Tick past the restarted guard while still inside the 24 h window — without
            // this wait the run returns at the guard and the stamp is never consulted.
            clock.nowMs += 11.minutes.inWholeMilliseconds
            restarted.run(now())
            assertEquals(1, maintenance.calls.size, "a restart inside 24 h does not re-run")

            // Once the stamp ages past the interval the next pass fires.
            clock.nowMs += 24.hours.inWholeMilliseconds
            restarted.run(now())
            assertEquals(2, maintenance.calls.size, "the stamp re-arms the daily pass")
        }

    @Test
    fun thePassGetsAVacuumBlockerThatReadsLiveSyncActivity() =
        runTest {
            lane.run(now())
            clock.nowMs += DesktopMaintenanceLane.START_GUARD.inWholeMilliseconds
            syncActivity.active = true
            lane.run(now())

            assertEquals(1, maintenance.calls.size)
            val call = maintenance.calls.single()
            assertTrue(call.hadVacuumBlocker, "the desktop pass must carry a vacuum blocker")
            assertEquals("sync", call.blockerReason, "an active sync round blocks the vacuum")
            assertEquals(
                1,
                maintenance.blockerEvaluations,
                "the blocker is consulted by the pass, not by the lane before it",
            )
        }

    @Test
    fun anIdleSyncRoundLeavesTheVacuumBlockerOpen() =
        runTest {
            lane.run(now())
            clock.nowMs += DesktopMaintenanceLane.START_GUARD.inWholeMilliseconds
            syncActivity.active = false
            lane.run(now())

            assertEquals(null, maintenance.calls.single().blockerReason)
        }

    @Test
    fun theSyncBlockerDoesNotSkipTheMaintenancePass() =
        runTest {
            lane.run(now())
            clock.nowMs += DesktopMaintenanceLane.START_GUARD.inWholeMilliseconds
            syncActivity.active = true
            lane.run(now())

            assertEquals(1, maintenance.calls.size, "the pass still runs; only the vacuum step skips")
            assertEquals(clock.now(), settings.get(DiagnosticsSettingKeys.MAINTENANCE_LAST_RUN_AT))
        }

    @Test
    fun aFailingPassStillStampsTheWindow() =
        runTest {
            lane.run(now())
            clock.nowMs += DesktopMaintenanceLane.START_GUARD.inWholeMilliseconds
            maintenance.result = Result.failure(IllegalStateException("boom"))
            lane.run(now())
            maintenance.result = Result.success(Unit)

            // The lane swallows the throw (the runner must not back off a 24 h job) and
            // stamps anyway: a crash-looping pass retries tomorrow, not every minute.
            lane.run(now())
            assertEquals(1, maintenance.calls.size, "a failed pass waits for the next window")
            assertEquals(clock.now(), settings.get(DiagnosticsSettingKeys.MAINTENANCE_LAST_RUN_AT))
        }

    private fun now(): Instant = Instant.fromEpochMilliseconds(clock.now())
}
