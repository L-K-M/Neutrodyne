// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.window

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The `desktop.window_bounds` codec and restore rule (11 Window state). */
class WindowBoundsPreferenceTest {
    @Test fun `a stored rectangle round trips`() {
        val stored =
            StoredWindowBounds(screen = "screen0", x = 360, y = 140, width = 1200, height = 800, maximised = true)

        val decoded = WindowBoundsPreference.decode(WindowBoundsPreference.encode(stored))

        assertThat(decoded).isEqualTo(stored)
    }

    @Test fun `blank malformed and impossible values are rejected`() {
        assertThat(WindowBoundsPreference.decode("")).isNull()
        assertThat(WindowBoundsPreference.decode("not json")).isNull()
        assertThat(WindowBoundsPreference.decode("""{"screen":"","x":0,"y":0,"width":10,"height":10}""")).isNull()
        assertThat(WindowBoundsPreference.decode("""{"screen":"s","x":0,"y":0,"width":0,"height":10}""")).isNull()
    }

    @Test fun `a rectangle mostly on its stored screen is restored`() {
        val stored = StoredWindowBounds(screen = "primary", x = 360, y = 140, width = 1200, height = 800)

        val restored = WindowBoundsPreference.restore(stored, listOf(primaryScreen()))

        assertThat(restored).isNotNull()
        assertThat(restored!!.x).isEqualTo(360.dp)
        assertThat(restored.y).isEqualTo(140.dp)
        assertThat(restored.width).isEqualTo(1200.dp)
        assertThat(restored.height).isEqualTo(800.dp)
        assertThat(restored.maximised).isFalse()
    }

    @Test fun `exactly half the rectangle on the screen is enough`() {
        val stored = StoredWindowBounds(screen = "primary", x = -960, y = 30, width = 1920, height = 960)

        val restored = WindowBoundsPreference.restore(stored, listOf(primaryScreen()))

        assertThat(restored).isNotNull()
    }

    @Test fun `less than half the rectangle on the screen is refused`() {
        val stored = StoredWindowBounds(screen = "primary", x = -1000, y = 0, width = 1920, height = 960)

        val restored = WindowBoundsPreference.restore(stored, listOf(primaryScreen()))

        assertThat(restored).isNull()
    }

    @Test fun `another screen id is refused`() {
        val stored = StoredWindowBounds(screen = "other", x = 0, y = 0, width = 1200, height = 800)

        assertThat(WindowBoundsPreference.restore(stored, listOf(primaryScreen()))).isNull()
    }

    @Test fun `a screen that disappeared is refused`() {
        val stored = StoredWindowBounds(screen = "screen0", x = 0, y = 0, width = 1200, height = 800)

        assertThat(WindowBoundsPreference.restore(stored, emptyList())).isNull()
    }

    @Test fun `a secondary display keeps its negative origin`() {
        val secondary =
            Screen(
                id = "secondary",
                x = -1920,
                y = 200,
                width = 1920,
                height = 1080,
                usableX = -1920,
                usableY = 200,
                usableWidth = 1920,
                usableHeight = 960,
            )
        val stored =
            StoredWindowBounds(screen = "secondary", x = -1560, y = 340, width = 1200, height = 800, maximised = true)

        val restored = WindowBoundsPreference.restore(stored, listOf(secondary))

        assertThat(restored).isNotNull()
        assertThat(restored!!.x).isEqualTo((-1560).dp)
        assertThat(restored.y).isEqualTo(340.dp)
        assertThat(restored.maximised).isTrue()
    }

    private fun primaryScreen(): Screen =
        Screen(
            id = "primary",
            x = 0,
            y = 0,
            width = 1920,
            height = 1080,
            usableX = 0,
            usableY = 30,
            usableWidth = 1920,
            usableHeight = 960,
        )
}
