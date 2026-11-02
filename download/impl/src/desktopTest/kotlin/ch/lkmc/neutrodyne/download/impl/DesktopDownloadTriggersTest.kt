// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.domain.AutoDownloadPlanner
import ch.lkmc.neutrodyne.core.domain.IngestionEvents
import ch.lkmc.neutrodyne.core.domain.RefreshStatus
import ch.lkmc.neutrodyne.core.model.DeleteAfter
import ch.lkmc.neutrodyne.core.model.EffectiveAutoDownload
import ch.lkmc.neutrodyne.core.model.NetworkPolicy
import ch.lkmc.neutrodyne.core.model.NewEpisodes
import ch.lkmc.neutrodyne.core.model.SettingSource
import ch.lkmc.neutrodyne.core.testing.FakeEffectiveSettingsResolver
import ch.lkmc.neutrodyne.core.testing.FakeRefreshController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopDownloadTriggersTest {
    @Test fun ingestionUnionsPodcastIdsForFiveSecondsInsteadOfDebouncingUntilRefreshEnds() =
        runTest {
            val rig = Rig(this)
            rig.start()
            // Ignore the independent initial policy pass.
            advanceTimeBy(2_001)
            rig.calls.clear()
            rig.events.emit(NewEpisodes(1, listOf(11), initialFetch = false))
            runCurrent()
            advanceTimeBy(2_000)
            rig.events.emit(NewEpisodes(2, listOf(22), initialFetch = false))
            runCurrent()
            advanceTimeBy(2_999)
            rig.events.emit(NewEpisodes(1, listOf(12), initialFetch = false))
            runCurrent()
            assertTrue(rig.calls.isEmpty())
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(setOf(1L, 2L)), rig.calls)
            rig.events.emit(NewEpisodes(3, listOf(33), initialFetch = false))
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(listOf(setOf(1L, 2L), setOf(3L)), rig.calls)
        }

    @Test fun policyChangesCoalesceForTwoSecondsAndRunCleanupAfterPlanning() =
        runTest {
            val rig = Rig(this)
            rig.refresh.status.value = running
            rig.start()
            advanceTimeBy(1_000)
            rig.resolver.autoDownloadValues.value = mapOf(1L to policy.copy(keepLatest = 4))
            runCurrent()
            advanceTimeBy(1_000)
            rig.resolver.autoDownloadValues.value = mapOf(1L to policy.copy(enabled = false), 2L to policy)
            runCurrent()
            advanceTimeBy(1_999)
            assertTrue(rig.calls.isEmpty())
            assertEquals(0, rig.cleanups)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(setOf(1L, 2L)), rig.calls)
            assertEquals(listOf("plan", "cleanup"), rig.order)
            assertEquals(1, rig.cleanups)
        }

    @Test fun refreshOnlyPlansOnIdleTransitionsWithTheFullMap() =
        runTest {
            val rig = Rig(this)
            rig.refresh.status.value = running
            rig.start()
            assertTrue(rig.calls.isEmpty())
            rig.refresh.status.value = running.copy(done = 1)
            runCurrent()
            assertTrue(rig.calls.isEmpty())
            rig.refresh.status.value = idle
            runCurrent()
            assertEquals(listOf(setOf(1L)), rig.calls)
            rig.refresh.status.value = idle.copy(lastRunFinishedAt = 1)
            runCurrent()
            assertEquals(1, rig.calls.size)
            rig.refresh.status.value = running
            runCurrent()
            rig.resolver.autoDownloadValues.value = mapOf(2L to policy)
            rig.refresh.status.value = idle.copy(lastRunFinishedAt = 2)
            runCurrent()
            assertEquals(listOf(setOf(1L), setOf(2L)), rig.calls)
        }

    @Test fun initialFetchEventsAreIgnored() =
        runTest {
            val rig = Rig(this)
            rig.start()
            advanceTimeBy(2_001)
            rig.calls.clear()
            rig.events.emit(NewEpisodes(99, listOf(99), initialFetch = true))
            advanceTimeBy(5_001)
            runCurrent()
            assertTrue(rig.calls.isEmpty())
        }

    @Test fun policyEmissionOnlyReplansChangedEntries() =
        runTest {
            val rig = Rig(this)
            rig.resolver.autoDownloadValues.value = mapOf(1L to policy, 2L to policy)
            rig.start()
            advanceTimeBy(2_001)
            rig.calls.clear()
            rig.resolver.autoDownloadValues.value = mapOf(1L to policy.copy(keepLatest = 4), 2L to policy)
            advanceTimeBy(2_001)
            runCurrent()
            assertEquals(listOf(setOf(1L)), rig.calls)
        }

    @Test fun plannerFailureDoesNotKillLaterTriggers() =
        runTest {
            val rig = Rig(this)
            rig.refresh.status.value = running
            rig.fail = true
            rig.start()
            rig.refresh.status.value = idle
            runCurrent()
            assertEquals(1, rig.calls.size)
            rig.fail = false
            rig.refresh.status.value = running
            runCurrent()
            rig.refresh.status.value = idle
            runCurrent()
            assertEquals(2, rig.calls.size)
        }

    private class Rig(
        private val scope: TestScope,
    ) {
        val resolver = FakeEffectiveSettingsResolver().also { it.autoDownloadValues.value = mapOf(1L to policy) }
        val refresh = FakeRefreshController()
        val events = MutableSharedFlow<NewEpisodes>()
        val calls = mutableListOf<Set<Long>>()
        val order = mutableListOf<String>()
        var cleanups = 0
        var fail = false
        private val triggers =
            DesktopDownloadTriggers(
                resolver,
                object : IngestionEvents {
                    override val newEpisodes = events
                },
                refresh,
                object : AutoDownloadPlanner {
                    override suspend fun plan(podcastIds: Collection<Long>) {
                        calls += podcastIds.toSet()
                        order += "plan"
                        if (fail) error("temporary planner failure")
                    }
                },
                {
                    cleanups++
                    order += "cleanup"
                },
            )

        fun start() {
            scope.backgroundScope.launch { triggers.observe() }
            scope.runCurrent()
        }
    }

    private companion object {
        val policy =
            EffectiveAutoDownload(
                true,
                3,
                NetworkPolicy.UNMETERED,
                false,
                false,
                DeleteAfter.AFTER_24H,
                SettingSource.AppDefault,
            )
        val running = RefreshStatus(true, null, 0, 2, null)
        val idle = running.copy(running = false, done = 2)
    }
}
