// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class EntryBackDispatchHostTest : NdNavHostHarness() {
    @Test
    fun currentEntryHandlerKeepsPrecedenceOverNavigation() {
        var handled = 0
        val installers =
            setOf<EntryProviderInstaller>({
                for (tab in TOP_LEVEL_TABS) {
                    entry(tab, metadata = NdSceneMetadata.paneList()) { Text("root") }
                }
                entry<SettingsKey>(metadata = NdSceneMetadata.paneDetail()) {
                    EntryHandler { handled++ }
                }
            })
        setFixedHost(installers = installers, directive = TwoPaneDirective)
        rule.runOnUiThread { navigation.push(SettingsKey(SettingsPage.ABOUT)) }
        settle()
        val before = navigation.stack(FeedsKey).toList()
        val input = DirectNavigationEventInput()
        rule.runOnUiThread {
            requireNotNull(dispatcher).addInput(input)
            input.backCompleted()
        }
        settle()
        assertEquals(1, handled)
        assertEquals(before, navigation.stack(FeedsKey).toList())
    }

    @Test
    fun entryCheckRemainsLiveAfterLeavingTheScreen() {
        var isCurrent: (() -> Boolean)? = null
        val installers =
            setOf<EntryProviderInstaller>({
                for (tab in TOP_LEVEL_TABS) {
                    entry(tab, metadata = NdSceneMetadata.paneList()) {
                        if (tab == LibraryKey) isCurrent = rememberEntryIsCurrent(LibraryKey)
                        Text("root")
                    }
                }
            })
        setFixedHost(installers = installers, directive = TwoPaneDirective)
        rule.runOnUiThread { navigation.selectTab(LibraryKey) }
        settle()
        rule.runOnUiThread { assertTrue(requireNotNull(isCurrent).invoke()) }
        rule.runOnUiThread { navigation.selectTab(FeedsKey) }
        settle()
        rule.runOnUiThread { assertFalse(requireNotNull(isCurrent).invoke()) }
    }

    @Composable
    @Suppress("FunctionNaming") // Compose functions use PascalCase.
    private fun EntryHandler(onBack: () -> Unit) {
        val events = rememberNavigationEventState(NavigationEventInfo.None)
        NavigationBackHandler(events, isBackEnabled = true, onBackCompleted = onBack)
        Text("entry")
    }
}
