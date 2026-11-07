// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.window

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.model.SleepTimerMode
import ch.lkmc.neutrodyne.core.navigation.AddPodcastKey
import ch.lkmc.neutrodyne.core.navigation.BackupKey
import ch.lkmc.neutrodyne.core.navigation.DiagnosticsKey
import ch.lkmc.neutrodyne.core.navigation.DiscoverKey
import ch.lkmc.neutrodyne.core.navigation.DownloadsKey
import ch.lkmc.neutrodyne.core.navigation.ExportKey
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.ImportFileKey
import ch.lkmc.neutrodyne.core.navigation.InstallHelpKey
import ch.lkmc.neutrodyne.core.navigation.KeyboardShortcutsKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.LicencesKey
import ch.lkmc.neutrodyne.core.navigation.SettingsHomeKey
import ch.lkmc.neutrodyne.core.navigation.SpeedKey
import ch.lkmc.neutrodyne.core.navigation.UpNextKey
import ch.lkmc.neutrodyne.core.ui.resources.nav_discover
import ch.lkmc.neutrodyne.core.ui.resources.nav_downloads
import ch.lkmc.neutrodyne.core.ui.resources.nav_feeds
import ch.lkmc.neutrodyne.core.ui.resources.nav_library
import ch.lkmc.neutrodyne.core.ui.resources.nav_up_next
import ch.lkmc.neutrodyne.desktop.resources.Res
import ch.lkmc.neutrodyne.desktop.resources.menu_add_podcast
import ch.lkmc.neutrodyne.desktop.resources.menu_back
import ch.lkmc.neutrodyne.desktop.resources.menu_back_up
import ch.lkmc.neutrodyne.desktop.resources.menu_bring_all_to_front
import ch.lkmc.neutrodyne.desktop.resources.menu_close_window
import ch.lkmc.neutrodyne.desktop.resources.menu_copy
import ch.lkmc.neutrodyne.desktop.resources.menu_cut
import ch.lkmc.neutrodyne.desktop.resources.menu_diagnostics
import ch.lkmc.neutrodyne.desktop.resources.menu_edit
import ch.lkmc.neutrodyne.desktop.resources.menu_export_opml
import ch.lkmc.neutrodyne.desktop.resources.menu_file
import ch.lkmc.neutrodyne.desktop.resources.menu_find
import ch.lkmc.neutrodyne.desktop.resources.menu_go
import ch.lkmc.neutrodyne.desktop.resources.menu_help
import ch.lkmc.neutrodyne.desktop.resources.menu_import
import ch.lkmc.neutrodyne.desktop.resources.menu_install_help
import ch.lkmc.neutrodyne.desktop.resources.menu_keyboard_shortcuts
import ch.lkmc.neutrodyne.desktop.resources.menu_licences
import ch.lkmc.neutrodyne.desktop.resources.menu_minimise
import ch.lkmc.neutrodyne.desktop.resources.menu_next_chapter
import ch.lkmc.neutrodyne.desktop.resources.menu_next_episode
import ch.lkmc.neutrodyne.desktop.resources.menu_paste
import ch.lkmc.neutrodyne.desktop.resources.menu_pause
import ch.lkmc.neutrodyne.desktop.resources.menu_play
import ch.lkmc.neutrodyne.desktop.resources.menu_playback
import ch.lkmc.neutrodyne.desktop.resources.menu_previous_chapter
import ch.lkmc.neutrodyne.desktop.resources.menu_previous_episode
import ch.lkmc.neutrodyne.desktop.resources.menu_refresh
import ch.lkmc.neutrodyne.desktop.resources.menu_report_problem
import ch.lkmc.neutrodyne.desktop.resources.menu_select_all
import ch.lkmc.neutrodyne.desktop.resources.menu_settings
import ch.lkmc.neutrodyne.desktop.resources.menu_skip_back
import ch.lkmc.neutrodyne.desktop.resources.menu_skip_forward
import ch.lkmc.neutrodyne.desktop.resources.menu_skip_silence
import ch.lkmc.neutrodyne.desktop.resources.menu_sleep_end
import ch.lkmc.neutrodyne.desktop.resources.menu_sleep_minutes
import ch.lkmc.neutrodyne.desktop.resources.menu_sleep_off
import ch.lkmc.neutrodyne.desktop.resources.menu_sleep_timer
import ch.lkmc.neutrodyne.desktop.resources.menu_speed
import ch.lkmc.neutrodyne.desktop.resources.menu_speed_custom
import ch.lkmc.neutrodyne.desktop.resources.menu_speed_preset
import ch.lkmc.neutrodyne.desktop.resources.menu_undo
import ch.lkmc.neutrodyne.desktop.resources.menu_volume_down
import ch.lkmc.neutrodyne.desktop.resources.menu_volume_up
import ch.lkmc.neutrodyne.desktop.resources.menu_window
import ch.lkmc.neutrodyne.desktop.resources.menu_zoom
import ch.lkmc.neutrodyne.desktop.resources.tray_quit
import org.jetbrains.compose.resources.stringResource
import java.awt.Frame
import ch.lkmc.neutrodyne.core.ui.resources.Res as UiRes

/**
 * The window's menu bar (11 Menus): the macOS screen menu bar and, on Windows and Linux, a menu
 * bar inside the window. Every item is wired to the same action as its root shortcut or the
 * window's close/quit rule; the items carry the accelerators of 11's table, except Space and
 * the arrow keys, which the root handles (the Playback menu shows them in its labels).
 *
 * [textFieldFocused] clears the accelerators while a text field is focused: AWT processes a
 * menu accelerator before Compose's key input, so the root cannot suppress it by consuming
 * the event (08 Keyboard and mouse). The root's bubbling handler still stands the chords down
 * once the accelerators are gone.
 */
@Composable
internal fun FrameWindowScope.DesktopMenuBar(
    actions: DesktopMenuActions,
    enabled: Boolean = true,
    textFieldFocused: Boolean = false,
    isPlaying: Boolean = false,
    skipSilenceEnabled: Boolean = false,
    speedPresets: List<Float> = emptyList(),
) {
    val os = AppDirs.DesktopOs.current()
    // A focused text field owns the keyboard, so the accelerators stand down.
    val accelerator: (Key) -> KeyShortcut? = { key ->
        if (textFieldFocused) {
            null
        } else {
            KeyShortcut(key, ctrl = os != AppDirs.DesktopOs.MACOS, meta = os == AppDirs.DesktopOs.MACOS)
        }
    }
    // Space and the arrow keys have no menu accelerators (a menu accelerator on Space would
    // steal it from text fields), so the Playback menu shows them in its labels (11 Menus).
    val modifier = if (os == AppDirs.DesktopOs.MACOS) "⌘" else "Ctrl"
    val withHint: (String, String) -> String = { label, hint -> "$label ($hint)" }

    MenuBar {
        Menu(stringResource(Res.string.menu_file)) {
            Item(
                stringResource(Res.string.menu_add_podcast),
                enabled = enabled,
                shortcut = accelerator(Key.N),
            ) {
                actions.pushKey(AddPodcastKey(null))
            }
            Item(
                stringResource(Res.string.menu_import),
                enabled = enabled,
                shortcut = if (textFieldFocused) null else importFileShortcut(os),
            ) {
                actions.pushKey(ImportFileKey)
            }
            Item(stringResource(Res.string.menu_export_opml), enabled = enabled) {
                actions.pushKey(ExportKey(null))
            }
            Item(stringResource(Res.string.menu_back_up), enabled = enabled) {
                actions.pushKey(BackupKey)
            }
            Item(
                stringResource(Res.string.menu_refresh),
                enabled = enabled,
                shortcut = accelerator(Key.R),
            ) {
                actions.refresh()
            }
            Separator()
            Item(
                stringResource(Res.string.menu_close_window),
                enabled = enabled,
                shortcut = accelerator(Key.W),
            ) {
                actions.closeWindow()
            }
            if (os != AppDirs.DesktopOs.MACOS) {
                Item(
                    stringResource(Res.string.tray_quit),
                    enabled = enabled,
                    shortcut = accelerator(Key.Q),
                ) {
                    actions.quit()
                }
            }
        }

        Menu(stringResource(Res.string.menu_edit)) {
            Item(
                stringResource(Res.string.menu_find),
                enabled = enabled,
                shortcut = accelerator(Key.F),
            ) {
                actions.find()
            }
            // 08's Context menus section assigns Undo, Cut, Copy, Paste and Select all to the
            // text field's own menu; the menu bar cannot reach the focused field's actions, so
            // they stay disabled rather than pretending to work.
            Separator()
            Item(stringResource(Res.string.menu_undo), enabled = false) {}
            Item(stringResource(Res.string.menu_cut), enabled = false) {}
            Item(stringResource(Res.string.menu_copy), enabled = false) {}
            Item(stringResource(Res.string.menu_paste), enabled = false) {}
            Item(stringResource(Res.string.menu_select_all), enabled = false) {}
        }

        Menu(stringResource(Res.string.menu_playback)) {
            Item(
                withHint(stringResource(if (isPlaying) Res.string.menu_pause else Res.string.menu_play), "Space"),
                enabled = enabled,
            ) {
                actions.playback.togglePlayPause()
            }
            Item(withHint(stringResource(Res.string.menu_skip_back), "←"), enabled = enabled) {
                actions.playback.skipBack()
            }
            Item(withHint(stringResource(Res.string.menu_skip_forward), "→"), enabled = enabled) {
                actions.playback.skipForward()
            }
            Separator()
            Item(withHint(stringResource(Res.string.menu_previous_chapter), "Shift+←"), enabled = enabled) {
                actions.playback.previousChapter()
            }
            Item(withHint(stringResource(Res.string.menu_next_chapter), "Shift+→"), enabled = enabled) {
                actions.playback.nextChapter()
            }
            Separator()
            Item(
                withHint(stringResource(Res.string.menu_previous_episode), "$modifier+Shift+←"),
                enabled = enabled,
            ) {
                actions.playback.previousEpisode()
            }
            Item(withHint(stringResource(Res.string.menu_next_episode), "$modifier+Shift+→"), enabled = enabled) {
                actions.playback.nextEpisode()
            }
            Separator()
            Menu(stringResource(Res.string.menu_speed)) {
                for (preset in speedPresets) {
                    Item(stringResource(Res.string.menu_speed_preset, preset.toString()), enabled = enabled) {
                        actions.setSpeed(preset)
                    }
                }
                Item(stringResource(Res.string.menu_speed_custom), enabled = enabled) {
                    actions.openSpeedSheet()
                }
            }
            CheckboxItem(
                stringResource(Res.string.menu_skip_silence),
                skipSilenceEnabled,
                enabled = enabled,
            ) { actions.setSkipSilence(it) }
            Menu(stringResource(Res.string.menu_sleep_timer)) {
                for (minutes in SLEEP_TIMER_MINUTES) {
                    Item(stringResource(Res.string.menu_sleep_minutes, minutes), enabled = enabled) {
                        actions.setSleepTimer(SleepTimerMode.Minutes(minutes))
                    }
                }
                Item(stringResource(Res.string.menu_sleep_end), enabled = enabled) {
                    actions.setSleepTimer(SleepTimerMode.EndOfEpisode)
                }
                Item(stringResource(Res.string.menu_sleep_off), enabled = enabled) {
                    actions.setSleepTimer(SleepTimerMode.Off)
                }
            }
            Item(stringResource(Res.string.menu_volume_up), enabled = enabled) {
                actions.playback.volumeUp()
            }
            Item(stringResource(Res.string.menu_volume_down), enabled = enabled) {
                actions.playback.volumeDown()
            }
        }

        Menu(stringResource(Res.string.menu_go)) {
            Item(stringResource(UiRes.string.nav_feeds), enabled = enabled, shortcut = accelerator(Key.One)) {
                actions.pushKey(FeedsKey)
            }
            Item(stringResource(UiRes.string.nav_library), enabled = enabled, shortcut = accelerator(Key.Two)) {
                actions.pushKey(LibraryKey)
            }
            Item(stringResource(UiRes.string.nav_up_next), enabled = enabled, shortcut = accelerator(Key.Three)) {
                actions.pushKey(UpNextKey)
            }
            Item(stringResource(UiRes.string.nav_downloads), enabled = enabled, shortcut = accelerator(Key.Four)) {
                actions.pushKey(DownloadsKey)
            }
            Item(stringResource(UiRes.string.nav_discover), enabled = enabled, shortcut = accelerator(Key.Five)) {
                actions.pushKey(DiscoverKey)
            }
            Separator()
            Item(stringResource(Res.string.menu_settings), enabled = enabled, shortcut = accelerator(Key.Comma)) {
                actions.pushKey(SettingsHomeKey)
            }
            Item(stringResource(Res.string.menu_back), enabled = enabled) {
                actions.back()
            }
        }

        if (os == AppDirs.DesktopOs.MACOS) {
            Menu(stringResource(Res.string.menu_window)) {
                Item(stringResource(Res.string.menu_minimise)) {
                    window.extendedState = window.extendedState or Frame.ICONIFIED
                }
                Item(stringResource(Res.string.menu_zoom)) {
                    window.extendedState =
                        if (window.extendedState and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH) {
                            window.extendedState and Frame.MAXIMIZED_BOTH.inv()
                        } else {
                            window.extendedState or Frame.MAXIMIZED_BOTH
                        }
                }
                Item(stringResource(Res.string.menu_bring_all_to_front)) {
                    window.toFront()
                }
            }
        }

        Menu(stringResource(Res.string.menu_help)) {
            Item(stringResource(Res.string.menu_install_help), enabled = enabled) {
                actions.pushKey(InstallHelpKey())
            }
            Item(stringResource(Res.string.menu_keyboard_shortcuts), enabled = enabled) {
                actions.pushKey(KeyboardShortcutsKey)
            }
            Item(stringResource(Res.string.menu_licences), enabled = enabled) {
                actions.pushKey(LicencesKey)
            }
            Item(stringResource(Res.string.menu_diagnostics), enabled = enabled) {
                actions.pushKey(DiagnosticsKey)
            }
            Item(stringResource(Res.string.menu_report_problem), enabled = enabled) {
                actions.reportProblem()
            }
        }
    }
}

/** 11 Menus: the Sleep timer submenu's durations. */
private val SLEEP_TIMER_MINUTES = listOf(15, 30, 45, 60)

/**
 * The Import accelerator (11 Keyboard shortcuts); kept as the named contract the menu test
 * asserts. New items use the [KeyShortcut] builder inline.
 */
internal fun importFileShortcut(os: AppDirs.DesktopOs = AppDirs.DesktopOs.current()): KeyShortcut =
    KeyShortcut(Key.O, ctrl = os != AppDirs.DesktopOs.MACOS, meta = os == AppDirs.DesktopOs.MACOS)
