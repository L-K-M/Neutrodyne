// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.navigation.AddPodcastKey
import ch.lkmc.neutrodyne.core.navigation.ExportKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import ch.lkmc.neutrodyne.core.navigation.PodcastKey
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class OverlaySceneDismissTest {
    private lateinit var previousLocale: Locale

    @BeforeTest
    fun setUp() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest
    fun tearDown() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun staleSheetWindowDismissKeepsUnderlyingEntry() = checkDismiss(Overlay.SHEET)

    @Test
    fun staleDialogWindowDismissKeepsUnderlyingEntry() = checkDismiss(Overlay.DIALOG)

    private fun checkDismiss(overlay: Overlay) =
        runComposeUiTest {
            lateinit var navigation: NavigationState
            var dismissals = 0
            setContent {
                val nav = rememberNavigationState(LibraryKey)
                navigation = nav
                remember(nav) {
                    nav.push(PodcastKey(7))
                    nav.push(overlay.key)
                }
                val onDismiss: (NavEntry<NavKey>) -> Unit = { entry ->
                    dismissals++
                    val key = entry.metadata.getValue(PANE_NAV_KEY) as NavKey
                    assertEquals(overlay.key, key)
                    nav.pop(key)
                }
                val strategy: SceneStrategy<NavKey> =
                    when (overlay) {
                        Overlay.SHEET -> rememberNdBottomSheetSceneStrategy(onDismiss)
                        Overlay.DIALOG -> rememberNdDialogSceneStrategy(onDismiss)
                    }
                // Retain the actual outgoing scene, including its window callback, after pop.
                val scene =
                    remember(strategy) {
                        val scope = SceneStrategyScope<NavKey>()
                        val entries =
                            listOf(
                                NavEntry<NavKey>(PodcastKey(7)) {},
                                NavEntry<NavKey>(
                                    overlay.key,
                                    metadata = overlay.metadata + (PANE_NAV_KEY to overlay.key),
                                ) { Text("Outgoing overlay") },
                            )
                        requireNotNull(with(strategy) { scope.calculateScene(entries) })
                    }
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    scene.content()
                }
            }
            waitForIdle()
            runOnIdle { assertTrue(navigation.pop(overlay.key)) }

            when (overlay) {
                Overlay.SHEET -> {
                    onNodeWithContentDescription("Close sheet").performTouchInput { click(Offset(4f, 4f)) }
                }

                Overlay.DIALOG -> {
                    onNodeWithText("Outgoing overlay").performTouchInput { click(Offset(-20f, -20f)) }
                }
            }
            waitForIdle()

            assertEquals(1, dismissals)
            assertEquals(listOf(LibraryKey, PodcastKey(7)), navigation.stack(LibraryKey).toList())
            assertEquals(LibraryKey, navigation.selectedTab)
        }

    private enum class Overlay(
        val key: NavKey,
        val metadata: Map<String, Any>,
    ) {
        SHEET(AddPodcastKey(null), NdSceneMetadata.bottomSheet()),
        DIALOG(ExportKey(null), NdSceneMetadata.dialog()),
    }
}
