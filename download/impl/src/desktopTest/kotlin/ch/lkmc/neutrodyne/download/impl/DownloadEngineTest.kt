// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.model.SourceKind
import ch.lkmc.neutrodyne.download.api.DeleteReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ch.lkmc.neutrodyne.core.model.DownloadError as Error
import ch.lkmc.neutrodyne.core.model.DownloadLane as Lane
import ch.lkmc.neutrodyne.core.model.DownloadState as State
import ch.lkmc.neutrodyne.core.model.WaitReason as Wait

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadEngineTest {
    @Test fun freshSuccessHasExactlyThreeSqlWritesAndMemoryVerification() =
        engineTest {
            val id = seed().single()
            audit()
            assertIs<DrainOutcome.Empty>(engine.drain(drainRequest()))
            assertEquals(listOf("RESOLVING", "DOWNLOADING", "COMPLETED"), writes())
            val row = row(id)
            assertEquals(4096L, row.downloadedBytes)
            assertNull(row.tempPath)
            assertNull(row.runnerToken)
            assertEquals("\"tag\"", row.etag)
            assertEquals(listOf(id), completed)
            assertEquals(row.finalUri, indexed[id])
            assertTrue(faults.operations.indexOf("close") < faults.operations.indexOf("move"))
        }

    @Test fun twoParallelDrainsNeverDoubleClaimAndInitializeOnce() =
        engineTest {
            val ids = seed(16) { it.copy(sourceRef = "https://host${it.episodeId}.example/media") }
            listOf("desk:a", "desk:b")
                .map { token ->
                    async(Dispatchers.Default) { engine.drain(drainRequest(token)) }
                }.awaitAll()
            // A drain may close while another holds the slots; the remaining owner drains the queue.
            assertEquals(ids.sorted(), source.calls.sorted())
            assertEquals(
                ids.size,
                source.calls
                    .distinct()
                    .size,
            )
            assertEquals(1, resets)
            assertTrue(ids.all { row(it).state == State.COMPLETED })
        }

    @Test fun pagingPastTwentyBlockedHostsUsesThirdSlotAndKeepsOrdering() =
        engineTest {
            val ids =
                seed(26) {
                    val host = if (it.requestedAt <= 25) "host-a" else "host-b"
                    it.copy(sourceRef = "https://$host/media")
                }
            source.action = { _, _, _ -> awaitCancellation() }
            val drain = async { engine.drain(drainRequest()) }
            val starts = List(3) { source.starts.receive() }
            assertEquals(listOf(ids[0], ids[1], ids[25]), starts)
            runCurrent()
            assertEquals(3, source.calls.size)
            assertEquals(Wait.SLOT, row(ids[20]).waitReason)
            drain.cancelAndJoin()
            assertTrue(starts.all { row(it).attempt == 0 && row(it).runnerToken == null })
            val again = async { engine.drain(drainRequest("desk:again")) }
            assertEquals(starts, List(3) { source.starts.receive() })
            again.cancelAndJoin()
        }

    @Test fun futureCandidateWakesWhileEarlierCandidatesWaitForHostSlots() =
        engineTest {
            val ids =
                seed(4) {
                    val future = it.requestedAt == 4L
                    it.copy(
                        sourceRef = if (future) "https://b/media" else "https://a/media",
                        nextAttemptAt = if (future) clock.now() + 1000 else null,
                    )
                }
            source.action = { _, _, _ -> awaitCancellation() }
            val runner = async { engine.drain(drainRequest(horizon = 0)) }
            try {
                repeat(2) { source.starts.receive() }
                delay(1000)
                runCurrent()
                assertEquals(listOf(ids[0], ids[1], ids[3]), source.calls.toList())
            } finally {
                runner.cancelAndJoin()
            }
        }

    @Test fun autoCapReservesManualSlotAndYoutubeIsLimitedToOne() =
        engineTest {
            val autos =
                seed(
                    4,
                ) { it.copy(lane = Lane.AUTO, sourceKind = SourceKind.YOUTUBE, priority = 0) }
            val rss = seed(2) { it.copy(lane = Lane.AUTO, priority = 0) }
            val manual = seed().single()
            source.action = { _, _, _ -> awaitCancellation() }
            youtube.action = source.action
            val auto = async { engine.drain(drainRequest("desk:auto", setOf(Lane.AUTO))) }
            assertEquals(autos[0], youtube.starts.receive())
            assertEquals(rss[0], source.starts.receive())
            runCurrent()
            assertEquals(1, youtube.calls.size)
            assertEquals(1, source.calls.size)
            val user = async { engine.drain(drainRequest("desk:manual")) }
            assertEquals(manual, source.starts.receive())
            auto.cancelAndJoin()
            user.cancelAndJoin()
            assertTrue((autos + rss + manual).all { row(it).attempt == 0 })
        }

    @Test fun priorityThenRequestTimeThenIdDeterminesAdmission() =
        engineTest {
            val ids =
                seed(5) {
                    val priority =
                        when (it.requestedAt) {
                            4L -> 200
                            5L -> 0
                            else -> 100
                        }
                    it.copy(priority = priority, requestedAt = 1)
                }
            source.action = { _, _, _ -> awaitCancellation() }
            val runner = async { engine.drain(drainRequest()) }
            assertEquals(listOf(ids[3], ids[0]), List(2) { source.starts.receive() })
            runner.cancelAndJoin()
        }

    @Test fun cancelImmediatelyAfterClaimJournalsBeforeRemovalAndTombstonesOnlyUser() =
        runTest {
            for (reason in DeleteReason.entries) {
                EngineRig(this).use { f ->
                    val id = f.seed().single()
                    val preparing = CompletableDeferred<Unit>()
                    f.source.beforePrepare = { _, _ ->
                        preparing.complete(Unit)
                        awaitCancellation()
                    }
                    val exit = TestDrainExit()
                    val runner = async { f.engine.drain(drainRequest(exit = exit)) }
                    preparing.await()
                    if (reason ==
                        DeleteReason.USER
                    ) {
                        f.controller.cancel(listOf(id))
                    } else {
                        f.controller.delete(listOf(id), reason)
                    }
                    runner.await()
                    assertNull(f.dao.byEpisode(id))
                    assertEquals(listOf("journal:$id", "delete:$id"), f.events)
                    assertEquals(
                        reason == DeleteReason.USER,
                        f.db
                            .episodeStateDao()
                            .byEpisode(id)
                            ?.downloadDismissedAt != null,
                    )
                    assertTrue(exit.live.isEmpty())
                }
            }
        }

    @Test fun pauseAndRunnerStopKeepPartWithoutConsumingAttemptAndReleaseSlots() =
        runTest {
            val intents =
                listOf(
                    StopIntent.Pause,
                    StopIntent.Requeue(Wait.NONE, DownloadStopReasons.HANDOVER),
                    StopIntent.RunnerStopped(DownloadStopReasons.APP_QUIT),
                    StopIntent.RunnerStopped(DownloadStopReasons.USER_TASK_MANAGER),
                )
            for (intent in intents) {
                EngineRig(this).use { f ->
                    val id = f.seed().single()
                    f.source.action = { _, part, sink ->
                        sink.started(TransferMetadata(0, 8192, "audio/mpeg", "\"v1\"", null))
                        part.append(mediaBytes(), 4096)
                        awaitCancellation()
                    }
                    val runner = async { f.engine.drain(drainRequest()) }
                    f.source.starts.receive()
                    runCurrent()
                    f.mayClaim = false
                    f.engine.claimMutex.withLock { f.engine.stopTransfer(id, intent) }
                    val stopped = f.row(id)
                    assertEquals(0, stopped.attempt)
                    assertEquals(4096L, stopped.downloadedBytes)
                    assertNull(stopped.runnerToken)
                    val paused =
                        intent == StopIntent.Pause ||
                            intent == StopIntent.RunnerStopped(DownloadStopReasons.USER_TASK_MANAGER)
                    assertEquals(if (paused) State.PAUSED else State.QUEUED, stopped.state)
                    runner.await()
                    f.mayClaim = true
                    f.source.action = { _, _, _ -> TransferResult.Permanent(Error.HTTP_GONE, 410) }
                    val next = f.seed().single()
                    f.engine.drain(drainRequest("desk:next"))
                    assertEquals(State.FAILED, f.row(next).state)
                }
            }
        }

    @Test fun staleOwnerCannotFinishOrPublishIndex() =
        engineTest {
            val id = seed().single()
            val gate = CompletableDeferred<Unit>()
            val transferring = CompletableDeferred<Unit>()
            source.action = { _, part, sink ->
                sink.started(TransferMetadata(0, 4096, "audio/mpeg", "\"tag\"", null))
                part.append(mediaBytes(), 4096)
                transferring.complete(Unit)
                withContext(NonCancellable) { gate.await() }
                TransferResult.Complete(4096, "audio/mpeg", "\"tag\"", null, MediaKind.MP3)
            }
            val runner = async { engine.drain(drainRequest()) }
            source.starts.receive()
            transferring.await()
            val owned = row(id)
            dao.transition(owned.copy(state = State.QUEUED), owned.state, "desk:test")
            assertEquals(1, dao.claim(id, "desk:new"))
            gate.complete(Unit)
            runner.await()
            assertEquals("desk:new", row(id).runnerToken)
            assertEquals(State.RESOLVING, row(id).state)
            assertTrue(indexed.isEmpty())
            assertFalse("move" in faults.operations)
        }

    @Test fun retriesUseVirtualTwoEightThirtySecondsAndStopAtEightAttempts() =
        runTest {
            for (attempt in listOf(0, 7)) {
                EngineRig(this).use { f ->
                    val id = f.seed { it.copy(attempt = attempt) }.single()
                    val times = mutableListOf<Long>()
                    f.source.action =
                        {
                            _,
                            _,
                            _,
                            ->
                            times += f.clock.elapsedRealtime()
                            TransferResult.Transient(Error.HTTP_SERVER, 503, null)
                        }
                    val end = f.engine.drain(drainRequest())
                    assertEquals(listOf(0L, 2000L, 10000L, 40000L), times.map { it - times[0] })
                    val row = f.row(id)
                    assertEquals(attempt + 1, row.attempt)
                    if (attempt == 0) {
                        assertIs<DrainOutcome.Waiting>(end)
                        assertEquals(60_000L, row.nextAttemptAt!! - f.clock.now())
                    } else {
                        assertEquals(State.FAILED, row.state)
                        assertNull(row.nextAttemptAt)
                    }
                }
            }
        }

    @Test fun restartBudgetBacksOffWithoutConsumingIntegrityFailures() =
        engineTest {
            val id = seed().single()
            source.action = { _, _, _ -> TransferResult.Restart("changed") }
            assertIs<DrainOutcome.Waiting>(engine.drain(drainRequest()))
            assertEquals(State.QUEUED, row(id).state)
            assertEquals(Wait.BACKOFF, row(id).waitReason)
            assertEquals(0, row(id).integrityFailures)
            assertEquals(1, row(id).attempt)
            assertEquals(6, source.calls.size)
        }

    @Test fun twoVerificationFailuresStopWithoutNetworkRetries() =
        engineTest {
            val id = seed().single()
            source.action = { _, _, _ -> TransferResult.Transient(Error.SIZE_MISMATCH, null, null) }
            engine.drain(drainRequest())
            assertEquals(State.FAILED, row(id).state)
            assertEquals(2, row(id).integrityFailures)
            assertEquals(0, row(id).attempt)
            assertEquals(2, source.calls.size)
        }

    @Test fun progressTicksNeverWriteTheDatabase() =
        engineTest {
            val id = seed().single()
            audit()
            val started = CompletableDeferred<Unit>()
            source.action = { _, _, sink ->
                sink.started(TransferMetadata(0, null, "audio/mpeg", null, null))
                started.complete(Unit)
                repeat(100) {
                    sink.bytes(it.toLong(), null)
                    delay(10)
                }
                awaitCancellation()
            }
            val runner = async { engine.drain(drainRequest()) }
            started.await()
            delay(1000)
            assertEquals(listOf("RESOLVING", "DOWNLOADING"), writes())
            engine.stopTransfer(id, StopIntent.Pause)
            runner.await()
            assertEquals(listOf("RESOLVING", "DOWNLOADING", "PAUSED"), writes())
        }

    @Test fun cancellationAfterDurableClaimOrStartedCommitStillClearsOwner() =
        runTest {
            for (target in listOf(State.RESOLVING, State.DOWNLOADING)) {
                val driver = CancelOnCommitDriver(target)
                EngineRig(this, driver = driver).use { f ->
                    val id = f.seed().single()
                    f.source.action = { _, _, sink ->
                        sink.started(TransferMetadata(0, null, "audio/mpeg", null, null))
                        awaitCancellation()
                    }
                    val runner = async(start = CoroutineStart.LAZY) { f.engine.drain(drainRequest()) }
                    driver.onCommit = { runner.cancel() }
                    runner.start()
                    runner.join()
                    assertTrue(driver.cancelled.get(), "commit hook must run for $target")
                    assertEquals(State.QUEUED, f.row(id).state)
                    assertNull(f.row(id).runnerToken)
                    assertEquals(0, f.row(id).attempt)
                }
            }
        }

    @Test fun requeuedOwnerRemainsExclusiveUntilItsCleanupFinishes() =
        engineTest {
            val id = seed().single()
            val committed = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            onSchedule = {
                committed.complete(Unit)
                release.await()
            }
            source.beforePrepare = { _, _ -> Prepared.Wait(Wait.NONE, null, null, false) }
            val first = async { runCatching { engine.drain(drainRequest("desk:first")) } }
            var second: Deferred<Result<DrainOutcome>>? = null
            try {
                committed.await()
                source.beforePrepare = { _, _ -> null }
                second = async { runCatching { engine.drain(drainRequest("desk:second")) } }
                runCurrent()
                assertNull(row(id).runnerToken)
                assertTrue(source.calls.isEmpty())
                release.complete(Unit)
                first.await().getOrThrow()
                second.await().getOrThrow()
                assertEquals(State.COMPLETED, row(id).state)
                assertEquals(listOf(id), source.calls.toList())
            } finally {
                release.complete(Unit)
                first.cancelAndJoin()
                second?.cancelAndJoin()
            }
        }

    @Test fun userPauseAfterSystemRequeueStillEndsPaused() =
        engineTest {
            val id = seed().single()
            val committed = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            onSchedule = {
                committed.complete(Unit)
                release.await()
            }
            source.action = { _, _, _ -> awaitCancellation() }
            val request = drainRequest()
            val runner = async { engine.drain(request) }
            try {
                source.starts.receive()
                engine.stopRunner(request.runnerToken, StopIntent.Requeue(Wait.NETWORK))
                committed.await()
                val pause = async { engine.claimMutex.withLock { engine.stopTransfer(id, StopIntent.Pause) } }
                runCurrent()
                release.complete(Unit)
                pause.await()
                runner.await()
                assertEquals(State.PAUSED, row(id).state)
                assertEquals(DownloadStopReasons.USER_PAUSE, row(id).lastStopReason)
                assertEquals(0, row(id).attempt)
            } finally {
                release.complete(Unit)
                runner.cancelAndJoin()
            }
        }

    @Test fun runnerStopDuringClaimIsAppliedBeforeAnyTransferWork() =
        runTest {
            val driver = CancelOnCommitDriver(State.RESOLVING)
            EngineRig(this, driver = driver).use { f ->
                val id = f.seed().single()
                val stop = StopIntent.RunnerStopped(DownloadStopReasons.APP_QUIT)
                driver.onCommit = { f.engine.stopRunner(RunnerToken("desk:test"), stop) }
                f.engine.drain(drainRequest())
                assertTrue(driver.cancelled.get())
                assertEquals(State.QUEUED, f.row(id).state)
                assertEquals(DownloadStopReasons.APP_QUIT, f.row(id).lastStopReason)
                assertEquals(0, f.row(id).attempt)
                assertTrue(f.source.calls.isEmpty())
            }
        }

    @Test fun concurrentCancelAndClaimAlwaysRemoveRowAndReleaseOwner() =
        engineTest {
            source.action = { _, _, _ -> awaitCancellation() }
            repeat(12) { n ->
                val id = seed().single()
                val runner = async(Dispatchers.Default) { engine.drain(drainRequest("desk:race$n")) }
                val cancel = async(Dispatchers.Default) { controller.cancel(listOf(id)) }
                cancel.await()
                runner.await()
                assertNull(dao.byEpisode(id))
                assertNotNull(db.episodeStateDao().byEpisode(id)?.downloadDismissedAt)
            }
        }

    @Test fun waitsDoNotConsumeAttemptsAndLongRetryAfterDoes() =
        runTest {
            for (counted in listOf(false, true)) {
                EngineRig(this).use { f ->
                    val id = f.seed().single()
                    f.source.beforePrepare =
                        { _, _ ->
                            Prepared.Wait(
                                Wait.BACKOFF,
                                Error.HTTP_RATE_LIMITED,
                                f.clock.now() + 7_200_000,
                                counted,
                            )
                        }
                    assertIs<DrainOutcome.Waiting>(f.engine.drain(drainRequest()))
                    assertEquals(if (counted) 1 else 0, f.row(id).attempt)
                    assertEquals(7_200_000L, f.row(id).nextAttemptAt!! - f.clock.now())
                    assertTrue(f.source.calls.isEmpty())
                }
            }
        }

    @Test fun deadlineWinsOverRetry() =
        engineTest {
            val id = seed().single()
            source.action = { _, _, _ -> TransferResult.Transient(Error.NETWORK_IO, null, null) }
            assertIs<DrainOutcome.DeadlineReached>(engine.drain(drainRequest(deadline = 1000)))
            assertEquals(0, row(id).attempt)
            assertEquals(DownloadStopReasons.SOFT_DEADLINE, row(id).lastStopReason)
            assertEquals(Wait.SYSTEM, row(id).waitReason)
            assertTrue(scheduled.isNotEmpty())
        }

    @Test fun waitHorizonKeepsOnlyNearRetriesAlive() =
        runTest {
            for (minutes in listOf(4, 6)) {
                EngineRig(this).use { f ->
                    val id = f.seed { it.copy(nextAttemptAt = f.clock.now() + minutes * 60_000) }.single()
                    val elapsed = f.clock.elapsedRealtime()
                    val outcome = f.engine.drain(drainRequest(horizon = 300_000))
                    if (minutes == 4) {
                        assertIs<DrainOutcome.Empty>(outcome)
                        assertEquals(240_000L, f.clock.elapsedRealtime() - elapsed)
                        assertEquals(State.COMPLETED, f.row(id).state)
                    } else {
                        assertIs<DrainOutcome.Waiting>(outcome)
                        assertEquals(0L, f.clock.elapsedRealtime() - elapsed)
                    }
                }
            }
        }

    @Test fun offlineMakesNoClaimsAndConditionChangesStopOnlyRestrictedRows() =
        engineTest {
            val ids = seed(2) { it.copy(allowMetered = it.requestedAt == 2L) }
            conditions.value = conditions.value.copy(connected = false)
            assertEquals(DrainOutcome.Blocked(Wait.NETWORK), engine.drain(drainRequest()))
            assertTrue(source.calls.isEmpty())
            assertTrue(ids.all { row(it).state == State.QUEUED })
            conditions.value = conditions.value.copy(connected = true)
            source.action = { _, _, _ -> awaitCancellation() }
            val runner = async { engine.drain(drainRequest()) }
            repeat(2) { source.starts.receive() }
            conditions.value = conditions.value.copy(unmetered = false)
            runCurrent()
            assertEquals(Wait.UNMETERED_NETWORK, row(ids[0]).waitReason)
            assertNotNull(row(ids[1]).runnerToken)
            conditions.value = conditions.value.copy(connected = false)
            runner.await()
            assertTrue(ids.all { row(it).attempt == 0 && row(it).runnerToken == null })
        }

    @Test fun wakeRechecksQueueAndExitReopensWithoutLosingNewRows() =
        engineTest {
            val id = seed().single()
            val gate = CompletableDeferred<Unit>()
            source.action = { _, _, _ ->
                gate.await()
                TransferResult.Permanent(Error.HTTP_GONE, 410)
            }
            val exit = TestDrainExit()
            var added: Long? = null
            exit.close = {
                if (added == null) {
                    added = seed().single()
                    engine.wake(Lane.MANUAL)
                    false
                } else {
                    true
                }
            }
            val runner = async { engine.drain(drainRequest(exit = exit)) }
            source.starts.receive()
            gate.complete(Unit)
            runner.await()
            assertEquals(listOf(id, added), source.calls)
            assertTrue(exit.live.isEmpty())
        }

    @Test fun credentialsBlockAllClaimsAndRunnerTokenSurvivesChildCleanup() =
        runTest {
            val credentialsReady = CompletableDeferred<Unit>()
            val credentials =
                object : CredentialLookup {
                    override fun basicAuthorization(origin: ch.lkmc.neutrodyne.core.common.Origin): String? = null

                    override suspend fun awaitLoaded() {
                        credentialsReady.await()
                    }
                }
            EngineRig(this, credentials).use { f ->
                val id = f.seed().single()
                val cleanup = CompletableDeferred<Unit>()
                val cleaning = CompletableDeferred<Unit>()
                f.source.action = { _, _, _ ->
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            cleaning.complete(Unit)
                            cleanup.await()
                        }
                    }
                }
                val exit = TestDrainExit()
                val runner = async { f.engine.drain(drainRequest(exit = exit)) }
                runCurrent()
                assertEquals(State.QUEUED, f.row(id).state)
                credentialsReady.complete(Unit)
                f.source.starts.receive()
                runner.cancel()
                cleaning.await()
                assertTrue("desk:test" in exit.live)
                cleanup.complete(Unit)
                runner.join()
                assertTrue(exit.live.isEmpty())
                assertNull(f.row(id).runnerToken)
            }
        }
}
