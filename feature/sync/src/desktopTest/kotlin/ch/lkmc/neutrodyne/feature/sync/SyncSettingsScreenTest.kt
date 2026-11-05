// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.sync

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
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
import ch.lkmc.neutrodyne.core.testing.FakeSyncController
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.sync.api.SyncStatus
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 08 Sync screens, the "Share feed passwords" row: it follows [FakeSyncController]'s switch state,
 * asks before turning sharing on and not before turning it off, and shows the switch's help text.
 */
@OptIn(ExperimentalTestApi::class)
class SyncSettingsScreenTest {
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
    fun turningShareOnAsksFirstAndOffDoesNot() =
        runComposeUiTest {
            val controller = FakeSyncController(linkedStatus())
            setPage { SyncSettingsScreen(controller) }

            onNodeWithText("Share feed passwords").assertIsDisplayed()
            onNode(isToggleable()).assertIsOff()
            onNodeWithText(
                "Lets your other devices refresh private feeds that need a password. " +
                    "Passwords already on the server stay there until you change them there or delete your data.",
            ).assertIsDisplayed()

            // On asks first; nothing is written before Share.
            onNode(isToggleable()).performClick()
            onNodeWithText("Share feed passwords?").assertIsDisplayed()
            onNodeWithText("Share").performClick()
            assertEquals(listOf(true), controller.passwordSharing)
            assertEquals(true, controller.shareFeedPasswords.value)

            // Off needs no confirmation.
            onNode(isToggleable()).assertIsOn().performClick()
            assertEquals(listOf(true, false), controller.passwordSharing)
            assertEquals(false, controller.shareFeedPasswords.value)
        }

    @Test
    fun cancellingTheDialogLeavesSharingOff() =
        runComposeUiTest {
            val controller = FakeSyncController(linkedStatus())
            setPage { SyncSettingsScreen(controller) }

            onNode(isToggleable()).performClick()
            onNodeWithText("Cancel").performClick()
            assertTrue(controller.passwordSharing.isEmpty())
            onNode(isToggleable()).assertIsOff()
        }

    /**
     * 08 Sync screens: while an ALL_DEVICES restore session is pending, the body shows
     * "Finishing restore…" instead of the first-link choices.
     */
    @Test
    fun aPendingAllDevicesRestoreShowsFinishingRestore() =
        runComposeUiTest {
            val controller =
                FakeSyncController(SyncStatus.FinishingRestore("https://sync.example.org"))
            setPage { SyncSettingsScreen(controller) }

            onNodeWithText("Finishing restore…").assertIsDisplayed()
            onNodeWithText("Finish linking to https://sync.example.org.").assertDoesNotExist()
        }

    private fun linkedStatus(): SyncStatus.Linked =
        SyncStatus.Linked(
            serverUrl = "https://sync.example.org",
            accountId = "acc",
            deviceId = "dev",
            username = "anna",
            nodeId = "0000000000000001",
            lastSyncAtMs = null,
            problem = null,
        )

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
