// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.root

import ch.lkmc.neutrodyne.core.testing.FakePlaybackController
import ch.lkmc.neutrodyne.core.testing.testNowPlaying
import ch.lkmc.neutrodyne.playback.api.PlayerPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackKeysTest {
    @Test
    fun transportKeysDispatchExactlyOnce() {
        val expected =
            mapOf(
                PlaybackKey.SKIP_BACK to FakePlaybackController.Action.BACK,
                PlaybackKey.SKIP_FORWARD to FakePlaybackController.Action.FORWARD,
                PlaybackKey.PREVIOUS_EPISODE to FakePlaybackController.Action.PREVIOUS,
                PlaybackKey.NEXT_EPISODE to FakePlaybackController.Action.NEXT,
            )
        for ((key, action) in expected) {
            val controller = FakePlaybackController()
            assertTrue(dispatchPlaybackKey(key, controller, testNowPlaying()))
            assertEquals<List<FakePlaybackController.Call>>(
                listOf(FakePlaybackController.Call.Transport(action)),
                controller.calls,
            )
        }
    }

    @Test
    fun bufferingTogglePausesAndPausedToggleResumes() {
        val controller = FakePlaybackController()
        val buffering = testNowPlaying().copy(phase = PlayerPhase.BUFFERING, isPlaying = false)
        assertTrue(dispatchPlaybackKey(PlaybackKey.TOGGLE, controller, buffering))
        assertTrue(dispatchPlaybackKey(PlaybackKey.TOGGLE, controller, buffering.copy(playWhenReady = false)))
        assertEquals<List<FakePlaybackController.Call>>(
            listOf(
                FakePlaybackController.Call.Transport(FakePlaybackController.Action.PAUSE),
                FakePlaybackController.Call.Transport(FakePlaybackController.Action.TOGGLE),
            ),
            controller.calls,
        )
    }

    @Test
    fun unsupportedKeysAreNotConsumed() {
        val controller = FakePlaybackController()
        for (key in listOf(
            PlaybackKey.PREVIOUS_CHAPTER,
            PlaybackKey.NEXT_CHAPTER,
            PlaybackKey.VOLUME_UP,
            PlaybackKey.VOLUME_DOWN,
            PlaybackKey.NEXT_FEED_PAGE,
            PlaybackKey.PREVIOUS_FEED_PAGE,
        )) {
            assertFalse(dispatchPlaybackKey(key, controller, null))
        }
        assertTrue(controller.calls.isEmpty())
    }
}
