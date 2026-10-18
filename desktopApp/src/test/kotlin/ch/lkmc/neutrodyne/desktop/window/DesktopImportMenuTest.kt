// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.window

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.navigation3.runtime.NavKey
import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.navigation.ImportFileKey
import ch.lkmc.neutrodyne.desktop.resources.Res
import ch.lkmc.neutrodyne.desktop.resources.desktop_cannot_open_file
import ch.lkmc.neutrodyne.desktop.resources.drop_to_add
import ch.lkmc.neutrodyne.desktop.resources.drop_to_import
import ch.lkmc.neutrodyne.desktop.resources.menu_file
import ch.lkmc.neutrodyne.desktop.resources.menu_import
import ch.lkmc.neutrodyne.desktop.resources.tray_quit
import ch.lkmc.neutrodyne.desktop.resources.tray_show
import ch.lkmc.neutrodyne.desktop.resources.tray_tooltip
import ch.lkmc.neutrodyne.desktop.resources.window_title
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
import javax.swing.SwingUtilities

class DesktopImportMenuTest {
    @Test fun englishShellStringsAreLocalized() =
        runTest {
            withLocale(Locale.ENGLISH) {
                assertThat(getString(Res.string.menu_file)).isEqualTo("File")
                assertThat(getString(Res.string.menu_import)).isEqualTo("Import OPML or backup…")
                assertThat(getString(Res.string.drop_to_import)).isEqualTo("Drop to import")
                assertThat(getString(Res.string.drop_to_add)).isEqualTo("Drop to add podcast")
                assertThat(getString(Res.string.desktop_cannot_open_file)).isEqualTo("Neutrodyne can't open this file")
                assertThat(getString(Res.string.window_title)).isEqualTo("Neutrodyne")
                assertThat(getString(Res.string.tray_tooltip)).isEqualTo("Neutrodyne")
                assertThat(getString(Res.string.tray_show)).isEqualTo("Show Neutrodyne")
                assertThat(getString(Res.string.tray_quit)).isEqualTo("Quit")
            }
        }

    @Test fun germanShellStringsAreLocalized() =
        runTest {
            withLocale(Locale.GERMANY) {
                assertThat(getString(Res.string.menu_file)).isEqualTo("Datei")
                assertThat(getString(Res.string.menu_import)).isEqualTo("OPML oder Sicherung importieren…")
                assertThat(getString(Res.string.drop_to_import)).isEqualTo("Zum Importieren ablegen")
                assertThat(getString(Res.string.drop_to_add)).isEqualTo("Zum Hinzufügen eines Podcasts ablegen")
                assertThat(
                    getString(Res.string.desktop_cannot_open_file),
                ).isEqualTo("Neutrodyne kann diese Datei nicht öffnen")
                assertThat(getString(Res.string.window_title)).isEqualTo("Neutrodyne")
                assertThat(getString(Res.string.tray_tooltip)).isEqualTo("Neutrodyne")
                assertThat(getString(Res.string.tray_show)).isEqualTo("Neutrodyne anzeigen")
                assertThat(getString(Res.string.tray_quit)).isEqualTo("Beenden")
            }
        }

    @Test fun englishFileMenuRegistersTheAcceleratorAndOpensTheSharedPicker() =
        withLocale(Locale.ENGLISH) {
            assertFileMenu("File", "Import OPML or backup…")
        }

    @Test fun germanFileMenuRegistersTheAcceleratorAndOpensTheSharedPicker() =
        withLocale(Locale.GERMANY) {
            assertFileMenu("Datei", "OPML oder Sicherung importieren…")
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
        menu: String,
        label: String,
    ) {
        // AWT's native menu needs a display; the shortcut contract also runs headless.
        assumeFalse(GraphicsEnvironment.isHeadless() || AppDirs.DesktopOs.current() == AppDirs.DesktopOs.MACOS)
        val pushed = mutableListOf<NavKey>()
        val actions = DesktopMenuActions().apply { attachPush { pushed += it } }
        val window =
            onEdt {
                ComposeWindow().apply {
                    setSize(600, 480)
                    setContent { DesktopMenuBar(actions) }
                    isVisible = true
                }
            }
        try {
            val deadline =
                System.nanoTime() +
                    java.util.concurrent.TimeUnit.SECONDS
                        .toNanos(10)
            while (onEdt {
                    window.jMenuBar
                        ?.getMenu(0)
                        ?.takeIf { it.itemCount > 0 }
                        ?.getItem(0)
                        ?.text
                        .isNullOrEmpty()
                } && System.nanoTime() < deadline
            ) {
                Thread.sleep(10)
            }
            onEdt {
                val item = window.jMenuBar.getMenu(0).getItem(0)
                assertThat(window.jMenuBar.getMenu(0).text).isEqualTo(menu)
                assertThat(item.text).isEqualTo(label)
                assertThat(item.accelerator.keyCode).isEqualTo(KeyEvent.VK_O)
                assertThat(item.accelerator.modifiers and InputEvent.CTRL_DOWN_MASK).isNotEqualTo(0)
                item.doClick()
            }
            onEdt { assertThat(pushed).containsExactly(ImportFileKey) }
        } finally {
            onEdt { window.dispose() }
        }
    }

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
