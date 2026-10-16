// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.feeds

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.components.NdModalBottomSheet
import ch.lkmc.neutrodyne.core.designsystem.components.NdSheetExpansion
import ch.lkmc.neutrodyne.core.designsystem.components.NdTextButton
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.FeedSourceRef
import ch.lkmc.neutrodyne.core.model.FeedTab
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys
import ch.lkmc.neutrodyne.core.navigation.AllGroupsKey
import ch.lkmc.neutrodyne.core.navigation.AppNavigator
import ch.lkmc.neutrodyne.core.navigation.GroupEditKey
import ch.lkmc.neutrodyne.core.navigation.GroupsManageKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import ch.lkmc.neutrodyne.core.navigation.PodcastKey
import ch.lkmc.neutrodyne.core.testing.FakeEpisodeLiveStateSource
import ch.lkmc.neutrodyne.core.testing.FakeEpisodeRepository
import ch.lkmc.neutrodyne.core.testing.FakeFeedRepository
import ch.lkmc.neutrodyne.core.testing.FakeGroupRepository
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakePodcastRepository
import ch.lkmc.neutrodyne.core.testing.FakeRefreshController
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.LocalUiClock
import ch.lkmc.neutrodyne.core.ui.navigation.NavigationState
import ch.lkmc.neutrodyne.core.ui.navigation.rememberNavigationState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

@OptIn(ExperimentalTestApi::class)
class AllGroupsSheetTest {
    private lateinit var previousLocale: Locale
    private val fixture = AllGroupsSheetFixture()

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
    fun installerRegistersHalfHeightSheetOverlay() {
        val provider = entryProvider<NavKey> { FeedsNavigation.entries().invoke(this) }
        assertEquals(NdSceneMetadata.OVERLAY_SHEET, provider(AllGroupsKey).metadata[NdSceneMetadata.KEY_OVERLAY])
        assertEquals(true, provider(AllGroupsKey).metadata[NdSceneMetadata.KEY_HALF_HEIGHT])
    }

    @Test
    fun recentAndAlphabeticalSectionsUseThreeColumns() =
        runComposeUiTest {
            setSheet()
            onNodeWithTag("recent-heading").assertIsDisplayed()
            val row =
                listOf(6L, 5L, 4L).map {
                    onNodeWithTag("recent:${allGroupsTestGroup(it).uuid}").fetchSemanticsNode().boundsInRoot
                }
            assertEquals(row[0].top, row[1].top)
            assertEquals(row[0].top, row[2].top)
            assertTrue(row[0].left < row[1].left && row[1].left < row[2].left)
            onNodeWithTag("all-groups-grid").performScrollToIndex(4)
            val fourth = onNodeWithTag("recent:${allGroupsTestGroup(3).uuid}").fetchSemanticsNode().boundsInRoot
            assertEquals(row[0].left, fourth.left)
            onNodeWithTag("all-groups-grid").performScrollToIndex(5)
            onNodeWithTag("all-heading").assertIsDisplayed()
            val first = onNodeWithTag("all:${allGroupsTestGroup(1).uuid}").fetchSemanticsNode().boundsInRoot
            val second = onNodeWithTag("all:${allGroupsTestGroup(2).uuid}").fetchSemanticsNode().boundsInRoot
            assertEquals(first.top, second.top)
            assertTrue(first.left < second.left)
        }

    @Test
    fun recentTapPersistsGroupAndCloses() =
        runComposeUiTest {
            setSheet()
            onNodeWithTag("recent:${allGroupsTestGroup(6).uuid}").performClick()
            waitForIdle()
            assertSelection(6)
            onNodeWithTag("all-groups-sheet").assertDoesNotExist()
            assertEquals(
                listOf<RecordingAppNavigator.Call>(RecordingAppNavigator.Call.PopEntry(AllGroupsKey)),
                fixture.recording.calls,
            )
        }

    @Test
    fun completionAndDismissDuringExitKeepUnderlyingPodcast() =
        runComposeUiTest {
            setContent { fixture.Content(SheetHost.RETAINED_EXIT) }
            onNodeWithTag("recent:${allGroupsTestGroup(6).uuid}").performClick()
            waitForIdle()
            assertSelection(6)

            // Keep the outgoing content composed, as an overlay can be during its exit.
            onNodeWithTag("all-groups-sheet").performKeyInput { pressKey(Key.Escape) }
            onNodeWithText("New group").performClick()
            onNodeWithText("Manage groups").performClick()
            waitForIdle()

            assertEquals(listOf(LibraryKey, PodcastKey(7)), fixture.navigation.stack(LibraryKey).toList())
            assertEquals(LibraryKey, fixture.navigation.selectedTab)
            onNodeWithText("Underlying podcast").assertIsDisplayed()
        }

    @Test
    fun allGroupsTapPersistsGroupAndCloses() =
        runComposeUiTest {
            setSheet()
            onNodeWithContentDescription("Drag handle").performClick()
            onNodeWithTag("all-groups-grid").performScrollToIndex(6)
            onNodeWithTag("all:${allGroupsTestGroup(1).uuid}").performClick()
            waitForIdle()
            assertSelection(1)
            onNodeWithTag("all-groups-sheet").assertDoesNotExist()
        }

    @Test
    fun hiddenGroupIsMarkedAndSelectionCreatesTransientPagerTab() =
        runComposeUiTest {
            fixture.groups.groups.value =
                listOf(
                    allGroupsTestGroup(2, "Hidden", showAsTab = false),
                )
            setContent { fixture.Content(SheetHost.FEEDS) }
            onNodeWithContentDescription("All groups").performClick()
            onNodeWithContentDescription("Not shown as tab", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag("all:${allGroupsTestGroup(2).uuid}").performClick()
            waitForIdle()
            assertSelection(2)
            onNodeWithTag("all-groups-sheet").assertDoesNotExist()
            onNodeWithTag("feed-tab:${FeedSourceRef.Group(allGroupsTestGroup(2).uuid).encode()}").assertIsSelected()
            val hiddenRef = FeedSourceRef.Group(allGroupsTestGroup(2).uuid)
            assertTrue(
                fixture.feedModel.uiState.value.tabs
                    .single { it.ref == hiddenRef }
                    .transient,
            )
            onNodeWithTag("feed-tab:all").performClick()
            waitForIdle()
            onNodeWithTag("feed-tab:${FeedSourceRef.Group(allGroupsTestGroup(2).uuid).encode()}").assertDoesNotExist()
        }

    @Test
    fun emptyStateOffersNewGroupWithoutGridOrRecent() =
        runComposeUiTest {
            fixture.groups.groups.value = emptyList()
            setSheet()
            onNodeWithText("No groups yet").assertIsDisplayed()
            onNodeWithTag("all-groups-grid").assertDoesNotExist()
            onNodeWithTag("recent-heading").assertDoesNotExist()
            onNodeWithText("New group").performClick()
            assertEquals(
                listOf(
                    RecordingAppNavigator.Call.PopEntry(AllGroupsKey),
                    RecordingAppNavigator.Call.Push(GroupEditKey(null)),
                ),
                fixture.recording.calls,
            )
        }

    @Test
    fun newGroupClosesSheetAndOpensEditor() =
        runComposeUiTest {
            setSheet()
            onNodeWithText("New group").performClick()
            onNodeWithTag("all-groups-sheet").assertDoesNotExist()
            assertEquals(
                listOf(
                    RecordingAppNavigator.Call.PopEntry(AllGroupsKey),
                    RecordingAppNavigator.Call.Push(GroupEditKey(null)),
                ),
                fixture.recording.calls,
            )
        }

    @Test
    fun manageGroupsClosesSheetAndOpensManager() =
        runComposeUiTest {
            setSheet()
            onNodeWithText("Manage groups").performClick()
            onNodeWithTag("all-groups-sheet").assertDoesNotExist()
            assertEquals(
                listOf(
                    RecordingAppNavigator.Call.PopEntry(AllGroupsKey),
                    RecordingAppNavigator.Call.Push(GroupsManageKey),
                ),
                fixture.recording.calls,
            )
        }

    @Test
    fun escapeClosesAndReturnsFocusToOpenerWithoutChangingSelection() =
        runComposeUiTest {
            setSheet()
            onNodeWithText("New group").assertIsFocused()
            onNodeWithTag("all-groups-sheet").performKeyInput { pressKey(Key.Escape) }
            onNodeWithTag("all-groups-sheet").assertDoesNotExist()
            onNodeWithText("Open groups").assertIsFocused()
            assertEquals(
                listOf<RecordingAppNavigator.Call>(RecordingAppNavigator.Call.PopEntry(AllGroupsKey)),
                fixture.recording.calls,
            )
            assertEquals("all", runBlocking { fixture.settings.get(AppearanceSettingKeys.FEEDS_SELECTED_SOURCE) })
        }

    @Test
    fun failedSelectionKeepsSheetOpenAndAllowsRetry() =
        runComposeUiTest {
            fixture.settings.failNextSet = SettingsError.OutOfRange("disk")
            setSheet()
            val tile = onNodeWithTag("recent:${allGroupsTestGroup(6).uuid}")
            tile.performClick()
            waitForIdle()
            onNodeWithTag("all-groups-sheet").assertIsDisplayed()
            assertTrue(fixture.recording.calls.isEmpty())
            onNodeWithText("Couldn't save the change").assertIsDisplayed()
            assertEquals("all", runBlocking { fixture.settings.get(AppearanceSettingKeys.FEEDS_SELECTED_SOURCE) })
            tile.performClick()
            waitForIdle()
            assertSelection(6)
            onNodeWithTag("all-groups-sheet").assertDoesNotExist()
        }

    @Test
    fun scrimClosesWithoutChangingSelection() =
        runComposeUiTest {
            setSheet()
            onNodeWithContentDescription("Close sheet").performTouchInput { click(Offset(4f, 4f)) }
            onNodeWithTag("all-groups-sheet").assertDoesNotExist()
            assertEquals(
                listOf<RecordingAppNavigator.Call>(RecordingAppNavigator.Call.PopEntry(AllGroupsKey)),
                fixture.recording.calls,
            )
            assertEquals("all", runBlocking { fixture.settings.get(AppearanceSettingKeys.FEEDS_SELECTED_SOURCE) })
        }

    @Test
    fun androidLeavesInitialFocusToModalSheet() =
        runComposeUiTest {
            setContent { fixture.Content(SheetHost.OPENER, PlatformKind.ANDROID) }
            onNodeWithText("Open groups").performClick()
            onNodeWithText("New group").assertIsNotFocused()
        }

    private fun ComposeUiTest.setSheet() {
        setContent { fixture.Content(SheetHost.OPENER) }
        onNodeWithText("Open groups").performClick()
    }

    private fun assertSelection(id: Long) {
        assertEquals(
            FeedSourceRef.Group(allGroupsTestGroup(id).uuid).encode(),
            runBlocking { fixture.settings.get(AppearanceSettingKeys.FEEDS_SELECTED_SOURCE) },
        )
    }
}

private enum class SheetHost { OPENER, FEEDS, RETAINED_EXIT }

@OptIn(ExperimentalCoroutinesApi::class)
private class AllGroupsSheetFixture {
    val groups =
        FakeGroupRepository().apply {
            groups.value =
                (1L..6L).map { allGroupsTestGroup(it, lastViewedAt = it * 100) }
        }
    val settings = FakeSettingsRepository()
    val recording = RecordingAppNavigator()
    lateinit var navigation: NavigationState
    lateinit var feedModel: FeedsViewModel
    private val feeds = FakeFeedRepository()
    private val store = ViewModelStore()
    private val clock = TestClock()
    private var open by mutableStateOf(false)
    private val navigator =
        object : AppNavigator by recording {
            override fun pop(key: NavKey): Boolean {
                val popped = recording.pop(key)
                if (popped) open = false
                return popped
            }
        }

    @Suppress("ktlint:compose:modifier-missing-check")
    @Composable
    fun Content(
        host: SheetHost,
        platform: PlatformKind = PlatformKind.DESKTOP,
    ) {
        val dispatcher = rememberCoroutineScope().coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        val model =
            remember {
                Dispatchers.setMain(dispatcher)
                AllGroupsViewModel(groups, feeds, settings, AllGroupsTestCollator, clock).also {
                    store.put("sheet", it)
                }
            }
        val pagerModel =
            remember {
                val visible = groups.groups.value.filter { it.showAsTab }
                feeds.tabs.value += visible.map { FeedTab(FeedSource.Group(it.id), it.name, null, null, it.uuid) }
                FeedsViewModel(
                    feeds,
                    FakeEpisodeRepository(),
                    FakeRefreshController(),
                    FakeNetworkMonitor(FakeNetworkMonitor.ONLINE),
                    FakePodcastRepository(),
                    clock,
                    FakeEpisodeLiveStateSource(),
                    groups,
                    settings,
                    dispatcher,
                ).also {
                    feedModel = it
                    store.put("feeds", it)
                }
            }
        DisposableEffect(Unit) {
            onDispose {
                store.clear()
                Dispatchers.resetMain()
            }
        }
        val nav = rememberNavigationState(LibraryKey)
        remember(nav) {
            nav.push(PodcastKey(7))
            nav.push(AllGroupsKey)
        }
        navigation = nav
        val routeNavigator = if (host == SheetHost.RETAINED_EXIT) nav else navigator
        val owner = remember { AllGroupsOwner() }
        CompositionLocalProvider(
            LocalAppNavigator provides routeNavigator,
            LocalLifecycleOwner provides owner,
            LocalPlatformKind provides platform,
            LocalUiClock provides clock,
        ) {
            NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                if (host == SheetHost.RETAINED_EXIT) {
                    if (nav.stack(LibraryKey).lastOrNull() == PodcastKey(7)) Text("Underlying podcast")
                } else if (host == SheetHost.FEEDS) {
                    val state by pagerModel.uiState.collectAsStateWithLifecycle()
                    FeedsScreen(
                        state = state,
                        pagingFor = pagerModel::pagingFor,
                        prefsFor = pagerModel::prefsFor,
                        onRefresh = pagerModel::onRefresh,
                        onFiltersChange = pagerModel::onFilters,
                        onAction = {},
                        onMarkAllPlayedClick = {},
                        onAddPodcast = {},
                        onSearch = {},
                        onPageSettled = pagerModel::onPageSettled,
                        onAllGroups = { open = true },
                    )
                } else {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    NdTextButton("Open groups", { open = true }, Modifier.focusRequester(focus))
                }
                if (open || host == SheetHost.RETAINED_EXIT) {
                    NdModalBottomSheet(
                        onDismissRequest = { routeNavigator.pop(AllGroupsKey) },
                        initialExpansion = NdSheetExpansion.HALF,
                    ) {
                        AllGroupsRoute(model)
                    }
                }
            }
        }
    }
}

private class AllGroupsOwner : LifecycleOwner {
    override val lifecycle =
        LifecycleRegistry.createUnsafe(this).apply {
            currentState = Lifecycle.State.RESUMED
        }
}
