// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.common.JobLanePoker
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.datastore.SettingStore
import ch.lkmc.neutrodyne.core.model.DeleteAfter
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.EffectiveAutoDownload
import ch.lkmc.neutrodyne.core.model.NetworkPolicy
import ch.lkmc.neutrodyne.core.model.SettingSource
import ch.lkmc.neutrodyne.core.model.WaitReason
import ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.testing.FakeEffectiveSettingsResolver
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakePlaybackMediaLookup
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
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
                manualLane(
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
                manualLane(
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

    @Test fun schedulerWakesAndPokesBothLanes() =
        runTest {
            val pokes = mutableListOf<String>()
            val wakes = mutableListOf<DownloadLane>()
            val scheduler = DesktopDownloadScheduler({ JobLanePoker { pokes += it } }, { wakes += it })
            scheduler.ensureScheduled(DownloadLane.MANUAL)
            scheduler.scheduleWake(DownloadLane.MANUAL, Long.MAX_VALUE, WaitReason.BACKOFF)
            scheduler.wake(DownloadLane.AUTO)
            scheduler.ensureScheduled(DownloadLane.AUTO)
            scheduler.scheduleWake(DownloadLane.AUTO, Long.MAX_VALUE, WaitReason.BACKOFF)
            assertEquals(listOf("downloads-manual", "downloads-manual", "downloads-auto", "downloads-auto"), pokes)
            assertEquals(listOf(DownloadLane.MANUAL, DownloadLane.MANUAL) + List(3) { DownloadLane.AUTO }, wakes)
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
                val lane = manualLane({}, { f.engine.drain(it) }, activity)
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

    @Test fun bothLanesWaitForOneStartupAndKeepIndependentActivity() =
        runTest {
            val events = mutableListOf<String>()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val startup =
                DesktopDownloadStartup {
                    events += "reconcile"
                    entered.complete(Unit)
                    release.await()
                    events += "planner"
                }
            val activity = DesktopDownloadActivity()
            val requests = mutableMapOf<DownloadLane, DrainRequest>()

            fun lane(lane: DownloadLane) =
                DesktopDownloadLane(
                    lane,
                    startup,
                    { events += "daily" },
                    { true },
                    {
                        requests[lane] = it
                        events += "drain:$lane"
                        it.reporter.active(setOf(lane.ordinal.toLong()))
                        try {
                            awaitCancellation()
                        } finally {
                            it.reporter.active(emptySet())
                        }
                    },
                    activity,
                )
            val manual = async { lane(DownloadLane.MANUAL).run(at(1)) }
            entered.await()
            val auto = async { lane(DownloadLane.AUTO).run(at(1)) }
            runCurrent()
            assertEquals(listOf("reconcile"), events)
            release.complete(Unit)
            runCurrent()
            assertEquals(listOf("reconcile", "planner", "drain:MANUAL", "daily", "drain:AUTO"), events)
            assertEquals(setOf(DownloadLane.AUTO), requests.getValue(DownloadLane.AUTO).lanes)
            assertTrue(
                requests
                    .getValue(DownloadLane.AUTO)
                    .runnerToken.value
                    .startsWith("desk:AUTO:"),
            )
            manual.cancelAndJoin()
            assertTrue(activity.hasActiveTransfers(), "MANUAL completion cannot clear AUTO activity")
            auto.cancelAndJoin()
            assertFalse(activity.hasActiveTransfers())
        }

    @Test fun failedStartupPlannerRetriesRecoveryBeforeEitherLaneCanDrain() =
        runTest {
            var reconciles = 0
            var plans = 0
            var drains = 0
            val startup =
                DesktopDownloadStartup {
                    reconciles++
                    if (plans++ == 0) error("planner failed")
                }

            fun lane(mode: DownloadLane) =
                DesktopDownloadLane(
                    mode,
                    startup,
                    {},
                    { true },
                    {
                        drains++
                        DrainOutcome.Empty
                    },
                    DesktopDownloadActivity(),
                )
            assertFailsWith<IllegalStateException> { lane(DownloadLane.AUTO).run(at(1)) }
            assertEquals(0, drains)
            lane(DownloadLane.MANUAL).run(at(2))
            lane(DownloadLane.AUTO).run(at(2))
            assertEquals(2, reconciles)
            assertEquals(2, plans)
            assertEquals(2, drains)
        }

    @Test fun dailyRunsAtExactBoundaryPersistsAcrossRestartAndCatchesUpAfterSleep() =
        runTest {
            val store = JournalTestStore()
            val events = mutableListOf<String>()

            fun daily() =
                DesktopDownloadDaily(store) {
                    events += "reconcile-files"
                    events += "cleanup:DAILY"
                    events += "planner"
                }
            val start = 10_000L
            val day = DesktopDownloadDaily.DAILY_INTERVAL_MS
            val daily = daily()
            daily.run(at(start))
            daily.run(at(start + day - 1))
            daily().run(at(start + day - 1))
            assertEquals(3, events.size)
            assertEquals(start, store.get(DownloadSettingKeys.CLEANUP_LAST_AT))
            daily.run(at(start + day))
            assertEquals(6, events.size)
            // A suspended process gets no intermediate ticks. Its first wake invocation catches up once.
            daily.run(at(start + 5 * day))
            daily.run(at(start + 5 * day + 60_000))
            assertEquals(9, events.size)
            assertEquals(start + 5 * day, store.get(DownloadSettingKeys.CLEANUP_LAST_AT))
            assertEquals(List(3) { listOf("reconcile-files", "cleanup:DAILY", "planner") }.flatten(), events)
        }

    @Test fun dailyFailureOrQuitDoesNotAdvancePersistedTime() =
        runTest {
            val store = JournalTestStore()
            store.set(DownloadSettingKeys.CLEANUP_LAST_AT, 1L)
            val due = 1L + DesktopDownloadDaily.DAILY_INTERVAL_MS
            var calls = 0
            val failed =
                DesktopDownloadDaily(store) {
                    calls++
                    error("disk unavailable")
                }
            repeat(2) { assertFailsWith<IllegalStateException> { failed.run(at(due)) } }
            assertEquals(2, calls)
            assertEquals(1L, store.get(DownloadSettingKeys.CLEANUP_LAST_AT))
            val entered = CompletableDeferred<Unit>()
            val cancelled =
                DesktopDownloadDaily(store) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            val job = async { cancelled.run(at(due)) }
            entered.await()
            job.cancelAndJoin()
            assertEquals(1L, store.get(DownloadSettingKeys.CLEANUP_LAST_AT))
            DesktopDownloadDaily(store) {}.run(at(due))
            assertEquals(due, store.get(DownloadSettingKeys.CLEANUP_LAST_AT))
        }

    @Test fun failedTimestampWriteLeavesDailyPassDue() =
        runTest {
            val delegate = JournalTestStore()
            var fail = true
            val store =
                object : SettingStore by delegate {
                    override suspend fun <T : Any> set(
                        key: SettingKey<T>,
                        value: T,
                    ) {
                        if (fail) error("disk full")
                        delegate.set(key, value)
                    }
                }
            var passes = 0
            val daily = DesktopDownloadDaily(store) { passes++ }
            assertFailsWith<IllegalStateException> { daily.run(at(1)) }
            fail = false
            daily.run(at(1))
            assertEquals(2, passes)
            assertEquals(1L, store.get(DownloadSettingKeys.CLEANUP_LAST_AT))
        }

    @Test fun onlyAutoPerformsDailyMaintenanceEvenOffline() =
        runTest {
            val events = mutableListOf<String>()
            val startup = DesktopDownloadStartup { events += "startup" }
            val daily = DesktopDownloadDaily(JournalTestStore()) { events += "daily" }
            for (mode in listOf(DownloadLane.MANUAL, DownloadLane.AUTO, DownloadLane.AUTO)) {
                DesktopDownloadLane(mode, startup, daily::run, { false }, {
                    error("offline drain")
                }, DesktopDownloadActivity()).run(at(100))
            }
            assertEquals(listOf("startup", "daily"), events)
        }

    @Test fun clockMovingBackwardsDoesNotRepeatDailyCleanup() =
        runTest {
            val store = JournalTestStore()
            var passes = 0
            val daily = DesktopDownloadDaily(store) { passes++ }
            daily.run(at(10_000))
            daily.run(at(1))
            assertEquals(1, passes)
        }

    @Test fun autoLaneUsesSharedPlannerToDownloadNewestThreeWithoutPolicyHolds() =
        runTest {
            EngineRig(this).use { f ->
                val podcast =
                    f.db.podcastDao().insertPodcast(
                        podcastEntity(subscribedAt = 1) {
                            copy(autoDownloadEligibleAfter = 1)
                        },
                    )
                val ids =
                    (1..5).map { n ->
                        f.db.episodeDao().insertStub(
                            episodeEntity(podcastId = podcast, sortDate = n.toLong()) {
                                copy(
                                    isNew = true,
                                    firstSeenAt = 2,
                                    enclosureUrl = "https://origin.example/$n.mp3",
                                    enclosureType = "audio/mpeg",
                                )
                            },
                        )
                    }
                val resolver =
                    FakeEffectiveSettingsResolver().also {
                        it.autoDownloadValues.value =
                            mapOf(
                                podcast to
                                    EffectiveAutoDownload(
                                        true,
                                        3,
                                        NetworkPolicy.UNMETERED,
                                        true,
                                        false,
                                        DeleteAfter.AFTER_24H,
                                        SettingSource.AppDefault,
                                    ),
                            )
                    }
                val planner =
                    AutoDownloadPlannerImpl(
                        f.db,
                        resolver,
                        DownloadQuota(FakeSettingsRepository()),
                        JournalTestStore(),
                        f.clock,
                        f.roots,
                        f.scheduler,
                        f.engine.claimMutex,
                        f.engine::stopTransfer,
                        f.disk,
                        FakePlaybackMediaLookup(),
                        PlatformKind.DESKTOP,
                        f.dispatcher,
                    )
                val rig = ReconcileRig(f)
                val lane =
                    DesktopDownloadLane(
                        DownloadLane.AUTO,
                        DesktopDownloadStartup {
                            rig.reconciler.reconcile()
                            planner.plan(listOf(podcast))
                        },
                        {},
                        { true },
                        { f.engine.drain(it) },
                        DesktopDownloadActivity(),
                    )
                lane.run(at(1))
                assertEquals(
                    ids.takeLast(3).toSet(),
                    f.dao
                        .allRows()
                        .map { it.episodeId }
                        .toSet(),
                )
                for (row in f.dao.allRows()) {
                    assertEquals(DownloadLane.AUTO, row.lane)
                    assertEquals(DownloadState.COMPLETED, row.state)
                    assertTrue(row.allowMetered)
                    assertFalse(row.requireCharging)
                }
                assertEquals(listOf(DownloadLane.AUTO), f.scheduled)
            }
        }

    @Test fun busyIncludesPendingMoveEvenWithoutDownloadsOrNetwork() =
        runTest {
            EngineRig(this).use { f ->
                val activity = DesktopDownloadActivity()
                val store = JournalTestStore()
                val controller =
                    DownloadControllerImpl(
                        f.db,
                        FakeSettingsRepository(),
                        FakeNetworkMonitor(),
                        f.clock,
                        f.roots,
                        f.scheduler,
                        f.engine.claimMutex,
                        f.engine::stopTransfer,
                        f.disk,
                        FakePlaybackMediaLookup(),
                        { false },
                        busy = activity.observeBusy(store),
                    )
                assertFalse(controller.observeBusy().first())
                store.set(DownloadSettingKeys.MOVE_TARGET, "new")
                assertTrue(controller.observeBusy().first())
                store.set(DownloadSettingKeys.MOVE_TARGET, "")
                assertFalse(controller.observeBusy().first())
            }
        }

    @Test fun dailyReconcileChecksFilesWithoutResettingAnyRunner() =
        runTest {
            EngineRig(this).use { f ->
                val active = f.seed { it.copy(state = DownloadState.DOWNLOADING, runnerToken = "desk:active") }.single()
                val completed =
                    f
                        .seed {
                            it.copy(state = DownloadState.COMPLETED, relativePath = "gone.mp3", totalBytes = 4096)
                        }.single()
                val rig = ReconcileRig(f)
                DesktopDownloadDaily(rig.store) { rig.reconciler.reconcileFiles() }.run(at(1))
                assertEquals(DownloadState.DOWNLOADING, f.row(active).state)
                assertEquals("desk:active", f.row(active).runnerToken)
                assertEquals(DownloadState.MISSING, f.row(completed).state)
                assertNull(rig.index.localUriOrNull(completed))
            }
        }

    private fun at(ms: Long) = Instant.fromEpochMilliseconds(ms)

    private fun manualLane(
        reconcile: suspend () -> Unit,
        drain: suspend (DrainRequest) -> DrainOutcome,
        activity: DesktopDownloadActivity,
    ) = DesktopDownloadLane(DownloadLane.MANUAL, DesktopDownloadStartup(reconcile), {}, { true }, drain, activity)

    private companion object {
        const val CALLBACK_USER_STOP = 3
    }
}
