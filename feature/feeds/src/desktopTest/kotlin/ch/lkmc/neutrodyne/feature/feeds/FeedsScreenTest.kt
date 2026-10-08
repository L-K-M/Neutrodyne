// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.feeds

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.EpisodeRow
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import ch.lkmc.neutrodyne.core.testing.testEpisodeRow
import ch.lkmc.neutrodyne.core.ui.EpisodeAction
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.LocalUiClock
import ch.lkmc.neutrodyne.core.ui.platform.ExternalUrlOpener
import ch.lkmc.neutrodyne.core.ui.platform.FilePicker
import ch.lkmc.neutrodyne.core.ui.platform.FileSaver
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.OpenResult
import ch.lkmc.neutrodyne.core.ui.platform.PlatformActions
import kotlinx.coroutines.flow.Flow
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The All feed driven through `runComposeUiTest` on the desktop JVM (09): the onboarding empty
 * state, caught-up and load-error branches, chips, the overflow's mark-all dialog, the row's
 * click and custom accessibility actions, and 02's append paging without duplicate rows. A real
 * [Pager] against [PagedListSource] proves the screen pages; `TestClock` fixes the day headers.
 *
 * The tests run on the v2 `runComposeUiTest` (its `StandardTestDispatcher` scopes paging's
 * collects to the test scheduler — the deprecated v1 entry point leaves them racing on real
 * dispatchers).
 */
@OptIn(ExperimentalTestApi::class)
class FeedsScreenTest {
    private lateinit var previousLocale: Locale
    private val navigator = RecordingAppNavigator()

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
    fun emptyLibraryShowsOnboarding() =
        runComposeUiTest {
            var added = false
            setFeeds(state = FeedsUiState(hasSubscriptions = false), onAddPodcast = { added = true })
            onNodeWithText("Your feeds live here").assertIsDisplayed()
            onNodeWithText("Add a podcast").performClick()
            assertEquals(true, added)
        }

    @Test
    fun subscribedEmptyFeedShowsCaughtUp() =
        runComposeUiTest {
            setFeeds(state = FeedsUiState(hasSubscriptions = true), source = PagedListSource(listOf(emptyList())))
            onNodeWithText("You're all caught up").assertIsDisplayed()
        }

    @Test
    fun filteredEmptyFeedOffersShowPlayed() =
        runComposeUiTest {
            var filters: FeedFilters? = null
            setFeeds(
                state = FeedsUiState(hasSubscriptions = true, filters = FeedFilters(unplayedOnly = true)),
                source = PagedListSource(listOf(emptyList())),
                onFiltersChange = { filters = it },
            )
            onNodeWithText("Show played episodes").performClick()
            assertEquals(FeedFilters(), filters)
        }

    @Test
    fun refreshErrorShowsRetry() =
        runComposeUiTest {
            setFeeds(
                state = FeedsUiState(hasSubscriptions = true),
                source = PagedListSource.failing(),
            )
            onNodeWithText("Couldn't load episodes").assertIsDisplayed()
            onNodeWithText("Retry").assertIsDisplayed()
        }

    @Test
    fun contentShowsDayHeaderAndRows() =
        runComposeUiTest {
            setFeeds(
                state = FeedsUiState(hasSubscriptions = true),
                rows = listOf(testEpisodeRow(1, title = "Morning Show"), testEpisodeRow(2, title = "Evening Show")),
            )
            onNodeWithText("Today").assertIsDisplayed()
            onNodeWithText("Morning Show").assertIsDisplayed()
            onNodeWithText("Evening Show").assertIsDisplayed()
        }

    @Test
    fun rowClickDispatchesOpen() =
        runComposeUiTest {
            val actions = mutableListOf<EpisodeAction>()
            setFeeds(
                state = FeedsUiState(hasSubscriptions = true),
                rows = listOf(testEpisodeRow(9, title = "Tap me")),
                onAction = { actions += it },
            )
            onNodeWithText("Tap me").performClick()
            assertEquals(listOf<EpisodeAction>(EpisodeAction.Open(9)), actions)
        }

    @Test
    fun rowMarkPlayedIsACustomAction() =
        runComposeUiTest {
            val actions = mutableListOf<EpisodeAction>()
            setFeeds(
                state = FeedsUiState(hasSubscriptions = true),
                rows = listOf(testEpisodeRow(9, title = "Tap me")),
                onAction = { actions += it },
            )
            onAllNodes(hasText("Tap me") and hasClickAction())
                .onFirst()
                .performCustomAccessibilityActionWithLabel("Mark played")
            assertEquals(listOf<EpisodeAction>(EpisodeAction.SetPlayed(9, played = true)), actions)
        }

    @Test
    fun chipTogglesFilters() =
        runComposeUiTest {
            var filters: FeedFilters? = null
            setFeeds(
                state = FeedsUiState(hasSubscriptions = true),
                rows = listOf(testEpisodeRow(1)),
                onFiltersChange = { filters = it },
            )
            onNodeWithText("Unplayed").performClick()
            assertEquals(FeedFilters(unplayedOnly = true), filters)
        }

    @Test
    fun overflowMarkAllConfirmsFirst() =
        runComposeUiTest {
            var confirmed = false
            setFeeds(
                state = FeedsUiState(hasSubscriptions = true),
                rows = listOf(testEpisodeRow(1)),
                onMarkAllPlayed = { confirmed = true },
            )
            onNodeWithContentDescription("More actions").performClick()
            onNodeWithText("Mark all as played…").performClick()
            // The destructive action waits for the dialog's confirm (08's wording).
            assertEquals(false, confirmed)
            // The dialog's title and confirm share the string; the clickable node is the button.
            onAllNodes(hasClickAction() and hasText("Mark all as played…")).onFirst().performClick()
            assertEquals(true, confirmed)
        }

    @Test
    fun desktopRefreshButtonCallsOnRefresh() =
        runComposeUiTest {
            var refreshes = 0
            setFeeds(state = FeedsUiState(hasSubscriptions = true), onRefresh = { refreshes++ })
            onNodeWithContentDescription("Refresh").performClick()
            assertEquals(1, refreshes)
        }

    @Test
    fun offlineBannerShowsWhenOffline() =
        runComposeUiTest {
            setFeeds(
                state = FeedsUiState(hasSubscriptions = true, offline = true),
                rows = listOf(testEpisodeRow(1, title = "Tap me")),
            )
            onNodeWithText("You're offline — downloaded episodes still play").assertIsDisplayed()
        }

    @Test
    fun scrollingAppendsTheNextPageWithoutDuplicates() =
        runComposeUiTest {
            val pageSize = 20
            val source =
                PagedListSource(
                    listOf(
                        (1..pageSize).map { testEpisodeRow(it.toLong(), title = "Ep $it") },
                        (pageSize + 1..pageSize + 10).map { testEpisodeRow(it.toLong(), title = "Ep $it") },
                    ),
                )
            setFeeds(state = FeedsUiState(hasSubscriptions = true), source = source, pageSize = pageSize)
            onNodeWithText("Ep 1").assertIsDisplayed()

            // Scrolling to a row only on page two drives the append load.
            onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText("Ep 25"))
            waitForIdle()
            onNodeWithText("Ep 25").assertIsDisplayed()

            // A duplicated page would show its rows twice (episode ids are the item keys).
            assertEquals(1, onAllNodesWithText("Ep 25").fetchSemanticsNodes().size)
            assertEquals(2, source.loads)
        }

    private fun ComposeUiTest.setFeeds(
        state: FeedsUiState,
        rows: List<EpisodeRow> = emptyList(),
        source: PagedListSource? = null,
        pageSize: Int = 5,
        onRefresh: () -> Unit = {},
        onFiltersChange: (FeedFilters) -> Unit = {},
        onAction: (EpisodeAction) -> Unit = {},
        onMarkAllPlayed: () -> Unit = {},
        onAddPodcast: () -> Unit = {},
        onSearch: () -> Unit = {},
    ): LazyPagingItems<FeedItem> {
        lateinit var pagingItems: androidx.paging.compose.LazyPagingItems<FeedItem>
        val feed: Flow<PagingData<FeedItem>> =
            Pager(PagingConfig(pageSize = pageSize, initialLoadSize = pageSize)) {
                source ?: PagedListSource(listOf(rows))
            }.flow
                .withDayHeaders(TestClock.DEFAULT_NOW)
        setContent {
            CompositionLocalProvider(
                LocalPlatformKind provides PlatformKind.DESKTOP,
                LocalUiClock provides TestClock(),
                LocalAppNavigator provides navigator,
                LocalPlatformActions provides TestPlatformActions,
            ) {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    FeedsScreen(
                        state = state,
                        items = feed.collectAsLazyPagingItems().also { pagingItems = it },
                        onRefresh = onRefresh,
                        onFiltersChange = onFiltersChange,
                        onAction = onAction,
                        onMarkAllPlayedClick = onMarkAllPlayed,
                        onAddPodcast = onAddPodcast,
                        onSearch = onSearch,
                    )
                }
            }
        }
        // The load-state snapshot lands a scheduler tick after the first frame; pump until the
        // refresh settles so state branches (empty/error) are composed before assertions run.
        waitUntil(timeoutMillis = 5_000) { pagingItems.loadState.refresh !is LoadState.Loading }
        waitForIdle()
        return pagingItems
    }
}

/**
 * Named pages served by [Pager] — a page index past [pages] means an append load happened;
 * [failing] sources answer the refresh load with [LoadResult.Error].
 */
private class PagedListSource(
    private val pages: List<List<EpisodeRow>>,
    private val refreshError: Throwable? = null,
) : PagingSource<Int, EpisodeRow>() {
    var loads = 0
        private set

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, EpisodeRow> {
        loads++
        refreshError?.let { return LoadResult.Error(it) }
        val index = params.key ?: 0
        return LoadResult.Page(
            data = pages[index],
            prevKey = if (index == 0) null else index - 1,
            nextKey = if (index + 1 < pages.size) index + 1 else null,
        )
    }

    override fun getRefreshKey(state: PagingState<Int, EpisodeRow>): Int? = null

    companion object {
        fun failing(error: Throwable = IllegalStateException("boom")) =
            PagedListSource(emptyList(), refreshError = error)
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
