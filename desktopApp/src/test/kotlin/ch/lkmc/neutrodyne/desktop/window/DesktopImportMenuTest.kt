// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.window

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.navigation3.runtime.NavKey
import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.navigation.AddPodcastKey
import ch.lkmc.neutrodyne.core.navigation.ImportFileKey
import ch.lkmc.neutrodyne.desktop.resources.Res
import ch.lkmc.neutrodyne.desktop.resources.desktop_cannot_open_file
import ch.lkmc.neutrodyne.desktop.resources.drop_to_add
import ch.lkmc.neutrodyne.desktop.resources.drop_to_import
import ch.lkmc.neutrodyne.desktop.resources.menu_add_podcast
import ch.lkmc.neutrodyne.desktop.resources.menu_back
import ch.lkmc.neutrodyne.desktop.resources.menu_back_up
import ch.lkmc.neutrodyne.desktop.resources.menu_close_window
import ch.lkmc.neutrodyne.desktop.resources.menu_edit
import ch.lkmc.neutrodyne.desktop.resources.menu_export_opml
import ch.lkmc.neutrodyne.desktop.resources.menu_file
import ch.lkmc.neutrodyne.desktop.resources.menu_find
import ch.lkmc.neutrodyne.desktop.resources.menu_go
import ch.lkmc.neutrodyne.desktop.resources.menu_help
import ch.lkmc.neutrodyne.desktop.resources.menu_import
import ch.lkmc.neutrodyne.desktop.resources.menu_playback
import ch.lkmc.neutrodyne.desktop.resources.menu_refresh
import ch.lkmc.neutrodyne.desktop.resources.menu_settings
import ch.lkmc.neutrodyne.desktop.resources.no_tray_hint_body
import ch.lkmc.neutrodyne.desktop.resources.no_tray_hint_title
import ch.lkmc.neutrodyne.desktop.resources.tray_next
import ch.lkmc.neutrodyne.desktop.resources.tray_pause
import ch.lkmc.neutrodyne.desktop.resources.tray_play
import ch.lkmc.neutrodyne.desktop.resources.tray_quit
import ch.lkmc.neutrodyne.desktop.resources.tray_show
import ch.lkmc.neutrodyne.desktop.resources.window_title
import ch.lkmc.neutrodyne.desktop.resources.window_title_playing
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.getString
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.awt.GraphicsEnvironment
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.Locale
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.SwingUtilities

/**
 * The window menu bar (11 Menus): the English and German item labels, the File menu's item
 * order and accelerators, the rule that a focused text field clears them, and the platform
 * modifier of the Import accelerator. The menu structure needs a display, so those tests skip
 * on headless runners.
 */
class DesktopImportMenuTest {
    @Test fun englishShellStringsAreLocalized() =
        runTest {
            withLocale(Locale.ENGLISH) {
                assertThat(getString(Res.string.menu_file)).isEqualTo("File")
                assertThat(getString(Res.string.menu_add_podcast)).isEqualTo("Add podcast…")
                assertThat(getString(Res.string.menu_import)).isEqualTo("Import OPML or backup…")
                assertThat(getString(Res.string.menu_export_opml)).isEqualTo("Export OPML…")
                assertThat(getString(Res.string.menu_back_up)).isEqualTo("Back up library…")
                assertThat(getString(Res.string.menu_refresh)).isEqualTo("Refresh")
                assertThat(getString(Res.string.menu_close_window)).isEqualTo("Close window")
                assertThat(getString(Res.string.menu_edit)).isEqualTo("Edit")
                assertThat(getString(Res.string.menu_find)).isEqualTo("Find")
                assertThat(getString(Res.string.menu_playback)).isEqualTo("Playback")
                assertThat(getString(Res.string.menu_go)).isEqualTo("Go")
                assertThat(getString(Res.string.menu_help)).isEqualTo("Help")
                assertThat(getString(Res.string.menu_settings)).isEqualTo("Settings")
                assertThat(getString(Res.string.menu_back)).isEqualTo("Back")
                assertThat(getString(Res.string.drop_to_import)).isEqualTo("Drop to import")
                assertThat(getString(Res.string.drop_to_add)).isEqualTo("Drop to add podcast")
                assertThat(getString(Res.string.desktop_cannot_open_file)).isEqualTo("Neutrodyne can't open this file")
                assertThat(getString(Res.string.window_title)).isEqualTo("Neutrodyne")
                assertThat(getString(Res.string.window_title_playing, "Episode")).isEqualTo("Episode — Neutrodyne")
                assertThat(getString(Res.string.tray_show)).isEqualTo("Show Neutrodyne")
                assertThat(getString(Res.string.tray_play)).isEqualTo("Play")
                assertThat(getString(Res.string.tray_pause)).isEqualTo("Pause")
                assertThat(getString(Res.string.tray_next)).isEqualTo("Next")
                assertThat(getString(Res.string.tray_quit)).isEqualTo("Quit")
                assertThat(getString(Res.string.no_tray_hint_title)).isEqualTo("No tray icon on this desktop")
                assertThat(getString(Res.string.no_tray_hint_body))
                    .isEqualTo(
                        "Closing the window minimises Neutrodyne to the taskbar instead of hiding it to a tray icon.",
                    )
            }
        }

    @Test fun germanShellStringsAreLocalized() =
        runTest {
            withLocale(Locale.GERMANY) {
                assertThat(getString(Res.string.menu_file)).isEqualTo("Datei")
                assertThat(getString(Res.string.menu_add_podcast)).isEqualTo("Podcast hinzufügen…")
                assertThat(getString(Res.string.menu_import)).isEqualTo("OPML oder Sicherung importieren…")
                assertThat(getString(Res.string.menu_export_opml)).isEqualTo("OPML exportieren…")
                assertThat(getString(Res.string.menu_back_up)).isEqualTo("Bibliothek sichern…")
                assertThat(getString(Res.string.menu_refresh)).isEqualTo("Aktualisieren")
                assertThat(getString(Res.string.menu_close_window)).isEqualTo("Fenster schließen")
                assertThat(getString(Res.string.menu_edit)).isEqualTo("Bearbeiten")
                assertThat(getString(Res.string.menu_find)).isEqualTo("Suchen")
                assertThat(getString(Res.string.menu_playback)).isEqualTo("Wiedergabe")
                assertThat(getString(Res.string.menu_go)).isEqualTo("Gehe")
                assertThat(getString(Res.string.menu_help)).isEqualTo("Hilfe")
                assertThat(getString(Res.string.menu_settings)).isEqualTo("Einstellungen")
                assertThat(getString(Res.string.menu_back)).isEqualTo("Zurück")
                assertThat(getString(Res.string.drop_to_import)).isEqualTo("Zum Importieren ablegen")
                assertThat(getString(Res.string.drop_to_add)).isEqualTo("Zum Hinzufügen eines Podcasts ablegen")
                assertThat(
                    getString(Res.string.desktop_cannot_open_file),
                ).isEqualTo("Neutrodyne kann diese Datei nicht öffnen")
                assertThat(getString(Res.string.window_title)).isEqualTo("Neutrodyne")
                assertThat(getString(Res.string.window_title_playing, "Episode")).isEqualTo("Episode — Neutrodyne")
                assertThat(getString(Res.string.tray_show)).isEqualTo("Neutrodyne anzeigen")
                assertThat(getString(Res.string.tray_play)).isEqualTo("Wiedergabe")
                assertThat(getString(Res.string.tray_pause)).isEqualTo("Pause")
                assertThat(getString(Res.string.tray_next)).isEqualTo("Nächste Episode")
                assertThat(getString(Res.string.tray_quit)).isEqualTo("Beenden")
                assertThat(getString(Res.string.no_tray_hint_title)).isEqualTo("Kein Tray-Symbol auf diesem System")
                assertThat(getString(Res.string.no_tray_hint_body))
                    .isEqualTo("Beim Schließen wird Neutrodyne stattdessen in der Taskleiste minimiert.")
            }
        }

    @Test fun fileMenuCarriesTheAcceleratorsAndPushesItsKeys() =
        withLocale(Locale.ENGLISH) {
            assertFileMenu(
                menus = menuTitles(Locale.ENGLISH),
                addPodcast = "Add podcast…",
                import = "Import OPML or backup…",
                fileItems = fileItemLabels(Locale.ENGLISH),
            )
        }

    @Test fun germanFileMenuCarriesTheAcceleratorsAndPushesItsKeys() =
        withLocale(Locale.GERMANY) {
            assertFileMenu(
                menus = menuTitles(Locale.GERMANY),
                addPodcast = "Podcast hinzufügen…",
                import = "OPML oder Sicherung importieren…",
                fileItems = fileItemLabels(Locale.GERMANY),
            )
        }

    private fun menuTitles(locale: Locale): List<String> {
        val file = if (locale == Locale.GERMANY) "Datei" else "File"
        val edit = if (locale == Locale.GERMANY) "Bearbeiten" else "Edit"
        val playback = if (locale == Locale.GERMANY) "Wiedergabe" else "Playback"
        val go = if (locale == Locale.GERMANY) "Gehe" else "Go"
        val help = if (locale == Locale.GERMANY) "Hilfe" else "Help"
        return if (AppDirs.DesktopOs.current() == AppDirs.DesktopOs.MACOS) {
            listOf(file, edit, playback, go, if (locale == Locale.GERMANY) "Fenster" else "Window", help)
        } else {
            listOf(file, edit, playback, go, help)
        }
    }

    private fun fileItemLabels(locale: Locale): List<String> {
        val german = locale == Locale.GERMANY
        val items =
            listOf(
                if (german) "Podcast hinzufügen…" else "Add podcast…",
                if (german) "OPML oder Sicherung importieren…" else "Import OPML or backup…",
                if (german) "OPML exportieren…" else "Export OPML…",
                if (german) "Bibliothek sichern…" else "Back up library…",
                if (german) "Aktualisieren" else "Refresh",
                if (german) "Fenster schließen" else "Close window",
            )
        // macOS keeps Quit in the application menu (11 Menus).
        return if (AppDirs.DesktopOs.current() == AppDirs.DesktopOs.MACOS) {
            items
        } else {
            items + if (german) "Beenden" else "Quit"
        }
    }

    @Test fun aFocusedTextFieldClearsTheMenuAccelerators() =
        withLocale(Locale.ENGLISH) {
            assertAcceleratorsCleared()
        }

    private inline fun <T> withLocale(
        locale: Locale,
        block: () -> T,
    ): T {
        val previousLocale = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            return block()
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    private fun assertFileMenu(
        menus: List<String>,
        addPodcast: String,
        import: String,
        fileItems: List<String>,
    ) {
        // AWT's native menu needs a display; the shortcut contract also runs headless.
        assumeFalse(GraphicsEnvironment.isHeadless())
        val pushed = mutableListOf<NavKey>()
        val actions = DesktopMenuActions().apply { attachPush { pushed += it } }
        val window = menuWindow(actions, textFieldFocused = false)
        try {
            waitForMenu(window.jMenuBar)
            onEdt {
                val bar = window.jMenuBar
                assertThat(bar.menuCount).isEqualTo(menus.size)
                assertThat(menuTitles(bar)).containsExactlyElementsIn(menus).inOrder()
                val file = bar.getMenu(0)
                assertThat(file.text).isEqualTo(menus.first())
                assertThat(itemTexts(file)).containsExactlyElementsIn(fileItems).inOrder()
                val add = file.getItem(0)
                val modifier =
                    if (AppDirs.DesktopOs.current() == AppDirs.DesktopOs.MACOS) {
                        InputEvent.META_DOWN_MASK
                    } else {
                        InputEvent.CTRL_DOWN_MASK
                    }
                assertThat(add.accelerator.keyCode).isEqualTo(KeyEvent.VK_N)
                assertThat(add.accelerator.modifiers and modifier).isNotEqualTo(0)
                val importItem = file.getItem(1)
                assertThat(importItem.accelerator.keyCode).isEqualTo(KeyEvent.VK_O)
                add.doClick()
                importItem.doClick()
            }
            onEdt {
                assertThat(pushed).containsExactly(AddPodcastKey(null), ImportFileKey).inOrder()
            }
        } finally {
            onEdt { window.dispose() }
        }
    }

    private fun assertAcceleratorsCleared() {
        assumeFalse(GraphicsEnvironment.isHeadless())
        val actions = DesktopMenuActions()
        val window = menuWindow(actions, textFieldFocused = true)
        try {
            waitForMenu(window.jMenuBar)
            onEdt {
                val file = window.jMenuBar.getMenu(0)
                assertThat(file.getItem(0).accelerator).isNull()
                assertThat(file.getItem(1).accelerator).isNull()
                assertThat(file.getItem(4).accelerator).isNull()
            }
        } finally {
            onEdt { window.dispose() }
        }
    }

    private fun menuWindow(
        actions: DesktopMenuActions,
        textFieldFocused: Boolean,
    ): ComposeWindow =
        onEdt {
            ComposeWindow().apply {
                setSize(600, 480)
                setContent { DesktopMenuBar(actions, textFieldFocused = textFieldFocused) }
                isVisible = true
            }
        }

    private fun waitForMenu(bar: JMenuBar?) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (onEdt {
                bar
                    ?.getMenu(0)
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItem(0)
                    ?.text
                    .isNullOrEmpty()
            } &&
            System.nanoTime() < deadline
        ) {
            Thread.sleep(10)
        }
    }

    private fun menuTitles(bar: JMenuBar): List<String> = (0 until bar.menuCount).map { bar.getMenu(it).text }

    private fun itemTexts(menu: JMenu): List<String> = (0 until menu.itemCount).mapNotNull { menu.getItem(it)?.text }

    private fun <T> onEdt(block: () -> T): T {
        val task = FutureTask(block)
        SwingUtilities.invokeAndWait(task)
        return task.get()
    }

    @Test fun importAcceleratorUsesThePlatformModifier() {
        assertThat(importFileShortcut(AppDirs.DesktopOs.MACOS)).isEqualTo(KeyShortcut(Key.O, meta = true))
        for (os in listOf(AppDirs.DesktopOs.WINDOWS, AppDirs.DesktopOs.LINUX)) {
            assertThat(importFileShortcut(os)).isEqualTo(KeyShortcut(Key.O, ctrl = true))
        }
    }
}
