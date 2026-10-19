// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.feeds

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotMutableState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMultiModalInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.cachedIn
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.CategoryCount
import ch.lkmc.neutrodyne.core.model.EpisodeRow
import ch.lkmc.neutrodyne.core.model.FeedCounts
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedPrefs
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.FeedSourceRef
import ch.lkmc.neutrodyne.core.model.FeedTab
import ch.lkmc.neutrodyne.core.model.Group
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.model.RowLive
import ch.lkmc.neutrodyne.core.model.VirtualCounts
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.DiscoverSettingKeys
import ch.lkmc.neutrodyne.core.navigation.GroupEditKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.testing.FakeEpisodeLiveStateSource
import ch.lkmc.neutrodyne.core.testing.FakeEpisodeRepository
import ch.lkmc.neutrodyne.core.testing.FakeFeedRepository
import ch.lkmc.neutrodyne.core.testing.FakeGroupRepository
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakePlaybackController
import ch.lkmc.neutrodyne.core.testing.FakePlaybackStateSource
import ch.lkmc.neutrodyne.core.testing.FakePodcastRepository
import ch.lkmc.neutrodyne.core.testing.FakeRefreshController
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import ch.lkmc.neutrodyne.core.testing.testEpisodeRow
import ch.lkmc.neutrodyne.core.testing.testLibraryTile
import ch.lkmc.neutrodyne.core.testing.testPlatformInfo
import ch.lkmc.neutrodyne.core.ui.EpisodeAction
import ch.lkmc.neutrodyne.core.ui.EpisodeSelectionController
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.LocalUiClock
import ch.lkmc.neutrodyne.core.ui.MarkFeedPlayedDialog
import ch.lkmc.neutrodyne.core.ui.platform.ExternalUrlOpener
import ch.lkmc.neutrodyne.core.ui.platform.FilePicker
import ch.lkmc.neutrodyne.core.ui.platform.FileSaver
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.OpenResult
import ch.lkmc.neutrodyne.core.ui.platform.PlatformActions
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import ch.lkmc.neutrodyne.core.ui.root.ShowUserMessages
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.util.Locale
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

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
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
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
    fun categoryCardShowsThroughTheFeedsRoute() =
        runComposeUiTest {
            val fixture = PagerFixture()
            fixture.groups.groups.value = emptyList()
            fixture.podcasts.categoryCounts.value = listOf(CategoryCount("News", listOf(1, 2)))
            fixture.feeds.tabs.value = listOf(FeedTab(FeedSource.All, "All", null, null, null))
            setContent { fixture.Content(FeedContent.ROUTE) }
            fixture.awaitSelected(this, 0)
            onNodeWithText("News (2)").assertIsDisplayed()
            onNodeWithText("Not now").performClick()
            waitUntil { runBlocking { fixture.settings.get(DiscoverSettingKeys.SUGGESTED_GROUPS_DISMISSED) } }
            onNodeWithText("News (2)").assertDoesNotExist()
            fixture.close()
        }

    @Test
    fun noSuggestionsHideTheCardWithoutSubscriptions() =
        runComposeUiTest {
            setFeeds(state = FeedsUiState(hasSubscriptions = false))
            onNodeWithText("Group podcasts into feeds").assertDoesNotExist()
        }

    @Test
    fun noMatchingSuggestionsHideTheCardWithHiddenGroups() =
        runComposeUiTest {
            val fixture = PagerFixture()
            fixture.groups.groups.value =
                fixture.groups.groups.value
                    .map { it.copy(showAsTab = false) }
            fixture.feeds.tabs.value = listOf(FeedTab(FeedSource.All, "All", null, null, null))
            setContent { fixture.Content(FeedContent.ROUTE) }
            fixture.awaitSelected(this, 0)
            onNodeWithText("Group podcasts into feeds").assertDoesNotExist()
            fixture.close()
        }

    @Test
    fun selectedCategoryCreatesGroupMembersThroughTheFeedsRoute() =
        runComposeUiTest {
            val fixture = PagerFixture()
            fixture.groups.groups.value = emptyList()
            fixture.podcasts.categoryCounts.value = listOf(CategoryCount("News", listOf(1, 2)))
            fixture.feeds.tabs.value = listOf(FeedTab(FeedSource.All, "All", null, null, null))
            setContent { fixture.Content(FeedContent.ROUTE) }
            fixture.awaitSelected(this, 0)
            onNodeWithText("News (2)").performClick()
            onNodeWithText("Create groups").performClick()
            waitUntil { fixture.groups.groups.value.size == 1 && !fixture.model.suggestedGroups.state.value.busy }
            assertEquals(
                setOf(1L, 2L),
                fixture.groups.members.value.values
                    .single(),
            )
            assertEquals(
                "News",
                fixture.groups.groups.value
                    .single()
                    .name,
            )
            fixture.close()
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
    fun selectionBarRangesSelectAllBulkActionAndEscape() =
        runComposeUiTest {
            val writes = mutableListOf<List<Long>>()
            val selection =
                EpisodeSelectionController(
                    CoroutineScope(Dispatchers.Unconfined),
                    { ids, _ -> writes += ids },
                    { throw it },
                )
            setFeeds(
                state = FeedsUiState(),
                rows = (1L..5L).map { testEpisodeRow(it, title = "Episode $it") },
                selection = selection,
            )
            onNodeWithText("Episode 1").performTouchInput { longClick() }
            assertEquals(setOf(1L), selection.ids.value)
            onNodeWithText("1 selected").assertIsDisplayed()
            onNodeWithText("Episode 3").performMultiModalInput {
                key { keyDown(Key.ShiftLeft) }
                mouse { click(centerLeft) }
                key { keyUp(Key.ShiftLeft) }
            }
            onNodeWithText("3 selected").assertIsDisplayed()
            onNodeWithText("Episode 3").performKeyInput {
                keyDown(Key.CtrlLeft)
                pressKey(Key.A)
                keyUp(Key.CtrlLeft)
            }
            onNodeWithText("5 selected").assertIsDisplayed()
            onNodeWithContentDescription("Mark played").performClick()
            assertEquals(listOf(listOf(1L, 2L, 3L, 4L, 5L)), writes)
            onNodeWithText("Episode 1").performTouchInput { longClick() }
            onNodeWithText("Episode 1").performKeyInput { pressKey(Key.Escape) }
            assertTrue(selection.ids.value.isEmpty())
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
    fun overflowOpensCountedConfirmation() =
        runComposeUiTest {
            var opens = 0
            setFeeds(
                state = FeedsUiState(hasSubscriptions = true),
                rows = listOf(testEpisodeRow(1)),
                onMarkAllPlayed = { opens++ },
            )
            onNodeWithContentDescription("More actions").performClick()
            onNodeWithText("Mark all as played…").performClick()
            assertEquals(1, opens)
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
            onNodeWithTag("feed-list").performScrollToNode(hasText("Ep 25"))
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
            onNodeWithTag("feed-list").performScrollToIndex(90)
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

    @Test
    fun selectedGroupAndScrollRestoreWithStringPageKeys() =
        runComposeUiTest {
            val fixture = PagerFixture()
            fixture.select(2)
            var registry by mutableStateOf<SaveableStateRegistry?>(SaveableStateRegistry(null, ::canSaveFeedState))
            setContent {
                registry?.let { current ->
                    CompositionLocalProvider(LocalSaveableStateRegistry provides current) { fixture.Content() }
                }
            }
            fixture.awaitSelected(this, 2)
            val list = pageList(fixture.ref(2))
            list.performScrollToIndex(35)
            val before = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertTrue(before > 0)

            lateinit var saved: Map<String, List<Any?>>
            runOnIdle {
                saved = registry!!.performSave()
                registry = null
            }
            waitForIdle()
            assertTrue(saved.toString().contains(fixture.ref(2).encode()))
            runOnIdle { registry = SaveableStateRegistry(saved, ::canSaveFeedState) }
            fixture.awaitSelected(this, 2)
            val after =
                pageList(
                    fixture.ref(2),
                ).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertEquals(before, after)
            onNodeWithTag("feed-tab:${fixture.ref(2).encode()}").assertIsSelected()
            fixture.close()
        }

    @Test
    fun scrollSurvivesLeavingThePagingLru() =
        runComposeUiTest {
            val fixture = PagerFixture()
            setContent { fixture.Content() }
            fixture.awaitSelected(this, 0)
            pageList(FeedSourceRef.All).performScrollToIndex(35)
            val before =
                pageList(
                    FeedSourceRef.All,
                ).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            fixture.select(5)
            fixture.awaitSelected(this, 5)
            assertTrue(fixture.feeds.cancelledPagingRequests.any { it.first == FeedSource.All })
            fixture.select(0)
            fixture.awaitSelected(this, 0)
            val after =
                pageList(
                    FeedSourceRef.All,
                ).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertEquals(before, after)
            assertEquals(2, fixture.feeds.pagingRequests.count { it.first == FeedSource.All })
            fixture.close()
        }

    @Test
    fun reorderKeepsTheSelectedGroupAndAllGroupsNavigates() =
        runComposeUiTest {
            val fixture = PagerFixture()
            fixture.select(2)
            setContent { fixture.Content() }
            fixture.awaitSelected(this, 2)
            runOnIdle {
                fixture.clock.advanceBy(1.seconds)
                fixture.feeds.tabs.value =
                    fixture.feeds.tabs.value
                        .let { listOf(it.first()) + it.drop(1).reversed() }
            }
            waitForIdle()
            onNodeWithTag("feed-tab:${fixture.ref(2).encode()}").assertIsSelected()
            assertEquals(fixture.ref(2), fixture.model.uiState.value.selected)
            assertEquals(
                null,
                fixture.feeds.prefs.value[FeedSource.Group(2)]
                    ?.lastViewedAt,
            )
            onNodeWithContentDescription("All groups").performClick()
            assertEquals(1, fixture.allGroupsOpens)
            fixture.close()
        }

    @Test
    fun deletingSelectedGroupShowsAllAndTheFallbackSnackbar() =
        runComposeUiTest {
            val fixture = PagerFixture()
            fixture.select(2)
            setContent { fixture.Content() }
            fixture.awaitSelected(this, 2)
            runOnIdle {
                fixture.groups.groups.value =
                    fixture.groups.groups.value
                        .filterNot { it.id == 2L }
                fixture.feeds.tabs.value =
                    fixture.feeds.tabs.value
                        .filterNot { it.source == FeedSource.Group(2) }
            }
            fixture.awaitSelected(this, 0)
            waitUntil(timeoutMillis = 5_000) { fixture.snackbar.currentSnackbarData != null }
            assertEquals(
                "'Group 2' was deleted — showing All",
                fixture.snackbar.currentSnackbarData
                    ?.visuals
                    ?.message,
            )
            assertEquals("all", runBlocking { fixture.settings.get(AppearanceSettingKeys.FEEDS_SELECTED_SOURCE) })
            fixture.close()
        }

    @Test
    fun hiddenGroupRouteAddsATransientTabUntilAnotherPageSettles() =
        runComposeUiTest {
            val fixture = PagerFixture()
            runOnIdle {
                fixture.groups.groups.value =
                    fixture.groups.groups.value
                        .map { if (it.id == 2L) it.copy(showAsTab = false) else it }
                fixture.feeds.tabs.value =
                    fixture.feeds.tabs.value
                        .filterNot { it.source == FeedSource.Group(2) }
            }
            setContent { fixture.Content() }
            fixture.awaitSelected(this, 0)
            fixture.select(2)
            fixture.awaitSelected(this, 2)
            onNodeWithContentDescription("Not shown as tab", useUnmergedTree = true).assertExists()
            onNodeWithTag("feeds-pager").performCustomAccessibilityActionWithLabel("Previous group")
            waitUntil(timeoutMillis = 5_000) { fixture.model.uiState.value.selected != fixture.ref(2) }
            waitForIdle()
            onNodeWithText("Group 2").assertDoesNotExist()
            fixture.close()
        }

    @Test
    fun nextGroupActionWorksWhenRowSwipeDisablesPagerDrag() =
        runComposeUiTest {
            val fixture = PagerFixture()
            runBlocking { fixture.settings.set(AppearanceSettingKeys.FEEDS_ROW_SWIPE, true) }
            setContent { fixture.Content() }
            fixture.awaitSelected(this, 0)
            onNodeWithTag("feeds-pager").performTouchInput { swipeLeft() }
            waitForIdle()
            assertEquals(FeedSourceRef.All, fixture.model.uiState.value.selected)
            onNodeWithTag("feeds-pager").performCustomAccessibilityActionWithLabel("Next group")
            fixture.awaitSelected(this, 1)
            onNodeWithTag("feed-tab:${fixture.ref(1).encode()}").assertIsSelected()
            fixture.close()
        }

    @Test
    fun pagerDragSwitchesPagesWithRowSwipeOff() =
        runComposeUiTest {
            val fixture = PagerFixture()
            setContent { fixture.Content() }
            fixture.awaitSelected(this, 0)
            onNodeWithTag("feeds-pager").performTouchInput { swipeLeft() }
            fixture.awaitSelected(this, 1)
            fixture.close()
        }

    @Test
    fun pageOverflowCountsAndMarksItsGroupAfterReconfirmation() =
        runComposeUiTest {
            val fixture = PagerFixture()
            val source = FeedSource.Group(2)
            fixture.select(2)
            fixture.feeds.unplayedCounts[source to null] = 2
            fixture.episodes.unplayedCounts[source to null] = 3
            setContent { fixture.Content() }
            fixture.awaitSelected(this, 2)
            onNode(
                hasContentDescription("More actions") and
                    hasAnyAncestor(hasTestTag("feed-page:${fixture.ref(2).encode()}")),
                useUnmergedTree = true,
            ).performClick()
            onNodeWithText("Mark all as played…").performClick()
            onNodeWithText("Mark 2 episodes in 'Group 2' as played?").assertIsDisplayed()
            assertTrue(fixture.episodes.calls.isEmpty())
            onNodeWithText("Mark as played").performClick()
            onNodeWithText("Mark 3 episodes in 'Group 2' as played?").assertIsDisplayed()
            assertTrue(fixture.episodes.calls.isEmpty())
            onNodeWithText("Mark as played").performClick()
            waitForIdle()
            assertEquals(listOf("markFeedPlayed($source, null, 3)"), fixture.episodes.calls)
            fixture.close()
        }

    @Test
    fun refreshAndFiltersWriteToTheSelectedSource() =
        runComposeUiTest {
            val fixture = PagerFixture()
            fixture.select(2)
            setContent { fixture.Content() }
            fixture.awaitSelected(this, 2)
            onNodeWithContentDescription("Refresh").performClick()
            assertEquals(listOf("refreshFeed(Group(groupId=2))"), fixture.refresh.calls)
            onNode(
                hasText("Unplayed") and hasAnyAncestor(hasTestTag("feed-page:${fixture.ref(2).encode()}")),
            ).performClick()
            waitUntil(timeoutMillis = 5_000) {
                fixture.feeds.prefs.value[FeedSource.Group(2)]
                    ?.filters
                    ?.unplayedOnly ==
                    true
            }
            assertEquals(
                false,
                fixture.feeds.prefs.value[FeedSource.All]
                    ?.filters
                    ?.unplayedOnly ?: false,
            )
            fixture.close()
        }

    @Test
    fun pullToRefreshUsesThePagesSource() =
        runComposeUiTest {
            val fixture = PagerFixture()
            fixture.select(2)
            setContent { fixture.Content() }
            fixture.awaitSelected(this, 2)
            pageList(fixture.ref(2)).performTouchInput {
                swipe(start = center.copy(y = top + 10f), end = center.copy(y = bottom - 10f), durationMillis = 1_000)
            }
            waitUntil(timeoutMillis = 5_000) { fixture.refresh.calls.isNotEmpty() }
            assertEquals(listOf("refreshFeed(Group(groupId=2))"), fixture.refresh.calls)
            fixture.close()
        }

    @Test
    fun visitsWriteOnlyAfterASecondAndEndOnPause() =
        runComposeUiTest {
            val fixture = PagerFixture()
            fixture.select(2)
            setContent { fixture.Content() }
            fixture.awaitSelected(this, 2)
            runOnIdle { fixture.clock.advanceBy(1.seconds) }
            onNodeWithTag("feeds-pager").performCustomAccessibilityActionWithLabel("Next group")
            fixture.awaitSelected(this, 3)
            waitUntil(timeoutMillis = 5_000) {
                fixture.feeds.prefs.value[FeedSource.Group(2)]
                    ?.lastViewedAt ==
                    fixture.clock.now()
            }
            runOnIdle { fixture.owner.lifecycle.currentState = Lifecycle.State.STARTED }
            waitForIdle()
            assertEquals(
                null,
                fixture.feeds.prefs.value[FeedSource.Group(3)]
                    ?.lastViewedAt,
            )
            fixture.close()
        }

    @Test
    fun keyboardSwitchesPagesAndRefreshesTheSettledSource() =
        runComposeUiTest {
            val fixture = PagerFixture()
            setContent { fixture.Content() }
            fixture.awaitSelected(this, 0)
            onNodeWithTag("feeds-pager").performKeyInput {
                keyDown(Key.CtrlLeft)
                pressKey(Key.Tab)
                keyUp(Key.CtrlLeft)
            }
            fixture.awaitSelected(this, 1)
            onNodeWithTag("feeds-pager").performKeyInput { pressKey(Key.F5) }
            assertEquals(listOf("refreshFeed(Group(groupId=1))"), fixture.refresh.calls)
            fixture.close()
        }

    @Test
    fun oneTabHasNoTabRowAndEnglishVirtualNamesIgnoreRepositoryTitles() =
        runComposeUiTest {
            val fixture = PagerFixture()
            fixture.groups.groups.value = emptyList()
            fixture.feeds.tabs.value = listOf(FeedTab(FeedSource.All, "Wrong All title", null, null, null))
            setContent { fixture.Content() }
            fixture.awaitSelected(this, 0)
            onNodeWithTag("feed-tab:all").assertDoesNotExist()
            runOnIdle {
                fixture.feeds.tabs.value += FeedTab(FeedSource.Ungrouped, "Wrong Ungrouped title", null, null, null)
            }
            waitForIdle()
            onNodeWithText("All").assertIsDisplayed()
            onNodeWithText("Ungrouped").assertIsDisplayed()
            onNodeWithText("Wrong All title").assertDoesNotExist()
            fixture.close()
        }

    @Test
    fun virtualTabLabelsUseGermanResourcesAndCounts() =
        runComposeUiTest {
            Locale.setDefault(Locale.GERMAN)
            val fixture = PagerFixture()
            fixture.feeds.tabs.value =
                listOf(
                    FeedTab(FeedSource.All, "Wrong All title", null, null, null),
                    FeedTab(FeedSource.Ungrouped, "Wrong Ungrouped title", null, null, null),
                )
            fixture.feeds.virtualCounts.value = VirtualCounts(all = FeedCounts(7, 3), ungrouped = FeedCounts(2, 1))
            setContent { fixture.Content() }
            fixture.awaitSelected(this, 0)
            onNodeWithText("Alle").assertIsDisplayed()
            onNodeWithText("Ohne Gruppe").assertIsDisplayed()
            onNodeWithContentDescription("Alle, 7 ungespielt, 3 neu", useUnmergedTree = true).assertExists()
            onNodeWithText("Wrong All title").assertDoesNotExist()
            fixture.close()
        }

    @Test
    fun hourlyAgeWindowPagerSwapKeepsScroll() =
        runComposeUiTest {
            val fixture = PagerFixture()
            fixture.feeds.prefs.value = mapOf(FeedSource.All to FeedPrefs(hideOlderThanDays = 7))
            setContent { fixture.Content() }
            fixture.awaitSelected(this, 0)
            onNodeWithText("Clear filters").assertDoesNotExist()
            pageList(FeedSourceRef.All).performScrollToIndex(35)
            val before =
                pageList(
                    FeedSourceRef.All,
                ).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            val requests = fixture.feeds.pagingRequests.count { it.first == FeedSource.All }
            runOnIdle {
                fixture.clock.advanceBy(1.hours)
                // Deliver the repository's next hourly prefs emission with the new cutoff.
                fixture.feeds.prefs.value =
                    fixture.feeds.prefs.value
                        .mapValues { (_, prefs) -> prefs.at(fixture.clock.now()) }
            }
            waitUntil(timeoutMillis = 5_000) {
                fixture.feeds.pagingRequests.count { it.first == FeedSource.All } >
                    requests
            }
            waitForIdle()
            val after =
                pageList(
                    FeedSourceRef.All,
                ).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertEquals(before, after)
            val cutoffs =
                fixture.feeds.pagingRequests
                    .filter { it.first == FeedSource.All }
                    .map { it.second.minSortDate }
            assertEquals(FeedPrefs.HOUR_MS, cutoffs.last()!! - cutoffs.first()!!)
            fixture.close()
        }

    private fun ComposeUiTest.pageList(ref: FeedSourceRef) =
        onNode(
            hasTestTag("feed-list") and hasAnyAncestor(hasTestTag("feed-page:${ref.encode()}")),
            useUnmergedTree = true,
        )

    private fun allTab() = FeedTabUi(FeedTab(FeedSource.All, "All", null, null, null), FeedSourceRef.All)

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
        selection: EpisodeSelectionController? = null,
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
        val owner = ResumedFeedsOwner()
        setContent {
            CompositionLocalProvider(
                LocalLifecycleOwner provides owner,
                LocalPlatformKind provides PlatformKind.DESKTOP,
                LocalUiClock provides TestClock(),
                LocalAppNavigator provides navigator,
                LocalPlatformActions provides TestPlatformActions,
            ) {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    val scope = rememberCoroutineScope()
                    if (selection != null) {
                        remember {
                            Dispatchers.setMain(scope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher)
                        }
                        DisposableEffect(Unit) { onDispose { Dispatchers.resetMain() } }
                    }
                    val cachedFeed = remember { feed.cachedIn(scope) }
                    cachedFeed.collectAsLazyPagingItems().also { pagingItems = it }
                    wrapScreen {
                        FeedsScreen(
                            state = state.copy(tabs = persistentListOf(allTab())),
                            selection = selection,
                            live = live,
                            onVisibleIds = { _, ids -> onVisibleIds(ids) },
                            pagingFor = { cachedFeed },
                            onRefresh = { onRefresh() },
                            onPlay = {},
                            onFiltersChange = { _, filters -> onFiltersChange(filters) },
                            onAction = onAction,
                            onMarkAllPlayedClick = { onMarkAllPlayed() },
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

private enum class FeedContent { SCREEN, ROUTE }

/** Real D1 state and cache with fake application services, shared by pager UI proofs. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
private class PagerFixture {
    val clock = TestClock()
    val feeds = FakeFeedRepository(clock)
    val episodes = FakeEpisodeRepository()
    val groups = FakeGroupRepository(clock)
    val podcasts = FakePodcastRepository().apply { tiles.value = listOf(testLibraryTile(1)) }
    val settings = FakeSettingsRepository()
    val refresh = FakeRefreshController()
    val snackbar = SnackbarHostState()
    val owner = ResumedFeedsOwner()
    private val store = ViewModelStore()
    lateinit var model: FeedsViewModel
    var allGroupsOpens = 0
    val navigator = RecordingAppNavigator()

    init {
        groups.groups.value =
            (1L..5L).map { id ->
                Group(
                    id,
                    "00000000-0000-4000-8000-${id.toString().padStart(12, '0')}",
                    "Group $id",
                    "$id",
                    null,
                    null,
                    FeedOrder.NEWEST_FIRST,
                    FeedOrder.NEWEST_FIRST,
                    0,
                    MediaFilter.ALL,
                    null,
                    true,
                    null,
                    clock.now() - 1_000,
                    1,
                )
            }
        feeds.tabs.value = listOf(FeedTab(FeedSource.All, "All", null, null, null)) +
            groups.groups.value.map {
                FeedTab(FeedSource.Group(it.id), it.name, it.colorArgb, it.iconKey, it.uuid)
            }
        feeds.rows.value = (1L..100L).map { testEpisodeRow(it, title = "Episode $it") }
    }

    fun ref(id: Int): FeedSourceRef =
        if (id ==
            0
        ) {
            FeedSourceRef.All
        } else {
            FeedSourceRef.Group(
                groups.groups.value
                    .single { it.id == id.toLong() }
                    .uuid,
            )
        }

    fun select(id: Int) {
        runBlocking { settings.set(AppearanceSettingKeys.FEEDS_SELECTED_SOURCE, ref(id).encode()) }
    }

    fun awaitSelected(
        test: ComposeUiTest,
        id: Int,
    ) {
        test.waitUntil(timeoutMillis = 5_000) {
            ::model.isInitialized &&
                model.uiState.value.tabs
                    .isNotEmpty() &&
                model.uiState.value.selected == ref(id)
        }
        test.waitForIdle()
        test
            .onNode(
                hasTestTag("feed-list") and hasAnyAncestor(hasTestTag("feed-page:${ref(id).encode()}")),
                useUnmergedTree = true,
            ).assertIsDisplayed()
    }

    fun close() {
        store.clear()
    }

    @Composable
    fun Content(content: FeedContent = FeedContent.SCREEN) {
        val effectDispatcher = rememberCoroutineScope().coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        val viewModel =
            remember {
                // Keep ViewModel emissions on the same scheduler as the v2 Compose harness.
                Dispatchers.setMain(effectDispatcher)
                FeedsViewModel(
                    feeds,
                    episodes,
                    refresh,
                    FakeNetworkMonitor(FakeNetworkMonitor.ONLINE),
                    podcasts,
                    clock,
                    FakeEpisodeLiveStateSource(),
                    groups,
                    settings,
                    FakePlaybackController(),
                    FakePlaybackStateSource(),
                    testPlatformInfo,
                    Dispatchers.Unconfined,
                    downloadController =
                        ch.lkmc.neutrodyne.core.testing
                            .FakeDownloadController(),
                    externalOpenService =
                        ch.lkmc.neutrodyne.core.testing
                            .FakeYouTubeExternalOpen(),
                    capabilities =
                        ch.lkmc.neutrodyne.core.testing
                            .FakeYouTubeCapabilitiesSource(),
                    youTubeHealth =
                        ch.lkmc.neutrodyne.core.testing
                            .FakeYouTubeHealth(),
                ).also {
                    model = it
                    store.put("feeds", it)
                }
            }
        DisposableEffect(Unit) {
            onDispose {
                close()
                Dispatchers.resetMain()
            }
        }
        CompositionLocalProvider(
            LocalPlatformKind provides PlatformKind.DESKTOP,
            LocalUiClock provides clock,
            LocalLifecycleOwner provides owner,
            LocalAppNavigator provides navigator,
            LocalPlatformActions provides TestPlatformActions,
            LocalSnackbarHost provides snackbar,
        ) {
            NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                if (content == FeedContent.ROUTE) {
                    FeedsRoute(viewModel)
                } else {
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    FeedsScreen(
                        state = state,
                        pagingFor = viewModel::pagingFor,
                        prefsFor = viewModel::prefsFor,
                        onRefresh = viewModel::onRefresh,
                        onPlay = viewModel::playFeed,
                        onFiltersChange = viewModel::onFilters,
                        onAction = {},
                        onMarkAllPlayedClick = viewModel::markAllPlayed,
                        onAddPodcast = {},
                        onSearch = {},
                        onPageSettled = viewModel::onPageSettled,
                        onVisitStart = viewModel::onVisitStart,
                        onVisitEnd = viewModel::onVisitEnd,
                        onAllGroups = { allGroupsOpens++ },
                    )
                    val markPlayed by viewModel.markPlayed.state.collectAsStateWithLifecycle()
                    markPlayed?.let {
                        MarkFeedPlayedDialog(
                            it,
                            viewModel.markPlayed::select,
                            viewModel.markPlayed::confirm,
                            viewModel.markPlayed::dismiss,
                        )
                    }
                    ShowUserMessages(state.messages, viewModel::onMessageShown)
                }
            }
        }
    }
}

private class ResumedFeedsOwner : LifecycleOwner {
    override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
}

/** Android-compatible primitives and collection keys, rather than desktop's permissive default. */
private fun canSaveFeedState(value: Any): Boolean =
    when (value) {
        is SnapshotMutableState<*> -> {
            value.value?.let(::canSaveFeedState) ?: true
        }

        is String, is Number, is Boolean, is Char -> {
            true
        }

        is List<*> -> {
            value.all { it == null || canSaveFeedState(it) }
        }

        is Map<*, *> -> {
            value.keys.all { it is String || it is Number } &&
                value.values.all { it == null || canSaveFeedState(it) }
        }

        else -> {
            false
        }
    }
