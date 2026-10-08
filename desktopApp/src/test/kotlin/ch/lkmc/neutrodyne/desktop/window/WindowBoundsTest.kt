// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.window

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 11 Window state's first-start rule: 1200 × 800 dp clamped to 90 % of the primary screen and
 * centred, never below the PO-19 minimum unless the screen itself is smaller.
 */
class WindowBoundsTest {
    @Test
    fun `a large screen keeps the default centred`() {
        val bounds = defaultWindowBounds(Screen(x = 0, y = 0, width = 1920, height = 1080, dpi = 96))

        assertThat(bounds.width).isEqualTo(1200.dp)
        assertThat(bounds.height).isEqualTo(800.dp)
        assertThat(bounds.x).isEqualTo(360.dp) // (1920 - 1200) / 2
        assertThat(bounds.y).isEqualTo(140.dp) // (1080 - 800) / 2
    }

    @Test
    fun `a small screen clamps to 90 percent and recentres`() {
        val bounds = defaultWindowBounds(Screen(x = 0, y = 0, width = 1000, height = 700, dpi = 96))

        assertThat(bounds.width).isEqualTo(900.dp)
        assertThat(bounds.height).isEqualTo(630.dp)
        assertThat(bounds.x).isEqualTo(50.dp)
        assertThat(bounds.y).isEqualTo(35.dp)
    }

    @Test
    fun `a scaled display converts the screen through its dpi`() {
        // 1920 px at 200 % scale is a 960 dp wide screen: 90 % = 864 dp.
        val bounds = defaultWindowBounds(Screen(x = 0, y = 0, width = 1920, height = 1080, dpi = 192))

        assertThat(bounds.width).isEqualTo(864.dp)
        assertThat(bounds.height).isEqualTo(486.dp) // 90 % of the 540 dp screen height
        assertThat(bounds.x).isEqualTo(48.dp)
    }

    @Test
    fun `a multi-monitor offset is kept`() {
        val bounds = defaultWindowBounds(Screen(x = -1920, y = 200, width = 1920, height = 1080, dpi = 96))

        assertThat(bounds.x).isEqualTo(-1920.dp + 360.dp)
        assertThat(bounds.y).isEqualTo(200.dp + 140.dp)
    }

    @Test
    fun `a screen that can hold the minimum never gets a smaller default`() {
        // 90 % of 620 dp = 558 dp, below the 600 dp minimum the screen can hold: 600 wins.
        val bounds = defaultWindowBounds(Screen(x = 0, y = 0, width = 620, height = 560, dpi = 96))

        assertThat(bounds.width).isEqualTo(600.dp)
        assertThat(bounds.height).isEqualTo(504.dp) // 90 % of 560, above the 480 dp minimum
    }

    @Test
    fun `a medium screen takes the 90 percent clamp`() {
        val bounds = defaultWindowBounds(Screen(x = 0, y = 0, width = 800, height = 620, dpi = 96))

        assertThat(bounds.width).isEqualTo(720.dp)
        assertThat(bounds.height).isEqualTo(558.dp)
    }

    @Test
    fun `a screen smaller than the minimum wins`() {
        val bounds = defaultWindowBounds(Screen(x = 0, y = 0, width = 400, height = 300, dpi = 96))

        assertThat(bounds.width).isEqualTo(400.dp)
        assertThat(bounds.height).isEqualTo(300.dp)
        assertThat(bounds.x).isEqualTo(0.dp)
        assertThat(bounds.y).isEqualTo(0.dp)
    }
}
