// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import ch.lkmc.neutrodyne.core.testing.FakePlaybackController
import ch.lkmc.neutrodyne.core.testing.testNowPlaying
import ch.lkmc.neutrodyne.core.ui.root.ReconnectProfile
import ch.lkmc.neutrodyne.sync.api.RemoteSessionOffer
import ch.lkmc.neutrodyne.sync.api.SyncNotice
import ch.lkmc.neutrodyne.sync.api.SyncStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * MS3's "Continue on this device" card and `MarkedPlayedElsewhere` notice through the real
 * `MainActivity` (08 Continue on this device / Sync notices): the offer the service publishes
 * becomes the card, Play routes to `PlaybackController.play()` and Dismiss to
 * `SyncController.dismissRemoteSession()`. The parent mapped no offer and wired both callbacks
 * to no-ops, so any regression to that state fails here — no fake UI stands in for the shell.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = SyncHandoffHostApplication::class, sdk = [34])
class MainActivitySyncHandoffTest {
    private lateinit var previousLocale: Locale

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val app: SyncHandoffHostApplication
        get() = RuntimeEnvironment.getApplication() as SyncHandoffHostApplication

    @Before
    fun setUp() {
        // The shared test-task config sets de_DE; these assertions name English labels.
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        // Production opens the database in band-100 `DatabaseOpenInitializer` before the UI can
        // need it; this host runs no initializers, so the test drives the same `awaitOpen()`.
        runBlocking { app.productionGraph.databaseOpener.awaitOpen() }
    }

    @After
    fun tearDown() {
        Locale.setDefault(previousLocale)
        app.productionGraph.appScope.coroutineContext[kotlinx.coroutines.Job]
            ?.cancel()
    }

    @Test
    fun theOfferShowsAsTheCardResumesAndDismisses() {
        app.sync.remoteSession.value = OFFER

        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule
                .onAllNodesWithText("Continue on this device")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("Episode 7 · 23:14 · from Pixel 8").assertExists()
        assertThat(app.syncResolutions).isGreaterThan(0)

        composeRule.onNodeWithText("Play").performClick()
        composeRule.waitUntil(TIMEOUT_MS) {
            app.playback.calls.contains(
                FakePlaybackController.Call.Transport(FakePlaybackController.Action.PLAY),
            )
        }
        assertThat(app.sync.remoteSessionDismissals).isEmpty()

        app.sync.remoteSession.value = OFFER
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule
                .onAllNodesWithText("Continue on this device")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("Dismiss").performClick()
        composeRule.waitUntil(TIMEOUT_MS) {
            app.sync.remoteSessionDismissals.size == 1
        }
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule
                .onAllNodesWithText("Continue on this device")
                .fetchSemanticsNodes()
                .isEmpty()
        }
    }

    /**
     * 08: the card owns the mini player's slot while the adopted item stays paused underneath —
     * a loaded-but-paused `NowPlaying` must not suppress it; only an actively playing local
     * session does. The offer itself stays untouched while suppressed.
     */
    @Test
    fun aPausedMiniPlayerKeepsTheCardAndOnlyPlayingHidesIt() {
        app.playback.state.nowPlaying.value =
            testNowPlaying().copy(isPlaying = false, playWhenReady = false)
        app.sync.remoteSession.value = OFFER

        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule
                .onAllNodesWithText("Continue on this device")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        app.playback.state.nowPlaying.value = testNowPlaying().copy(isPlaying = true)
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule
                .onAllNodesWithText("Continue on this device")
                .fetchSemanticsNodes()
                .isEmpty()
        }
        assertThat(app.sync.remoteSession.value).isEqualTo(OFFER)

        app.playback.state.nowPlaying.value =
            testNowPlaying().copy(isPlaying = false, playWhenReady = false)
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule
                .onAllNodesWithText("Continue on this device")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    @Test
    fun markedPlayedElsewhereReachesTheShellSnackbar() {
        // `notices` is replay-0: a post before the gated collector subscribes is dropped.
        composeRule.waitUntil(TIMEOUT_MS) { app.sync.noticeSubscriptions.value > 0 }
        app.sync.post(SyncNotice.MarkedPlayedElsewhere(42, "Episode 7", "Pixel 8"))

        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule
                .onAllNodesWithText("Marked played on Pixel 8. It keeps playing here.")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    @Test
    fun reconnectOpensTheRealSetupEntryWithTheSavedAddress() {
        app.sync.status.value = SyncStatus.Reconnect(RECONNECT_URL, "anna")
        composeRule.onNodeWithTag("nav_library").performClick()
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithText(RECONNECT_MESSAGE).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("Reconnect").performClick()
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithText("Link a device").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(RECONNECT_URL).assertExists()
        composeRule.onNodeWithText(RECONNECT_MESSAGE).assertDoesNotExist()
    }

    @Test
    fun notNowSurvivesActivityRecreationThroughTheAppScopedHolder() {
        app.sync.status.value = SyncStatus.Reconnect(RECONNECT_URL, "anna")
        composeRule.onNodeWithTag("nav_library").performClick()
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithText(RECONNECT_MESSAGE).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Not now").performClick()
        assertThat(app.productionGraph.reconnectDismissals.isDismissed(ReconnectProfile(RECONNECT_URL, "anna")))
            .isTrue()

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_library").performClick()
        composeRule.onNodeWithText(RECONNECT_MESSAGE).assertDoesNotExist()
        assertThat(app.sync.status.value).isEqualTo(SyncStatus.Reconnect(RECONNECT_URL, "anna"))
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val RECONNECT_URL = "https://sync.example.org/personal"
        const val RECONNECT_MESSAGE = "Reconnect to sync.example.org to keep syncing"

        val OFFER =
            RemoteSessionOffer(
                episodeId = 42,
                title = "Episode 7",
                podcastTitle = "Show",
                positionMs = 1_394_000,
                deviceName = "Pixel 8",
            )
    }
}
