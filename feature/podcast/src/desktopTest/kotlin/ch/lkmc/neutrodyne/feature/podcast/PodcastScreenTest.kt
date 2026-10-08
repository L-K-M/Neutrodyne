// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.podcast

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
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
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.EpisodeRow
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.ShowType
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import ch.lkmc.neutrodyne.core.testing.testEpisodeRow
import ch.lkmc.neutrodyne.core.testing.testFeedHealth
import ch.lkmc.neutrodyne.core.testing.testPodcastDetail
import ch.lkmc.neutrodyne.core.ui.EpisodeAction
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.LocalUiClock
import ch.lkmc.neutrodyne.core.ui.platform.ExternalUrlOpener
import ch.lkmc.neutrodyne.core.ui.platform.FilePicker
import ch.lkmc.neutrodyne.core.ui.platform.FileSaver
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.OpenResult
import ch.lkmc.neutrodyne.core.ui.platform.PlatformActions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The podcast detail screen through `runComposeUiTest` (08 Podcast detail): loading, the header
 * and chips, the per-feed-state banners (password, gone, maybe-moved, pending, error), the order
 * dropdown, the overflow's mark-all and unsubscribe dialogs, "Load older", and paged rows via a
 * real [Pager] on [PagedListSource]. `installFakeImageLoader` keeps the artwork off the network.
 */
@OptIn(ExperimentalTestApi::class)
class PodcastScreenTest {
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
            setPodcast(PodcastUiState())
            onNodeWithText("Loading podcast…").assertIsDisplayed()
        }

    @Test
    fun contentShowsHeaderAndRows() =
        runComposeUiTest {
            setPodcast(
                PodcastUiState(
                    detail = testPodcastDetail(7, displayTitle = "The Show"),
                    loaded = true,
                ),
                rows = listOf(testEpisodeRow(1, podcastId = 7, title = "First ep")),
            )
            // The title sits in the scrolling header; the top bar stays empty until it scrolls off.
            onNodeWithText("The Show").assertIsDisplayed()
            onNodeWithText("First ep").assertIsDisplayed()
        }

    @Test
    fun backPopsTheNavigator() =
        runComposeUiTest {
            setPodcast(PodcastUiState(detail = testPodcastDetail(7), loaded = true))
            onNodeWithContentDescription("Back").performClick()
            assertEquals(listOf<RecordingAppNavigator.Call>(RecordingAppNavigator.Call.Pop), navigator.calls)
        }

    @Test
    fun refreshButtonDisabledOffline() =
        runComposeUiTest {
            var refreshes = 0
            setPodcast(
                PodcastUiState(detail = testPodcastDetail(7), loaded = true, offline = true),
                onRefresh = { refreshes++ },
            )
            onNodeWithText("You're offline — downloaded episodes still play").assertIsDisplayed()
            onNodeWithContentDescription("Refresh").assertIsNotEnabled().performClick()
            assertEquals(0, refreshes)
        }

    @Test
    fun chipTogglesFilters() =
        runComposeUiTest {
            var filters: FeedFilters? = null
            setPodcast(
                PodcastUiState(detail = testPodcastDetail(7), loaded = true),
                onFiltersChange = { filters = it },
            )
            onNodeWithText("Unplayed").performClick()
            assertEquals(FeedFilters(unplayedOnly = true), filters)
        }

    @Test
    fun orderChipPersistsChoice() =
        runComposeUiTest {
            var order: FeedOrder? = null
            setPodcast(
                PodcastUiState(
                    detail = testPodcastDetail(7, showType = ShowType.SERIAL),
                    loaded = true,
                ),
                onOrderChange = { order = it },
            )
            // Serial shows default to oldest first; the chip reflects that before the tap.
            onNodeWithText("Oldest first").performClick()
            onAllNodes(hasClickAction() and hasText("Newest first")).onFirst().performClick()
            assertEquals(FeedOrder.NEWEST_FIRST, order)
        }

    @Test
    fun needsCredentialsHidesPasswordAction() =
        runComposeUiTest {
            setPodcast(
                PodcastUiState(
                    detail = testPodcastDetail(7, health = testFeedHealth(needsCredentials = true)),
                    loaded = true,
                ),
            )
            onNodeWithText("This feed needs a password").assertIsDisplayed()
            // `setCredentials` is M1b: the banner keeps its warning but no entry point.
            onNodeWithText("Enter password").assertDoesNotExist()
        }

    @Test
    fun goneFeedOffersUnsubscribe() =
        runComposeUiTest {
            var unsubscribed = false
            setPodcast(
                PodcastUiState(
                    detail = testPodcastDetail(7, health = testFeedHealth(gone = true)),
                    loaded = true,
                ),
                onUnsubscribeRequest = { unsubscribed = true },
            )
            onNodeWithText("This feed no longer exists").assertIsDisplayed()
            onAllNodes(hasClickAction() and hasText("Unsubscribe")).onFirst().performClick()
            assertEquals(true, unsubscribed)
        }

    @Test
    fun errorBannerRetries() =
        runComposeUiTest {
            var retried = false
            setPodcast(
                PodcastUiState(
                    detail =
                        testPodcastDetail(
                            7,
                            health =
                                testFeedHealth(
                                    failureCount = 2,
                                    lastErrorKind = ch.lkmc.neutrodyne.core.model.FeedErrorKind.TIMEOUT,
                                ),
                        ),
                    loaded = true,
                ),
                onRetryFeed = { retried = true },
            )
            onAllNodes(hasClickAction() and hasText("Retry")).onFirst().performClick()
            assertEquals(true, retried)
        }

    @Test
    fun loadOlderCallsBack() =
        runComposeUiTest {
            var older = 0
            setPodcast(
                PodcastUiState(
                    detail = testPodcastDetail(7, hasOlderPages = true),
                    loaded = true,
                ),
                rows = listOf(testEpisodeRow(1, podcastId = 7)),
                onLoadOlder = { older++ },
            )
            onNodeWithText("Load older episodes").performClick()
            assertEquals(1, older)
        }

    @Test
    fun overflowMarkAllConfirmsFirst() =
        runComposeUiTest {
            var confirmed = false
            setPodcast(
                PodcastUiState(detail = testPodcastDetail(7), loaded = true),
                onMarkAllPlayedClick = { confirmed = true },
            )
            onNodeWithContentDescription("More actions").performClick()
            onNodeWithText("Mark all as played").performClick()
            assertEquals(false, confirmed)
            onAllNodes(hasClickAction() and hasText("Mark all as played")).onFirst().performClick()
            assertEquals(true, confirmed)
        }

    @Test
    fun unsubscribeDialogCountsDownloads() =
        runComposeUiTest {
            var confirmed = false
            setPodcast(
                PodcastUiState(detail = testPodcastDetail(7, displayTitle = "The Show"), loaded = true),
                pendingUnsubscribe = PendingUnsubscribe(title = "The Show", downloads = 4),
                onConfirmUnsubscribe = { confirmed = true },
            )
            onNodeWithText("Unsubscribe from The Show?").assertIsDisplayed()
            onNodeWithText("4 downloaded episodes will be deleted.").assertIsDisplayed()
            onAllNodes(hasClickAction() and hasText("Unsubscribe")).onFirst().performClick()
            assertEquals(true, confirmed)
        }

    @Test
    fun rowMarkPlayedIsACustomAction() =
        runComposeUiTest {
            val actions = mutableListOf<EpisodeAction>()
            setPodcast(
                PodcastUiState(detail = testPodcastDetail(7), loaded = true),
                rows = listOf(testEpisodeRow(9, podcastId = 7, title = "Tap me")),
                onAction = { actions += it },
            )
            onAllNodes(hasText("Tap me") and hasClickAction())
                .onFirst()
                .performCustomAccessibilityActionWithLabel("Mark played")
            assertEquals(listOf<EpisodeAction>(EpisodeAction.SetPlayed(9, played = true)), actions)
        }

    @Test
    fun firstPageKeepsRestoredScrollIndex() =
        runComposeUiTest {
            // The restore lands while detail has arrived but paging's first page is still out:
            // a header-only LazyColumn must not clamp index 100 before the rows exist.
            val gate = CompletableDeferred<Unit>()
            val listState = LazyListState(firstVisibleItemIndex = 100)
            val pagingItems =
                setPodcast(
                    PodcastUiState(detail = testPodcastDetail(7), loaded = true),
                    source =
                        PagedListSource(
                            pages = listOf(List(RESTORE_PAGE_SIZE) { i -> testEpisodeRow(i + 1L, podcastId = 7) }),
                            firstLoadGate = gate,
                        ),
                    listState = listState,
                    waitForFirstPage = false,
                )
            assertEquals(0, pagingItems.itemCount)

            gate.complete(Unit)
            waitUntil(timeoutMillis = 5_000) { pagingItems.itemCount == RESTORE_PAGE_SIZE }
            waitForIdle()

            assertEquals(100, listState.firstVisibleItemIndex)
        }

    @Test
    fun filteredEmptyOffersShowPlayed() =
        runComposeUiTest {
            var filters: FeedFilters? = null
            setPodcast(
                PodcastUiState(
                    detail = testPodcastDetail(7, episodeCount = 3),
                    loaded = true,
                    filters = FeedFilters(unplayedOnly = true),
                ),
                source = PagedListSource(listOf(emptyList())),
                onFiltersChange = { filters = it },
            )
            onNodeWithText("No episodes yet").assertIsDisplayed()
            onNodeWithText("Show played episodes").performClick()
            assertEquals(FeedFilters(), filters)
        }

    private fun ComposeUiTest.setPodcast(
        state: PodcastUiState,
        rows: List<EpisodeRow> = emptyList(),
        source: PagedListSource? = null,
        listState: LazyListState = LazyListState(),
        waitForFirstPage: Boolean = true,
        pendingUnsubscribe: PendingUnsubscribe? = null,
        onRefresh: () -> Unit = {},
        onOpenSettings: () -> Unit = {},
        onFiltersChange: (FeedFilters) -> Unit = {},
        onOrderChange: (FeedOrder) -> Unit = {},
        onAction: (EpisodeAction) -> Unit = {},
        onLoadOlder: () -> Unit = {},
        onRetryFeed: () -> Unit = {},
        onEnterCredentials: (BasicCredentials) -> Unit = {},
        onMarkAllPlayedClick: () -> Unit = {},
        onUnsubscribeRequest: () -> Unit = {},
        onConfirmUnsubscribe: () -> Unit = {},
        onDismissUnsubscribe: () -> Unit = {},
    ): LazyPagingItems<EpisodeRow> {
        lateinit var pagingItems: LazyPagingItems<EpisodeRow>
        val feed: Flow<PagingData<EpisodeRow>> =
            Pager(PagingConfig(pageSize = 20, initialLoadSize = 20)) {
                source ?: PagedListSource(listOf(rows))
            }.flow
        setContent {
            CompositionLocalProvider(
                LocalPlatformKind provides PlatformKind.DESKTOP,
                LocalUiClock provides TestClock(),
                LocalAppNavigator provides navigator,
                LocalPlatformActions provides TestPlatformActions,
            ) {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    PodcastScreen(
                        state = state,
                        items = feed.collectAsLazyPagingItems().also { pagingItems = it },
                        listState = listState,
                        pendingUnsubscribe = pendingUnsubscribe,
                        onRefresh = onRefresh,
                        onOpenSettings = onOpenSettings,
                        onFiltersChange = onFiltersChange,
                        onOrderChange = onOrderChange,
                        onAction = onAction,
                        onLoadOlder = onLoadOlder,
                        onRetryFeed = onRetryFeed,
                        onEnterCredentials = onEnterCredentials,
                        onMarkAllPlayedClick = onMarkAllPlayedClick,
                        onUnsubscribeRequest = onUnsubscribeRequest,
                        onConfirmUnsubscribe = onConfirmUnsubscribe,
                        onDismissUnsubscribe = onDismissUnsubscribe,
                    )
                }
            }
        }
        if (waitForFirstPage) {
            waitUntil(timeoutMillis = 5_000) { pagingItems.loadState.refresh !is LoadState.Loading }
            waitForIdle()
        }
        return pagingItems
    }
}

/** The page served while the restore test's gate is closed; index 100 must stay visible inside it. */
private const val RESTORE_PAGE_SIZE = 150

/** Named pages served by [Pager] — one page's worth per `load`; [firstLoadGate] parks load 0. */
private class PagedListSource(
    private val pages: List<List<EpisodeRow>>,
    private val firstLoadGate: CompletableDeferred<Unit>? = null,
) : PagingSource<Int, EpisodeRow>() {
    private var firstLoad = true

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, EpisodeRow> {
        if (firstLoad) {
            firstLoad = false
            firstLoadGate?.await()
        }
        val index = params.key ?: 0
        return LoadResult.Page(
            data = pages[index],
            prevKey = if (index == 0) null else index - 1,
            nextKey = if (index + 1 < pages.size) index + 1 else null,
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
