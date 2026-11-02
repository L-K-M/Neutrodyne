// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.testing

import app.cash.turbine.test
import ch.lkmc.neutrodyne.core.domain.CleanupReason
import ch.lkmc.neutrodyne.core.domain.MoveResult
import ch.lkmc.neutrodyne.core.domain.ProcessExit
import ch.lkmc.neutrodyne.core.domain.ProcessExitReason
import ch.lkmc.neutrodyne.core.model.DeleteAfter
import ch.lkmc.neutrodyne.core.model.EffectiveAutoDownload
import ch.lkmc.neutrodyne.core.model.NetworkPolicy
import ch.lkmc.neutrodyne.core.model.SettingSource
import ch.lkmc.neutrodyne.download.api.FolderRootResult
import ch.lkmc.neutrodyne.download.api.RootChangeMode
import ch.lkmc.neutrodyne.download.api.RootChangeResult
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class FakeAutomaticDownloadsTest {
    @Test fun plannerAndCleanupRecordAndPropagateFailure() =
        runTest {
            val planner = FakeAutoDownloadPlanner()
            planner.plan(listOf(3L, 1L))
            assertEquals(listOf(listOf(3L, 1L)), planner.calls)
            planner.onPlan = { error("planner failure") }
            assertFailsWith<IllegalStateException> { planner.plan(emptyList()) }
            val cleanup = FakeCleanupPlanner()
            cleanup.run(CleanupReason.DAILY)
            assertEquals(listOf(CleanupReason.DAILY), cleanup.calls)
            cleanup.onRun = { error("cleanup failure") }
            assertFailsWith<IllegalStateException> { cleanup.run(CleanupReason.STORAGE) }
        }

    @Test fun moverRecordsMonotonicDeadlineAndExplicitResult() =
        runTest {
            val mover = FakeDownloadMover()
            assertEquals(MoveResult.PENDING, mover.run("target"))
            mover.result = MoveResult.DEADLINE
            assertEquals(MoveResult.DEADLINE, mover.run("other", 123L))
            assertEquals(listOf("target" to null, "other" to 123L), mover.calls)
        }

    @Test fun rootsDoNotPretendUnimplementedOperationsSucceeded() =
        runTest {
            val roots = FakeDownloadRootRegistration()
            assertIs<FolderRootResult.Rejected>(roots.registerFolderRoot("/folder"))
            assertEquals(RootChangeResult.Unavailable, roots.changeRoot("root", RootChangeMode.MOVE_EXISTING))
            assertEquals(listOf("/folder"), roots.registrations)
            assertEquals(listOf("root" to RootChangeMode.MOVE_EXISTING), roots.changes)
        }

    @Test fun scopeFakeClearsOnlyGroupDependentsAndValidatesKeepLatest() =
        runTest {
            val scopes = FakeScopeSettingsRepository()
            scopes.seedPodcast(1)
            scopes.seedGroup(2, "group", "Group")
            scopes.updateGroup(2) {
                it.copy(
                    autoDownload = true,
                    autoDownloadKeepLatest = 7,
                    deleteAfterPlayed = DeleteAfter.NEVER,
                )
            }
            scopes.updateGroup(2) { it.copy(autoDownload = null) }
            val group = requireNotNull(scopes.observeGroup(2).first())
            assertEquals(null, group.own.autoDownloadKeepLatest)
            assertEquals(DeleteAfter.NEVER, group.own.deleteAfterPlayed)
            scopes.updatePodcast(1) { it.copy(autoDownload = false, autoDownloadKeepLatest = 5) }
            assertEquals(
                5,
                scopes
                    .observePodcast(1)
                    .first()
                    ?.own
                    ?.autoDownloadKeepLatest,
            )
            assertIs<ch.lkmc.neutrodyne.core.common.Outcome.Failure<*>>(
                scopes.updatePodcast(1) {
                    it.copy(autoDownloadKeepLatest = 11)
                },
            )
        }

    @Test fun exitProbeModelsNoHistoryAndTimestamp() {
        val probe = FakeExitReasonProbe()
        assertNull(probe.latest())
        val exit = ProcessExit(ProcessExitReason.USER_REQUESTED, 123)
        probe.exit = exit
        assertEquals(exit, probe.latest())
    }

    @OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun resolverSelectsIdsAndConsumerOwnsTwoSecondDebounce() =
        runTest {
            val resolver = FakeEffectiveSettingsResolver()
            val policy =
                EffectiveAutoDownload(
                    false,
                    3,
                    NetworkPolicy.UNMETERED,
                    false,
                    false,
                    DeleteAfter.AFTER_24H,
                    SettingSource.AppDefault,
                )
            resolver.autoDownloadValues.value = mapOf(1L to policy, 2L to policy)
            assertEquals(mapOf(1L to policy), resolver.autoDownload(listOf(1L, 1L, -1L)))
            resolver.observeAutoDownload().debounce(2_000L).test {
                advanceTimeBy(1_999L)
                expectNoEvents()
                resolver.autoDownloadValues.value = mapOf(1L to policy.copy(enabled = true))
                runCurrent()
                advanceTimeBy(1_999L)
                expectNoEvents()
                advanceTimeBy(1L)
                runCurrent()
                assertEquals(resolver.autoDownloadValues.value, awaitItem())
                resolver.autoDownloadValues.value = resolver.autoDownloadValues.value.toMap()
                advanceTimeBy(2_000L)
                expectNoEvents()
            }
        }
}
