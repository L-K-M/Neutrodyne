// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.window

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The primary screen's rectangle in AWT user-space units, which are the density-independent
 * units Compose's `WindowState` uses on the desktop. [id] identifies the display across
 * launches so a restored window can be matched to its screen.
 */
internal data class Screen(
    val id: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/** The window's initial rectangle in dp (11 Window and tray behaviour: "Window state" row). */
internal data class WindowBounds(
    val x: Dp,
    val y: Dp,
    val width: Dp,
    val height: Dp,
) {
    companion object {
        /** 11 Window sizing: the first-start default. */
        val DEFAULT_WIDTH = 1200.dp

        val DEFAULT_HEIGHT = 800.dp

        /** PO-19: the window's minimum. */
        val MIN_WIDTH = 600.dp

        val MIN_HEIGHT = 480.dp

        /** 11 Window state: the default is clamped to this share of the primary screen. */
        const val SCREEN_FRACTION = 0.9f
    }
}

/**
 * The PO-19 minimum as an AWT size at [density] (11 Window sizing: "Minimum 600 × 480 dp,
 * converted with the window's density"). Pure, so the conversion is testable without a display.
 */
internal fun minimumWindowDimension(density: Density): java.awt.Dimension =
    java.awt.Dimension(
        with(density) { WindowBounds.MIN_WIDTH.roundToPx() },
        with(density) { WindowBounds.MIN_HEIGHT.roundToPx() },
    )

/**
 * The first-start bounds (11 Window state): 1200 × 800 dp clamped to 90 % of the primary screen
 * and centred on it; the clamped size never falls below the PO-19 minimum unless the screen
 * itself is smaller. [screen] is already in user-space units, so no DPI guess is needed. Pure,
 * so the clamping rule is testable without a display.
 */
internal fun defaultWindowBounds(screen: Screen): WindowBounds {
    val screenWidth = screen.width.dp
    val screenHeight = screen.height.dp
    val clampedWidth = WindowBounds.DEFAULT_WIDTH.coerceAtMost(screenWidth * WindowBounds.SCREEN_FRACTION)
    val clampedHeight = WindowBounds.DEFAULT_HEIGHT.coerceAtMost(screenHeight * WindowBounds.SCREEN_FRACTION)

    // The PO-19 minimum wins over the 90 % clamp when the screen can hold it; a screen smaller
    // than the minimum gets the screen itself.
    val width = clampedWidth.coerceAtLeast(minOf(WindowBounds.MIN_WIDTH, screenWidth))
    val height = clampedHeight.coerceAtLeast(minOf(WindowBounds.MIN_HEIGHT, screenHeight))

    return WindowBounds(
        x = screen.x.dp + (screenWidth - width) / 2,
        y = screen.y.dp + (screenHeight - height) / 2,
        width = width,
        height = height,
    )
}
