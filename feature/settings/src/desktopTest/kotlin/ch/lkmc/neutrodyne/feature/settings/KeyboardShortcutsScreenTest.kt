// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.DesktopOs
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class KeyboardShortcutsScreenTest {
    private lateinit var previous: Locale

    @BeforeTest fun english() {
        previous = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest fun restore() {
        Locale.setDefault(previous)
    }

    /** MD4: the dialog's four design groups and the macOS modifier names. */
    @Test
    fun theDialogListsTheFourGroupsWithMacModifiers() =
        runDesktopComposeUiTest(width = 600, height = 1200) {
            setContent {
                CompositionLocalProvider(LocalAppNavigator provides RecordingAppNavigator()) {
                    NeutrodyneTheme(SystemUiState.DEFAULT) {
                        KeyboardShortcutsScreen(DesktopOs.MACOS)
                    }
                }
            }

            onNodeWithTag("shortcuts_group_playback").assertIsDisplayed()
            onNodeWithTag("shortcuts_group_navigation").assertIsDisplayed()
            onNodeWithTag("shortcuts_group_library").assertIsDisplayed()
            onNodeWithTag("shortcuts_group_window").assertIsDisplayed()
            onNodeWithText("Space").assertIsDisplayed()
            onNodeWithText("⌘,").assertIsDisplayed()
            onNodeWithText("⇧⌘→").assertExists()
            onNodeWithText("⌃Tab").assertExists()
        }

    /** Windows and Linux spell the modifiers out and join them with "+". */
    @Test
    fun otherSystemsSpellTheModifiersOut() =
        runDesktopComposeUiTest(width = 600, height = 1200) {
            setContent {
                CompositionLocalProvider(LocalAppNavigator provides RecordingAppNavigator()) {
                    NeutrodyneTheme(SystemUiState.DEFAULT) {
                        KeyboardShortcutsScreen(DesktopOs.WINDOWS)
                    }
                }
            }

            onNodeWithText("Ctrl+,").assertIsDisplayed()
            onNodeWithText("Ctrl+Shift+→").assertExists()
            onNodeWithText("Ctrl+Tab").assertExists()
        }
}
