// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.window

import ch.lkmc.neutrodyne.playback.api.NowPlaying

/**
 * 11's tray-menu and tooltip rules as pure functions; the Compose `Tray` call site stays a
 * thin render of them so every label and string is unit-tested without AWT.
 */

/** The "Neutrodyne — {episode title}" tooltip, hard-truncated to 60 characters (11 Tray icon). */
internal fun trayTooltip(nowPlaying: NowPlaying?): String {
    val title = nowPlaying?.title ?: return APP_NAME
    return "$APP_NAME — $title".take(TOOLTIP_MAX_CHARS)
}

/** The Play/Pause item's state (11 Tray icon: the label follows the playback state). */
internal enum class TrayPlayPause { PLAY, PAUSE }

internal fun trayPlayPause(nowPlaying: NowPlaying?): TrayPlayPause =
    if (nowPlaying?.isPlaying == true) TrayPlayPause.PAUSE else TrayPlayPause.PLAY

/** Play/Pause and Next are enabled only with a loaded item; Next needs a successor too. */
internal fun trayPlayPauseEnabled(nowPlaying: NowPlaying?): Boolean = nowPlaying != null

internal fun trayNextEnabled(nowPlaying: NowPlaying?): Boolean = nowPlaying?.hasNext == true

private const val APP_NAME = "Neutrodyne"
private const val TOOLTIP_MAX_CHARS = 60
