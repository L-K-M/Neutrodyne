// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.window

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The window chords of 11 Keyboard shortcuts: Ctrl/Cmd+W closes and Ctrl/Cmd+Q quits, key-down
 * only. The fallback used to treat Q like W and hide the window while audio played.
 */
class WindowChordTest {
    @Test fun `w closes and q quits`() {
        assertThat(windowChord(Key.W, KeyEventType.KeyDown, commandHeld = true)).isEqualTo(WindowChord.CLOSE)
        assertThat(windowChord(Key.Q, KeyEventType.KeyDown, commandHeld = true)).isEqualTo(WindowChord.QUIT)
    }

    @Test fun `key up maps to nothing`() {
        assertThat(windowChord(Key.W, KeyEventType.KeyUp, commandHeld = true)).isNull()
        assertThat(windowChord(Key.Q, KeyEventType.KeyUp, commandHeld = true)).isNull()
    }

    @Test fun `plain keys and unrelated chords map to nothing`() {
        assertThat(windowChord(Key.W, KeyEventType.KeyDown, commandHeld = false)).isNull()
        assertThat(windowChord(Key.Q, KeyEventType.KeyDown, commandHeld = false)).isNull()
        assertThat(windowChord(Key.R, KeyEventType.KeyDown, commandHeld = true)).isNull()
        assertThat(windowChord(Key.W, KeyEventType.Unknown, commandHeld = true)).isNull()
    }
}
