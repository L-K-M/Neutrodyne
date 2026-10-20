// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.navigation

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class PaneFocusRetentionTest {
    @Test
    fun partitionChangesRestoreTheSameChildWithoutAPlayer() = verifyPartitionFocus(textField = false)

    @Test
    fun partitionChangesKeepTheFocusedFieldEditable() = verifyPartitionFocus(textField = true)

    private fun verifyPartitionFocus(textField: Boolean) =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var partitions by mutableIntStateOf(2)
            var text = ""
            var compositions = 0
            val installers =
                setOf<EntryProviderInstaller>({
                    entry<ch.lkmc.neutrodyne.core.navigation.LibraryKey> {}
                    entry<ch.lkmc.neutrodyne.core.navigation.UpNextKey> {}
                    entry<ch.lkmc.neutrodyne.core.navigation.DownloadsKey> {}
                    entry<ch.lkmc.neutrodyne.core.navigation.DiscoverKey> {}
                    entry<FeedsKey>(metadata = NdSceneMetadata.paneList()) {
                        remember { compositions++ }
                        Column {
                            Text("First", Modifier.testTag("first").focusable())
                            if (textField) {
                                var value by remember { mutableStateOf("") }
                                TextField(value, {
                                    value = it
                                    text = it
                                }, Modifier.testTag("target"))
                            } else {
                                Text("Second", Modifier.testTag("target").focusable())
                            }
                        }
                    }
                })
            setContent {
                NavigationFocusRoot {
                    Column {
                        Text(
                            "Outside",
                            Modifier.testTag("outside").focusable(),
                        )
                        NeutrodyneNavHost(
                            rememberNavigationState(),
                            installers,
                            PaneScaffoldDirective.Default.copy(maxHorizontalPartitions = partitions),
                        )
                    }
                }
            }
            onNodeWithTag("first").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            onNodeWithTag("target").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            onNodeWithTag("target").assertIsFocused()
            onNodeWithTag("first").assertIsNotFocused()
            if (textField) onNodeWithTag("target").performTextInput("before ")
            for (next in listOf(1, 2, 3, 1, 2)) {
                runOnIdle { partitions = next }
                waitForIdle()
                onNodeWithTag("target").assertIsFocused()
                onNodeWithTag("first").assertIsNotFocused()
            }
            if (textField) {
                onNodeWithTag("target").performTextInput("retained")
                runOnIdle { assertEquals("before retained", text) }
            }
            runOnIdle { assertEquals(1, compositions) }
            onNodeWithTag("outside").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            for (next in listOf(1, 2)) {
                runOnIdle { partitions = next }
                waitForIdle()
                onNodeWithTag("outside").assertIsFocused()
            }

            onNodeWithTag("target").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            onNodeWithTag("target").assertIsFocused()
            mainClock.autoAdvance = false
            runOnIdle { partitions = 1 }
            mainClock.advanceTimeByFrame()
            // The completed-layout boundary restores within this frame, before the intentional transfer.
            onNodeWithTag("target").assertIsFocused()
            onNodeWithTag("first").assertIsNotFocused()
            onNodeWithTag("outside").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            onNodeWithTag("outside").assertIsFocused()
            mainClock.autoAdvance = true
            waitForIdle()
            onNodeWithTag("outside").assertIsFocused()
            onNodeWithTag("target").assertIsNotFocused()
            onNodeWithTag("first").assertIsNotFocused()
            runOnIdle { assertEquals(1, compositions) }
        }
}
