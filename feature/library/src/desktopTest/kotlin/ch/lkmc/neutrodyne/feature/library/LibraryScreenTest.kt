// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.library

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.common.TitleCollator
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.domain.UnsubscribeUseCase
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.LibraryTile
import ch.lkmc.neutrodyne.core.model.settings.LibrarySort
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.testing.FakeEpisodeRepository
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakePodcastRepository
import ch.lkmc.neutrodyne.core.testing.FakeRefreshController
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import ch.lkmc.neutrodyne.core.testing.testCoverArtwork
import ch.lkmc.neutrodyne.core.testing.testLibraryTile
import ch.lkmc.neutrodyne.core.testing.testMonogramArtwork
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.LocalUiClock
import ch.lkmc.neutrodyne.core.ui.platform.ExternalUrlOpener
import ch.lkmc.neutrodyne.core.ui.platform.FilePicker
import ch.lkmc.neutrodyne.core.ui.platform.FileSaver
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.OpenResult
import ch.lkmc.neutrodyne.core.ui.platform.PlatformActions
import kotlinx.collections.immutable.toImmutableList
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Library grid through `runComposeUiTest` (08 Library, AC5/AC10): the skeleton, onboarding,
 * offline banner, sort menu and title toggle, the unsubscribe confirmation, and a 20-podcast
 * grid that mixes `u-` covers and `m-` monograms — every tile carrying its title as content
 * description and reachable by scroll.
 *
 * `installFakeImageLoader` keeps the `u-` tiles off the network; `v2.runComposeUiTest` gives the
 * test a `StandardTestDispatcher`.
 */
@OptIn(ExperimentalTestApi::class)
class LibraryScreenTest {
    private lateinit var previousLocale: Locale
    private val navigator = RecordingAppNavigator()

    // The route tests drive the real ViewModel so `episodes.calls` proves the write path.
    private val podcasts = FakePodcastRepository()
    private val episodes = FakeEpisodeRepository()
    private val refreshController = FakeRefreshController()
    private val settings = FakeSettingsRepository()
    private val network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE)

    /** Case-insensitive ordering so the sort assertions are locale-free and deterministic. */
    private val collator =
        object : TitleCollator {
            override fun compare(
                a: String,
                b: String,
            ): Int = a.compareTo(b, ignoreCase = true)
        }

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
    fun loadingShowsSkeletonsNotEmptyState() =
        runComposeUiTest {
            setLibrary(LibraryUiState(loaded = false))
            onNodeWithText("Library").assertIsDisplayed()
            onNodeWithText("Your library is empty").assertDoesNotExist()
        }

    @Test
    fun emptyLibraryShowsOnboarding() =
        runComposeUiTest {
            var added = false
            setLibrary(LibraryUiState(loaded = true), onAddPodcast = { added = true })
            onNodeWithText("Your library is empty").assertIsDisplayed()
            onNodeWithText("Add a podcast").performClick()
            assertEquals(true, added)
        }

    @Test
    fun twentyTilesAllReachableByTitle() =
        runComposeUiTest {
            // AC5/AC10's shape: 20 podcasts, Coil-loaded covers and painted monograms mixed.
            val tiles =
                (1..20).map { i ->
                    testLibraryTile(
                        podcastId = i.toLong(),
                        displayTitle = "Show %02d".format(i),
                        artwork =
                            if (i % 2 == 0) {
                                testCoverArtwork("https://example.com/art-$i.jpg")
                            } else {
                                testMonogramArtwork("mono-$i")
                            },
                    )
                }
            setLibrary(LibraryUiState(tiles = tiles.toImmutableList(), loaded = true))

            val grid = onAllNodes(hasScrollToIndexAction()).onFirst()
            for (i in 1..20) {
                val title = "Show %02d".format(i)
                // Hidden titles land in the tile's content description ("{title}, n unplayed").
                grid.performScrollToNode(hasContentDescription(title))
                onNodeWithContentDescription(title).assertIsDisplayed()
            }
        }

    @Test
    fun shownTitlesRenderAsText() =
        runComposeUiTest {
            val tiles =
                (1..6).map { i ->
                    testLibraryTile(podcastId = i.toLong(), displayTitle = "Show %02d".format(i))
                }
            setLibrary(LibraryUiState(tiles = tiles.toImmutableList(), loaded = true, showTitles = true))
            onNodeWithText("Show 01").assertIsDisplayed()
        }

    @Test
    fun shownTitleMergesIntoTheClickableTile() =
        runComposeUiTest {
            var opened: Long? = null
            setLibrary(
                LibraryUiState(
                    tiles = listOf(testLibraryTile(5, displayTitle = "Tap Show")).toImmutableList(),
                    loaded = true,
                    showTitles = true,
                ),
                onOpenPodcast = { opened = it },
            )
            // 08: with titles shown the cover is decorative and the merged tile reads the title
            // text — one node carrying both the title and the click action, not a label sibling.
            onAllNodes(hasText("Tap Show")).assertCountEquals(1)
            onAllNodes(hasText("Tap Show") and hasClickAction())
                .assertCountEquals(1)
                .onFirst()
                .performClick()
            assertEquals(5L, opened)
        }

    @Test
    fun hiddenTitleKeepsTheDescriptionOnTheTile() =
        runComposeUiTest {
            var opened: Long? = null
            setLibrary(
                LibraryUiState(
                    tiles = listOf(testLibraryTile(5, displayTitle = "Tap Show")).toImmutableList(),
                    loaded = true,
                    showTitles = false,
                ),
                onOpenPodcast = { opened = it },
            )
            // Hidden titles land in the tile's content description ("{title}, n unplayed").
            onAllNodes(hasContentDescription("Tap Show") and hasClickAction())
                .assertCountEquals(1)
                .onFirst()
                .performClick()
            assertEquals(5L, opened)
        }

    @Test
    fun tileClickOpensPodcast() =
        runComposeUiTest {
            var opened: Long? = null
            setLibrary(
                LibraryUiState(
                    tiles = listOf(testLibraryTile(5, displayTitle = "Tap Show")).toImmutableList(),
                    loaded = true,
                ),
                onOpenPodcast = { opened = it },
            )
            onNodeWithContentDescription("Tap Show").performClick()
            assertEquals(5L, opened)
        }

    @Test
    fun tileMenuActionsAreCustomAccessibilityActions() =
        runComposeUiTest {
            val calls = mutableListOf<Pair<Long, TileAction>>()
            setLibrary(
                LibraryUiState(
                    tiles = listOf(testLibraryTile(5, displayTitle = "Tap Show")).toImmutableList(),
                    loaded = true,
                ),
                onTileAction = { id, action -> calls += id to action },
            )
            val tile = onNodeWithContentDescription("Tap Show")
            tile.performCustomAccessibilityActionWithLabel("Podcast settings")
            tile.performCustomAccessibilityActionWithLabel("Refresh")
            tile.performCustomAccessibilityActionWithLabel("Mark all as played…")
            tile.performCustomAccessibilityActionWithLabel("Unsubscribe")
            assertEquals(
                listOf(
                    5L to TileAction.SETTINGS,
                    5L to TileAction.REFRESH,
                    5L to TileAction.MARK_PLAYED,
                    5L to TileAction.UNSUBSCRIBE,
                ),
                calls,
            )
        }

    @Test
    fun sortMenuDispatchesChosenMode() =
        runComposeUiTest {
            var sort: LibrarySort? = null
            setLibrary(LibraryUiState(loaded = true), onSort = { sort = it })
            onNodeWithContentDescription("Sort").performClick()
            onNodeWithText("Most unplayed").performClick()
            assertEquals(LibrarySort.MOST_UNPLAYED, sort)
        }

    @Test
    fun overflowTogglesTitles() =
        runComposeUiTest {
            var showTitles: Boolean? = null
            setLibrary(
                LibraryUiState(loaded = true, showTitles = false),
                onToggleTitles = { showTitles = it },
            )
            onNodeWithContentDescription("More actions").performClick()
            onNodeWithText("Show titles").performClick()
            assertEquals(true, showTitles)
        }

    @Test
    fun unsubscribeDialogCountsDownloads() =
        runComposeUiTest {
            val tile = testLibraryTile(5, displayTitle = "Tap Show")
            var confirmed: LibraryTile? = null
            var dismissed = false
            setLibrary(
                LibraryUiState(tiles = listOf(tile).toImmutableList(), loaded = true),
                pendingUnsubscribe = PendingUnsubscribe(tile, downloads = 3),
                onConfirmUnsubscribe = { confirmed = it },
                onDismissUnsubscribe = { dismissed = true },
            )
            onNodeWithText("Unsubscribe from Tap Show?").assertIsDisplayed()
            onNodeWithText("3 downloaded episodes will be deleted.").assertIsDisplayed()

            onNodeWithText("Cancel").performClick()
            assertEquals(true, dismissed)
            assertEquals(null, confirmed)
        }

    @Test
    fun unsubscribeDialogConfirms() =
        runComposeUiTest {
            val tile = testLibraryTile(5, displayTitle = "Tap Show")
            var confirmed: LibraryTile? = null
            setLibrary(
                LibraryUiState(tiles = listOf(tile).toImmutableList(), loaded = true),
                pendingUnsubscribe = PendingUnsubscribe(tile, downloads = 1),
                onConfirmUnsubscribe = { confirmed = it },
            )
            onNodeWithText("1 downloaded episode will be deleted.").assertIsDisplayed()
            onAllNodes(hasText("Unsubscribe") and hasClickAction()).onFirst().performClick()
            assertEquals(tile, confirmed)
        }

    @Test
    fun markAllPlayedConfirmsBeforeWriting() =
        runComposeUiTest {
            // 08's required confirmation: the tile action must not write until confirmed.
            setLibraryRoute()

            onNodeWithContentDescription("Tap Show")
                .performCustomAccessibilityActionWithLabel("Mark all as played…")
            waitForIdle()

            // The dialog's title and its confirm button both read "Mark all as played".
            onAllNodes(hasText("Mark all as played")).assertCountEquals(2)
            assertEquals(emptyList(), episodes.calls)

            onAllNodes(hasText("Mark all as played") and hasClickAction()).onFirst().performClick()
            waitForIdle()
            assertEquals(
                listOf("markFeedPlayed(${FeedSource.Podcast(5)}, null)"),
                episodes.calls,
            )
        }

    @Test
    fun markAllPlayedCancelWritesNothing() =
        runComposeUiTest {
            setLibraryRoute()

            onNodeWithContentDescription("Tap Show")
                .performCustomAccessibilityActionWithLabel("Mark all as played…")
            waitForIdle()

            onNodeWithText("Cancel").performClick()
            waitForIdle()
            assertEquals(emptyList(), episodes.calls)
            onNodeWithText("Mark all as played").assertDoesNotExist()
        }

    @Test
    fun offlineBannerShowsWhenOffline() =
        runComposeUiTest {
            setLibrary(LibraryUiState(loaded = true, offline = true))
            onNodeWithText("You're offline — downloaded episodes still play").assertIsDisplayed()
        }

    private fun ComposeUiTest.setLibrary(
        state: LibraryUiState,
        pendingUnsubscribe: PendingUnsubscribe? = null,
        pendingMarkPlayed: Long? = null,
        onSort: (LibrarySort) -> Unit = {},
        onToggleTitles: (Boolean) -> Unit = {},
        onOpenPodcast: (Long) -> Unit = {},
        onTileAction: (Long, TileAction) -> Unit = { _, _ -> },
        onConfirmUnsubscribe: (LibraryTile) -> Unit = {},
        onDismissUnsubscribe: () -> Unit = {},
        onConfirmMarkPlayed: (Long) -> Unit = {},
        onDismissMarkPlayed: () -> Unit = {},
        onAddPodcast: () -> Unit = {},
    ) {
        setContent {
            CompositionLocalProvider(
                LocalPlatformKind provides PlatformKind.DESKTOP,
                LocalUiClock provides TestClock(),
                LocalAppNavigator provides navigator,
                LocalPlatformActions provides TestPlatformActions,
            ) {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    LibraryScreen(
                        state = state,
                        pendingUnsubscribe = pendingUnsubscribe,
                        pendingMarkPlayed = pendingMarkPlayed,
                        onSort = onSort,
                        onToggleTitles = onToggleTitles,
                        onOpenPodcast = onOpenPodcast,
                        onTileAction = onTileAction,
                        onConfirmUnsubscribe = onConfirmUnsubscribe,
                        onDismissUnsubscribe = onDismissUnsubscribe,
                        onConfirmMarkPlayed = onConfirmMarkPlayed,
                        onDismissMarkPlayed = onDismissMarkPlayed,
                        onAddPodcast = onAddPodcast,
                    )
                }
            }
        }
        waitForIdle()
    }

    /**
     * The route driven with the real [LibraryViewModel] on fakes (09): the tile menu's "Mark
     * all as played…" has to reach `episodes.calls` only through the confirmation dialog.
     */
    private fun ComposeUiTest.setLibraryRoute() {
        podcasts.tiles.value = listOf(testLibraryTile(5, displayTitle = "Tap Show"))
        val viewModel =
            LibraryViewModel(
                podcasts,
                episodes,
                refreshController,
                UnsubscribeUseCase(podcasts),
                settings,
                collator,
                network,
            )
        setContent {
            // collectAsStateWithLifecycle (rule 10) needs a started owner; a bare compose
            // scene provides none, so the test installs a RESUMED one.
            CompositionLocalProvider(LocalLifecycleOwner provides ResumedLifecycleOwner()) {
                CompositionLocalProvider(
                    LocalPlatformKind provides PlatformKind.DESKTOP,
                    LocalUiClock provides TestClock(),
                    LocalAppNavigator provides navigator,
                    LocalPlatformActions provides TestPlatformActions,
                ) {
                    NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                        LibraryRoute(viewModel = viewModel)
                    }
                }
            }
        }
        waitForIdle()
    }

    /** `createUnsafe` skips the main-thread checks so the test can resume it off the UI thread. */
    private class ResumedLifecycleOwner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)

        init {
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
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
