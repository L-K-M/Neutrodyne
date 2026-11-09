// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.SyncSettingsKey
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.FakeSyncController
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import ch.lkmc.neutrodyne.sync.api.SyncActivity
import ch.lkmc.neutrodyne.sync.api.SyncStatus
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Settings home Sync row (08 Settings › Sync): while nothing is configured it is the only
 * sync affordance anywhere (R7.1), it pushes `SyncSettingsKey`, and its summary is the short
 * status text ("Off" / "Linked · synced {t}" / "Reconnect to {host}" / …).
 */
@OptIn(ExperimentalTestApi::class)
class SyncSettingsRowTest {
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
    fun syncRowPushesSyncSettingsKey() =
        runComposeUiTest {
            val navigator = RecordingAppNavigator()
            setHome(navigator)

            onNodeWithText("Sync").performScrollTo().performClick()

            assertEquals(
                listOf<RecordingAppNavigator.Call>(RecordingAppNavigator.Call.Push(SyncSettingsKey)),
                navigator.calls,
            )
        }

    @Test
    fun syncRowSummaryFollowsTheStatus() =
        runComposeUiTest {
            val controller = FakeSyncController()
            setHome(RecordingAppNavigator(), controller)

            onNodeWithText("Off").assertIsDisplayed()

            controller.status.value =
                SyncStatus.Linked(
                    serverUrl = "https://sync.example.org",
                    accountId = "acc",
                    deviceId = "dev",
                    username = "anna",
                    nodeId = "node",
                    lastSyncAtMs = System.currentTimeMillis(),
                    activity = SyncActivity.IDLE,
                    problem = null,
                )
            waitForIdle()
            onNodeWithText("Linked · synced Just now").assertIsDisplayed()

            controller.status.value =
                SyncStatus.Reconnect("https://sync.example.org", username = "anna")
            waitForIdle()
            onNodeWithText("Reconnect to sync.example.org").assertIsDisplayed()

            controller.status.value = SyncStatus.LinkUnfinished("https://sync.example.org")
            waitForIdle()
            onNodeWithText("Finish linking").assertIsDisplayed()

            controller.status.value = SyncStatus.Revoked("https://sync.example.org")
            waitForIdle()
            onNodeWithText("This device was unlinked").assertIsDisplayed()
        }

    private fun ComposeUiTest.setHome(
        navigator: RecordingAppNavigator,
        controller: FakeSyncController = FakeSyncController(),
    ) {
        setContent {
            CompositionLocalProvider(
                LocalLifecycleOwner provides ResumedLifecycleOwner(),
                LocalAppNavigator provides navigator,
            ) {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    SettingsHomeRoute(FakeSettingsRepository(), sync = controller)
                }
            }
        }
        waitForIdle()
    }

    private class ResumedLifecycleOwner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)

        init {
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
    }
}
