// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.podcast

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import ch.lkmc.neutrodyne.core.testing.testFeedInfo
import ch.lkmc.neutrodyne.core.testing.testPodcastDetail
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.LocalUiClock
import ch.lkmc.neutrodyne.core.ui.platform.ExternalUrlOpener
import ch.lkmc.neutrodyne.core.ui.platform.FilePicker
import ch.lkmc.neutrodyne.core.ui.platform.FileSaver
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.OpenResult
import ch.lkmc.neutrodyne.core.ui.platform.PlatformActions
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Podcast settings through `runComposeUiTest` (08 Podcast settings): the General and Feed rows,
 * the custom-title / episode-order / edit-address / credentials dialogs, and the private feed's
 * reveal warning. Writes are asserted as the callbacks the route forwards to the ViewModel.
 */
@OptIn(ExperimentalTestApi::class)
class PodcastSettingsScreenTest {
    private lateinit var previousLocale: Locale
    private val navigator = RecordingAppNavigator()

    @BeforeTest
    fun setUp() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        installFakeImageLoader()
    }

    @AfterTest
    fun tearDown() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun loadingShowsSpinner() =
        runComposeUiTest {
            setSettings(PodcastSettingsUiState())
            onNodeWithText("Loading podcast…").assertIsDisplayed()
        }

    @Test
    fun showsGeneralAndFeedSections() =
        runComposeUiTest {
            setSettings(
                PodcastSettingsUiState(
                    detail = testPodcastDetail(7, displayTitle = "The Show"),
                    feedInfo =
                        testFeedInfo(
                            feedUrl = "https://feeds.example.com/show.xml",
                            redactedUrl = "https://feeds.…/show.xml",
                        ),
                    loaded = true,
                ),
            )
            onNodeWithText("Podcast settings · The Show").assertIsDisplayed()
            onNodeWithText("Custom title").assertIsDisplayed()
            onNodeWithText("Episode order").assertIsDisplayed()
            onNodeWithText("Feed address").assertIsDisplayed()
            // The address stays redacted until tapped.
            onNodeWithText("https://feeds.…/show.xml · Tap to show").assertIsDisplayed()
            onNodeWithText("https://feeds.example.com/show.xml").assertDoesNotExist()
        }

    @Test
    fun feedAddressRevealsOnTap() =
        runComposeUiTest {
            setSettings(
                PodcastSettingsUiState(
                    detail = testPodcastDetail(7),
                    feedInfo =
                        testFeedInfo(
                            feedUrl = "https://feeds.example.com/show.xml",
                            redactedUrl = "https://feeds.…/show.xml",
                        ),
                    loaded = true,
                ),
            )
            onNodeWithText("Feed address").performClick()
            onNodeWithText("https://feeds.example.com/show.xml").assertIsDisplayed()
        }

    @Test
    fun privateFeedAddressWarnsBeforeReveal() =
        runComposeUiTest {
            setSettings(
                PodcastSettingsUiState(
                    detail = testPodcastDetail(7),
                    feedInfo =
                        testFeedInfo(
                            feedUrl = "https://secret.example.com/feed.xml",
                            redactedUrl = "https://secret.…/feed.xml",
                            isPrivate = true,
                        ),
                    loaded = true,
                ),
            )
            onNodeWithText("Feed address").performClick()
            // The private-feed warning gates the reveal.
            onAllNodes(hasClickAction() and hasText("Tap to show")).onFirst().performClick()
            onNodeWithText("https://secret.example.com/feed.xml").assertIsDisplayed()
        }

    @Test
    fun customTitleDialogWrites() =
        runComposeUiTest {
            var title: String? = "unset"
            setSettings(
                PodcastSettingsUiState(detail = testPodcastDetail(7), loaded = true),
                onCustomTitle = { title = it },
            )
            onNodeWithText("Custom title").performClick()
            onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("Renamed show")
            onAllNodes(hasClickAction() and hasText("Save")).onFirst().performClick()
            assertEquals("Renamed show", title)
        }

    @Test
    fun blankCustomTitleClears() =
        runComposeUiTest {
            var title: String? = "unset"
            setSettings(
                PodcastSettingsUiState(detail = testPodcastDetail(7), loaded = true),
                onCustomTitle = { title = it },
            )
            onNodeWithText("Custom title").performClick()
            onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("   ")
            onAllNodes(hasClickAction() and hasText("Save")).onFirst().performClick()
            assertEquals(null, title)
        }

    @Test
    fun orderDialogWrites() =
        runComposeUiTest {
            var order: FeedOrder? = null
            setSettings(
                PodcastSettingsUiState(detail = testPodcastDetail(7), loaded = true),
                onOrderChange = { order = it },
            )
            onNodeWithText("Episode order").performClick()
            onNodeWithText("Oldest first").performClick()
            assertEquals(FeedOrder.OLDEST_FIRST, order)
        }

    @Test
    fun m1bFeedAccountControlsHidden() =
        runComposeUiTest {
            setSettings(
                PodcastSettingsUiState(
                    detail = testPodcastDetail(7),
                    feedInfo = testFeedInfo(),
                    loaded = true,
                ),
            )
            // The edit-address and credentials writes are M1b's: their repository methods still
            // throw, so the rows stay hidden until `FEED_ACCOUNT_CONTROLS_ENABLED` flips.
            onNodeWithText("Edit feed address").assertDoesNotExist()
            onNodeWithText("Username and password").assertDoesNotExist()
        }

    @Test
    fun youtubeFeedHidesEditAddress() =
        runComposeUiTest {
            setSettings(
                PodcastSettingsUiState(
                    detail = testPodcastDetail(7, sourceType = SourceType.YOUTUBE_CHANNEL),
                    feedInfo = testFeedInfo(),
                    loaded = true,
                ),
            )
            onNodeWithText("Edit feed address").assertDoesNotExist()
            onNodeWithText("Username and password").assertDoesNotExist()
        }

    private fun ComposeUiTest.setSettings(
        state: PodcastSettingsUiState,
        onCustomTitle: (String?) -> Unit = {},
        onOrderChange: (FeedOrder) -> Unit = {},
        onEditFeedUrl: (String) -> Unit = {},
        onCredentials: (BasicCredentials) -> Unit = {},
    ) {
        setContent {
            CompositionLocalProvider(
                LocalPlatformKind provides PlatformKind.DESKTOP,
                LocalUiClock provides TestClock(),
                LocalAppNavigator provides navigator,
                LocalPlatformActions provides TestPlatformActions,
            ) {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    PodcastSettingsScreen(
                        state = state,
                        onCustomTitle = onCustomTitle,
                        onOrderChange = onOrderChange,
                        onEditFeedUrl = onEditFeedUrl,
                        onCredentials = onCredentials,
                    )
                }
            }
        }
        waitForIdle()
    }
}

/** The shells' `PlatformActions` contract on the desktop test composition. */
private val TestPlatformActions =
    object : PlatformActions {
        override val urls: ExternalUrlOpener = ExternalUrlOpener { OpenResult.OPENED }
        override val share = null
        override val files: FilePicker =
            object : FilePicker {
                override suspend fun pickFile(
                    mimeTypes: List<String>,
                    extensions: List<String>,
                ): String? = null

                override suspend fun pickFolder(title: String): String? = null
            }
        override val saver: FileSaver = FileSaver { _, _ -> null }
        override val reveal = null
        override val notifications = null
    }
