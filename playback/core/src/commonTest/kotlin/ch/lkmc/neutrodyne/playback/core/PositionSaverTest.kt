// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.core

import ch.lkmc.neutrodyne.core.domain.PositionStore
import ch.lkmc.neutrodyne.core.domain.StoredPosition
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.model.QueueOrigin
import ch.lkmc.neutrodyne.core.testing.FakePlaySessionStore
import ch.lkmc.neutrodyne.core.testing.FakePositionStore
import ch.lkmc.neutrodyne.core.testing.TestClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PositionSaverTest {
    private val store = FakePositionStore()
    private val clock = TestClock(nowMs = 1000)
    private val saver = PositionSaver(store, clock, PlayedRule())

    private fun load(
        source: PositionSource = PositionSource.STREAM,
        replay: Boolean = false,
    ) {
        saver.load(1, 1000, source, replay)
        saver.startPositionApplied()
    }

    @Test fun ticksSaveEveryFiveSecondsOnlyWhileAdvancing() =
        runTest {
            load()
            saver.onPlaying()
            assertEquals(PositionSaveResult.Skipped, saver.onTick(4000, 100_000, true))
            clock.elapsedMs = 4999
            saver.onTick(4999, 100_000, true)
            assertTrue(store.calls.isEmpty())
            clock.elapsedMs = 5000
            assertEquals(PositionSaveResult.Skipped, saver.onTick(5000, 100_000, false))
            assertEquals(PositionSaveResult.Saved, saver.onTick(5000, 100_000, true))
            clock.elapsedMs = 9999
            saver.onTick(9999, 100_000, true)
            assertEquals(1, store.calls.size)
            clock.elapsedMs = 10_000
            saver.onTick(10_000, 100_000, true)
            assertEquals(listOf(5000L, 10_000), store.calls.map { it.positionMs })
        }

    @Test fun pauseSeekAndFlushPersistPositionAndSourceWithCurrentSince() =
        runTest {
            load(PositionSource.DOWNLOAD)
            saver.onPause(10_000, 100_000)
            saver.onSeek(15_000, 100_000, SeekOrigin.USER)
            saver.flush(20_000, 100_000)
            assertEquals(listOf(10_000L, 15_000, 20_000), store.calls.map { it.positionMs })
            assertTrue(store.calls.all { it.source == PositionSource.DOWNLOAD && it.pinStartedAt == 1000L })
            assertEquals(20_000L, store.positions.value[1]?.positionMs)
        }

    @Test fun startBarrierAndCoreSeeksDoNotOverwriteLongSavedProgress() =
        runTest {
            store.positions.value = mapOf(1L to StoredPosition(1, 2_700_000, 900, null))
            saver.load(1, 1000, PositionSource.STREAM)
            clock.elapsedMs = 5000
            saver.onTick(200, null, true)
            saver.onPause(250, null)
            saver.onSeek(300, null, SeekOrigin.CORE)
            assertTrue(store.calls.isEmpty())
            saver.startPositionApplied()
            saver.onTick(2_705_000, null, true)
            assertEquals(2_705_000L, store.positions.value[1]?.positionMs)
        }

    @Test fun regularZeroCannotOverwriteNonzeroButUserSeekBelowOneSecondResets() =
        runTest {
            load()
            saver.onPause(9000, null)
            assertEquals(PositionSaveResult.Skipped, saver.onPause(0, null))
            assertEquals(9000L, store.positions.value[1]?.positionMs)
            saver.onSeek(999, null, SeekOrigin.USER)
            assertEquals(0L, store.positions.value[1]?.positionMs)
            assertEquals(FakePositionStore.Operation.RESET, store.calls.last().operation)
            saver.onSeek(1000, null, SeekOrigin.USER)
            assertEquals(1000L, store.positions.value[1]?.positionMs)
            assertEquals(FakePositionStore.Operation.SAVE, store.calls.last().operation)
        }

    @Test fun playedAfterStartGuardKeepsResetAndOlderReplayMarkDoesNotBlockSave() =
        runTest {
            load()
            store.positions.value = mapOf(1L to StoredPosition(1, 0, 1000, 1000))
            assertEquals(PositionSaveResult.Skipped, saver.onPause(9000, null))
            assertEquals(0L, store.positions.value[1]?.positionMs)
            store.positions.value = mapOf(1L to StoredPosition(1, 0, 999, 999))
            assertEquals(PositionSaveResult.Saved, saver.onPause(9000, null))
        }

    @Test fun failedSqliteFullWriteRetriesOnNextDueTick() =
        runTest {
            load()
            clock.elapsedMs = 5000
            store.failure = IllegalStateException("SQLITE_FULL")
            assertIs<PositionSaveResult.Failed>(saver.onTick(5000, null, true))
            assertTrue(store.positions.value.isEmpty())
            store.failure = null
            clock.elapsedMs = 6000
            assertEquals(PositionSaveResult.Saved, saver.onTick(6000, null, true))
            assertEquals(6000L, store.positions.value[1]?.positionMs)
        }

    @Test fun failedExplicitResetRetriesAsResetInsteadOfLosingToZeroGuard() =
        runTest {
            load()
            saver.onPause(9000, null)
            store.failure = IllegalStateException("SQLITE_FULL")
            assertIs<PositionSaveResult.Failed>(saver.onSeek(0, null, SeekOrigin.USER))
            store.failure = null
            clock.elapsedMs = 5000
            assertEquals(PositionSaveResult.Saved, saver.onTick(0, null, true))
            assertEquals(FakePositionStore.Operation.RESET, store.calls.last().operation)
            assertEquals(0L, store.positions.value[1]?.positionMs)
        }

    @Test fun transitionCapturesOutgoingBeforeArrivalAndPreservesOriginAndDuration() =
        runTest {
            load()
            saver.onPlaying()
            val outgoing = saver.capture(95_000, 100_000, PlaybackExit.SEEK)!!
            val sessions = FakePlaySessionStore()
            sessions.onTransition(outgoing.departure, 2, QueueOrigin.CONTEXT, clock.now())
            saver.load(2, 2000, PositionSource.DOWNLOAD)
            saver.startPositionApplied()
            assertEquals(
                1L,
                sessions.calls
                    .single()
                    .departure!!
                    .episodeId,
            )
            assertEquals(
                95_000L,
                sessions.calls
                    .single()
                    .departure!!
                    .positionMs,
            )
            assertTrue(
                sessions.calls
                    .single()
                    .departure!!
                    .markPlayed,
            )
            assertEquals(QueueOrigin.CONTEXT, sessions.calls.single().origin)
            // An application-scope flush still targets outgoing, even after arrival has loaded.
            saver.flush(outgoing)
            saver.onPause(10_000, null)
            assertEquals(listOf(1L, 2), store.calls.map { it.id })
            assertEquals(listOf(95_000L, 10_000), store.calls.map { it.positionMs })
        }

    @Test fun pauseAndDismissNeverMarkAndNeverStartedSkipDoesNotMark() {
        load()
        assertFalse(saver.capture(95_000, 100_000, PlaybackExit.SEEK)!!.departure.markPlayed)
        assertFalse(saver.capture(95_000, 100_000, PlaybackExit.PAUSE)!!.departure.markPlayed)
        assertFalse(saver.capture(95_000, 100_000, PlaybackExit.DISMISS)!!.departure.markPlayed)
        assertTrue(saver.capture(95_000, 100_000, PlaybackExit.END)!!.departure.markPlayed)
    }

    @Test fun replayClearsPlayedOnlyAtFirstActualPlayingAndRetriesFailure() =
        runTest {
            store.positions.value = mapOf(1L to StoredPosition(1, 0, 1, 1))
            load(replay = true)
            assertTrue(store.calls.isEmpty())
            store.failure = IllegalStateException("SQLITE_FULL")
            assertIs<PositionSaveResult.Failed>(saver.onPlaying())
            assertEquals(1L, store.positions.value[1]?.playedAt)
            store.failure = null
            clock.elapsedMs = 5000
            saver.onTick(5000, null, true)
            assertNull(store.positions.value[1]?.playedAt)
            val replayCalls = store.calls.count { it.operation == FakePositionStore.Operation.REPLAY }
            saver.onPlaying()
            assertEquals(replayCalls, store.calls.count { it.operation == FakePositionStore.Operation.REPLAY })
        }

    @Test fun allowedPredicateIsEvaluatedAtCommitAfterAQueuedSaveWasCaptured() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val delayed =
                object : PositionStore by store {
                    override suspend fun save(
                        episodeId: Long,
                        positionMs: Long,
                        durationMs: Long?,
                        source: PositionSource,
                        now: Long,
                        pinStartedAt: Long,
                        allowed: () -> Boolean,
                    ): Boolean {
                        release.await()
                        return store.save(episodeId, positionMs, durationMs, source, now, pinStartedAt, allowed)
                    }
                }
            var frozen = false
            val saver = PositionSaver(delayed, clock, PlayedRule())
            saver.load(1, 1000, PositionSource.STREAM, allowed = { !frozen })
            saver.startPositionApplied()
            val pending = async(start = CoroutineStart.UNDISPATCHED) { saver.onPause(10_000, null) }
            frozen = true
            release.complete(Unit)
            assertEquals(PositionSaveResult.Skipped, pending.await())
            assertTrue(store.positions.value.isEmpty())
            val capture = saver.capture(20_000, 30_000, PlaybackExit.END)!!.departure
            assertFalse(capture.writePosition)
            assertFalse(capture.markPlayed)
        }

    @Test fun cancellationPropagatesAndEmptySaverSkips() =
        runTest {
            assertEquals(PositionSaveResult.Skipped, saver.flush(1000, null))
            assertNull(saver.capture(1000, null))
            load()
            store.failure = CancellationException("cancel")
            assertFailsWith<CancellationException> { saver.onPause(2000, null) }
            saver.clear()
            assertNull(saver.capture(1000, null))
        }
}
