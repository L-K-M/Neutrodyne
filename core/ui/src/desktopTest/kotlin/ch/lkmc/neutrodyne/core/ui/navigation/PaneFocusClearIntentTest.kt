// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.navigation

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class PaneFocusClearIntentTest {
    @Test
    fun explicitClearWhileEntryIsEmptyRevokesRestoration() = verifyClear(acquireOutside = false)

    @Test
    fun acquireThenClearDoesNotRestoreTheOldEntry() = verifyClear(acquireOutside = true)

    private fun verifyClear(acquireOutside: Boolean) =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var partitions by mutableIntStateOf(2)
            lateinit var manager: FocusManager
            val installers =
                setOf<EntryProviderInstaller>({
                    entry<ch.lkmc.neutrodyne.core.navigation.LibraryKey> {}
                    entry<ch.lkmc.neutrodyne.core.navigation.UpNextKey> {}
                    entry<ch.lkmc.neutrodyne.core.navigation.DownloadsKey> {}
                    entry<ch.lkmc.neutrodyne.core.navigation.DiscoverKey> {}
                    entry<FeedsKey>(metadata = NdSceneMetadata.paneList()) {
                        Text("Target", Modifier.testTag("target").focusable())
                    }
                })
            setContent {
                NavigationFocusRoot {
                    manager = LocalFocusManager.current
                    Column {
                        Text("Outside", Modifier.testTag("outside").focusable())
                        NeutrodyneNavHost(
                            rememberNavigationState(),
                            installers,
                            PaneScaffoldDirective.Default.copy(maxHorizontalPartitions = partitions),
                        )
                    }
                }
            }
            onNodeWithTag("target").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            onNodeWithTag("target").assertIsFocused()
            mainClock.autoAdvance = false
            runOnIdle { partitions = 1 }
            mainClock.advanceTimeByFrame()
            if (acquireOutside) {
                onNodeWithTag("outside").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                onNodeWithTag("outside").assertIsFocused()
            }
            runOnIdle { manager.clearFocus() }
            onNodeWithTag("target").assertIsNotFocused()
            onNodeWithTag("outside").assertIsNotFocused()
            mainClock.autoAdvance = true
            waitForIdle()
            onNodeWithTag("target").assertIsNotFocused()
            onNodeWithTag("outside").assertIsNotFocused()
        }
}
