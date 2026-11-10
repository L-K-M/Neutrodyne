// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.root

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import ch.lkmc.neutrodyne.core.navigation.SyncSetupKey
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The shared priority-4 reconnect banner, including its real button callbacks. */
@OptIn(ExperimentalTestApi::class)
class ReconnectBannerTest {
    private lateinit var previousLocale: Locale

    @BeforeTest
    fun englishLabels() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest
    fun restoreLocale() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun theLibraryBannerOpensTheExactProfileAndDoesNotShowOnFeeds() =
        runDesktopComposeUiTest(width = 360, height = 300) {
            var library by mutableStateOf(false)
            val navigation = RecordingAppNavigator()
            showBanners(navigation, library = { library }, actions = actions(live = { true }))
            onNodeWithText(MESSAGE).assertDoesNotExist()
            runOnIdle { library = true }
            onNodeWithText(MESSAGE).assertIsDisplayed()
            onNodeWithText("Not now").assertIsDisplayed()
            onNodeWithText("Reconnect").assertIsDisplayed().performClick()
            val push = assertIs<RecordingAppNavigator.Call.Push>(navigation.calls.single())
            assertEquals(BANNER.serverUrl, assertIs<SyncSetupKey>(push.key).serverUrl)
        }

    @Test
    fun theButtonRechecksTheLiveTargetBeforeNavigating() =
        runDesktopComposeUiTest(width = 360, height = 300) {
            var allowed by mutableStateOf(false)
            val navigation = RecordingAppNavigator()
            showBanners(navigation, actions = actions(live = { allowed }))
            onNodeWithText("Reconnect").performClick()
            assertTrue(navigation.calls.isEmpty())
            runOnIdle { allowed = true }
            onNodeWithText("Reconnect").performClick()
            val push = assertIs<RecordingAppNavigator.Call.Push>(navigation.calls.single())
            assertEquals(BANNER.serverUrl, assertIs<SyncSetupKey>(push.key).serverUrl)
        }

    @Test
    fun notNowDispatchesOnlyTheRenderedProfile() =
        runDesktopComposeUiTest(width = 360, height = 300) {
            val dismissed = mutableListOf<ReconnectBanner>()
            showBanners(RecordingAppNavigator(), actions = actions(dismiss = { dismissed += it }))
            onNodeWithText("Not now").performClick()
            assertEquals(listOf(BANNER), dismissed)
        }

    @Test
    fun heldAndReconnectTakeBothSlotsBeforeTheImportBanner() =
        runDesktopComposeUiTest(width = 360, height = 300) {
            var importAvailable: Boolean? = null
            showBanners(
                RecordingAppNavigator(),
                state = RootUiState.READY.copy(reconnect = BANNER, heldChanges = HeldChangesBanner(7, "Tablet", 3, 1)),
                importResume = { available ->
                    importAvailable = available
                    if (available) Text("import-resume")
                    available
                },
            )
            onNodeWithText("Review").assertIsDisplayed()
            onNodeWithText("Reconnect").assertIsDisplayed()
            onNodeWithText("import-resume").assertDoesNotExist()
            assertEquals(false, importAvailable)
        }

    @Test
    fun unwiredActionsCannotOpenSetup() =
        runDesktopComposeUiTest(width = 360, height = 300) {
            val navigation = RecordingAppNavigator()
            // Existing positional consumers keep the original first five parameters.
            showBanners(navigation, actions = RootActions({}, {}, {}, {}, { false }))
            onNodeWithText("Reconnect").performClick()
            assertTrue(navigation.calls.isEmpty())
        }

    private fun ComposeUiTest.showBanners(
        navigation: RecordingAppNavigator,
        state: RootUiState = RootUiState.READY.copy(reconnect = BANNER),
        library: () -> Boolean = { true },
        actions: RootActions = actions(),
        importResume: @Composable (Boolean) -> Boolean = { false },
    ) {
        setContent {
            MaterialTheme {
                Column {
                    RootBanners(
                        state,
                        actions,
                        navigation,
                        onFeedsOrLibrary = true,
                        onLibrary = library(),
                        importResume = importResume,
                    )
                }
            }
        }
    }

    private fun actions(
        live: (ReconnectBanner) -> Boolean = { false },
        dismiss: (ReconnectBanner) -> Unit = {},
    ): RootActions =
        RootActions(
            retryStartup = {},
            dismissNotice = {},
            continueHere = {},
            dismissRemoteSession = {},
            playbackKey = { false },
            dismissReconnect = dismiss,
            isReconnectTargetLive = live,
        )

    private companion object {
        val BANNER = ReconnectBanner("https://sync.example/ExactPath", "anna", "sync.example")
        const val MESSAGE = "Reconnect to sync.example to keep syncing"
    }
}
