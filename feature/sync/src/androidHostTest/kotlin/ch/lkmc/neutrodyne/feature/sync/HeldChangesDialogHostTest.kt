// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.sync

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.SyncHeldChangesKey
import ch.lkmc.neutrodyne.core.testing.FakeSyncController
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import ch.lkmc.neutrodyne.sync.api.HeldResolution
import ch.lkmc.neutrodyne.sync.api.MassChangePrompt
import dev.zacsweers.metro.Provider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The held-review destination on the Android runtime, rendered through the production
 * [SyncNavigation] entry provider: the summary and stacked decisions in English and German.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HeldChangesDialogHostTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theEntryRendersTheSummaryAndDecideLaterResolves() {
        val controller =
            FakeSyncController().apply {
                heldChanges.value = listOf(PROMPT)
            }
        val navigator = RecordingAppNavigator()
        setEntryContent(controller, navigator)

        compose.onNodeWithText("Another device removed 2 podcasts and 1 group").assertIsDisplayed()
        compose.onNodeWithText("Apply this on this device too?").assertIsDisplayed()
        compose.onNodeWithText("Held Show A").assertIsDisplayed()
        compose.onNodeWithText("Held Group").assertIsDisplayed()

        compose.onNodeWithText("Decide later").performClick()

        assertEquals(listOf(PROMPT.id to HeldResolution.DECIDE_LATER), controller.heldResolutions)
        assertTrue(
            navigator.calls.contains(RecordingAppNavigator.Call.PopEntry(SyncHeldChangesKey(PROMPT.id))),
        )
    }

    @Test
    @Config(qualifiers = "de")
    fun theEntryRendersTheGermanWording() {
        val controller =
            FakeSyncController().apply {
                heldChanges.value = listOf(PROMPT)
            }
        setEntryContent(controller, RecordingAppNavigator())

        compose.onNodeWithText("Ein anderes Gerät hat 2 Podcasts und 1 Gruppe entfernt").assertIsDisplayed()
        compose.onNodeWithText("Auch auf diesem Gerät übernehmen?").assertIsDisplayed()
        compose.onNodeWithText("Übernehmen").assertIsDisplayed()
        compose.onNodeWithText("Meine behalten").assertIsDisplayed()
        // Robolectric's display is short; the bottom action is in the tree below the fold.
        compose.onNodeWithText("Später entscheiden").fetchSemanticsNode()
    }

    /** Composes the production `SyncHeldChangesKey` entry as the dialog scene would. */
    private fun setEntryContent(
        controller: FakeSyncController,
        navigator: RecordingAppNavigator,
    ) {
        val provider =
            entryProvider<NavKey> {
                SyncNavigation.entries(Provider { controller }).invoke(this)
            }
        val entry = provider(SyncHeldChangesKey(PROMPT.id))
        compose.setContent {
            CompositionLocalProvider(LocalAppNavigator provides navigator) {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    entry.Content()
                }
            }
        }
    }

    private companion object {
        val PROMPT =
            MassChangePrompt(
                id = 7,
                deviceName = null,
                podcastTitles = listOf("Held Show A", "Held Show B"),
                groupNames = listOf("Held Group"),
            )
    }
}
