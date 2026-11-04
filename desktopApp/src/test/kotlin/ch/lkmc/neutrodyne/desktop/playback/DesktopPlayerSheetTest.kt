// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import ch.lkmc.neutrodyne.desktop.window.DesktopMenuActions
import ch.lkmc.neutrodyne.desktop.window.NeutrodyneWindowContent
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.Locale
import kotlin.test.assertFalse

/** Real desktop window/player/queue ViewModels driving the real controller; no audio device. */
@OptIn(ExperimentalTestApi::class)
class DesktopPlayerSheetTest {
    @Test
    fun sharedPanelPausesHidesAndReopensWithoutReloadingAudio() =
        runDesktopComposeUiTest(width = 1200, height = 800) {
            runBlocking {
                val locale = Locale.getDefault()
                Locale.setDefault(Locale.ENGLISH)
                val fixture = PlaybackFixture()
                var showing by mutableStateOf(true)
                try {
                    fixture.seed()
                    fixture.controller.playEpisode(1)
                    fixture.engine.ready()
                    fixture.awaitPlaying()
                    val windows = fixture.engine.windows.size
                    setContent {
                        if (showing) {
                            NeutrodyneWindowContent(
                                installers = fixture.graph.entryInstallers,
                                menuActions = DesktopMenuActions(),
                                banners = fixture.graph.bannerNotifier,
                                viewModelFactory = fixture.graph.metroViewModelFactory,
                                playbackController = fixture.controller,
                                playbackState = fixture.controller,
                                settingsRepository = fixture.graph.settingsRepository,
                            )
                        }
                    }
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodes(hasTestTag("full_player")).fetchSemanticsNodes().isNotEmpty()
                    }
                    onNodeWithTag("player_side_panel").assertIsDisplayed()
                    onNodeWithTag("full_player").assertIsDisplayed()
                    onNodeWithContentDescription("Pause").performClick()
                    fixture.awaitPaused()
                    assertFalse(
                        fixture.controller.nowPlaying.value!!
                            .isPlaying,
                    )
                    onNodeWithContentDescription("Hide player panel").performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodes(hasTestTag("mini_player")).fetchSemanticsNodes().isNotEmpty()
                    }
                    onNodeWithTag("player_side_panel").assertDoesNotExist()
                    onNodeWithTag("mini_player").performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodes(hasTestTag("player_side_panel")).fetchSemanticsNodes().isNotEmpty()
                    }
                    onNodeWithTag("player_side_panel").assertIsDisplayed()
                    kotlin.test.assertEquals(windows, fixture.engine.windows.size)
                } finally {
                    showing = false
                    waitForIdle()
                    fixture.close()
                    Locale.setDefault(locale)
                }
            }
        }
}
