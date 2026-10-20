// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.core

import ch.lkmc.neutrodyne.core.model.SleepTimerMode
import ch.lkmc.neutrodyne.core.model.SleepTimerState
import ch.lkmc.neutrodyne.core.testing.TestClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SleepTimerCoreTest {
    private val core = SleepTimerCore()
    private val clock = TestClock()
    private var lastTick = clock.elapsedRealtime()

    private fun tick(ms: Long): List<SleepTimerCore.Effect> {
        clock.elapsedMs += ms
        val delta = clock.elapsedRealtime() - lastTick
        lastTick = clock.elapsedRealtime()
        return core.tick(delta)
    }

    private fun start() {
        core.advancing(true)
        core.set(SleepTimerMode.Minutes(1), 1)
    }

    @Test fun onlyListeningTimeCountsAndWallClockIsIrrelevant() {
        core.set(SleepTimerMode.Minutes(1), 1)
        tick(20_000)
        assertEquals(60_000, assertIs<SleepTimerState.Running>(core.state).remainingMs)
        core.advancing(true)
        clock.nowMs -= 999_999
        tick(15_000)
        assertEquals(45_000, assertIs<SleepTimerState.Running>(core.state).remainingMs)
        core.advancing(false)
        tick(50_000)
        assertEquals(45_000, assertIs<SleepTimerState.Running>(core.state).remainingMs)
    }

    @Test fun twentyHalfSecondFadeStepsThenPauseRestoreAndOneEvent() {
        start()
        tick(50_000)
        for (step in 1..19) {
            val effect = tick(500).single()
            assertEquals(SleepTimerCore.Effect.Fade(1f - step.toFloat() / 20), effect)
        }
        assertEquals(
            listOf(
                SleepTimerCore.Effect.Fade(0f),
                SleepTimerCore.Effect.Pause,
                SleepTimerCore.Effect.Fade(1f),
                SleepTimerCore.Effect.Fired,
            ),
            tick(500),
        )
        assertEquals(SleepTimerState.Off, core.state)
        assertTrue(tick(50_000).isEmpty())
    }

    @Test fun bufferingHoldsFadeButExplicitPauseRestoresAndResumeRecomputes() {
        start()
        tick(55_000)
        core.advancing(false)
        assertTrue(tick(30_000).isEmpty())
        assertEquals(5_000, assertIs<SleepTimerState.Running>(core.state).remainingMs)
        assertEquals(listOf(SleepTimerCore.Effect.Fade(1f)), core.paused())
        assertEquals(listOf(SleepTimerCore.Effect.Fade(0.5f)), core.advancing(true))
    }

    @Test fun delayedTicksExpireWithoutUnderflowOrMultipleEvents() {
        start()
        assertEquals(1, tick(Long.MAX_VALUE).count { it == SleepTimerCore.Effect.Fired })
        assertEquals(SleepTimerState.Off, core.state)
    }

    @Test fun extensionRestoresFadeAndCapsRemainingTime() {
        start()
        tick(55_000)
        assertEquals(listOf(SleepTimerCore.Effect.Fade(1f)), core.extend(5))
        assertEquals(305_000, assertIs<SleepTimerState.Running>(core.state).remainingMs)
        core.extend(240)
        assertEquals(14_400_000, assertIs<SleepTimerState.Running>(core.state).remainingMs)
        core.set(SleepTimerMode.Off, null)
        assertTrue(core.extend(5).isEmpty())
    }

    @Test fun invalidInputDoesNotMutateTimer() {
        start()
        val before = core.state
        for (minutes in listOf(Int.MIN_VALUE, -1, 0, 241, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { core.set(SleepTimerMode.Minutes(minutes), 1) }
            assertFailsWith<IllegalArgumentException> { core.extend(minutes) }
            assertEquals(before, core.state)
        }
    }

    @Test fun replacementAndOffRestoreVolumeAndDisarm() {
        start()
        tick(55_000)
        val effects = core.set(SleepTimerMode.EndOfEpisode, 2)
        assertEquals(SleepTimerCore.Effect.Fade(1f), effects.first())
        assertEquals(SleepTimerCore.Effect.ArmEndOfItem(true), effects.last())
        assertEquals(SleepTimerState.EndOfEpisode(2), core.state)
        assertEquals(
            listOf(SleepTimerCore.Effect.Fade(1f), SleepTimerCore.Effect.ArmEndOfItem(false)),
            core.set(SleepTimerMode.Off, null),
        )
    }

    @Test fun endOfEpisodeFollowsManualSwitchAndFiresOnce() {
        core.set(SleepTimerMode.EndOfEpisode, 1)
        core.currentChanged(2)
        assertEquals(SleepTimerState.EndOfEpisode(2), core.state)
        val effects = core.endOfItem()
        assertEquals(SleepTimerCore.Effect.Pause, effects.first())
        assertEquals(SleepTimerCore.Effect.Fired, effects.last())
        assertTrue(core.endOfItem().isEmpty())
        assertEquals(SleepTimerState.Off, core.state)
    }

    @Test fun missingCurrentDisarmsEndOfEpisodeWithoutFiring() {
        core.set(SleepTimerMode.EndOfEpisode, null)
        assertEquals(SleepTimerState.Off, core.state)
        core.set(SleepTimerMode.EndOfEpisode, 1)
        assertTrue(core.currentChanged(null).none { it == SleepTimerCore.Effect.Fired })
        assertEquals(SleepTimerState.Off, core.state)
    }
}
