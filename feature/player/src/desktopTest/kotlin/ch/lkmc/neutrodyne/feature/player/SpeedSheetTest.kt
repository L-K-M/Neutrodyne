// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.Effective
import ch.lkmc.neutrodyne.core.model.EffectivePlayback
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.model.PlayContextInfo
import ch.lkmc.neutrodyne.core.model.SettingScope
import ch.lkmc.neutrodyne.core.model.SettingSource
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.playback.api.NowPlaying
import ch.lkmc.neutrodyne.playback.api.PlayerPhase
import ch.lkmc.neutrodyne.playback.api.PositionSnapshot
import ch.lkmc.neutrodyne.playback.api.StreamKind
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The speed sheet through `runComposeUiTest` (08 Speed and sleep sheets): preset chips and the
 * slider write at the source-derived scope, a wider pick that would drop a narrower override is
 * confirmed first, the group segment only lives while a group context plays, and the presets
 * dialog validates 2–8 speeds before its Save enables.
 */
@OptIn(ExperimentalTestApi::class)
class SpeedSheetTest {
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
    fun nothingPlayingShowsFallbackAndDismisses() =
        runComposeUiTest {
            val calls = Calls()
            setSheet(calls, now = null, effective = null)
            waitForIdle()
            onNodeWithText("Nothing playing").assertIsDisplayed()
            assertEquals(1, calls.dismisses)
        }

    @Test
    fun chipWritesAtSourceDefaultScope() =
        runComposeUiTest {
            val calls = Calls()
            setSheet(
                calls,
                testNowPlaying(),
                testEffective(speed = Effective(1.2f, SettingSource.PodcastOverride)),
            )
            waitForIdle()
            onNodeWithText("This podcast").assertIsSelected()
            onNodeWithText("1.2").assertIsSelected()
            onNodeWithText("1.5").performClick()
            assertEquals(listOf(1.5f to SettingScope.PODCAST), calls.speed)
        }

    @Test
    fun sliderCommitsOnRelease() =
        runComposeUiTest {
            val calls = Calls()
            setSheet(calls, testNowPlaying(), testEffective())
            waitForIdle()
            onNodeWithContentDescription("Playback speed")
                .performSemanticsAction(SemanticsActions.SetProgress) { it(2.0f) }
            waitForIdle()
            assertEquals(listOf(2.0f to SettingScope.GLOBAL), calls.speed)
            onNodeWithText("2.00×").assertIsDisplayed()
        }

    @Test
    fun stepButtonsWriteNormalizedSpeeds() =
        runComposeUiTest {
            val calls = Calls()
            setSheet(calls, testNowPlaying(), testEffective(speed = Effective(1.0f, SettingSource.AppDefault)))
            waitForIdle()
            onNodeWithText("+").performClick()
            onNodeWithText("+").performClick()
            onNodeWithText("−").performClick()
            assertEquals(
                listOf(
                    1.05f to SettingScope.GLOBAL,
                    1.1f to SettingScope.GLOBAL,
                    1.05f to SettingScope.GLOBAL,
                ),
                calls.speed,
            )
        }

    @Test
    fun switchWritesSkipSilence() =
        runComposeUiTest {
            val calls = Calls()
            setSheet(
                calls,
                testNowPlaying(),
                testEffective(
                    speed = Effective(1.0f, SettingSource.PodcastOverride),
                    silence = Effective(false, SettingSource.PodcastOverride),
                ),
            )
            waitForIdle()
            onNodeWithContentDescription("Skip silence").performClick()
            assertEquals(listOf(true to SettingScope.PODCAST), calls.silence)
        }

    @Test
    fun widerScopeConfirmsBeforeSpeedWrite() =
        runComposeUiTest {
            val calls = Calls()
            setSheet(
                calls,
                testNowPlaying(),
                testEffective(speed = Effective(1.2f, SettingSource.PodcastOverride)),
            )
            waitForIdle()
            onNodeWithText("All").performClick()
            onNodeWithText("All").assertIsSelected()
            onNodeWithText("1.5").performClick()
            onNodeWithText("Apply to a wider scope?").assertIsDisplayed()
            onNodeWithText("This also removes this podcast's own speed (1.20×).").assertIsDisplayed()
            assertTrue(calls.speed.isEmpty())
            onNodeWithText("Apply").performClick()
            assertEquals(listOf(1.5f to SettingScope.GLOBAL), calls.speed)
            onNodeWithText("Apply to a wider scope?").assertDoesNotExist()
        }

    @Test
    fun globalWriteConfirmsGroupSilenceOverride() =
        runComposeUiTest {
            val calls = Calls()
            setSheet(
                calls,
                testNowPlaying(groupContext()),
                testEffective(
                    speed = Effective(1.0f, SettingSource.AppDefault),
                    silence = Effective(false, SettingSource.Group("u1", "news")),
                ),
            )
            waitForIdle()
            onNodeWithText("All").assertIsSelected()
            onNodeWithContentDescription("Skip silence").performClick()
            onNodeWithText("This also removes the skip silence setting of group 'news'.").assertIsDisplayed()
            onNodeWithText("Cancel").performClick()
            assertTrue(calls.silence.isEmpty())
            onNodeWithContentDescription("Skip silence").performClick()
            onNodeWithText("Apply").performClick()
            assertEquals(listOf(true to SettingScope.GLOBAL), calls.silence)
        }

    @Test
    fun groupScopeResetsWhenGroupContextIsLost() =
        runComposeUiTest {
            val calls = Calls()
            val now = mutableStateOf(testNowPlaying(groupContext()))
            setContent {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    SpeedSheet(
                        now = now.value,
                        effective = testEffective(speed = Effective(1.5f, SettingSource.Group("u1", "news"))),
                        presets = PRESETS,
                        onSpeed = { v, s -> calls.speed += v to s },
                        onSkipSilence = { v, s -> calls.silence += v to s },
                        onPresets = { calls.presets += it },
                        onDismiss = { calls.dismisses++ },
                    )
                }
            }
            waitForIdle()
            onNodeWithText("Group 'news'").assertIsSelected()

            runOnIdle { now.value = testNowPlaying() }
            waitForIdle()
            onNodeWithText("This podcast").assertIsSelected()
            onNodeWithText("Only when playing from a group").assertIsDisplayed()
            onNodeWithText("Group").assertIsNotEnabled()
        }

    @Test
    fun presetsDialogValidatesBeforeSave() =
        runComposeUiTest {
            val calls = Calls()
            setSheet(calls, testNowPlaying(), testEffective())
            waitForIdle()
            onNodeWithText("Edit presets").performClick()
            onNodeWithText("Speed presets").assertIsDisplayed()
            val field = onAllNodes(hasSetTextAction()).onFirst()

            field.performTextReplacement("junk")
            onNodeWithText("Enter 2–8 speeds from 0.5 to 3.0 in steps of 0.05").assertIsDisplayed()
            onNodeWithText("Save").assertIsNotEnabled()
            assertTrue(calls.presets.isEmpty())

            field.performTextReplacement("0.9,1.4")
            onNodeWithText("Save").assertIsEnabled().performClick()
            assertEquals(listOf("0.9,1.4"), calls.presets)
            onNodeWithText("Speed presets").assertDoesNotExist()
        }

    @Test
    fun eachFieldShowsItsOwnAttribution() =
        runComposeUiTest {
            val calls = Calls()
            setSheet(
                calls,
                testNowPlaying(groupContext()),
                testEffective(
                    speed = Effective(1.2f, SettingSource.PodcastOverride),
                    silence = Effective(true, SettingSource.Group("u1", "news")),
                ),
            )
            waitForIdle()
            onNodeWithText("Set for this podcast").assertIsDisplayed()
            onNodeWithText("From group 'news'").assertIsDisplayed()
            onNodeWithContentDescription("Decrease speed").assertIsDisplayed()
            onNodeWithContentDescription("Increase speed").assertIsDisplayed()
        }

    @Test
    fun germanStringsResolve() =
        runComposeUiTest {
            Locale.setDefault(Locale.GERMAN)
            val calls = Calls()
            setSheet(calls, testNowPlaying(), testEffective())
            waitForIdle()
            onNodeWithText("Wiedergabegeschwindigkeit").assertIsDisplayed()
            onNodeWithText("Stille überspringen").assertIsDisplayed()
            onNodeWithContentDescription("Geschwindigkeit verringern").assertIsDisplayed()
        }

    private fun ComposeUiTest.setSheet(
        calls: Calls,
        now: NowPlaying?,
        effective: EffectivePlayback?,
        presets: String = PRESETS,
    ) {
        setContent {
            NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                SpeedSheet(
                    now = now,
                    effective = effective,
                    presets = presets,
                    onSpeed = { v, s -> calls.speed += v to s },
                    onSkipSilence = { v, s -> calls.silence += v to s },
                    onPresets = { calls.presets += it },
                    onDismiss = { calls.dismisses++ },
                )
            }
        }
    }

    private class Calls {
        val speed = mutableListOf<Pair<Float, SettingScope>>()
        val silence = mutableListOf<Pair<Boolean, SettingScope>>()
        val presets = mutableListOf<String>()
        var dismisses = 0
    }

    private companion object {
        const val PRESETS = "0.8,1.0,1.2,1.5,1.8,2.0"

        fun testNowPlaying(context: PlayContextInfo? = null): NowPlaying =
            NowPlaying(
                episodeId = 11,
                podcastId = 7,
                title = "Episode",
                podcastTitle = "Show",
                artwork = ArtworkRef("art", null, 0),
                sourceType = SourceType.RSS,
                isVideo = false,
                phase = PlayerPhase.READY,
                isPlaying = true,
                playWhenReady = true,
                position = PositionSnapshot(11, 0, 0, 60_000, 1f, true, 0),
                hasNext = false,
                stream = StreamKind.LOCAL,
                issue = null,
                context = context,
            )

        fun groupContext(): PlayContextInfo =
            PlayContextInfo(ContextType.GROUP, 3, "news", FeedOrder.NEWEST_FIRST, 0, MediaFilter.ALL)

        fun testEffective(
            speed: Effective<Float> = Effective(1.0f, SettingSource.AppDefault),
            silence: Effective<Boolean> = Effective(false, SettingSource.AppDefault),
        ): EffectivePlayback = EffectivePlayback(speed, silence)
    }
}
