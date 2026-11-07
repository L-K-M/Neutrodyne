// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.root

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.navigation.AddPodcastKey
import ch.lkmc.neutrodyne.core.navigation.DiscoverKey
import ch.lkmc.neutrodyne.core.navigation.DownloadsKey
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.ImportFileKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import ch.lkmc.neutrodyne.core.navigation.SettingsHomeKey
import ch.lkmc.neutrodyne.core.navigation.UpNextKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Test

/**
 * MD4's desktop shortcuts (08 Keyboard and mouse, 11 Keyboard shortcuts): Space, the arrow
 * chords, Ctrl/Cmd+N, Ctrl/Cmd+,, Ctrl/Cmd+1-5 and Ctrl/Cmd+O, and the rule that a focused text
 * field stands them all down.
 *
 * The destinations are the radio home's five tabs (Feeds, Library, Up next, Downloads,
 * Discover), which replaced the bottom bar 08's table was written against; the mapping is
 * recorded in 11 Keyboard shortcuts.
 */
@OptIn(ExperimentalTestApi::class)
class RootShortcutTest {
    private val played = mutableListOf<PlaybackKey>()
    private val focusChanges = mutableListOf<Boolean>()

    @Test
    fun spaceTogglesPlaybackWithoutATextField() =
        runComposeUiTest {
            setContent { TestRoot() }
            focusTarget()

            press(Key.Spacebar)

            assertThat(played).containsExactly(PlaybackKey.TOGGLE)
        }

    @Test
    fun leftAndRightSkipWithoutATextField() =
        runComposeUiTest {
            setContent { TestRoot() }
            focusTarget()

            press(Key.DirectionLeft)
            press(Key.DirectionRight)

            assertThat(played).containsExactly(PlaybackKey.SKIP_BACK, PlaybackKey.SKIP_FORWARD)
        }

    @Test
    fun ctrlArrowsDispatchVolume() =
        runComposeUiTest {
            setContent { TestRoot() }
            focusTarget()

            pressCtrl(Key.DirectionUp)
            pressCtrl(Key.DirectionDown)

            assertThat(played).containsExactly(PlaybackKey.VOLUME_UP, PlaybackKey.VOLUME_DOWN).inOrder()
        }

    @Test
    fun ctrlNOpensTheAddPodcastSheet() =
        runComposeUiTest {
            setContent { TestRoot() }
            focusTarget()

            pressCtrl(Key.N)

            onNodeWithText("add-podcast-sheet").assertExists()
        }

    @Test
    fun ctrlCommaOpensSettings() =
        runComposeUiTest {
            setContent { TestRoot() }
            focusTarget()

            pressCtrl(Key.Comma)

            onNodeWithText("settings-home").assertExists()
        }

    @Test
    fun ctrlODoesNotOpenTheImportPickerWhileATextFieldIsFocused() =
        runComposeUiTest {
            setContent { TestRoot() }

            focusField()
            pressCtrl(Key.O)

            onNodeWithText("import-file").assertDoesNotExist()
        }

    @Test
    fun ctrlDigitsSelectTheFiveDestinations() =
        runComposeUiTest {
            setContent { TestRoot() }

            for (
            (key, content) in
            listOf(
                Key.One to "feeds-content",
                Key.Two to "library-content",
                Key.Three to "up-next-content",
                Key.Four to "downloads-content",
                Key.Five to "discover-content",
            )
            ) {
                focusTarget()
                pressCtrl(key)
                onNodeWithText(content).assertExists()
            }
        }

    @Test
    fun aFocusedTextFieldStandsDownSpace() =
        runComposeUiTest {
            setContent { TestRoot() }

            focusField()
            press(Key.Spacebar)

            assertThat(played).isEmpty()
        }

    @Test
    fun aFocusedTextFieldStandsDownTheArrowShortcuts() =
        runComposeUiTest {
            setContent { TestRoot() }

            focusField()
            press(Key.DirectionLeft)
            press(Key.DirectionRight)

            assertThat(played).isEmpty()
        }

    @Test
    fun aFocusedTextFieldStandsDownCtrlN() =
        runComposeUiTest {
            setContent { TestRoot() }

            focusField()
            pressCtrl(Key.N)

            onNodeWithText("add-podcast-sheet").assertDoesNotExist()
        }

    @Test
    fun aFocusedTextFieldStandsDownCtrlComma() =
        runComposeUiTest {
            setContent { TestRoot() }

            focusField()
            pressCtrl(Key.Comma)

            onNodeWithText("settings-home").assertDoesNotExist()
        }

    @Test
    fun textFieldFocusReportsEveryTransition() =
        runComposeUiTest {
            setContent { TestRoot() }

            focusField()
            focusTarget()

            assertThat(focusChanges).containsExactly(true, false).inOrder()
        }

    private fun ComposeUiTest.focusTarget() {
        // A plain focus target (no click handler) so Space reaches the root; the scene's own
        // target is re-focused before every chord because switching tabs disposes the old one.
        onNodeWithTag("focus-target").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        waitForIdle()
    }

    private fun ComposeUiTest.focusField() {
        onNodeWithTag("field").performClick()
        onNodeWithTag("field").assertIsFocused()
        waitForIdle()
    }

    private fun ComposeUiTest.press(key: Key) {
        onNodeWithTag("root-box").performKeyInput {
            keyDown(key)
            keyUp(key)
        }
        waitForIdle()
    }

    private fun ComposeUiTest.pressCtrl(key: Key) {
        onNodeWithTag("root-box").performKeyInput {
            withKeyDown(Key.CtrlLeft) {
                keyDown(key)
                keyUp(key)
            }
        }
        waitForIdle()
    }

    @Composable
    private fun TestRoot() {
        NeutrodyneRoot(
            state = RootUiState.READY,
            actions =
                RootActions(
                    retryStartup = {},
                    dismissNotice = {},
                    continueHere = {},
                    dismissRemoteSession = {},
                    playbackKey = { key ->
                        played += key
                        true
                    },
                    onTextFieldFocusChange = { focusChanges += it },
                ),
            slots = RootSlots(player = {}, userMessages = emptyFlow()),
            installers = shortcutInstallers,
            platform = BuildInfo.Platform.DESKTOP,
            modifier = Modifier.testTag("root-box"),
        )
    }

    /** A focus target that consumes no keys; every destination carries one. */
    @Composable
    private fun ColumnScope.FocusTarget() {
        Text(
            "focus-target",
            Modifier
                .padding(4.dp)
                .testTag("focus-target")
                .focusable(),
        )
    }

    private val shortcutInstallers: Set<EntryProviderInstaller> =
        setOf(
            {
                entry<FeedsKey>(metadata = NdSceneMetadata.paneList()) {
                    Column(Modifier.fillMaxSize()) {
                        Text("feeds-content")
                        FocusTarget()
                        var text by remember { mutableStateOf("") }
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier.testTag("field").ndTextFieldFocus(),
                        )
                    }
                }
                entry<LibraryKey>(metadata = NdSceneMetadata.paneList()) {
                    Column(Modifier.fillMaxSize()) {
                        Text("library-content")
                        FocusTarget()
                    }
                }
                entry<UpNextKey>(metadata = NdSceneMetadata.paneList()) {
                    Column(Modifier.fillMaxSize()) {
                        Text("up-next-content")
                        FocusTarget()
                    }
                }
                entry<DownloadsKey>(metadata = NdSceneMetadata.paneList()) {
                    Column(Modifier.fillMaxSize()) {
                        Text("downloads-content")
                        FocusTarget()
                    }
                }
                entry<DiscoverKey>(metadata = NdSceneMetadata.paneList()) {
                    Column(Modifier.fillMaxSize()) {
                        Text("discover-content")
                        FocusTarget()
                    }
                }
                entry<AddPodcastKey>(metadata = NdSceneMetadata.bottomSheet()) {
                    Text("add-podcast-sheet")
                }
                entry<SettingsHomeKey>(metadata = NdSceneMetadata.paneList()) { Text("settings-home") }
                entry<ImportFileKey>(metadata = NdSceneMetadata.paneDetail()) { Text("import-file") }
            },
        )
}
