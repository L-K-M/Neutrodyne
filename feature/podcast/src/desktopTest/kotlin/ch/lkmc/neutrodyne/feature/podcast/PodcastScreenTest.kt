// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.podcast

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
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
import ch.lkmc.neutrodyne.core.model.Group
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.model.RowLive
import ch.lkmc.neutrodyne.core.model.ShowType
import ch.lkmc.neutrodyne.core.navigation.AddToGroupsKey
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
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIsNot
import kotlin.test.assertTrue

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
    fun visibleIdsSkipHeadersAndLiveOverlayReachesTheRow() =
        runComposeUiTest {
            val live = mutableStateOf(mapOf(1L to RowLive(isNowPlaying = true, isPlaying = true)))
            var visible = emptySet<Long>()
            setPodcast(
                state = PodcastUiState(detail = testPodcastDetail(7), loaded = true, offline = true),
                rows = listOf(testEpisodeRow(1, podcastId = 7)),
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
    fun offlineBannerShiftsTheHeaderIndex() =
        runComposeUiTest {
            val listState = LazyListState()
            setPodcast(
                PodcastUiState(
                    detail = testPodcastDetail(7, displayTitle = "The Show"),
                    loaded = true,
                    offline = true,
                ),
                rows = (1L..40L).map { testEpisodeRow(it, podcastId = 7) },
                listState = listState,
            )
            onNodeWithText("You're offline — downloaded episodes still play").assertIsDisplayed()
            val barBottom =
                onNodeWithContentDescription("Back").fetchSemanticsNode().boundsInRoot.bottom

            // The offline banner takes list index 0; scrolling only it off leaves the header
            // first, so the title must stay in the scrolling header, not the top bar.
            listState.scrollToItem(1)
            waitForIdle()
            assertTrue(
                onAllNodes(hasText("The Show"))
                    .fetchSemanticsNodes()
                    .all { it.boundsInRoot.top >= barBottom - 0.5f },
            )

            listState.scrollToItem(2)
            waitForIdle()
            assertTrue(
                onAllNodes(hasText("The Show"))
                    .fetchSemanticsNodes()
                    .any { it.boundsInRoot.top < barBottom },
            )
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
    fun orderChipSelectsNewestFirst() =
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
    fun needsCredentialsOpensThePasswordDialogAndSubmits() =
        runComposeUiTest {
            var submitted: BasicCredentials? = null
            setPodcast(
                PodcastUiState(
                    detail = testPodcastDetail(7, health = testFeedHealth(needsCredentials = true)),
                    loaded = true,
                ),
                onEnterCredentials = { submitted = it },
            )
            onNodeWithText("This feed needs a password").assertIsDisplayed()
            // 08 Podcast detail: the banner's "Enter password" opens the credential dialog.
            onNodeWithText("Enter password").performClick()
            onAllNodes(hasSetTextAction())[0].performTextInput("alice")
            onAllNodes(hasSetTextAction())[1].performTextInput("hunter2")
            onAllNodes(hasClickAction() and hasText("Save")).onFirst().performClick()
            assertEquals(BasicCredentials("alice", "hunter2"), submitted)
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
    fun possiblyDeadWithoutSuccessShowsDatelessCopy() =
        runComposeUiTest {
            // A feed that never fetched successfully has no `lastSuccessAt` to format —
            // the banner falls back to the date-less copy, not a dangling "since  —".
            setPodcast(
                PodcastUiState(
                    detail =
                        testPodcastDetail(
                            7,
                            health =
                                testFeedHealth(
                                    failureCount = 12,
                                    lastSuccessAt = null,
                                    possiblyDead = true,
                                ),
                        ),
                    loaded = true,
                ),
            )
            onNodeWithText(
                "This feed hasn't updated since you added it — it may have moved",
            ).assertIsDisplayed()
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
    fun overflowDisablesUnsubscribeWithoutDetail() =
        runComposeUiTest {
            var requested = false
            // The top bar shows before the detail resolves; unsubscribing a feed we have no
            // detail for has nothing to act on, so only that item disables.
            setPodcast(
                PodcastUiState(detail = null, loaded = true),
                onUnsubscribeRequest = { requested = true },
            )
            onNodeWithContentDescription("More actions").performClick()
            onNodeWithText("Unsubscribe").assertIsNotEnabled()
            onNodeWithText("Mark all as played").assertIsEnabled()
            onNodeWithText("Unsubscribe").performClick()
            assertEquals(false, requested)
        }

    @Test
    fun credentialsDialogMasksThePasswordUntilToggled() =
        runComposeUiTest {
            setCredentialsDialog()

            val password = onAllNodes(hasSetTextAction())[1]
            password.performTextInput("hunter2")

            // 08's dialogs table ("masked, show toggle"): the secret stays transformed —
            // the Password semantics marker is set and the field's text is the mask, not
            // the raw password — until the trailing toggle reveals it. The marker stays
            // set after revealing: it tracks the password keyboard, not the mask.
            password.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            password.assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.EditableText,
                    AnnotatedString("•••••••"),
                ),
            )
            onNodeWithContentDescription("Show password").assertIsDisplayed()

            onNodeWithContentDescription("Show password").performClick()
            password.assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.EditableText,
                    AnnotatedString("hunter2"),
                ),
            )
            onNodeWithContentDescription("Hide password").assertIsDisplayed()
        }

    @Test
    fun credentialFieldsCarryAutofillContentTypes() =
        runComposeUiTest {
            setCredentialsDialog()

            // CMP 1.12 autofill: the platform's credential manager reads the ContentType
            // hint — username on field 0, password on field 1.
            onAllNodes(hasSetTextAction())[0].assert(
                SemanticsMatcher.expectValue(SemanticsProperties.ContentType, ContentType.Username),
            )
            onAllNodes(hasSetTextAction())[1].assert(
                SemanticsMatcher.expectValue(SemanticsProperties.ContentType, ContentType.Password),
            )
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
            // Production pages at 20 × 80 with placeholders on (05's PagingConfig): a restored
            // index beyond the loaded page sits on a placeholder row, so the placeholder must
            // keep a row's height — a zero-height stub makes the list back off to a nearer
            // index instead of landing on 100. The restore also lands while paging's first
            // page is still out: the header-only mount must not clamp it early either.
            val gate = CompletableDeferred<Unit>()
            val listState = LazyListState(firstVisibleItemIndex = 100)
            val pagingItems =
                setPodcast(
                    PodcastUiState(detail = testPodcastDetail(7), loaded = true),
                    source = CatalogueSource(CATALOGUE_SIZE, firstLoadGate = gate),
                    paging =
                        PagingConfig(
                            pageSize = 20,
                            initialLoadSize = 80,
                            enablePlaceholders = true,
                        ),
                    listState = listState,
                    waitForFirstPage = false,
                )
            assertEquals(0, pagingItems.itemCount)

            gate.complete(Unit)
            waitUntil(timeoutMillis = 5_000) { pagingItems.itemCount == CATALOGUE_SIZE }
            waitForIdle()

            assertEquals(100, listState.firstVisibleItemIndex)
        }

    @Test
    fun refreshErrorOffersRetry() =
        runComposeUiTest {
            // A failed first page shows the paged-list error state with Retry (08's
            // Failed shape): the retry lands on a working source.
            val source =
                PagedListSource(
                    pages = listOf(List(5) { i -> testEpisodeRow(i + 1L, podcastId = 7, title = "Ep ${i + 1}") }),
                    failRefresh = true,
                )
            val pagingItems =
                setPodcast(PodcastUiState(detail = testPodcastDetail(7), loaded = true), source = source)

            onNodeWithText("Couldn't load episodes").assertIsDisplayed()
            onNodeWithText("Retry").performClick()
            // Poll rather than waitForIdle: the retry's NdLoading spinner animates forever.
            waitUntil(timeoutMillis = 5_000) { pagingItems.itemCount == 5 }
            onNodeWithText("Ep 1").assertIsDisplayed()
        }

    @Test
    fun appendErrorOffersRetry() =
        runComposeUiTest {
            // A failed append surfaces once: the pinned banner under the top bar — 08's
            // revised paging-error rule dropped the spec's footer row, which placeholders
            // can park thousands of rows away and which repeated the banner at the list
            // end. Page one is sized past the viewport + prefetch distance so the failing
            // append is triggered by an access hint from scrolling — the hint is what
            // `retry()` replays; an auto-append after refresh carries no hint and cannot
            // be retried.
            val source =
                PagedListSource(
                    pages =
                        listOf(
                            List(60) { i -> testEpisodeRow(i + 1L, podcastId = 7, title = "Ep ${i + 1}") },
                            List(10) { i -> testEpisodeRow(61L + i, podcastId = 7, title = "Ep ${61 + i}") },
                        ),
                    failNextAppend = true,
                )
            val pagingItems =
                setPodcast(
                    PodcastUiState(detail = testPodcastDetail(7), loaded = true),
                    source = source,
                )
            onNodeWithText("Ep 1").assertIsDisplayed()

            onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText("Ep 59"))
            waitUntil(timeoutMillis = 5_000) { pagingItems.loadState.append is LoadState.Error }

            // At the very end of the list — past the last loaded row, where the spec's
            // footer row used to sit — the error stays a single surface.
            onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText("Ep 60"))
            onAllNodes(hasScrollToIndexAction()).onFirst().performTouchInput {
                repeat(2) { swipeUp() }
            }
            waitForIdle()
            onAllNodes(hasText("Couldn't load episodes")).assertCountEquals(1)
            onAllNodes(hasText("Retry")).assertCountEquals(1)

            val loadsBeforeRetry = source.loads
            onNodeWithText("Retry").performClick()
            // Poll state, not idle: the retry's append spinner animates indefinitely.
            waitUntil(timeoutMillis = 5_000) { pagingItems.itemCount == 70 }

            // The retry replayed exactly the failed append; the banner cleared with it.
            assertEquals(loadsBeforeRetry + 1, source.loads)
            assertEquals("Ep 70", pagingItems.itemSnapshotList[69]?.title)
            assertIsNot<LoadState.Error>(pagingItems.loadState.append)
        }

    @Test
    fun appendErrorBannerStaysVisibleMidList() =
        runComposeUiTest {
            // Review round 3: at catalogue scale the append failure's footer sits hundreds of
            // placeholder rows below the viewport — the pinned banner must surface it where
            // the user is. 80 loaded rows of 300, placeholders on like production's config.
            val source = CatalogueSource(CATALOGUE_SIZE)
            val pagingItems =
                setPodcast(
                    PodcastUiState(detail = testPodcastDetail(7), loaded = true),
                    source = source,
                    paging =
                        PagingConfig(
                            pageSize = 20,
                            initialLoadSize = 80,
                            enablePlaceholders = true,
                        ),
                )
            assertEquals(CATALOGUE_SIZE, pagingItems.itemCount)
            assertEquals(listOf<EpisodeRow?>(null), pagingItems.itemSnapshotList.subList(85, 86))

            // Accessing the placeholder edge of the loaded page triggers the failing append.
            source.failNextAppend = true
            onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(85)
            waitUntil(timeoutMillis = 5_000) { pagingItems.loadState.append is LoadState.Error }

            // The error banner is already on screen — no scroll to the faraway footer —
            // and it is the error's only surface: the spec's append footer row is gone.
            onNodeWithText("Couldn't load episodes").assertIsDisplayed()
            onAllNodes(hasText("Retry")).assertCountEquals(1)
            onNodeWithText("Retry").performClick()
            // The retried append lands (a successful page can retrigger appends for the
            // placeholders still in view, so wait for the row itself, not a load count).
            waitUntil(timeoutMillis = 5_000) {
                pagingItems.itemSnapshotList[85]?.title == "Ep 86"
            }
            assertIsNot<LoadState.Error>(pagingItems.loadState.append)
        }

    @Test
    fun prependErrorBannerHasNoFooterToHideBehind() =
        runComposeUiTest {
            // A prepend failure has no list row at all: the first page loads 200 rows into a
            // 300-row catalogue, so everything newer exists only as placeholders above. Any
            // placeholder access already counts as approaching the prepend edge (its hint's
            // distance is negative), so the flag is armed before composition.
            val source =
                CatalogueSource(CATALOGUE_SIZE, firstLoadOffset = 200).apply {
                    failNextPrepend = true
                }
            val pagingItems =
                setPodcast(
                    PodcastUiState(detail = testPodcastDetail(7), loaded = true),
                    source = source,
                    paging =
                        PagingConfig(
                            pageSize = 20,
                            initialLoadSize = 80,
                            enablePlaceholders = true,
                        ),
                )
            assertEquals(CATALOGUE_SIZE, pagingItems.itemCount)

            // Scrolling to a placeholder guarantees the access hint if the tall header kept
            // every episode row off the initial viewport.
            onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(10)
            waitUntil(timeoutMillis = 5_000) { pagingItems.loadState.prepend is LoadState.Error }

            onNodeWithText("Couldn't load episodes").assertIsDisplayed()
            onNodeWithText("Retry").performClick()
            // The retried prepend lands the page at catalogue offset 120.
            waitUntil(timeoutMillis = 5_000) {
                pagingItems.itemSnapshotList[120]?.title == "Ep 121"
            }
            assertIsNot<LoadState.Error>(pagingItems.loadState.prepend)
        }

    @Test
    fun refreshErrorWithContentShowsTheBanner() =
        runComposeUiTest {
            // A failed refresh while episodes are on screen has no in-list error either —
            // the empty-list error state only covers `itemCount == 0`. Each invalidated
            // source is replaced, so the fail flag is armed per creation.
            var failNextRefresh = false
            val pagingItems =
                setPodcast(
                    PodcastUiState(detail = testPodcastDetail(7), loaded = true),
                    sourceFactory = {
                        CatalogueSource(CATALOGUE_SIZE).also { it.failNextRefresh = failNextRefresh }
                    },
                    paging =
                        PagingConfig(
                            pageSize = 20,
                            initialLoadSize = 80,
                            enablePlaceholders = true,
                        ),
                )
            onNodeWithText("Ep 1").assertIsDisplayed()

            failNextRefresh = true
            runOnIdle { pagingItems.refresh() }
            waitUntil(timeoutMillis = 5_000) { pagingItems.loadState.refresh is LoadState.Error }

            onNodeWithText("Couldn't load episodes").assertIsDisplayed()
            failNextRefresh = false
            onNodeWithText("Retry").performClick()
            waitUntil(timeoutMillis = 5_000) { pagingItems.loadState.refresh !is LoadState.Error }
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

    @Test
    fun emptyFeedShowsNoEpisodesYet() =
        runComposeUiTest {
            // 08's `emptyFeed`: a fetched feed holding zero episodes gets the same copy as
            // the filtered-empty case, but no reset action while no filters are active.
            setPodcast(
                PodcastUiState(
                    detail = testPodcastDetail(7, episodeCount = 0, latestEpisodeAt = null),
                    loaded = true,
                ),
            )
            onNodeWithText("No episodes yet").assertIsDisplayed()
            onNodeWithText("Show played episodes").assertDoesNotExist()
        }

    @Test
    fun groupChipRemoveReportsTheGroup() =
        runComposeUiTest {
            var removed: Group? = null
            setPodcast(
                PodcastUiState(
                    detail = testPodcastDetail(7),
                    loaded = true,
                    groups = persistentListOf(testGroup(3, name = "tech")),
                ),
                onRemoveGroup = { removed = it },
            )
            onNodeWithText("tech").assertIsDisplayed()
            // The chip's trailing "x" carries its own contentDescription; the tap must hit it,
            // not the chip body (which opens the membership sheet).
            onNodeWithContentDescription("Remove from tech", useUnmergedTree = true).performClick()
            assertEquals(3L, removed?.id)
            assertTrue(navigator.calls.isEmpty())
        }

    @Test
    fun groupChipsAndAddButtonPushTheMembershipSheet() =
        runComposeUiTest {
            setPodcast(
                PodcastUiState(
                    detail = testPodcastDetail(7),
                    loaded = true,
                    groups = persistentListOf(testGroup(3, name = "tech")),
                ),
            )
            // Both entry points open the same "Add to groups" sheet for this podcast.
            onNodeWithText("+ Group").performClick()
            onAllNodes(hasClickAction() and hasText("tech")).onFirst().performClick()
            assertEquals(
                listOf<RecordingAppNavigator.Call>(
                    RecordingAppNavigator.Call.Push(AddToGroupsKey(listOf(7))),
                    RecordingAppNavigator.Call.Push(AddToGroupsKey(listOf(7))),
                ),
                navigator.calls,
            )
        }

    @Test
    fun headerWithoutMembershipsStillOffersAddGroup() =
        runComposeUiTest {
            setPodcast(PodcastUiState(detail = testPodcastDetail(7), loaded = true))
            onNodeWithText("+ Group").assertIsDisplayed()
        }

    private fun ComposeUiTest.setPodcast(
        state: PodcastUiState,
        rows: List<EpisodeRow> = emptyList(),
        live: State<Map<Long, RowLive>> = mutableStateOf(emptyMap()),
        onVisibleIds: (Set<Long>) -> Unit = {},
        source: PagingSource<Int, EpisodeRow>? = null,
        // Invalidation needs a fresh instance — `Pager` rejects a re-used source.
        sourceFactory: (() -> PagingSource<Int, EpisodeRow>)? = null,
        paging: PagingConfig = PagingConfig(pageSize = 20, initialLoadSize = 20),
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
        onRemoveGroup: (Group) -> Unit = {},
    ): LazyPagingItems<EpisodeRow> {
        lateinit var pagingItems: LazyPagingItems<EpisodeRow>
        val feed: Flow<PagingData<EpisodeRow>> =
            Pager(paging) {
                sourceFactory?.invoke() ?: source ?: PagedListSource(listOf(rows))
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
                        live = live,
                        onVisibleIds = onVisibleIds,
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
                        onRemoveGroup = onRemoveGroup,
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

    /** The banner's credentials dialog rendered directly. */
    private fun ComposeUiTest.setCredentialsDialog(
        onSubmit: (BasicCredentials) -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        setContent {
            CompositionLocalProvider(
                LocalPlatformKind provides PlatformKind.DESKTOP,
                LocalUiClock provides TestClock(),
                LocalAppNavigator provides navigator,
                LocalPlatformActions provides TestPlatformActions,
            ) {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    CredentialsDialog(onSubmit = onSubmit, onDismiss = onDismiss)
                }
            }
        }
        waitForIdle()
    }
}

/** The restore test's catalogue; index 100 lands among the placeholders past the 80 loaded rows. */
private const val CATALOGUE_SIZE = 300

/** A minimal [Group] row for the header's membership chips. */
private fun testGroup(
    id: Long,
    name: String,
) = Group(
    id = id,
    uuid = "group-$id",
    name = name,
    orderKey = "$id",
    colorArgb = null,
    iconKey = null,
    feedOrder = FeedOrder.NEWEST_FIRST,
    playOrder = FeedOrder.NEWEST_FIRST,
    filterFlags = 0,
    mediaFilter = MediaFilter.ALL,
    hideOlderThanDays = null,
    showAsTab = true,
    lastViewedAt = null,
    createdAt = 0,
    memberCount = 1,
)

/**
 * Named pages served by [Pager] — one page's worth per `load`; [firstLoadGate] parks load 0
 * and the `fail*` flags make the matching load fail once, so a Retry lands on a working source.
 */
private class PagedListSource(
    private val pages: List<List<EpisodeRow>>,
    private val firstLoadGate: CompletableDeferred<Unit>? = null,
    private var failRefresh: Boolean = false,
    private var failNextAppend: Boolean = false,
) : PagingSource<Int, EpisodeRow>() {
    var loads = 0
        private set

    private var firstLoad = true

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, EpisodeRow> {
        loads++
        if (firstLoad) {
            firstLoad = false
            firstLoadGate?.await()
        }
        val index = params.key ?: 0
        if (index == 0 && failRefresh) {
            failRefresh = false
            return LoadResult.Error(IllegalStateException("boom"))
        }
        if (index > 0 && failNextAppend) {
            failNextAppend = false
            return LoadResult.Error(IllegalStateException("boom"))
        }
        return LoadResult.Page(
            data = pages[index],
            prevKey = if (index == 0) null else index - 1,
            nextKey = if (index + 1 < pages.size) index + 1 else null,
        )
    }

    override fun getRefreshKey(state: PagingState<Int, EpisodeRow>): Int? = null
}

/**
 * An offset-keyed catalogue of [total] rows reporting `itemsBefore`/`itemsAfter`, so the Pager
 * knows the full count and hands out null placeholders for the unloaded tail — the shape
 * `EpisodeRowProjection`'s source produces in production.
 */
private class CatalogueSource(
    private val total: Int,
    private val firstLoadGate: CompletableDeferred<Unit>? = null,
    /** The first load's offset, so prepend tests can park the loaded page mid-catalogue. */
    private val firstLoadOffset: Int = 0,
) : PagingSource<Int, EpisodeRow>() {
    var failNextAppend = false
    var failNextPrepend = false
    var failNextRefresh = false

    var loads = 0
        private set

    private var firstLoad = true

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, EpisodeRow> {
        loads++
        val offset =
            if (firstLoad) {
                firstLoad = false
                firstLoadGate?.await()
                firstLoadOffset
            } else {
                params.key ?: 0
            }
        if (params is LoadParams.Append && failNextAppend) {
            failNextAppend = false
            return LoadResult.Error(IllegalStateException("boom"))
        }
        if (params is LoadParams.Prepend && failNextPrepend) {
            failNextPrepend = false
            return LoadResult.Error(IllegalStateException("boom"))
        }
        if (params is LoadParams.Refresh && failNextRefresh) {
            failNextRefresh = false
            return LoadResult.Error(IllegalStateException("boom"))
        }
        val data =
            (offset until minOf(offset + params.loadSize, total)).map {
                testEpisodeRow(it + 1L, podcastId = 7, title = "Ep ${it + 1}")
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
