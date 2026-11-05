// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.window

import ch.lkmc.neutrodyne.core.testing.testNowPlaying
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 11's Tray icon row through the pure menu/tooltip functions: the tooltip names the episode
 * truncated to 60 characters, the Play/Pause label follows the state, and the playback items
 * disable with nothing loaded (Next additionally needs a successor).
 */
class TrayMenuTest {
    @Test
    fun `the tooltip is the app name until something plays`() {
        assertThat(trayTooltip(null)).isEqualTo("Neutrodyne")
        assertThat(trayTooltip(testNowPlaying().copy(title = "Episode one")))
            .isEqualTo("Neutrodyne — Episode one")
    }

    @Test
    fun `the tooltip truncates at sixty characters`() {
        val tooltip = trayTooltip(testNowPlaying().copy(title = "T".repeat(100)))
        assertThat(tooltip).hasLength(60)
        assertThat(tooltip).startsWith("Neutrodyne — T")
        assertThat(tooltip).isEqualTo("Neutrodyne — " + "T".repeat(60 - "Neutrodyne — ".length))
    }

    @Test
    fun `the play pause label follows the state`() {
        assertThat(trayPlayPause(null)).isEqualTo(TrayPlayPause.PLAY)
        assertThat(trayPlayPause(testNowPlaying().copy(isPlaying = false))).isEqualTo(TrayPlayPause.PLAY)
        assertThat(trayPlayPause(testNowPlaying())).isEqualTo(TrayPlayPause.PAUSE)
    }

    @Test
    fun `playback items need a loaded item and next a successor`() {
        assertThat(trayPlayPauseEnabled(null)).isFalse()
        assertThat(trayPlayPauseEnabled(testNowPlaying())).isTrue()
        assertThat(trayNextEnabled(null)).isFalse()
        assertThat(trayNextEnabled(testNowPlaying().copy(hasNext = false))).isFalse()
        assertThat(trayNextEnabled(testNowPlaying())).isTrue()
    }
}
