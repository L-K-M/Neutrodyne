// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.feeds

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.test.performScrollToIndex
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
import ch.lkmc.neutrodyne.core.model.RowLive
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun emptyLibraryOffersSearchBeforeAddByUrl() =
        runComposeUiTest {
            var searches = 0
            var additions = 0
            setFeeds(
                state = FeedsUiState(hasSubscriptions = false),
                onSearch = { searches++ },
                onAddPodcast = { additions++ },
            )
            onNodeWithText("Your feeds live here").assertIsDisplayed()
            val search = onNodeWithText("Search podcasts").assertIsDisplayed()
            val add = onNodeWithText("Add by URL or YouTube link").assertIsDisplayed()
            assertTrue(search.fetchSemanticsNode().boundsInRoot.top < add.fetchSemanticsNode().boundsInRoot.top)
            search.performClick()
            assertEquals(1, searches)
            assertEquals(0, additions)
            add.performClick()
            assertEquals(1, additions)
            assertEquals(1, searches)
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
    fun visibleIdsSkipHeadersAndLiveOverlayReachesTheRow() =
        runComposeUiTest {
            val live = mutableStateOf(mapOf(1L to RowLive(isNowPlaying = true, isPlaying = true)))
            var visible = emptySet<Long>()
            setFeeds(
                state = FeedsUiState(hasSubscriptions = true, offline = true),
                rows = listOf(testEpisodeRow(1)),
                live = live,
                onVisibleIds = { visible = it },
            )
            assertEquals(setOf(1L), visible)
            onNodeWithContentDescription("Pause", useUnmergedTree = true).assertIsDisplayed()

            runOnIdle { live.value = emptyMap() }
            waitForIdle()
            onNodeWithContentDescription("Play", useUnmergedTree = true).assertIsDisplayed()
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
    fun markAllConfirmationSurvivesSaveAndRestore() =
        runComposeUiTest {
            var confirmations = 0
            var registry by mutableStateOf<SaveableStateRegistry?>(SaveableStateRegistry(null) { true })
            var saved: Map<String, List<Any?>> = emptyMap()
            setFeeds(
                state = FeedsUiState(hasSubscriptions = true),
                rows = listOf(testEpisodeRow(1)),
                onMarkAllPlayed = { confirmations++ },
                wrapScreen = { screen ->
                    registry?.let {
                        CompositionLocalProvider(LocalSaveableStateRegistry provides it) { screen() }
                    }
                },
            )
            onNodeWithContentDescription("More actions").performClick()
            onNodeWithText("Mark all as played…").performClick()
            onNodeWithText("Cancel").assertIsDisplayed()
            runOnIdle {
                saved = registry!!.performSave()
                registry = null
            }
            waitForIdle()
            runOnIdle { registry = SaveableStateRegistry(saved) { true } }
            waitForIdle()

            onNodeWithText("Cancel").assertIsDisplayed()
            assertEquals(0, confirmations)
            onAllNodes(hasClickAction() and hasText("Mark all as played…")).onFirst().performClick()
            assertEquals(1, confirmations)
            onNodeWithText("Cancel").assertDoesNotExist()
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

    @Test
    fun appendErrorPinsOneBannerMidList() =
        runComposeUiTest {
            // 08's revised paging-error rule: an append failure pins its banner under the
            // app bar — the spec's footer row was dropped because placeholders park it far
            // past the viewport. A 300-row catalogue with 80 loaded rows and placeholders
            // makes the spot real: mid-list, the pinned banner is the only surface.
            val source = CatalogueSource(CATALOGUE_SIZE)
            val items =
                setFeeds(
                    state = FeedsUiState(hasSubscriptions = true),
                    source = source,
                    paging =
                        PagingConfig(
                            pageSize = 20,
                            initialLoadSize = 80,
                            enablePlaceholders = true,
                        ),
                )
            assertTrue(items.itemCount >= CATALOGUE_SIZE)

            // Accessing the placeholder edge of the loaded page triggers the failing append.
            source.failNextAppend = true
            onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(90)
            waitUntil(timeoutMillis = 5_000) { items.loadState.append is LoadState.Error }

            // Mid-list — hundreds of placeholders short of the old footer spot — the pinned
            // banner is already on screen and is the error's only surface.
            onNodeWithText("Couldn't load episodes").assertIsDisplayed()
            assertEquals(1, onAllNodesWithText("Couldn't load episodes").fetchSemanticsNodes().size)

            // Retry replays the failed append through `items.retry()`; the page lands.
            onNodeWithText("Retry").performClick()
            waitUntil(timeoutMillis = 5_000) {
                items.itemSnapshotList.items.count { it is FeedItem.Episode } >= 100
            }
            assertTrue(items.loadState.append !is LoadState.Error)
        }

    private fun ComposeUiTest.setFeeds(
        state: FeedsUiState,
        rows: List<EpisodeRow> = emptyList(),
        live: State<Map<Long, RowLive>> = mutableStateOf(emptyMap()),
        onVisibleIds: (Set<Long>) -> Unit = {},
        source: PagingSource<Int, EpisodeRow>? = null,
        pageSize: Int = 5,
        paging: PagingConfig? = null,
        onRefresh: () -> Unit = {},
        onFiltersChange: (FeedFilters) -> Unit = {},
        onAction: (EpisodeAction) -> Unit = {},
        onMarkAllPlayed: () -> Unit = {},
        onAddPodcast: () -> Unit = {},
        onSearch: () -> Unit = {},
        wrapScreen: @Composable (@Composable () -> Unit) -> Unit = { it() },
    ): LazyPagingItems<FeedItem> {
        lateinit var pagingItems: androidx.paging.compose.LazyPagingItems<FeedItem>
        val feed: Flow<PagingData<FeedItem>> =
            Pager(paging ?: PagingConfig(pageSize = pageSize, initialLoadSize = pageSize)) {
                source ?: PagedListSource(listOf(rows))
            }.flow
                .withDayHeaders(TestClock(), Dispatchers.Unconfined)
        setContent {
            CompositionLocalProvider(
                LocalPlatformKind provides PlatformKind.DESKTOP,
                LocalUiClock provides TestClock(),
                LocalAppNavigator provides navigator,
                LocalPlatformActions provides TestPlatformActions,
            ) {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    val items = feed.collectAsLazyPagingItems().also { pagingItems = it }
                    wrapScreen {
                        FeedsScreen(
                            state = state,
                            live = live,
                            onVisibleIds = onVisibleIds,
                            items = items,
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
        // Page 0 is the refresh load; a later key is an append (page 0's null prevKey means
        // no prepend load ever runs).
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

/** The append-error test's catalogue; index 90 lands among the placeholders past the 80 loaded rows. */
private const val CATALOGUE_SIZE = 300

/**
 * An offset-keyed catalogue of [total] rows reporting `itemsBefore`/`itemsAfter`, so the Pager
 * knows the full count and hands out null placeholders for the unloaded tail — the shape the
 * production feed source produces with 05's placeholders on. [failNextAppend] fails the next
 * append load once, so a Retry lands on a working source.
 */
private class CatalogueSource(
    private val total: Int,
) : PagingSource<Int, EpisodeRow>() {
    var failNextAppend = false
    var loads = 0
        private set

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, EpisodeRow> {
        loads++
        val offset = params.key ?: 0
        if (params is LoadParams.Append && failNextAppend) {
            failNextAppend = false
            return LoadResult.Error(IllegalStateException("boom"))
        }
        val data =
            (offset until minOf(offset + params.loadSize, total)).map {
                testEpisodeRow(it + 1L, title = "Ep ${it + 1}")
            }
        return LoadResult.Page(
            data = data,
            prevKey = if (offset > 0) maxOf(offset - params.loadSize, 0) else null,
            nextKey = if (offset + data.size < total) offset + data.size else null,
            itemsBefore = offset,
            itemsAfter = total - offset - data.size,
        )
    }

    override fun getRefreshKey(state: PagingState<Int, EpisodeRow>): Int? = null
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
