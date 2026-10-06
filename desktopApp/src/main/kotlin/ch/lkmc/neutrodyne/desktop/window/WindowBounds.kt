// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.window

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The primary screen's AWT facts a window placement needs; the shell reads them once per start. */
internal data class Screen(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val dpi: Int,
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
 * The first-start bounds (11 Window state): 1200 × 800 dp clamped to 90 % of the primary screen
 * and centred on it; the clamped size never falls below the PO-19 minimum unless the screen
 * itself is smaller. Pure, so the clamping rule is testable without a display.
 */
internal fun defaultWindowBounds(screen: Screen): WindowBounds {
    // AWT reports pixels; the window state takes dp, so the screen enters the dp world through
    // its DPI (96 = 100 %). The window's own density follows the OS scale factor from there.
    val screenDp = ScreenDp(screen)
    val clampedWidth = WindowBounds.DEFAULT_WIDTH.coerceAtMost(screenDp.width * WindowBounds.SCREEN_FRACTION)
    val clampedHeight = WindowBounds.DEFAULT_HEIGHT.coerceAtMost(screenDp.height * WindowBounds.SCREEN_FRACTION)

    // The PO-19 minimum wins over the 90 % clamp when the screen can hold it; a screen smaller
    // than the minimum gets the screen itself.
    val width = clampedWidth.coerceAtLeast(minOf(WindowBounds.MIN_WIDTH, screenDp.width))
    val height = clampedHeight.coerceAtLeast(minOf(WindowBounds.MIN_HEIGHT, screenDp.height))

    return WindowBounds(
        x = screenDp.x + (screenDp.width - width) / 2,
        y = screenDp.y + (screenDp.height - height) / 2,
        width = width,
        height = height,
    )
}

/** [Screen]'s rectangle converted to dp at its DPI ([REFERENCE_DPI] = 100 % scale). */
private data class ScreenDp(
    val x: Dp,
    val y: Dp,
    val width: Dp,
    val height: Dp,
) {
    constructor(screen: Screen) : this(
        x = screen.x.pxToDp(screen.dpi),
        y = screen.y.pxToDp(screen.dpi),
        width = screen.width.pxToDp(screen.dpi),
        height = screen.height.pxToDp(screen.dpi),
    )
}

private const val REFERENCE_DPI = 96

private fun Int.pxToDp(dpi: Int): Dp = (this * REFERENCE_DPI.toFloat() / dpi).dp
