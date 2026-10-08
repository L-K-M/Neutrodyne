// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.testing.FakeRefreshController
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Settings › Feeds through `runComposeUiTest` on the desktop JVM (08 Settings › Feeds): the
 * refresh-interval chooser's option list must scroll so every choice is reachable in a short
 * window (landscape height), not clip the lower radios.
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
    fun intervalChooserScrollsToEveryChoiceInAShortWindow() =
        runDesktopComposeUiTest(width = 640, height = 280) {
            val settings = FakeSettingsRepository()
            setFeeds(settings)
            onAllNodesWithText("Refresh interval").onFirst().performClick()

            // Seven radio rows overflow a landscape-height window: the lowest option has to
            // scroll into view and stay selectable (08's dialog rule).
            onNodeWithText("Every 24 hours").performScrollTo().assertIsDisplayed()
            onNodeWithText("Every 24 hours").performClick()
            assertEquals(
                24 * 60,
                runBlocking { settings.get(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES) },
            )
        }

    private fun ComposeUiTest.setFeeds(settings: FakeSettingsRepository) {
        val viewModel = FeedsSettingsViewModel(settings, FakeRefreshController())
        setContent {
            // collectAsStateWithLifecycle (rule 10) needs a started owner; a bare compose
            // scene provides none, so the test installs a RESUMED one.
            CompositionLocalProvider(
                LocalLifecycleOwner provides ResumedLifecycleOwner(),
                LocalAppNavigator provides RecordingAppNavigator(),
                LocalPlatformKind provides PlatformKind.DESKTOP,
            ) {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    FeedsPage(platform = PlatformKind.DESKTOP, viewModel = viewModel)
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
