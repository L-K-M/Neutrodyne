// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.navigation.EpisodeKey
import ch.lkmc.neutrodyne.core.navigation.PaneLayout
import ch.lkmc.neutrodyne.core.ui.MeteredConsent
import ch.lkmc.neutrodyne.core.ui.root.PlayerPresentation
import ch.lkmc.neutrodyne.core.ui.root.PlayerSlotState
import ch.lkmc.neutrodyne.playback.api.NowPlaying
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The docked mini player: the radio home is the only full player (08 Owner revision, 2026-11-15). */
@OptIn(ExperimentalTestApi::class)
class MiniPlayerTest {
    private var previousLocale = Locale.ENGLISH

    @BeforeTest fun english() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest fun restoreLocale() {
        Locale.setDefault(previousLocale)
    }

    @Test fun tappingTheBarOpensTheRadioInsteadOfExpanding() =
        runComposeUiTest {
            val fixture = MiniFixture()
            setContent { fixture.Content() }
            onNodeWithTag("mini_player").assertIsDisplayed().performClick()
            assertEquals(listOf("openRadio"), fixture.calls)
            onNodeWithTag("full_player").assertDoesNotExist()
            onNodeWithTag("player_side_panel").assertDoesNotExist()
        }

    @Test fun radioHomeHidesTheBarAndOtherScreensShowIt() =
        runComposeUiTest {
            val fixture = MiniFixture().apply { presentation = PlayerPresentation.RADIO_HOME }
            setContent { fixture.Content() }
            onNodeWithTag("mini_player").assertDoesNotExist()
            runOnIdle { fixture.presentation = PlayerPresentation.STANDARD }
            onNodeWithTag("mini_player").assertIsDisplayed()
        }

    @Test fun transportControlsAndAccessibilityActionsReachTheController() =
        runComposeUiTest {
            val fixture = MiniFixture()
            setContent { fixture.Content() }
            // The bar merges its buttons; screen readers reach the transport through its actions.
            val bar = onNodeWithTag("mini_player")
            bar.performCustomAccessibilityActionWithLabel("Play")
            bar.performCustomAccessibilityActionWithLabel("Back 30 seconds")
            bar.performCustomAccessibilityActionWithLabel("Forward 30 seconds")
            assertEquals(listOf("toggle", "back", "forward"), fixture.calls)
            bar.performCustomAccessibilityActionWithLabel("Open the radio")
            bar.performCustomAccessibilityActionWithLabel("Dismiss player")
            assertEquals(listOf("toggle", "back", "forward", "openRadio", "dismiss"), fixture.calls)
        }

    @Test fun onlyAPausedBarOffersDismissal() =
        runComposeUiTest {
            val fixture = MiniFixture().apply { now = sampleNow(playing = true) }
            setContent { fixture.Content() }
            val actions =
                onNodeWithTag("mini_player").fetchSemanticsNode().config[SemanticsActions.CustomActions]
            assertFalse(actions.any { it.label == "Dismiss player" })
            assertTrue(actions.any { it.label == "Open the radio" })
            onNodeWithTag("mini_player").performTouchInput { swipe(center, center + Offset(0f, 300f), 300) }
            waitForIdle()
            assertFalse("dismiss" in fixture.calls)
        }

    @Test fun swipingAPausedBarDownDismissesIt() =
        runComposeUiTest {
            val fixture = MiniFixture()
            setContent { fixture.Content() }
            onNodeWithTag("mini_player").performTouchInput { swipe(center, center + Offset(0f, 300f), 300) }
            waitForIdle()
            assertEquals(listOf("dismiss"), fixture.calls)
        }

    /** 08 Context menus: the mini player's menu offers Open the radio and Dismiss. */
    @Test fun contextMenuOpensTheRadioAndNavigates() =
        runComposeUiTest {
            val fixture = MiniFixture()
            setContent { fixture.Content() }
            onNodeWithTag("mini_player").performMouseInput { rightClick(center) }
            onNodeWithText("Dismiss player").assertIsDisplayed()
            onNodeWithText("Open the radio").performClick()
            assertEquals(listOf("openRadio"), fixture.calls)
            onNodeWithTag("mini_player").performKeyInput { pressKey(Key.Menu) }
            onNodeWithText("Go to episode").performClick()
            assertEquals(listOf<NavKey>(EpisodeKey(1)), fixture.navigated)
        }

    @Test fun consentOutlivesTheBarOnTheRadioHomeAndWithoutAnEpisode() =
        runComposeUiTest {
            val fixture =
                MiniFixture().apply {
                    now = null
                    presentation = PlayerPresentation.RADIO_HOME
                    consent = MeteredConsent(1)
                }
            setContent { fixture.Content() }
            onNodeWithTag("player_sheet").assertDoesNotExist()
            onNodeWithText("Stream on mobile data?").assertIsDisplayed()
            onNodeWithText("Cancel").performClick()
            assertEquals(listOf("decline"), fixture.calls)
        }

    /**
     * 08 The docked bar: the root reserves 80 dp at the content's bottom edge while the bar shows,
     * and the radio's cabinet ends every tray above that reservation. The bar must stay inside it,
     * or it covers the bottom of every page again.
     */
    @Test fun theBarStaysInsideTheRootsReservation() =
        runComposeUiTest {
            val fixture = MiniFixture()
            setContent { fixture.Content() }
            val host = onNodeWithTag("playerHost").fetchSemanticsNode().boundsInRoot
            val bar = onNodeWithTag("player_sheet").fetchSemanticsNode().boundsInRoot
            val reach = with(density) { (host.bottom - bar.top).toDp() }
            assertTrue(reach <= ROOT_RESERVATION, "the bar reaches $reach above the content's bottom")
            assertTrue(bar.bottom <= host.bottom, "the bar hangs below the content's bottom")
        }
}

// `NeutrodyneRoot` reserves this much at the content's bottom edge while the bar shows (08).
private val ROOT_RESERVATION = 80.dp

private class MiniFixture {
    var now by mutableStateOf<NowPlaying?>(sampleNow())
    var presentation by mutableStateOf(PlayerPresentation.STANDARD)
    var consent by mutableStateOf<MeteredConsent?>(null)
    val calls = mutableListOf<String>()
    val navigated = mutableListOf<NavKey>()

    @androidx.compose.runtime.Composable
    fun Content() {
        NeutrodyneTheme(SystemUiState.DEFAULT.copy(reducedMotion = true)) {
            BoxWithConstraints {
                MiniPlayer(
                    ui = PlayerUiState(now = now, consent = consent, skipBackMs = 30_000, skipForwardMs = 30_000),
                    position = now?.position,
                    slot =
                        PlayerSlotState(
                            PaneLayout(1, maxWidth.value.toInt()),
                            { navigated += it },
                            presentation = presentation,
                            openRadio = { calls += "openRadio" },
                        ),
                    toggle = { calls += "toggle" },
                    back = { calls += "back" },
                    forward = { calls += "forward" },
                    dismiss = { calls += "dismiss" },
                    consent = { calls += "consent" },
                    declineConsent = { calls += "decline" },
                )
            }
        }
    }
}
