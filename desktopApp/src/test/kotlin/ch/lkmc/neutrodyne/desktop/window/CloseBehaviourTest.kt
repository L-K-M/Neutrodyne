// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.window

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 11's close rule and tray visibility as the tested contract (11 Testing's `CloseBehaviourTest`
 * M0b row: idle close quits; busy and `KEEP_RUNNING` hide; the tray shows only while hidden).
 */
class CloseBehaviourTest {
    @Test
    fun `an idle close quits under the default behaviour`() {
        assertThat(closeRequestOutcome(CloseBehaviour.QUIT_WHEN_IDLE, busy = false))
            .isEqualTo(CloseOutcome.QUIT)
    }

    @Test
    fun `a busy close hides instead`() {
        assertThat(closeRequestOutcome(CloseBehaviour.QUIT_WHEN_IDLE, busy = true))
            .isEqualTo(CloseOutcome.HIDE_TO_TRAY)
    }

    @Test
    fun `keep running always hides`() {
        assertThat(closeRequestOutcome(CloseBehaviour.KEEP_RUNNING, busy = false))
            .isEqualTo(CloseOutcome.HIDE_TO_TRAY)
        assertThat(closeRequestOutcome(CloseBehaviour.KEEP_RUNNING, busy = true))
            .isEqualTo(CloseOutcome.HIDE_TO_TRAY)
    }

    @Test
    fun `the tray is shown only while the window is hidden`() {
        assertThat(shouldShowTray(windowHidden = true)).isTrue()
        assertThat(shouldShowTray(windowHidden = false)).isFalse()
    }
}
