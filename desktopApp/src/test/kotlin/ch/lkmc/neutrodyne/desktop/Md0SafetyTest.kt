// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop

import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.testing.FakePositionStore
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.playback.core.PlayedRule
import ch.lkmc.neutrodyne.playback.core.PositionSaver
import ch.lkmc.neutrodyne.playback.native.NativeSessionEventKind
import kotlinx.coroutines.test.runTest
import java.io.IOException
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Md0SafetyTest {
    @Test
    fun suspendAndRemovalPauseAndSaveWithoutWakeAutoplay() =
        runTest {
            for (event in listOf(NativeSessionEventKind.SUSPEND, NativeSessionEventKind.REMOVED)) {
                val store = FakePositionStore()
                val saver = saver(store)
                var playing = true
                val coordinator = Md0SafetyCoordinator({ playing = false }, { 4_100L }, saver)
                coordinator.accept(event)
                assertFalse(playing)
                assertEquals(4_100L, store.positions.value[1]?.positionMs)
                coordinator.accept(NativeSessionEventKind.RESUME)
                assertFalse(playing)
                assertEquals(1, store.calls.size)
            }
        }

    @Test
    fun changingOnlyTheDefaultDeviceKeepsPlaying() =
        runTest {
            val store = FakePositionStore()
            var playing = true
            Md0SafetyCoordinator({ playing = false }, { 4_100L }, saver(store))
                .accept(NativeSessionEventKind.DEFAULT_CHANGED)
            assertTrue(playing)
            assertTrue(store.calls.isEmpty())
        }

    @Test
    fun failedSaveIsReportedWhilePlaybackStaysPaused() =
        runTest {
            val store = FakePositionStore().apply { failure = IOException("full") }
            var playing = true
            val coordinator = Md0SafetyCoordinator({ playing = false }, { 4_100L }, saver(store))
            assertFailsWith<IllegalStateException> { coordinator.accept(NativeSessionEventKind.SUSPEND) }
            assertFalse(playing)
        }

    private fun saver(store: FakePositionStore): PositionSaver =
        PositionSaver(store, TestClock(nowMs = 1_000), PlayedRule()).apply {
            load(1, 1_000, PositionSource.DOWNLOAD)
            startPositionApplied()
        }
}
