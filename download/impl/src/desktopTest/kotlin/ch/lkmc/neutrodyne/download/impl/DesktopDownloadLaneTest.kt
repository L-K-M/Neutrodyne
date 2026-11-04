// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.common.JobLanePoker
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.WaitReason
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakePlaybackMediaLookup
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopDownloadLaneTest {
    @Test fun orphanDeletionCannotTargetAFirstDownloadBeforeItsCompletionCommit() =
        runTest {
            EngineRig(this).use { f ->
                val id =
                    f
                        .seed {
                            it.copy(state = DownloadState.DOWNLOADING, runnerToken = "desk:first")
                        }.single()
                val rig = ReconcileRig(f)
                val path = "Podcast [p1]/Final [e$id].mp3"
                f.roots.selected = "new"
                f.files.createDirectories("new", "Podcast [p1]")
                val finalFile = f.files.openPart("new", path)
                finalFile.append(mediaBytes(), 4096)
                finalFile.close()
                assertTrue(rig.reconciler.orphanFiles().isEmpty())
                assertEquals(4096L, f.files.stat("new", path)?.bytes)
                val row = f.row(id)
                f.dao.transition(row.copy(state = DownloadState.FAILED), row.state, "desk:first")
                assertEquals(listOf(path), rig.reconciler.orphanFiles().map { it.second.path })
            }
        }

    @Test fun journalCannotDeleteAnActiveReplacementBetweenRenameAndCommit() =
        runTest {
            EngineRig(this).use { f ->
                val id = f.seed { it.copy(relativePath = "final.mp3") }.single()
                val rig = ReconcileRig(f)
                val old = f.row(id)
                rig.write("final.mp3")
                rig.deletes.journal(listOf(old))
                f.dao.removeUnclaimed(listOf(id))
                val active =
                    old.copy(
                        state = DownloadState.DOWNLOADING,
                        runnerToken = "desk:replacement",
                        relativePath = null,
                        tempPath = DownloadPaths.part(id),
                    )
                f.dao.insert(active)
                rig.write(DownloadPaths.part(id))
                f.files.atomicMove("old", DownloadPaths.part(id), "final.mp3")
                rig.deletes.drain(null)
                assertTrue(rig.deletes.entries().isNotEmpty())
                assertEquals(4096L, f.files.stat("old", "final.mp3")?.bytes)
                val completed =
                    active.copy(
                        state = DownloadState.COMPLETED,
                        relativePath = "final.mp3",
                        tempPath = null,
                    )
                f.dao.transition(completed, DownloadState.DOWNLOADING, "desk:replacement")
                rig.deletes.completed(completed)
                rig.deletes.drain(null)
                assertTrue(rig.deletes.entries().isEmpty())
                assertEquals(4096L, f.files.stat("old", "final.mp3")?.bytes)
            }
        }

    @Test fun laneReconcilesOnceBeforeDrainAndKeepsUniqueTokens() =
        runTest {
            val events = mutableListOf<String>()
            val requests = mutableListOf<DrainRequest>()
            val activity = DesktopDownloadActivity()
            val lane =
                DesktopDownloadLane(
                    { events += "reconcile" },
                    { request ->
                        events += "drain"
                        requests += request
                        DrainOutcome.Empty
                    },
                    activity,
                )
            repeat(2) { lane.run(Instant.fromEpochMilliseconds(0)) }
            assertEquals(listOf("reconcile", "drain", "drain"), events)
            assertEquals(DesktopDownloadLane.NAME, lane.name)
            assertEquals(2, requests.map { it.runnerToken }.toSet().size)
            for (request in requests) {
                assertEquals(setOf(DownloadLane.MANUAL), request.lanes)
                assertTrue(request.runnerToken.value.startsWith("desk:"))
                assertNull(request.softDeadlineElapsed)
                assertEquals(60_000L, request.waitHorizonMs)
                assertEquals(StopIntent.RunnerStopped(DownloadStopReasons.APP_QUIT), request.cancellationIntent())
            }
        }

    @Test fun failedReconcilePreventsClaimsAndIsRetried() =
        runTest {
            var calls = 0
            var drains = 0
            val lane =
                DesktopDownloadLane(
                    { if (calls++ == 0) error("unavailable") },
                    {
                        drains++
                        DrainOutcome.Empty
                    },
                    DesktopDownloadActivity(),
                )
            assertFailsWith<IllegalStateException> { lane.run(Instant.fromEpochMilliseconds(0)) }
            assertEquals(0, drains)
            lane.run(Instant.fromEpochMilliseconds(0))
            assertEquals(1, drains)
        }

    @Test fun schedulerWakesEngineAndPokesOnlyTheExistingManualLane() =
        runTest {
            val pokes = mutableListOf<String>()
            val wakes = mutableListOf<DownloadLane>()
            val scheduler = DesktopDownloadScheduler({ JobLanePoker { pokes += it } }, { wakes += it })
            scheduler.ensureScheduled(DownloadLane.MANUAL)
            scheduler.scheduleWake(DownloadLane.MANUAL, Long.MAX_VALUE, WaitReason.BACKOFF)
            scheduler.wake(DownloadLane.AUTO)
            assertEquals(List(2) { DesktopDownloadLane.NAME }, pokes)
            assertEquals(List(2) { DownloadLane.MANUAL }, wakes)
            assertFailsWith<IllegalArgumentException> { scheduler.ensureScheduled(DownloadLane.AUTO) }
        }

    @Test fun cancellationUsesQuitReasonKeepsPartAndUnregistersOnlyAfterCommit() =
        runTest {
            EngineRig(this).use { f ->
                val id = f.seed().single()
                val activity = DesktopDownloadActivity()
                f.source.action = { _, part, sink ->
                    sink.started(TransferMetadata(0, 8192, "audio/mpeg", "\"tag\"", null))
                    part.append(mediaBytes(), 4096)
                    awaitCancellation()
                }
                val lane = DesktopDownloadLane({}, { f.engine.drain(it) }, activity)
                val running = async { lane.run(Instant.fromEpochMilliseconds(0)) }
                f.source.starts.receive()
                f.runCurrent()
                assertEquals(1, activity.liveTokens().size)
                assertTrue(activity.hasActiveTransfers())
                running.cancelAndJoin()
                assertTrue(activity.liveTokens().isEmpty())
                assertFalse(activity.hasActiveTransfers())
                assertEquals(DownloadState.QUEUED, f.row(id).state)
                assertEquals(DownloadStopReasons.APP_QUIT, f.row(id).lastStopReason)
                assertEquals(4096L, f.files.stat("old", DownloadPaths.part(id))?.bytes)
                assertEquals(0, f.row(id).attempt)
            }
        }

    @Test fun callbackPauseModeIsHonoredWithoutHistoricalExitInference() =
        runTest {
            EngineRig(this).use { f ->
                val id = f.seed().single()
                f.source.action = { _, _, _ -> awaitCancellation() }
                val request =
                    drainRequest().copy(
                        cancellationIntent = { StopIntent.RunnerStopped(CALLBACK_USER_STOP, RunnerStopMode.PAUSE) },
                    )
                val running = async { f.engine.drain(request) }
                f.source.starts.receive()
                running.cancelAndJoin()
                assertEquals(DownloadState.PAUSED, f.row(id).state)
                assertEquals(CALLBACK_USER_STOP, f.row(id).lastStopReason)
            }
        }

    @Test fun busyIgnoresStorageAndFutureBackoffAndRefreshesAtLaneEnd() =
        runTest {
            EngineRig(this).use { f ->
                val activity = DesktopDownloadActivity()
                val controller =
                    DownloadControllerImpl(
                        f.db,
                        FakeSettingsRepository(),
                        FakeNetworkMonitor(FakeNetworkMonitor.ONLINE),
                        f.clock,
                        f.roots,
                        f.scheduler,
                        f.engine.claimMutex,
                        f.engine::stopTransfer,
                        f.disk,
                        FakePlaybackMediaLookup(),
                        { false },
                        activity.changes,
                        activity::hasActiveTransfers,
                    )
                val id =
                    f
                        .seed {
                            it.copy(waitReason = WaitReason.BACKOFF, nextAttemptAt = f.clock.now() + 1000)
                        }.single()
                val emissions = Channel<Boolean>(Channel.UNLIMITED)
                backgroundScope.launch { controller.observeBusy().collect { emissions.send(it) } }
                assertFalse(emissions.receive())
                testScheduler.advanceTimeBy(1001)
                activity.changed()
                assertTrue(emissions.receive())
                f.dao.markWait(listOf(id), WaitReason.STORAGE, null)
                assertFalse(controller.observeBusy().first())
                f.dao.markWait(listOf(id), WaitReason.NONE, null)
                activity.changed()
                assertTrue(controller.observeBusy().first())
                f.dao.removeUnclaimed(listOf(id))
                activity.active(setOf(id))
                assertTrue(controller.observeBusy().first())
                activity.active(emptySet())
                assertFalse(controller.observeBusy().first())
            }
        }

    private companion object {
        const val CALLBACK_USER_STOP = 3
    }
}
