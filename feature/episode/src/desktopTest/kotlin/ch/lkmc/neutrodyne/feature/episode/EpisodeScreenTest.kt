// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.episode

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.EpisodeDetail
import ch.lkmc.neutrodyne.core.model.ShowNoteBlock
import ch.lkmc.neutrodyne.core.model.ShowNoteSpan
import ch.lkmc.neutrodyne.core.model.ShowNotes
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import ch.lkmc.neutrodyne.core.testing.testEpisodeDetail
import ch.lkmc.neutrodyne.core.ui.EpisodeAction
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.LocalUiClock
import ch.lkmc.neutrodyne.core.ui.ShowNotesImageMode
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
import kotlin.test.assertTrue

/**
 * The episode detail screen through `runComposeUiTest` (08 Episode detail): the loading skeleton,
 * the "no longer available" branch, the header and action row dispatches, the overflow's
 * Go-to-podcast/Favourite/Copy-link items, the YouTube and offline variants, and the show-notes
 * wiring (link opens through `ExternalUrlOpener`, the TAP_TO_LOAD row reveals its image).
 * `installFakeImageLoader` keeps cover and note images off the network.
 */
@OptIn(ExperimentalTestApi::class)
class EpisodeScreenTest {
    private lateinit var previousLocale: Locale
    private val navigator = RecordingAppNavigator()
    private val openedUrls = mutableListOf<String>()
    private val clipboard = RecordingClipboard()

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
    fun loadingShowsSkeleton() =
        runComposeUiTest {
            setEpisode(EpisodeUiState())
            onNodeWithText("Loading episode…").assertIsDisplayed()
        }

    @Test
    fun goneEpisodeShowsNotFound() =
        runComposeUiTest {
            setEpisode(EpisodeUiState(loaded = true))
            onNodeWithText("This episode is no longer available").assertIsDisplayed()
        }

    @Test
    fun contentShowsHeaderMetaAndActions() =
        runComposeUiTest {
            setEpisode(loadedState())
            onNodeWithText("The Interview").assertIsDisplayed()
            onNodeWithText("The Show").assertIsDisplayed()
            onNode(hasText("45 min", substring = true)).assertIsDisplayed()
            onNodeWithText("Play").assertIsDisplayed()
            onNodeWithText("Download").assertIsDisplayed()
            onNodeWithText("Up next").assertIsDisplayed()
            onNodeWithText("Mark played").assertIsDisplayed()
        }

    @Test
    fun notesRenderAsBlocks() =
        runComposeUiTest {
            setEpisode(
                loadedState(
                    notes =
                        ShowNotes(
                            listOf(ShowNoteBlock.Paragraph(listOf(ShowNoteSpan.Text("Note body")))),
                        ),
                ),
            )
            scrollTo(hasText("Note body")).assertIsDisplayed()
        }

    @Test
    fun emptyNotesShowPlaceholder() =
        runComposeUiTest {
            setEpisode(loadedState())
            onNodeWithText("No show notes").assertIsDisplayed()
        }

    @Test
    fun backPopsTheNavigator() =
        runComposeUiTest {
            setEpisode(loadedState())
            onNodeWithContentDescription("Back").performClick()
            assertEquals(listOf<RecordingAppNavigator.Call>(RecordingAppNavigator.Call.Pop), navigator.calls)
        }

    @Test
    fun podcastLineOpensThePodcast() =
        runComposeUiTest {
            val actions = mutableListOf<EpisodeAction>()
            setEpisode(loadedState(), onAction = { actions += it })
            onNodeWithText("The Show").performClick()
            assertEquals(listOf<EpisodeAction>(EpisodeAction.OpenPodcast(9, 7)), actions)
        }

    @Test
    fun playButtonDispatchesToggle() =
        runComposeUiTest {
            val actions = mutableListOf<EpisodeAction>()
            setEpisode(loadedState(), onAction = { actions += it })
            onNodeWithText("Play").performClick()
            assertEquals(listOf<EpisodeAction>(EpisodeAction.PlayToggle(9)), actions)
        }

    @Test
    fun playedEpisodeOffersPlayAgainAndUnplayed() =
        runComposeUiTest {
            val actions = mutableListOf<EpisodeAction>()
            setEpisode(
                loadedState(episode = testEpisodeDetail(9, podcastId = 7, playedAt = TestClock.DEFAULT_NOW)),
                onAction = { actions += it },
            )
            onNodeWithText("Play again").assertIsDisplayed()
            onNodeWithText("Mark unplayed").performClick()
            assertEquals(listOf<EpisodeAction>(EpisodeAction.SetPlayed(9, played = false)), actions)
        }

    @Test
    fun downloadButtonDispatchesToggle() =
        runComposeUiTest {
            val actions = mutableListOf<EpisodeAction>()
            setEpisode(loadedState(), onAction = { actions += it })
            onNodeWithText("Download").performClick()
            assertEquals(listOf<EpisodeAction>(EpisodeAction.DownloadToggle(9)), actions)
        }

    @Test
    fun upNextMenuDispatchesQueueActions() =
        runComposeUiTest {
            val actions = mutableListOf<EpisodeAction>()
            setEpisode(loadedState(), onAction = { actions += it })
            onNodeWithText("Up next").performClick()
            onNodeWithText("Play next").assertIsDisplayed()
            onNodeWithText("Play last").performClick()
            assertEquals(listOf<EpisodeAction>(EpisodeAction.PlayLast(9)), actions)
        }

    @Test
    fun offlineUndownloadedShowsOfflineButton() =
        runComposeUiTest {
            val actions = mutableListOf<EpisodeAction>()
            setEpisode(loadedState(offline = true), onAction = { actions += it })
            onNodeWithText("You're offline — downloaded episodes still play").assertIsDisplayed()
            // The primary button becomes the inert "Offline" affordance (08's offline state).
            onNodeWithText("Offline").performClick()
            assertTrue(actions.isEmpty())
        }

    @Test
    fun offlineDownloadedKeepsPlayable() =
        runComposeUiTest {
            val actions = mutableListOf<EpisodeAction>()
            setEpisode(
                loadedState(
                    offline = true,
                    episode = testEpisodeDetail(9, podcastId = 7, downloadState = DownloadState.COMPLETED),
                ),
                onAction = { actions += it },
            )
            onNodeWithText("Play").performClick()
            assertEquals(listOf<EpisodeAction>(EpisodeAction.PlayToggle(9)), actions)
        }

    @Test
    fun youTubeEpisodeOffersWatchOnYouTube() =
        runComposeUiTest {
            val actions = mutableListOf<EpisodeAction>()
            setEpisode(
                loadedState(
                    episode =
                        testEpisodeDetail(
                            9,
                            podcastId = 7,
                            sourceType = SourceType.YOUTUBE_CHANNEL,
                            externalMediaId = "abc123",
                        ),
                ),
                onAction = { actions += it },
            )
            onNodeWithText("Opens in YouTube").assertIsDisplayed()
            onNodeWithText("Watch on YouTube").performClick()
            assertEquals(listOf<EpisodeAction>(EpisodeAction.WatchOnYouTube(9, "abc123")), actions)
        }

    @Test
    fun overflowGoToPodcastDispatches() =
        runComposeUiTest {
            val actions = mutableListOf<EpisodeAction>()
            setEpisode(loadedState(), onAction = { actions += it })
            onNodeWithContentDescription("More actions").performClick()
            onNodeWithText("Go to podcast").performClick()
            assertEquals(listOf<EpisodeAction>(EpisodeAction.OpenPodcast(9, 7)), actions)
        }

    @Test
    fun overflowFavouriteReportsFlag() =
        runComposeUiTest {
            var favorite: Boolean? = null
            setEpisode(loadedState(), onFavorite = { favorite = it })
            onNodeWithContentDescription("More actions").performClick()
            onNodeWithText("Favourite").performClick()
            assertEquals(true, favorite)
        }

    @Test
    fun overflowOpenWebsiteOpensTheLink() =
        runComposeUiTest {
            setEpisode(loadedState())
            onNodeWithContentDescription("More actions").performClick()
            onNodeWithText("Open website").performClick()
            assertEquals(listOf("https://example.com/ep-9"), openedUrls)
        }

    @Test
    fun overflowCopyLinkWritesTheClipboard() =
        runComposeUiTest {
            setEpisode(loadedState())
            onNodeWithContentDescription("More actions").performClick()
            onNodeWithText("Copy link").performClick()
            assertEquals("https://example.com/ep-9", clipboard.copied?.text)
        }

    @Test
    fun notesLinkOpensThroughTheUrlOpener() =
        runComposeUiTest {
            setEpisode(
                loadedState(
                    notes =
                        ShowNotes(
                            listOf(
                                ShowNoteBlock.Paragraph(
                                    listOf(ShowNoteSpan.Link("the site", "https://example.com/site")),
                                ),
                            ),
                        ),
                ),
            )
            scrollTo(hasText("the site")).performClick()
            assertEquals(listOf("https://example.com/site"), openedUrls)
        }

    @Test
    fun tapToLoadImageRowRevealsTheImage() =
        runComposeUiTest {
            setEpisode(
                loadedState(
                    imageMode = ShowNotesImageMode.TAP_TO_LOAD,
                    notes =
                        ShowNotes(
                            listOf(
                                ShowNoteBlock.Image("https://example.com/pic.png", alt = "diagram"),
                            ),
                        ),
                ),
            )
            scrollTo(hasText("Image: diagram")).performClick()
            // The reveal is screen-local state: the row swaps to the real image (alt as
            // contentDescription) without calling back out.
            onNodeWithContentDescription("diagram").assertIsDisplayed()
        }

    @Test
    fun wifiOnlyOnMeteredRowCannotBeTapped() =
        runComposeUiTest {
            setEpisode(
                loadedState(
                    imageMode = ShowNotesImageMode.BLOCKED,
                    notes =
                        ShowNotes(
                            listOf(
                                ShowNoteBlock.Image("https://example.com/pic.png", alt = "diagram"),
                            ),
                        ),
                ),
            )
            // Wi-Fi-only on a metered link offers no tap escape (03): the placeholder row has no
            // click action, so no tap can flip the screen's images to SHOWN.
            scrollTo(hasText("Image: diagram")).assertIsDisplayed()
            onAllNodes(hasText("Image: diagram") and hasClickAction()).assertCountEquals(0)
            onNodeWithContentDescription("diagram").assertDoesNotExist()
        }

    private fun loadedState(
        episode: EpisodeDetail =
            testEpisodeDetail(9, podcastId = 7, title = "The Interview", podcastTitle = "The Show"),
        notes: ShowNotes? = null,
        offline: Boolean = false,
        imageMode: ShowNotesImageMode = ShowNotesImageMode.SHOWN,
    ) = EpisodeUiState(
        episode = episode,
        notes = notes,
        loaded = true,
        offline = offline,
        imageMode = imageMode,
    )

    private fun ComposeUiTest.setEpisode(
        state: EpisodeUiState,
        onAction: (EpisodeAction) -> Unit = {},
        onFavorite: (Boolean) -> Unit = {},
    ) {
        setContent {
            CompositionLocalProvider(
                LocalPlatformKind provides PlatformKind.DESKTOP,
                LocalUiClock provides TestClock(),
                LocalAppNavigator provides navigator,
                LocalPlatformActions provides platformActions,
                LocalClipboardManager provides clipboard,
            ) {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    EpisodeScreen(state = state, onAction = onAction, onFavorite = onFavorite)
                }
            }
        }
        waitForIdle()
    }

    /** Scrolls the screen's lazy column until [matcher]'s node exists, then answers it. */
    private fun ComposeUiTest.scrollTo(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(matcher)
        waitForIdle()
        return onNode(matcher)
    }

    private val platformActions =
        object : PlatformActions {
            override val urls: ExternalUrlOpener =
                ExternalUrlOpener { url ->
                    openedUrls += url
                    OpenResult.OPENED
                }
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

    /** The screen's copy-link sink — the real desktop clipboard is the OS's, so test-local. */
    @Suppress("DEPRECATION")
    private class RecordingClipboard : ClipboardManager {
        var copied: AnnotatedString? = null

        override fun getText(): AnnotatedString? = copied

        override fun setText(text: AnnotatedString) {
            copied = text
        }
    }
}
