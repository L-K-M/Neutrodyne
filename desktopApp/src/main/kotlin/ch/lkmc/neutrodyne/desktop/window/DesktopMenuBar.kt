// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.window

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.navigation.ImportFileKey
import ch.lkmc.neutrodyne.desktop.resources.Res
import ch.lkmc.neutrodyne.desktop.resources.menu_file
import ch.lkmc.neutrodyne.desktop.resources.menu_import
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun FrameWindowScope.DesktopMenuBar(
    actions: DesktopMenuActions,
    enabled: Boolean = true,
) {
    MenuBar {
        Menu(stringResource(Res.string.menu_file)) {
            Item(stringResource(Res.string.menu_import), enabled = enabled, shortcut = importFileShortcut()) {
                actions.pushKey(ImportFileKey)
            }
        }
    }
}

internal fun importFileShortcut(os: AppDirs.DesktopOs = AppDirs.DesktopOs.current()): KeyShortcut =
    KeyShortcut(Key.O, ctrl = os != AppDirs.DesktopOs.MACOS, meta = os == AppDirs.DesktopOs.MACOS)
