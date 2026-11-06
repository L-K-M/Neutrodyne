// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.sync

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.testing.FakeLinkFlow
import ch.lkmc.neutrodyne.core.testing.FakeSyncController
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.sync.api.FirstLinkChoice
import ch.lkmc.neutrodyne.sync.api.LinkState
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 08 Sync screens, the second confirmation of "Use this device's library everywhere" (10
 * First-link choices): it names the other devices' removals, and only "Replace everywhere"
 * continues the choice.
 */
@OptIn(ExperimentalTestApi::class)
class SyncSetupScreenTest {
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
    fun theSecondConfirmationNamesTheRemovalsAndContinues() =
        runComposeUiTest {
            val link = FakeLinkFlow(LinkState.ConfirmReplaceEverywhere(removedPodcasts = 3, removedGroups = 2))
            val controller = FakeSyncController(link = link)
            setPage { SyncSetupScreen(controller, serverUrl = "https://sync.example.org") }

            onAllNodesWithText("Cancel").onFirst().assertIsDisplayed()
            onNodeWithText("library everywhere?", substring = true).assertIsDisplayed()
            onNodeWithText(
                "Other devices will remove 3 podcasts and 2 groups when they next sync; " +
                    "devices ask first when this exceeds their limits.",
            ).assertIsDisplayed()
            onNodeWithText("Replace everywhere").performClick()
            assertEquals(listOf(FirstLinkChoice.USE_DEVICE), controller.firstLinkChoices)
        }

    @Test
    fun cancellingTheSecondConfirmationLeavesTheFlow() =
        runComposeUiTest {
            val link = FakeLinkFlow(LinkState.ConfirmReplaceEverywhere(removedPodcasts = 1, removedGroups = 0))
            val controller = FakeSyncController(link = link)
            setPage { SyncSetupScreen(controller, serverUrl = "https://sync.example.org") }

            onNodeWithText("Cancel").performClick()
            assertEquals(1, link.cancelCount)
        }

    private fun ComposeUiTest.setPage(content: @Composable () -> Unit) {
        val owner =
            object : LifecycleOwner {
                override val lifecycle =
                    LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
            }
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalPlatformKind provides PlatformKind.DESKTOP,
                LocalLifecycleOwner provides owner,
            ) {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) { content() }
            }
        }
        waitForIdle()
    }
}
