// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.HingeOrientation
import ch.lkmc.neutrodyne.core.model.HingeState
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.WindowHinge
import ch.lkmc.neutrodyne.core.model.WindowPosture
import ch.lkmc.neutrodyne.core.model.WindowRectDp
import ch.lkmc.neutrodyne.core.navigation.PaneLayout
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import ch.lkmc.neutrodyne.core.ui.root.PlayerSlotState
import ch.lkmc.neutrodyne.feature.player.PlayerSheet
import ch.lkmc.neutrodyne.feature.player.PlayerSheetState
import ch.lkmc.neutrodyne.feature.player.PlayerSheetValue
import ch.lkmc.neutrodyne.feature.player.PlayerUiState
import ch.lkmc.neutrodyne.feature.player.rememberPlayerPanelPreference
import ch.lkmc.neutrodyne.playback.api.NowPlaying
import ch.lkmc.neutrodyne.playback.api.PlayerPhase
import ch.lkmc.neutrodyne.playback.api.PositionSnapshot
import ch.lkmc.neutrodyne.playback.api.StreamKind
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Injected geometry and fake transport only. Actual WindowManager hinges remain a device gate. */
@RunWith(AndroidJUnit4::class)
class AdaptivePlayerPresentationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun injectedTabletopAtDoubleFontKeepsControlsOutsideTheHinge() {
        compose.enableAccessibilityChecks()
        installFakeImageLoader()
        val calls = mutableListOf<String>()
        var hingeRootTopPx = 0f
        var hingeRootBottomPx = 0f
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                TestTheme {
                    var origin by remember { mutableStateOf(Offset.Zero) }
                    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
                        val top = maxHeight.value * 0.46f
                        val bottom = top + 12f
                        hingeRootTopPx = top * density
                        hingeRootBottomPx = bottom * density
                        val posture =
                            WindowPosture(
                                persistentListOf(
                                    WindowHinge(
                                        WindowRectDp(
                                            origin.x / density,
                                            origin.y / density + top,
                                            origin.x / density + maxWidth.value,
                                            origin.y / density + bottom,
                                        ),
                                        HingeOrientation.HORIZONTAL,
                                        HingeState.HALF_OPENED,
                                        true,
                                    ),
                                ),
                            )
                        TestPlayer(
                            now = fakeNow(),
                            slot =
                                PlayerSlotState(
                                    PaneLayout(1, false, maxWidth.value.toInt()),
                                    {},
                                    {},
                                    {},
                                    posture = posture,
                                ),
                            state = remember { PlayerSheetState(PlayerSheetValue.Expanded) },
                            action = { calls += it },
                        )
                    }
                }
            }
        }
        val art = compose.onNodeWithTag("playerArtRegion").fetchSemanticsNode().boundsInRoot
        val controls = compose.onNodeWithTag("playerControlsRegion").fetchSemanticsNode().boundsInRoot
        assertTrue(art.bottom <= hingeRootTopPx)
        assertTrue(controls.top >= hingeRootBottomPx)
        compose
            .onNodeWithContentDescription("Play")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose
            .onNodeWithContentDescription("Forward 30 seconds")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose
            .onNodeWithText("Sleep timer")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose
            .onAllNodesWithText("Up next")
            .onLast()
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose
            .onNodeWithText("Notes")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle { assertEquals(listOf("toggle", "forward", "navigate"), calls) }
    }

    @Test fun hiddenPanelReopensThroughTheRealDeviceSettingCallbackAfterNewPlayback() {
        compose.enableAccessibilityChecks()
        installFakeImageLoader()
        val settings = FakeSettingsRepository()
        var current by mutableStateOf(fakeNow())
        compose.setContent {
            TestTheme {
                val panel = rememberPlayerPanelPreference(settings)
                TestPlayer(
                    now = current,
                    slot =
                        PlayerSlotState(PaneLayout(1, !panel.hidden, 692), {
                        }, panel.close, panel.reopen, reopenPanelFromMini = panel.hidden),
                    state = remember { PlayerSheetState() },
                    action = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Close player panel").performScrollTo().performClick()
        compose.onNodeWithTag("miniPlayer").assertIsDisplayed()
        compose.runOnIdle { current = fakeNow().copy(episodeId = 2) }
        compose.onNodeWithTag("miniPlayer").assertIsDisplayed().performClick()
        compose.onNodeWithTag("playerSidePanel").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(
                listOf("set(ui.player_panel_hidden)", "set(ui.player_panel_hidden)"),
                settings.calls,
            )
        }
    }
}

@Composable
private fun TestTheme(content: @Composable () -> Unit) {
    NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT.copy(reducedMotion = true), content = content)
}

@Composable
private fun TestPlayer(
    now: NowPlaying,
    slot: PlayerSlotState,
    state: PlayerSheetState,
    action: (String) -> Unit,
) {
    PlayerSheet(
        ui = PlayerUiState(now = now),
        position = now.position,
        slot =
            slot.copy(navigate = {
                action("navigate")
            }),
        state = state,
        positionAt = {
            it.positionMs
        },
        toggle = { action("toggle") },
        back = { action("back") },
        forward = { action("forward") },
        previous = {
            action("previous")
        },
        next = { action("next") },
        seek = { action("seek") },
        dismiss = { action("dismiss") },
        stop = {
            action("stop")
        },
        markPlayed = { action("markPlayed") },
        consent = { action("consent") },
        declineConsent = { action("decline") },
    )
}

private fun fakeNow(): NowPlaying =
    NowPlaying(
        episodeId = 1,
        podcastId = 2,
        title = "An episode",
        podcastTitle = "A podcast",
        artwork = ArtworkRef("m-podcast", null, 0),
        sourceType = SourceType.RSS,
        isVideo = false,
        phase = PlayerPhase.READY,
        isPlaying = false,
        playWhenReady = false,
        position = PositionSnapshot(1, 12000, 20000, 60000, 1f, false, 1000),
        hasNext = true,
        stream = StreamKind.STREAM,
        issue = null,
        context = null,
    )
