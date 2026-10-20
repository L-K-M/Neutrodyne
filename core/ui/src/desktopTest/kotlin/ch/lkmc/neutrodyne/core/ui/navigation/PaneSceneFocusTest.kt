// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.navigation

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import ch.lkmc.neutrodyne.core.designsystem.components.NdButton
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.navigation.DiscoverKey
import ch.lkmc.neutrodyne.core.navigation.DownloadsKey
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.ExportKey
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import ch.lkmc.neutrodyne.core.navigation.PodcastKey
import ch.lkmc.neutrodyne.core.navigation.UpNextKey
import kotlin.test.Test
import kotlin.test.assertEquals

/** Exercises NavDisplay's deferred scene effect, not a model or a direct entry move. */
@OptIn(ExperimentalTestApi::class)
class PaneSceneFocusTest {
    @Test
    fun deferredSceneApplyRestoresTheClickedLeafAndRetainsItsState() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val fixture = Fixture()
            setContent { fixture.Content() }
            onNodeWithTag("clicked").performClick()
            onNodeWithTag("clicked").assertIsFocused()
            onNodeWithTag("first").assertIsNotFocused()
            runOnIdle { assertEquals(1, fixture.compositions) }
            runOnIdle { fixture.navigation.pop() }
            onNodeWithTag("clicked").assertIsFocused()
            onNodeWithTag("first").assertIsNotFocused()
            runOnIdle {
                assertEquals(listOf(FeedsKey), fixture.navigation.stack(FeedsKey))
                assertEquals(1, fixture.compositions)
            }
        }

    @Test
    fun outsideBeforeDeferredAndSupersedingSceneAppliesKeepsFocus() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val fixture = Fixture()
            setContent { fixture.Content() }
            mainClock.autoAdvance = false
            onNodeWithTag("clicked").performClick()
            mainClock.advanceTimeByFrame()
            onNodeWithTag("outside").performClick().assertIsFocused()
            runOnIdle {
                fixture.navigation.push(PodcastKey(4))
                fixture.navigation.pop()
            }
            onNodeWithTag("outside").assertIsFocused()
            mainClock.autoAdvance = true
            waitForIdle()
            onNodeWithTag("outside").assertIsFocused()
            onNodeWithTag("clicked").assertIsNotFocused()
            onNodeWithTag("first").assertIsNotFocused()
            runOnIdle {
                assertEquals(PodcastKey(3), fixture.navigation.stack(FeedsKey).last())
            }
        }

    @Test
    fun noLossApplyDoesNotAuthorizeRestorationAfterClear() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val fixture = Fixture()
            setContent { fixture.Content() }
            onNodeWithTag("clicked").performClick().assertIsFocused()
            runOnIdle { fixture.partitions = 3 }
            waitForIdle()
            onNodeWithTag("clicked").assertIsFocused()
            runOnIdle { fixture.manager.clearFocus() }
            waitForIdle()
            runOnIdle { fixture.navigation.pop() }
            waitForIdle()
            onNodeWithTag("outside").assertIsNotFocused()
            onNodeWithTag("clicked").assertIsNotFocused()
            onNodeWithTag("first").assertIsNotFocused()
        }

    @Test
    fun predictivePreviewCannotReclaimOutsideOnCancelOrCommit() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val fixture = Fixture()
            setContent { fixture.Content() }
            runOnIdle { fixture.navigation.push(PodcastKey(3)) }
            waitForIdle()
            onNodeWithTag("outside").performClick().assertIsFocused()
            runOnIdle {
                fixture.input.backStarted(NavigationEvent())
                fixture.input.backProgressed(NavigationEvent(progress = .5f))
            }
            waitForIdle()
            onNodeWithTag("outside").assertIsFocused()
            runOnIdle { fixture.input.backCancelled() }
            waitForIdle()
            onNodeWithTag("outside").assertIsFocused()
            runOnIdle {
                assertEquals(PodcastKey(3), fixture.navigation.stack(FeedsKey).last())
                fixture.input.backStarted(NavigationEvent())
                fixture.input.backProgressed(NavigationEvent(progress = .5f))
            }
            waitForIdle()
            runOnIdle { fixture.input.backCompleted() }
            waitForIdle()
            onNodeWithTag("outside").assertIsFocused()
            onNodeWithTag("clicked").assertIsNotFocused()
            runOnIdle {
                assertEquals(listOf(FeedsKey), fixture.navigation.stack(FeedsKey))
            }
        }

    @Test
    fun overlayPreventsUnderlyingPaneFromReclaimingFocus() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val fixture = Fixture()
            setContent { fixture.Content() }
            runOnIdle { fixture.navigation.push(ExportKey(null)) }
            onNodeWithTag("overlay").performClick().assertIsFocused()
            for (partitions in listOf(1, 2)) {
                runOnIdle { fixture.partitions = partitions }
                waitForIdle()
                // Overlay leaf restoration is window-owned; this adapter must fail closed underneath it.
                onNodeWithTag("clicked").assertIsNotFocused()
                onNodeWithTag("first").assertIsNotFocused()
            }
            runOnIdle { fixture.navigation.pop() }
            onNodeWithTag("overlay").assertDoesNotExist()
            runOnIdle {
                assertEquals(listOf(FeedsKey), fixture.navigation.stack(FeedsKey))
            }
        }

    private class Fixture {
        var partitions by mutableIntStateOf(2)
        var compositions = 0
        lateinit var navigation: NavigationState
        lateinit var manager: FocusManager
        val input = DirectNavigationEventInput()
        val navigationOwner =
            object : NavigationEventDispatcherOwner {
                override val navigationEventDispatcher = NavigationEventDispatcher().also { it.addInput(input) }
            }
        private val installers =
            setOf<EntryProviderInstaller>({
                entry<LibraryKey> {}
                entry<UpNextKey> {}
                entry<DownloadsKey> {}
                entry<DiscoverKey> {}
                entry<FeedsKey>(metadata = NdSceneMetadata.paneList()) {
                    remember { compositions++ }
                    Column {
                        Text("First", Modifier.testTag("first").focusable())
                        NdButton("Open detail", { navigation.push(PodcastKey(3)) }, Modifier.testTag("clicked"))
                    }
                }
                entry<PodcastKey>(metadata = NdSceneMetadata.paneDetail()) { Text("Detail ${it.podcastId}") }
                entry<ExportKey>(metadata = NdSceneMetadata.dialog()) {
                    NdButton("Overlay", {}, Modifier.testTag("overlay"))
                }
            })

        @Composable
        fun Content(modifier: Modifier = Modifier) {
            navigation = rememberNavigationState()
            DisposableEffect(navigationOwner) {
                onDispose { navigationOwner.navigationEventDispatcher.dispose() }
            }
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides navigationOwner) {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    NavigationFocusRoot(modifier) {
                        manager = LocalFocusManager.current
                        Column {
                            NdButton("Outside", {}, Modifier.testTag("outside"))
                            NeutrodyneNavHost(
                                navigation,
                                installers,
                                PaneScaffoldDirective.Default.copy(maxHorizontalPartitions = partitions),
                            )
                        }
                    }
                }
            }
        }
    }
}
