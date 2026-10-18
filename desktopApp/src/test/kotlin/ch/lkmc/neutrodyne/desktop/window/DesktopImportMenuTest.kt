// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.window

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.navigation3.runtime.NavKey
import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.navigation.ImportFileKey
import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.awt.GraphicsEnvironment
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.concurrent.FutureTask
import javax.swing.SwingUtilities

class DesktopImportMenuTest {
    @Test fun fileMenuRegistersTheAcceleratorAndOpensTheSharedPicker() {
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
                assertThat(item.text).isEqualTo("Import OPML or backup…")
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
