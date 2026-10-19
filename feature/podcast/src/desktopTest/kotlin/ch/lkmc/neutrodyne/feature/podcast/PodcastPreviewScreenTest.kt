// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.podcast

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.AlreadySubscribed
import ch.lkmc.neutrodyne.core.model.PreviewEpisode
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import ch.lkmc.neutrodyne.core.testing.testFeedPreview
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class PodcastPreviewScreenTest {
    private lateinit var locale: Locale

    @BeforeTest fun setup() {
        locale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        installFakeImageLoader()
    }

    @AfterTest fun cleanup() {
        Locale.setDefault(locale)
    }

    @Test
    fun previewShowsSummariesWithoutPlaybackAndRequiresSubscribeTap() =
        runComposeUiTest {
            var subscriptions = 0
            val preview =
                testFeedPreview().copy(
                    episodes = listOf(PreviewEpisode("Episode one", null, null, "Summary", null, false)),
                )
            setContent {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    PodcastPreviewScreen(PodcastPreviewState(loading = false, preview = preview), {
                    }, { subscriptions++ }, {}, {}, {}, {}, {})
                }
            }
            onNodeWithText("Episode one").performScrollTo().assertIsDisplayed()
            onNodeWithText("Subscribe to play").assertIsDisplayed()
            onNodeWithContentDescription("Play").assertDoesNotExist()
            onNodeWithContentDescription("Download").assertDoesNotExist()
            assertEquals(0, subscriptions)
            onNodeWithText("Subscribe").performScrollTo().performClick()
            assertEquals(1, subscriptions)
        }

    @Test
    fun softDuplicateAsksBeforeSubscribing() =
        runComposeUiTest {
            var subscriptions = 0
            val preview = testFeedPreview().copy(alreadySubscribed = AlreadySubscribed(3, false))
            setContent {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    PodcastPreviewScreen(PodcastPreviewState(loading = false, preview = preview), {
                    }, { subscriptions++ }, {}, {}, {}, {}, {})
                }
            }
            onNodeWithText("Subscribe").performScrollTo().performClick()
            assertEquals(0, subscriptions)
            onNodeWithText("Subscribe anyway?").assertIsDisplayed()
            onNodeWithText("Cancel").performClick()
            assertEquals(0, subscriptions)
        }
}
