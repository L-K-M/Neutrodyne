// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.testing.FakeRefreshController
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.platform.NotificationPermissionRequester
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Settings › Feeds through `runComposeUiTest` on the desktop JVM (08 Settings › Feeds): the
 * refresh-interval counter must reach every choice in a short window (landscape height), not
 * clip the lower options.
 */
@OptIn(ExperimentalTestApi::class)
class FeedsPageTest {
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
    fun intervalCounterStepsThroughEveryChoiceInAShortWindow() =
        runDesktopComposeUiTest(width = 640, height = 280) {
            val settings = FakeSettingsRepository()
            setFeeds(settings)

            // Seven options overflow a landscape-height window, so the counter shows one at a
            // time: step down through every choice to Manual only, then back up to Every 24
            // hours, checking the write at each step.
            fun step(description: String) {
                onNodeWithContentDescription(description).performClick()
                waitForIdle()
            }
            // The steps below start from the default, every 4 hours.
            onNodeWithContentDescription("Refresh interval, Every 4 hours").assertIsDisplayed()
            listOf(120, 60, 0).forEach { minutes ->
                step("Decrease Refresh interval")
                assertEquals(minutes, runBlocking { settings.get(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES) })
            }
            listOf(60, 120, 240, 480, 720, 1440).forEach { minutes ->
                step("Increase Refresh interval")
                assertEquals(minutes, runBlocking { settings.get(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES) })
            }
            onNodeWithContentDescription("Refresh interval, Every 24 hours").assertIsDisplayed()
        }

    @Test
    fun globalSwitchRequestsOnlyOnEnableAndDenialOffersSettings() =
        runDesktopComposeUiTest {
            val settings = FakeSettingsRepository()
            var requests = 0
            var opened = 0
            val requester =
                object : NotificationPermissionRequester {
                    override val granted = false

                    override fun request(onResult: (Boolean) -> Unit) {
                        requests++
                        onResult(false)
                    }

                    override fun openSettings() {
                        opened++
                    }
                }
            setFeeds(settings, requester)
            assertEquals(0, requests)
            onNodeWithText("New episodes").performScrollTo().performClick()
            waitForIdle()
            assertEquals(1, requests)
            assertEquals(true, runBlocking { settings.get(FeedsSettingKeys.NOTIFY_NEW_EPISODES) })
            onNodeWithText("Notifications are blocked").performScrollTo().assertIsDisplayed()
            onNodeWithText("Open settings").performClick()
            assertEquals(1, opened)
            onNodeWithText("New episodes").performScrollTo().performClick()
            waitForIdle()
            assertEquals(1, requests)
            onNodeWithText("Notifications are blocked").assertDoesNotExist()
        }

    @Test
    fun desktopSwitchPersistsWithoutPermissionRows() =
        runDesktopComposeUiTest {
            val settings = FakeSettingsRepository()
            setFeeds(settings)
            onNodeWithText("New episodes").performScrollTo().performClick()
            waitForIdle()
            assertEquals(true, runBlocking { settings.get(FeedsSettingKeys.NOTIFY_NEW_EPISODES) })
            onNodeWithText("Notifications are blocked").assertDoesNotExist()
            onNodeWithText("Open settings").assertDoesNotExist()
        }

    private fun ComposeUiTest.setFeeds(
        settings: FakeSettingsRepository,
        requester: NotificationPermissionRequester? = null,
    ) {
        val viewModel = FeedsSettingsViewModel(settings, FakeRefreshController())
        setContent {
            // collectAsStateWithLifecycle (rule 10) needs a started owner; a bare compose
            // scene provides none, so the test installs a RESUMED one.
            CompositionLocalProvider(
                LocalLifecycleOwner provides ResumedLifecycleOwner(),
                LocalAppNavigator provides RecordingAppNavigator(),
                LocalPlatformKind provides PlatformKind.DESKTOP,
            ) {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    FeedsPage(platform = PlatformKind.DESKTOP, viewModel = viewModel, notificationRequester = requester)
                }
            }
        }
        waitForIdle()
    }

    /** `createUnsafe` skips the main-thread checks so the test can resume it off the UI thread. */
    private class ResumedLifecycleOwner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)

        init {
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
    }
}
