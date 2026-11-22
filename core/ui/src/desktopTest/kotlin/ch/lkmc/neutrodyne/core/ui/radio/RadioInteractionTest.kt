// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.radio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_mark_played
import ch.lkmc.neutrodyne.core.ui.resources.action_pause
import ch.lkmc.neutrodyne.core.ui.resources.action_play
import ch.lkmc.neutrodyne.core.ui.resources.dismiss
import ch.lkmc.neutrodyne.core.ui.resources.radio_all_clear
import ch.lkmc.neutrodyne.core.ui.resources.radio_buffering
import ch.lkmc.neutrodyne.core.ui.resources.radio_choose_other
import ch.lkmc.neutrodyne.core.ui.resources.radio_cleared
import ch.lkmc.neutrodyne.core.ui.resources.radio_ended
import ch.lkmc.neutrodyne.core.ui.resources.radio_episodes
import ch.lkmc.neutrodyne.core.ui.resources.radio_error
import ch.lkmc.neutrodyne.core.ui.resources.radio_first_hint
import ch.lkmc.neutrodyne.core.ui.resources.radio_no_playlists
import ch.lkmc.neutrodyne.core.ui.resources.radio_no_playlists_empty
import ch.lkmc.neutrodyne.core.ui.resources.radio_paused
import ch.lkmc.neutrodyne.core.ui.resources.radio_playing
import ch.lkmc.neutrodyne.core.ui.resources.radio_skip_back
import ch.lkmc.neutrodyne.core.ui.resources.radio_skip_forward
import ch.lkmc.neutrodyne.core.ui.resources.radio_starting
import ch.lkmc.neutrodyne.core.ui.resources.radio_untuned
import ch.lkmc.neutrodyne.playback.api.PlaybackIssue
import ch.lkmc.neutrodyne.playback.api.PlayerPhase
import org.jetbrains.compose.resources.getString
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class RadioInteractionTest {
    private var previousLocale: Locale = Locale.getDefault()

    // Some assertions read the English plural forms, whatever the host's locale.
    @BeforeTest fun english() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest fun restoreLocale() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun aSlipTicksOnceAsItCrossesIntoReleaseToDiscard() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            val felt = mutableListOf<HapticFeedbackType>()
            val haptics =
                object : HapticFeedback {
                    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                        felt += hapticFeedbackType
                    }
                }
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                        RadioScreen(radioFixture(), RadioActions())
                    }
                }
            }
            onNodeWithTag("radio_episode_1").performTouchInput {
                down(center)
                moveBy(Offset(-40f, 0f))
                moveBy(Offset(-60f, 0f))
                moveBy(Offset(-20f, 0f))
                moveBy(Offset(50f, 0f))
                moveBy(Offset(-30f, 0f))
                cancel()
            }
            // -100 arms, -120 stays armed, -70 disarms, -100 arms again: a tick each time it arms.
            assertEquals(List(2) { HapticFeedbackType.GestureThresholdActivate }, felt)
        }

    @Test
    fun anEmptyRadioWaitsForItsPlaylistsAndOffersAnImport() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            var state by mutableStateOf(RadioState())
            var imports = 0
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    RadioScreen(state, RadioActions(openImport = { imports++ }))
                }
            }
            // Until the repository answers, an empty list is "not read yet", not "none".
            onNodeWithText(getString(Res.string.radio_no_playlists_empty)).assertDoesNotExist()
            onNodeWithTag("radio_empty_import").assertDoesNotExist()
            onNodeWithText(getString(Res.string.radio_all_clear)).assertDoesNotExist()
            onNodeWithText(getString(Res.string.radio_cleared)).assertDoesNotExist()
            onNodeWithText(getString(Res.string.radio_untuned)).assertIsDisplayed()
            // The hint waits too: neither a first listener's nor a cleared playlist's words fit yet.
            onNodeWithText(getString(Res.string.radio_first_hint)).assertDoesNotExist()
            onNodeWithText(getString(Res.string.radio_choose_other)).assertDoesNotExist()

            state = RadioState(playlistsLoaded = true)
            onNodeWithText(getString(Res.string.radio_no_playlists_empty))
                .assertIsDisplayed()
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            onNodeWithText(getString(Res.string.radio_untuned)).assertIsDisplayed()
            onNodeWithText(getString(Res.string.radio_first_hint)).assertIsDisplayed()
            onNodeWithTag("radio_empty_import")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .performClick()
            assertEquals(1, imports)

            state = radioFixture().copy(episodes = emptyList(), playlistsLoaded = true)
            onNodeWithTag("radio_empty_import").assertDoesNotExist()
            onNodeWithText(getString(Res.string.radio_all_clear)).assertIsDisplayed()
            onNodeWithText(getString(Res.string.radio_choose_other)).assertIsDisplayed()
        }

    @Test
    fun theEmptyRadiosKeysStayWholeOnSmallPhonesAndAtLargeText() {
        // A sideways-scrolling bank pushed Library past the paper's edge from 320 x 800 dp, on
        // most German phones, and at 130 % and 200 % text. The radio reads the window size and
        // the resources read the default locale, so each case gets its own window and locale.
        val papers =
            listOf(
                EmptyPaper(320, 568, 1f),
                EmptyPaper(320, 800, 1f),
                EmptyPaper(360, 800, 1.3f),
                EmptyPaper(320, 640, 2f),
                EmptyPaper(360, 640, 1f, Locale.GERMANY),
                EmptyPaper(360, 640, 1.3f, Locale.GERMANY),
                EmptyPaper(390, 800, 1f, Locale.GERMANY),
            )
        val saved = Locale.getDefault()
        try {
            for (paper in papers) {
                Locale.setDefault(paper.locale)
                runDesktopComposeUiTest(width = paper.width, height = paper.height) {
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(1f, paper.fontScale)) {
                            NeutrodyneTheme(SystemUiState.DEFAULT) {
                                RadioScreen(RadioState(playlistsLoaded = true), RadioActions())
                            }
                        }
                    }
                    // boundsInRoot is clipped, so a key cut off by the paper reads smaller than it is.
                    for (tag in listOf("radio_empty_discover", "radio_empty_import", "radio_empty_library")) {
                        val key = onNodeWithTag(tag).fetchSemanticsNode()
                        assertEquals(
                            key.size.width.toFloat(),
                            key.boundsInRoot.width,
                            1f,
                            "$tag is cut sideways at $paper",
                        )
                        assertEquals(
                            key.size.height.toFloat(),
                            key.boundsInRoot.height,
                            1f,
                            "$tag is cut off at $paper",
                        )
                    }
                }
            }
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun playbackFromElsewhereKeepsItsStatusWithoutAPlaylist() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            val playing = radioFixture().episodes.first()
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    RadioScreen(
                        RadioState(
                            playlistsLoaded = true,
                            selectedId = playing.id,
                            nowPlayingEpisode = playing,
                            playing = true,
                        ),
                        RadioActions(),
                    )
                }
            }
            onNodeWithText(getString(Res.string.radio_no_playlists_empty)).assertIsDisplayed()
            onNodeWithText(getString(Res.string.radio_untuned)).assertDoesNotExist()
            onNodeWithText(getString(Res.string.radio_playing)).assertIsDisplayed()
            onNodeWithText(playing.podcast).assertIsDisplayed()
            onNodeWithText(getString(Res.string.radio_all_clear)).assertDoesNotExist()
        }

    @Test
    fun swipeCommitsOnlyOnReleaseAtExactThresholdAndUndoRestores() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            var state by mutableStateOf(radioFixture())
            var discarded: Long? = null
            val original = state
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    RadioScreen(
                        state,
                        RadioActions(discard = { id ->
                            discarded = id
                            state =
                                state.copy(
                                    episodes = state.episodes.filterNot { it.id == id },
                                    removed = state.episodes.first { it.id == id },
                                )
                        }, undo = { state = original }),
                    )
                }
            }
            val row = onNodeWithTag("radio_episode_1")
            row.performTouchInput {
                down(center)
                moveBy(Offset(-80f, 0f))
                up()
            }
            waitForIdle()
            assertEquals(1L, discarded)
            onNodeWithTag("radio_episode_1").assertDoesNotExist()
            onNodeWithTag("radio_undo").assertIsFocused().performClick()
            onNodeWithTag("radio_episode_1").assertIsDisplayed().assertIsFocused()
        }

    @Test
    fun aSlipCanBeMarkedPlayedAndRestoredThroughSemantics() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            val original = radioFixture()
            var state by mutableStateOf(original)
            val selected = mutableListOf<Long>()
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    RadioScreen(
                        state,
                        RadioActions(
                            selectEpisode = { selected += it },
                            discard = { id ->
                                state =
                                    state.copy(
                                        episodes = state.episodes.filterNot { it.id == id },
                                        removed = state.episodes.first { it.id == id },
                                    )
                            },
                            undo = { state = original },
                        ),
                    )
                }
            }

            val row = onNodeWithTag("radio_episode_1")
            val markPlayed = getString(Res.string.action_mark_played)
            val action =
                row.fetchSemanticsNode().config[SemanticsActions.CustomActions].single {
                    it.label == markPlayed
                }
            runOnIdle { assertTrue(action.action()) }

            assertEquals(emptyList(), selected)
            row.assertDoesNotExist()
            onNodeWithTag("radio_episode_2").assertIsDisplayed()
            onNodeWithTag("radio_undo").assertIsFocused().performClick()
            row.assertIsDisplayed().assertIsFocused()
            onNodeWithTag("radio_episode_2").performClick()
            assertEquals(listOf(2L), selected)
            onNodeWithTag("radio_episode_2").assertIsDisplayed()
            onNodeWithTag("radio_undo").assertDoesNotExist()
        }

    @Test
    fun belowThresholdAndVerticalScrollDoNotDiscardOrSelect() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            var discards = 0
            var selections = 0
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    RadioScreen(
                        radioFixture(),
                        RadioActions(discard = { discards++ }, selectEpisode = { selections++ }),
                    )
                }
            }
            onNodeWithTag("radio_episode_2").performTouchInput {
                down(center)
                moveBy(Offset(-79f, 0f))
                up()
            }
            onNodeWithTag("radio_episode_2").performTouchInput {
                down(center)
                moveBy(Offset(-10f, -45f))
                up()
            }
            assertEquals(0, discards)
            assertEquals(0, selections)
        }

    @Test
    fun aSwipeThatStartsOnThePlayKeyDoesNotDiscard() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            var discards = 0
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    RadioScreen(radioFixture(), RadioActions(discard = { discards++ }))
                }
            }
            onNodeWithTag("radio_slip_play_1").performTouchInput {
                down(center)
                moveBy(Offset(-100f, 0f))
                up()
            }
            assertEquals(0, discards)
            onNodeWithTag("radio_episode_1").assertIsDisplayed()
        }

    /** The Nixie's spoken time uses plural forms and leaves out empty parts. */
    @Test fun theElapsedTimeIsSpokenInPluralForms() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            var elapsed by mutableLongStateOf(3_661_000L)
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    RadioScreen(radioFixture().copy(elapsedMs = elapsed, hasPlayed = true), RadioActions())
                }
            }
            onNodeWithContentDescription("Elapsed: 1 hour 1 minute 1 second").assertIsDisplayed()
            elapsed = 125_000L
            onNodeWithContentDescription("Elapsed: 2 minutes 5 seconds").assertIsDisplayed()
        }

    @Test fun savedResumeIsPausedAndElapsedAccessibilityIsUncapped() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            val fixture =
                radioFixture().let {
                    it.copy(
                        episodes = listOf(it.episodes.first().copy(durationMs = 54_000_000)),
                        elapsedMs = 43_200_000,
                    )
                }
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    RadioScreen(fixture, RadioActions())
                }
            }
            onNodeWithText(getString(Res.string.radio_paused)).assertIsDisplayed()
            onNodeWithContentDescription("Elapsed: 12 hours").assertIsDisplayed()
            onAllNodes(hasText("900:00")).assertCountEquals(2)
        }

    @Test
    fun elapsedTicksDoNotRescanTheProgramme() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            val episodes = List(200) { RadioEpisode(it.toLong(), "Episode $it", "Podcast") }
            var reads = 0
            val counted =
                object : AbstractList<RadioEpisode>() {
                    override val size = episodes.size

                    override fun get(index: Int): RadioEpisode = episodes[index].also { reads++ }
                }
            val baseline = radioFixture().copy(episodes = counted, selectedId = episodes.last().id)
            var elapsed by mutableLongStateOf(0L)
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    RadioPlayer(baseline.copy(elapsedMs = elapsed), RadioActions(), compact = true, short = false)
                }
            }
            runOnIdle { reads = 0 }
            repeat(5) { runOnIdle { elapsed += 1_000 } }
            runOnIdle { assertEquals(0, reads, "unchanged programme entries read across five ticks") }
        }

    @Test
    fun theSwitchEngagesBeforePlaybackAndResetsAfterRefusal() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            val initial = radioFixture()
            var state by mutableStateOf(initial)
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) { RadioScreen(state, RadioActions()) }
            }
            runOnIdle { state = initial.copy(startingEpisodeId = initial.selectedId) }
            onNodeWithTag("radio_play").assertIsOn()
            onNodeWithText(getString(Res.string.radio_starting)).assertIsDisplayed()
            runOnIdle { state = initial }
            onNodeWithTag("radio_play").assertIsOff()
            runOnIdle {
                state =
                    initial.copy(
                        phase = PlayerPhase.BUFFERING,
                        playWhenReadyEpisodeId = initial.selectedId,
                    )
            }
            onNodeWithTag("radio_play").assertIsOn()
            onNodeWithText(getString(Res.string.radio_buffering)).assertIsDisplayed()
        }

    @Test
    fun transportStatusFollowsTheLoadedPhase() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            var phase by mutableStateOf<PlayerPhase?>(null)
            var issue by mutableStateOf<PlaybackIssue?>(null)
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    RadioScreen(
                        radioFixture().copy(playing = true, phase = phase, issue = issue),
                        RadioActions(),
                    )
                }
            }
            onNodeWithText(getString(Res.string.radio_playing)).assertIsDisplayed()
            runOnIdle { phase = PlayerPhase.BUFFERING }
            onNodeWithText(getString(Res.string.radio_buffering)).assertIsDisplayed()
            runOnIdle { phase = PlayerPhase.ENDED }
            onNodeWithText(getString(Res.string.radio_ended)).assertIsDisplayed()
            runOnIdle {
                phase = PlayerPhase.ERROR
                issue = null
            }
            onNodeWithText(getString(Res.string.radio_error)).assertIsDisplayed()
            // An issue with a ready phase keeps the transport status but lights the red lamp,
            // as the mini player's error dot does.
            runOnIdle {
                phase = null
                issue = PlaybackIssue.NETWORK_LOST
            }
            onNodeWithText(getString(Res.string.radio_playing)).assertIsDisplayed()
            // An issue during buffering keeps the BUFFERING status text; the lamp treats any
            // issue as red regardless of the buffering branch order.
            runOnIdle {
                phase = PlayerPhase.BUFFERING
                issue = PlaybackIssue.NETWORK_LOST
            }
            onNodeWithText(getString(Res.string.radio_buffering)).assertIsDisplayed()
        }

    @Test
    fun arrowKeysMoveTheSelectionAndFocusThroughTheProgramme() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            var selected by mutableLongStateOf(1L)
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    RadioScreen(
                        radioFixture().copy(selectedId = selected),
                        RadioActions(selectEpisode = { selected = it }),
                    )
                }
            }
            onNodeWithTag("radio_episode_1").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            onNodeWithTag("radio_episode_1").performKeyInput { pressKey(Key.DirectionDown) }
            assertEquals(2L, selected)
            onNodeWithTag("radio_episode_2").assertIsFocused()
            onNodeWithTag("radio_episode_2").performKeyInput { pressKey(Key.DirectionDown) }
            assertEquals(3L, selected)
            onNodeWithTag("radio_episode_3").assertIsFocused()
            onNodeWithTag("radio_episode_3").performKeyInput { pressKey(Key.DirectionUp) }
            assertEquals(2L, selected)
            onNodeWithTag("radio_episode_2").assertIsFocused()
            onNodeWithTag("radio_episode_2").performKeyInput { pressKey(Key.DirectionUp) }
            assertEquals(1L, selected)
            onNodeWithTag("radio_episode_1").assertIsFocused()
            // The top stops instead of wrapping.
            onNodeWithTag("radio_episode_1").performKeyInput { pressKey(Key.DirectionUp) }
            assertEquals(1L, selected)
        }

    @Test
    fun arrowKeysScrollALongProgrammeToTheSelection() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            var selected by mutableLongStateOf(1L)
            val episodes =
                (1..40).map { RadioEpisode(it.toLong(), "Episode $it", "Podcast", 20 * 60_000L) }
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    RadioScreen(
                        RadioState(
                            playlists = listOf(RadioPlaylist(1, "Rotation", 1)),
                            playlistId = 1,
                            episodes = episodes,
                            selectedId = selected,
                        ),
                        RadioActions(selectEpisode = { selected = it }),
                    )
                }
            }
            onNodeWithTag("radio_episode_1").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            repeat(12) {
                onNode(isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
            }
            assertEquals(13L, selected)
            onNodeWithTag("radio_episode_13").assertIsFocused().assertIsDisplayed()
        }

    @Test
    fun arrowChordsPassThroughToTheRoot() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            var selected by mutableLongStateOf(1L)
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    RadioScreen(
                        radioFixture().copy(selectedId = selected),
                        RadioActions(selectEpisode = { selected = it }),
                    )
                }
            }
            onNodeWithTag("radio_episode_1").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            onNodeWithTag("radio_episode_1").performKeyInput {
                withKeyDown(Key.CtrlLeft) { pressKey(Key.DirectionDown) }
                withKeyDown(Key.MetaLeft) { pressKey(Key.DirectionUp) }
            }
            assertEquals(1L, selected)
            // A plain Shift+Arrow still tunes, as it did before the chord guard.
            onNodeWithTag("radio_episode_1").performKeyInput {
                withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionDown) }
            }
            assertEquals(2L, selected)
        }

    @Test
    fun arrowKeysSurviveTheListShiftingUnderTheFocusedSlip() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            var episodes by
                mutableStateOf(
                    (1..6).map { RadioEpisode(it.toLong(), "Episode $it", "Podcast", 20 * 60_000L) },
                )
            var selected by mutableLongStateOf(3L)
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    RadioScreen(
                        RadioState(
                            playlists = listOf(RadioPlaylist(1, "Rotation", 1)),
                            playlistId = 1,
                            episodes = episodes,
                            selectedId = selected,
                        ),
                        RadioActions(selectEpisode = { selected = it }),
                    )
                }
            }
            onNodeWithTag("radio_episode_3").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            // The row above the focused slip goes away, so the focused index no longer names it.
            runOnIdle { episodes = episodes.filterNot { it.id == 1L } }
            onNode(isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
            assertEquals(4L, selected)
        }

    @Test fun openChooserSurvivesColumnSwitchAndRecreation() =
        runDesktopComposeUiTest(width = 700, height = 900) {
            var width by mutableIntStateOf(390)
            var visible by mutableStateOf(true)
            var saved: Map<String, List<Any?>>? = null
            var registry: SaveableStateRegistry? = null
            setContent {
                if (visible) {
                    val current = remember { SaveableStateRegistry(saved) { true } }
                    registry = current
                    CompositionLocalProvider(LocalSaveableStateRegistry provides current) {
                        NeutrodyneTheme(SystemUiState.DEFAULT) {
                            Box(Modifier.width(width.dp).fillMaxHeight()) {
                                RadioScreen(radioFixture(), RadioActions())
                            }
                        }
                    }
                }
            }
            onNodeWithTag("radio_playlist").performClick()
            onNodeWithTag("radio_playlist_option_2").assertIsDisplayed()
            runOnIdle { width = 700 }
            onNodeWithTag("radio_playlist_option_2").assertIsDisplayed()
            runOnIdle {
                saved = registry!!.performSave()
                visible = false
            }
            runOnIdle { visible = true }
            onNodeWithTag("radio_playlist_option_1").assertIsFocused()
        }

    @Test fun chooserFocusesTheActivePlaylistAndPrintsItsCheck() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    RadioScreen(radioFixture().copy(playlistId = 2), RadioActions())
                }
            }
            onNodeWithTag("radio_playlist").performClick()
            onNodeWithTag("radio_playlist_option_2").assertIsFocused().assertIsSelected()
            onNodeWithTag("radio_playlist_option_1").assertIsNotSelected()
            onNodeWithTag("radio_playlist_active_check", useUnmergedTree = true).assertIsDisplayed()
        }

    @Test fun chooserFallsBackToTheFirstPlaylistWhenNoneIsActive() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    RadioScreen(radioFixture().copy(playlistId = null), RadioActions())
                }
            }
            onNodeWithTag("radio_playlist").performClick()
            onNodeWithTag("radio_playlist_option_1").assertIsFocused()
            onNodeWithTag("radio_playlist_active_check", useUnmergedTree = true).assertDoesNotExist()
        }

    @Test fun emptyChooserFocusesClose() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    RadioScreen(RadioState(), RadioActions())
                }
            }
            onNodeWithTag("radio_playlist").performClick()
            onNodeWithContentDescription(getString(Res.string.dismiss)).assertIsFocused()
        }

    @Test fun homeOffersNoPlaylistCreationOrSwipeHint() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    RadioScreen(radioFixture(), RadioActions())
                }
            }
            onNodeWithTag("radio_new_playlist").assertDoesNotExist()
            onNodeWithText("Swipe left to discard").assertDoesNotExist()
            onNodeWithTag("radio_playlist").performClick()
            onNodeWithText("New playlist").assertDoesNotExist()
        }

    @Test fun playSwitchTogglesOnTapAndLeverFlick() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            var playing by mutableStateOf(false)
            var toggles = 0
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    RadioScreen(
                        radioFixture().copy(playing = playing),
                        RadioActions(togglePlay = {
                            toggles++
                            playing = !playing
                        }),
                    )
                }
            }
            onNodeWithTag("radio_play").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            onNodeWithTag("radio_play").assertIsOff().performClick()
            assertEquals(1, toggles)
            onNodeWithTag("radio_play").assertIsOn()
            // Flicking the lever up while it is already up does nothing; down switches it off.
            onNodeWithTag("radio_play").performTouchInput { swipeUp() }
            assertEquals(1, toggles)
            onNodeWithTag("radio_play").performTouchInput { swipeDown() }
            assertEquals(2, toggles)
            onNodeWithTag("radio_play").assertIsOff()
            // A short flick just past touch slop still switches it on.
            onNodeWithTag("radio_play").performTouchInput { swipeUp(centerY, centerY - 24f, 120) }
            assertEquals(3, toggles)
            onNodeWithTag("radio_play").assertIsOn()
        }

    @Test fun playSwitchIsDisabledWithoutASelection() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            var toggles = 0
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    RadioScreen(radioFixture().copy(selectedId = null), RadioActions(togglePlay = { toggles++ }))
                }
            }
            onNodeWithTag("radio_play").assertIsNotEnabled().performClick()
            onNodeWithTag("radio_play").performTouchInput { swipeUp() }
            assertEquals(0, toggles)
        }

    @Test fun skipKeysFlankTheCounterWithTheListenersIntervals() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            var back = 0
            var forward = 0
            var selected by mutableStateOf<Long?>(radioFixture().selectedId)
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    RadioScreen(
                        radioFixture().copy(selectedId = selected, skipBackMs = 15_000, skipForwardMs = 45_000),
                        RadioActions(skipBack = { back++ }, skipForward = { forward++ }),
                    )
                }
            }
            onNodeWithTag(
                "radio_skip_back",
            ).assertContentDescriptionEquals(getString(Res.string.radio_skip_back, 15L)).performClick()
            onNodeWithTag(
                "radio_skip_forward",
            ).assertContentDescriptionEquals(getString(Res.string.radio_skip_forward, 45L)).performClick()
            assertEquals(1, back)
            assertEquals(1, forward)

            selected = null
            onNodeWithTag("radio_skip_back").assertIsNotEnabled().performClick()
            onNodeWithTag("radio_skip_forward").assertIsNotEnabled().performClick()
            assertEquals(1, back)
            assertEquals(1, forward)
        }

    @Test fun eachSlipHasAPlayKeyThatPausesThePlayingSlip() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            val played = mutableListOf<Long>()
            val state = radioFixture(true)
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    RadioScreen(state, RadioActions(playEpisode = { played += it }))
                }
            }
            val playing = state.selectedId!!
            val other = state.episodes.first { it.id != playing }.id
            onNodeWithTag("radio_slip_play_$playing").assertContentDescriptionEquals(getString(Res.string.action_pause))
            onNodeWithTag("radio_slip_play_$other")
                .assertContentDescriptionEquals(getString(Res.string.action_play))
                .performClick()
            onNodeWithTag("radio_slip_play_$playing").performClick()
            assertEquals(listOf(other, playing), played)
        }

    @Test fun doubleTextScaleOnANarrowScreenLeavesSkippingToTheNeedle() =
        runDesktopComposeUiTest(width = 320, height = 568) {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                    NeutrodyneTheme(SystemUiState.DEFAULT) { RadioScreen(radioFixture(), RadioActions()) }
                }
            }
            onNodeWithTag("radio_skip_back").assertDoesNotExist()
            onNodeWithTag("radio_skip_forward").assertDoesNotExist()
        }

    @Test fun emptyChooserExplainsWhereToCreatePlaylists() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    RadioScreen(RadioState(), RadioActions())
                }
            }
            onNodeWithTag("radio_playlist").performClick()
            onNode(
                hasText(getString(Res.string.radio_no_playlists)) and hasAnyAncestor(hasTestTag("radio_insert")),
            ).assertIsDisplayed()
        }

    @Test fun anEmptyRadioOffersDiscoverAndTheGroupWorkflow() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            var discover = 0
            var library = 0
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    RadioScreen(
                        RadioState(playlistsLoaded = true),
                        RadioActions(openDiscover = { discover++ }, openLibrary = { library++ }),
                    )
                }
            }
            onNodeWithText(getString(Res.string.radio_no_playlists_empty)).assertIsDisplayed()
            onNodeWithText(getString(Res.string.radio_all_clear)).assertDoesNotExist()
            onNodeWithTag("radio_empty_discover").performClick()
            onNodeWithTag("radio_empty_library").performClick()
            assertEquals(1, discover)
            assertEquals(1, library)
        }

    @Test fun anEmptyRadioHidesRouteKeysWhileLoading() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    RadioScreen(RadioState(loading = true), RadioActions())
                }
            }
            onNodeWithTag("radio_empty_discover").assertDoesNotExist()
            onNodeWithTag("radio_empty_library").assertDoesNotExist()
            onNodeWithTag("radio_empty_import").assertDoesNotExist()
        }

    @Test fun anEmptyRadioHidesRouteKeysWhenLoadFails() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    RadioScreen(RadioState(loadError = true), RadioActions())
                }
            }
            onNodeWithTag("radio_empty_discover").assertDoesNotExist()
            onNodeWithTag("radio_empty_library").assertDoesNotExist()
            onNodeWithTag("radio_empty_import").assertDoesNotExist()
        }

    @Test fun aClearedPlaylistKeepsItsAllClearSignWithoutRouteKeys() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    RadioScreen(
                        RadioState(
                            playlists = listOf(RadioPlaylist(1, "Tech", 0)),
                            playlistsLoaded = true,
                            playlistId = 1,
                        ),
                        RadioActions(),
                    )
                }
            }
            onNodeWithText(getString(Res.string.radio_all_clear)).assertIsDisplayed()
            onNodeWithText(getString(Res.string.radio_no_playlists_empty)).assertDoesNotExist()
            onNodeWithTag("radio_empty_discover").assertDoesNotExist()
            onNodeWithTag("radio_empty_library").assertDoesNotExist()
        }

    @Test fun pagingFailureOffersRetryAndRetainsTheProgramme() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            var retries = 0
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    RadioScreen(radioFixture().copy(loadError = true), RadioActions(retry = { retries++ }))
                }
            }
            onNodeWithTag("radio_episode_1").assertIsDisplayed()
            onNodeWithTag("radio_retry").performClick()
            assertEquals(1, retries)
        }

    @Test fun theHomeMenuKeyIsAButtonOnAnIvoryCap() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    RadioScreen(radioFixture(), RadioActions())
                }
            }
            onNodeWithTag("radio_menu").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button),
            )
        }

    @Test fun doubleTextScaleKeepsHeaderAndTransportReachable() =
        runDesktopComposeUiTest(width = 320, height = 568) {
            var menuClicks = 0
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                    NeutrodyneTheme(SystemUiState.DEFAULT) {
                        RadioScreen(radioFixture(), RadioActions(menu = { menuClicks++ }))
                    }
                }
            }
            onNodeWithTag("radio_menu").performTouchInput { click() }
            assertEquals(1, menuClicks)
            onNodeWithTag("radio_play").performScrollTo().assertIsDisplayed()
        }

    @Test fun aLongSelectedTitleKeepsTheDeckAndThePlayer() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            var state by mutableStateOf(radioFixture())
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    RadioScreen(state, RadioActions(), Modifier.padding(top = 48.dp, bottom = 24.dp))
                }
            }
            onNodeWithTag("radio_valve_deck").assertIsDisplayed()
            runOnIdle {
                state =
                    state.copy(
                        episodes =
                            state.episodes.map { episode ->
                                if (episode.id == 1L) {
                                    episode.copy(title = "A longer programme title that needs room. ".repeat(6))
                                } else {
                                    episode
                                }
                            },
                    )
            }
            onNodeWithTag("radio_valve_deck").assertIsDisplayed()
            onNodeWithTag("radio_play").assertIsDisplayed()
        }

    // The tubes stay on every screen (owner decision 2026-11-17): large text and small phones keep
    // the deck, with the play switch still on screen; only windows too short for it stow it.
    @Test fun largeTextKeepsTheDeckAndThePlaySwitch() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                    NeutrodyneTheme(SystemUiState.DEFAULT) { RadioScreen(radioFixture(), RadioActions()) }
                }
            }
            onNodeWithTag("radio_valve_deck").assertIsDisplayed()
            onNodeWithTag("radio_play").assertIsDisplayed()
        }

    @Test fun aSmallPhoneKeepsTheDeckAndThePlaySwitch() =
        runDesktopComposeUiTest(width = 320, height = 568) {
            setContent { NeutrodyneTheme(SystemUiState.DEFAULT) { RadioScreen(radioFixture(), RadioActions()) } }
            onNodeWithTag("radio_valve_deck").assertIsDisplayed()
            onNodeWithTag("radio_play").assertIsDisplayed()
        }

    @Test fun largeTextOnASmallPhoneKeepsALongTitledPlayerReachable() =
        runDesktopComposeUiTest(width = 320, height = 568) {
            val state =
                radioFixture().let { fixture ->
                    fixture.copy(
                        episodes =
                            fixture.episodes.map { episode ->
                                if (episode.id == fixture.selectedId) {
                                    episode.copy(title = "A longer programme title that needs room. ".repeat(6))
                                } else {
                                    episode
                                }
                            },
                    )
                }
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                    NeutrodyneTheme(SystemUiState.DEFAULT) { RadioScreen(state, RadioActions()) }
                }
            }
            onNodeWithTag("radio_valve_deck").assertIsDisplayed()
            onNodeWithTag("radio_play").performScrollTo().assertIsDisplayed()
        }

    @Test fun aWideWindowKeepsTheDeck() =
        runDesktopComposeUiTest(width = 1200, height = 800) {
            setContent { NeutrodyneTheme(SystemUiState.DEFAULT) { RadioScreen(radioFixture(), RadioActions()) } }
            onNodeWithTag("radio_valve_deck").assertIsDisplayed()
            onNodeWithTag("radio_play").assertIsDisplayed()
        }

    @Test fun aShortWindowStowsTheDeck() =
        runDesktopComposeUiTest(width = 700, height = 360) {
            setContent { NeutrodyneTheme(SystemUiState.DEFAULT) { RadioScreen(radioFixture(), RadioActions()) } }
            onNodeWithTag("radio_valve_deck").assertDoesNotExist()
            onNodeWithTag("radio_play").assertIsDisplayed()
        }

    @Test fun resizeCancelsArmedSwipe() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            var width by mutableIntStateOf(390)
            var discards = 0
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    Box(Modifier.width(width.dp).fillMaxHeight()) {
                        RadioScreen(radioFixture(), RadioActions(discard = { discards++ }))
                    }
                }
            }
            onNodeWithTag("radio_episode_1").performTouchInput {
                down(center)
                moveBy(Offset(-100f, 0f))
            }
            runOnIdle { width = 320 }
            onNodeWithTag("radio_episode_1").performTouchInput { up() }
            assertEquals(0, discards)
        }

    // The deck and the cabinet are sized from the usable height; the transport must follow it,
    // not the raw window. The test window is 844 dp tall, but the screen is pinned to 772 dp
    // of content (844 dp with 72 dp of insets), which is the compact home.
    @Test fun theTransportFollowsTheUsableHeightNotTheWindow() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            val heading = getString(Res.string.radio_episodes).uppercase()
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    Box(Modifier.height(772.dp)) {
                        RadioScreen(radioFixture(), RadioActions())
                    }
                }
            }
            // The compact home is really on screen: its transport and programme are present.
            onNodeWithTag("radio_play").assertIsDisplayed()
            onNodeWithTag("radio_episodes").assertIsDisplayed()
            onNodeWithText(heading).assertDoesNotExist()
        }

    @Test fun focusedDeleteAndChooserEscapeRestoreFocus() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            var removed: Long? = null
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    RadioScreen(radioFixture(), RadioActions(discard = { removed = it }))
                }
            }
            onNodeWithTag("radio_episode_2").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            onNodeWithTag("radio_episode_2").performKeyInput { pressKey(Key.Delete) }
            assertEquals(2L, removed)
            onNodeWithTag("radio_playlist").performClick()
            onNodeWithTag("radio_playlist_option_1").assertIsFocused()
            onNodeWithTag("radio_playlist_option_1").performKeyInput { pressKey(Key.Escape) }
            onNodeWithTag("radio_insert").assertDoesNotExist()
            onNodeWithTag("radio_playlist").assertIsFocused()
        }
}

/** A window for the empty radio: its size in dp, its text scale and the listener's locale. */
private data class EmptyPaper(
    val width: Int,
    val height: Int,
    val fontScale: Float,
    val locale: Locale = Locale.ENGLISH,
)
