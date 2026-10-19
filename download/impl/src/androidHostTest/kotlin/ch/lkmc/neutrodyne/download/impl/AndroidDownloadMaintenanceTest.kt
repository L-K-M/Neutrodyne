// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.datastore.SettingStore
import ch.lkmc.neutrodyne.core.domain.CleanupReason
import ch.lkmc.neutrodyne.core.model.DeleteAfter
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.EffectiveAutoDownload
import ch.lkmc.neutrodyne.core.model.NetworkPolicy
import ch.lkmc.neutrodyne.core.model.SettingSource
import ch.lkmc.neutrodyne.core.model.WaitReason
import ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.model.settings.SettingsFile
import ch.lkmc.neutrodyne.core.testing.FakeAutoDownloadPlanner
import ch.lkmc.neutrodyne.core.testing.FakeCleanupPlanner
import ch.lkmc.neutrodyne.core.testing.FakeEffectiveSettingsResolver
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AndroidDownloadMaintenanceTest {
    private val events = mutableListOf<String>()
    private val planner = FakeAutoDownloadPlanner().apply { onPlan = { events += "plan" } }
    private val cleanup = FakeCleanupPlanner().apply { onRun = { events += "cleanup:$it" } }
    private val resolver = FakeEffectiveSettingsResolver()
    private val device = AndroidDownloadTestStore()
    private val clock = TestClock(nowMs = 123_456)
    private var reconcile: suspend () -> Unit = { events += "reconcile" }
    private val scheduled = mutableListOf<DownloadLane>()
    private val moves = mutableListOf<String>()
    private val scheduler =
        object : DownloadScheduler {
            override suspend fun ensureScheduled(lane: DownloadLane) {
                events += "schedule:$lane"
                scheduled += lane
            }

            override fun wake(lane: DownloadLane) = Unit

            override suspend fun scheduleWake(
                lane: DownloadLane,
                earliestAt: Long?,
                reason: WaitReason,
            ) = Unit
        }
    private val host =
        AndroidDownloadMaintenance(
            { reconcile() },
            scheduler,
            planner,
            cleanup,
            resolver,
            device,
            clock,
            {
                events += "move"
                moves += it
            },
        )

    @Test fun startupVerifiesSchedulesWatermarksPlansThenCleans() =
        runTest {
            resolver.autoDownloadValues.value = mapOf(1L to autoPolicy(), 2L to autoPolicy().copy(enabled = false))
            host.startup()
            assertEquals(listOf("reconcile", "schedule:MANUAL", "schedule:AUTO", "plan", "cleanup:STARTUP"), events)
            assertEquals(listOf(listOf(1L)), planner.calls)
            assertEquals(listOf(DownloadLane.MANUAL, DownloadLane.AUTO), scheduled)
            assertEquals(0, device.get(DownloadSettingKeys.CLEANUP_LAST_AT))
        }

    @Test fun dailyVerifiesThenCleansPlansAndPersistsCompletionTime() =
        runTest {
            device.set(DownloadSettingKeys.MOVE_TARGET, "ext:card")
            host.daily()
            assertEquals(
                listOf("reconcile", "cleanup:DAILY", "plan", "schedule:MANUAL", "schedule:AUTO", "move"),
                events,
            )
            assertEquals(clock.now(), device.get(DownloadSettingKeys.CLEANUP_LAST_AT))
            assertEquals(listOf("ext:card"), moves)
        }

    @Test fun startupResumesPersistedMoveWithoutWaitingForSettingEmission() =
        runTest {
            device.set(DownloadSettingKeys.MOVE_TARGET, "ext:card")
            host.startup()
            assertEquals(listOf("ext:card"), moves)
            device.set(DownloadSettingKeys.MOVE_TARGET, "")
            host.startup()
            assertEquals(listOf("ext:card"), moves)
        }

    @Test fun cleanupFailureLeavesDailyReceiptUntouchedAndRetryRepeatsPass() =
        runTest {
            cleanup.onRun = { error("disk unavailable") }
            assertFailsWith<IllegalStateException> { host.daily() }
            assertTrue(planner.calls.isEmpty())
            assertEquals(0, device.get(DownloadSettingKeys.CLEANUP_LAST_AT))
            cleanup.onRun = {}
            host.daily()
            assertEquals(listOf(CleanupReason.DAILY, CleanupReason.DAILY), cleanup.calls)
            assertEquals(clock.now(), device.get(DownloadSettingKeys.CLEANUP_LAST_AT))
        }

    @Test fun startupAndDailyCannotInterleaveFilesystemPasses() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            reconcile = {
                events += "reconcile"
                gate.await()
            }
            val startup = async { host.startup() }
            runCurrent()
            val daily = async { host.daily() }
            runCurrent()
            assertEquals(listOf("reconcile"), events)
            gate.complete(Unit)
            startup.await()
            daily.await()
            assertEquals(
                listOf(
                    "reconcile",
                    "schedule:MANUAL",
                    "schedule:AUTO",
                    "plan",
                    "cleanup:STARTUP",
                    "reconcile",
                    "cleanup:DAILY",
                    "plan",
                    "schedule:MANUAL",
                    "schedule:AUTO",
                ),
                events,
            )
        }
}

internal fun autoPolicy() =
    EffectiveAutoDownload(
        true,
        3,
        NetworkPolicy.UNMETERED,
        false,
        false,
        DeleteAfter.AFTER_24H,
        SettingSource.AppDefault,
    )

internal class AndroidDownloadTestStore : SettingStore {
    private val delegate = FakeSettingsRepository()
    override val file = SettingsFile.DEVICE

    override fun <T : Any> observe(key: SettingKey<T>) = delegate.observe(key)

    override suspend fun <T : Any> get(key: SettingKey<T>) = delegate.get(key)

    override suspend fun <T : Any> set(
        key: SettingKey<T>,
        value: T,
    ) {
        delegate.set(key, value)
    }

    override suspend fun <T : Any> update(
        key: SettingKey<T>,
        transform: (T) -> T,
    ) {
        set(key, transform(get(key)))
    }

    override suspend fun reset(key: SettingKey<*>) = delegate.reset(key)
}
