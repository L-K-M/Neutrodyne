// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.library

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performMultiModalInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.common.TitleCollator
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.domain.UnsubscribeUseCase
import ch.lkmc.neutrodyne.core.model.FeedCounts
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.FeedSourceRef
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.model.LibraryTile
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.LibrarySort
import ch.lkmc.neutrodyne.core.navigation.AddPodcastKey
import ch.lkmc.neutrodyne.core.navigation.AddToGroupsKey
import ch.lkmc.neutrodyne.core.navigation.DiscoverKey
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.GroupEditKey
import ch.lkmc.neutrodyne.core.navigation.GroupsManageKey
import ch.lkmc.neutrodyne.core.navigation.ImportFileKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.testing.FakeEpisodeRepository
import ch.lkmc.neutrodyne.core.testing.FakeFeedRepository
import ch.lkmc.neutrodyne.core.testing.FakeGroupRepository
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakeNewEpisodePoster
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
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
    private val groups = FakeGroupRepository()
    private val feeds = FakeFeedRepository()
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

    /**
     * 08's no-subscriptions empty state (94-6): "Your feeds live here" with Search podcasts,
     * Add by URL or YouTube link and Import subscriptions — not a single Add.
     */
    @Test
    fun emptyLibraryShowsOnboarding() =
        runComposeUiTest {
            setLibrary(LibraryUiState(loaded = true))
            onNodeWithText("Your feeds live here").assertIsDisplayed()
            onNodeWithText("Search podcasts").assertIsDisplayed()
            onNodeWithText("Add by URL or YouTube link").assertIsDisplayed()
        }

    @Test
    fun emptyLibraryImportOpensTheSharedPickerEntry() =
        runComposeUiTest {
            setLibraryRoute(tiles = emptyList())
            onNodeWithText("Import subscriptions").performClick()
            waitForIdle()
            assertEquals(
                listOf<RecordingAppNavigator.Call>(RecordingAppNavigator.Call.Push(ImportFileKey)),
                navigator.calls,
            )
        }

    /** "Search podcasts" selects the Discover tab through the real navigator (94-6). */
    @Test
    fun emptyLibrarySearchSelectsDiscover() =
        runComposeUiTest {
            setLibraryRoute(tiles = emptyList())
            onNodeWithText("Search podcasts").performClick()
            waitForIdle()
            assertEquals(
                listOf<RecordingAppNavigator.Call>(RecordingAppNavigator.Call.SelectTab(DiscoverKey)),
                navigator.calls,
            )
        }

    /** "Add by URL or YouTube link" pushes the add-podcast sheet (94-6, 94-1). */
    @Test
    fun emptyLibraryAddByUrlOpensAddSheet() =
        runComposeUiTest {
            setLibraryRoute(tiles = emptyList())
            onNodeWithText("Add by URL or YouTube link").performClick()
            waitForIdle()
            assertEquals(
                listOf<RecordingAppNavigator.Call>(RecordingAppNavigator.Call.Push(AddPodcastKey(null))),
                navigator.calls,
            )
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

    /**
     * 08's context-menu rule (94-4): the extras in catalogue order — Refresh, Podcast settings,
     * Mark all as played… — and the destructive Unsubscribe last after a separator.
     */
    @Test
    fun tileMenuFollowsCatalogueOrder() =
        runComposeUiTest {
            setLibrary(
                LibraryUiState(
                    tiles = listOf(testLibraryTile(5, displayTitle = "Tap Show")).toImmutableList(),
                    loaded = true,
                ),
            )
            val labels =
                onNodeWithContentDescription("Tap Show")
                    .fetchSemanticsNode()
                    .config[SemanticsActions.CustomActions]
                    .map { it.label }
            assertEquals(
                listOf("Select", "Add to groups", "Refresh", "Podcast settings", "Mark all as played…", "Unsubscribe"),
                labels,
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

    /**
     * The menu must report which sort is active (94 review): every option carries the
     * radio-button role with `selected` exposed on the current sort only — a decorative
     * check icon alone leaves screen readers guessing.
     */
    @Test
    fun sortMenuMarksOnlyTheActiveOption() =
        runComposeUiTest {
            setLibrary(LibraryUiState(loaded = true, sort = LibrarySort.RECENTLY_UPDATED))
            onNodeWithContentDescription("Sort").performClick()
            onNodeWithText("Recently updated").assertIsSelected()
            onNodeWithText("Title (A–Z)").assertIsNotSelected()
            onNodeWithText("Most unplayed").assertIsNotSelected()
            onNodeWithText("Recently added").assertIsNotSelected()
        }

    /** The titles item reports its on/off state as a switch (94 review). */
    @Test
    fun titlesItemExposesSwitchState() =
        runComposeUiTest {
            setLibrary(LibraryUiState(loaded = true, showTitles = true))
            onNodeWithContentDescription("More actions").performClick()
            onNodeWithText("Show titles").assertIsOn()
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

    /**
     * 08 marks Unsubscribe destructive — the confirm must draw in the theme's error colour,
     * not the ordinary action colour (94-3). The assertion is hue-based so antialiased glyph
     * edges blending toward the surface do not count as matches.
     */
    @Test
    fun unsubscribeConfirmUsesErrorColour() =
        runComposeUiTest {
            val tile = testLibraryTile(5, displayTitle = "Tap Show")
            setLibrary(
                LibraryUiState(tiles = listOf(tile).toImmutableList(), loaded = true),
                pendingUnsubscribe = PendingUnsubscribe(tile, downloads = 1),
            )
            val pixels =
                onAllNodes(hasText("Unsubscribe") and hasClickAction())
                    .onFirst()
                    .captureToImage()
                    .toPixelMap()
            var errorPixels = 0
            for (x in 0 until pixels.width) {
                for (y in 0 until pixels.height) {
                    if (pixels[x, y].isErrorRed()) errorPixels++
                }
            }
            assertTrue(errorPixels > 0, "the unsubscribe confirm must draw in the error colour")
        }

    /**
     * The pending unsubscribe must survive recreation (94-2): a save/restore round trip
     * through the registry keeps the dialog up, with its title re-derived from state.
     */
    @Test
    fun unsubscribeDialogSurvivesRecreation() =
        runComposeUiTest {
            podcasts.downloadedIds.value = mapOf(5L to listOf(11L))
            var restored: Map<String, List<Any?>> = emptyMap()
            var registry = SaveableStateRegistry(restored) { true }
            setLibraryRoute(registry = registry)

            onNodeWithContentDescription("Tap Show")
                .performCustomAccessibilityActionWithLabel("Unsubscribe")
            waitForIdle()
            onNodeWithText("Unsubscribe from Tap Show?").assertIsDisplayed()

            runOnIdle { restored = registry.performSave() }
            registry = SaveableStateRegistry(restored) { true }
            setLibraryRoute(registry = registry)

            onNodeWithText("Unsubscribe from Tap Show?").assertIsDisplayed()
            onNodeWithText("1 downloaded episode will be deleted.").assertIsDisplayed()
        }

    /**
     * The pending mark-all dialog carries only the podcast id, which plain remember loses —
     * the harness proves the dialog is still up after a recreate (94-2).
     */
    @Test
    fun markAllDialogSurvivesRecreation() =
        runComposeUiTest {
            var restored: Map<String, List<Any?>> = emptyMap()
            var registry = SaveableStateRegistry(restored) { true }
            setLibraryRoute(registry = registry)

            onNodeWithContentDescription("Tap Show")
                .performCustomAccessibilityActionWithLabel("Mark all as played…")
            waitForIdle()
            // The title names the podcast (94 review); the confirm keeps the bare label.
            onNodeWithText("Mark all as played in Tap Show?").assertIsDisplayed()
            onAllNodes(hasText("Mark all as played")).assertCountEquals(1)

            runOnIdle { restored = registry.performSave() }
            registry = SaveableStateRegistry(restored) { true }
            setLibraryRoute(registry = registry)

            onNodeWithText("Mark all as played in Tap Show?").assertIsDisplayed()
            onAllNodes(hasText("Mark all as played")).assertCountEquals(1)
        }

    /**
     * The count read before the unsubscribe dialog is not a write — a failure must report a
     * load problem, not "Couldn't save the change" (94-5).
     */
    @Test
    fun unsubscribeCountReadFailureShowsLoadError() =
        runComposeUiTest {
            podcasts.writeError = IllegalStateException("count read failed")
            val snackbarHost = SnackbarHostState()
            setLibraryRoute(snackbarHost = snackbarHost)

            onNodeWithContentDescription("Tap Show")
                .performCustomAccessibilityActionWithLabel("Unsubscribe")
            // The count read runs in the ViewModel's coroutine, which waitForIdle() does not track.
            waitUntil { snackbarHost.currentSnackbarData != null }

            runOnIdle {
                assertEquals(
                    "Couldn't load the data",
                    snackbarHost.currentSnackbarData?.visuals?.message,
                )
            }
        }

    @Test
    fun markAllPlayedConfirmsBeforeWriting() =
        runComposeUiTest {
            // 08's required confirmation: the tile action must not write until confirmed.
            setLibraryRoute()

            onNodeWithContentDescription("Tap Show")
                .performCustomAccessibilityActionWithLabel("Mark all as played…")
            waitForIdle()

            // The title names the podcast; the confirm keeps the bare label.
            onNodeWithText("Mark all as played in Tap Show?").assertIsDisplayed()
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

    @Test fun chipsFilterGroupAndUngrouped() =
        runComposeUiTest {
            val id = createGroup("tech")
            val a = testLibraryTile(1, "A")
            val b = testLibraryTile(2, "B")
            groups.members.value = mapOf(id to setOf(1L))
            podcasts.tilesByGroup.value = mapOf(id to listOf(a))
            setLibraryRoute(listOf(a, b))
            onNodeWithText("All").assertIsSelected()
            onNodeWithText("tech").performClick()
            onNodeWithText("tech").assertIsSelected()
            onNodeWithContentDescription("A").assertIsDisplayed()
            onNodeWithContentDescription("B").assertDoesNotExist()
            onNodeWithText("Ungrouped").performClick()
            onNodeWithContentDescription("A").assertDoesNotExist()
            onNodeWithContentDescription("B").assertIsDisplayed()
            onNodeWithText("All").performClick()
            onNodeWithContentDescription("A").assertIsDisplayed()
            onNodeWithContentDescription("B").assertIsDisplayed()
        }

    @Test fun emptyGroupOffersAddPodcasts() =
        runComposeUiTest {
            createGroup("tech")
            setLibraryRoute()
            onNodeWithText("tech").performClick()
            onNodeWithText("No podcasts in 'tech'").assertIsDisplayed()
            onNodeWithText("Add podcasts").performClick()
            assertEquals(RecordingAppNavigator.Call.Push(AddPodcastKey(null)), navigator.calls.last())
        }

    @Test fun groupsSegmentShowsCountsAndSelectsFeedsRoot() =
        runComposeUiTest {
            val id = createGroup("tech")
            runBlocking { groups.setMembers(id, setOf(1L, 2L)) }
            feeds.groupCounts.value = mapOf(id to FeedCounts(unplayed = 14, newSinceVisit = 3))
            setLibraryRoute()
            onNodeWithText("Groups").performClick()
            onNodeWithText("14 unplayed · 3 new").assertIsDisplayed()
            onNodeWithContentDescription("tech, 2 podcasts, 14 unplayed, 3 new").assertIsDisplayed().performClick()
            waitForIdle()
            assertEquals(
                listOf(
                    RecordingAppNavigator.Call.ResetTab(FeedsKey),
                    RecordingAppNavigator.Call.SelectTab(FeedsKey),
                ),
                navigator.calls,
            )
            assertEquals(
                FeedSourceRef
                    .Group(
                        groups.groups.value
                            .single()
                            .uuid,
                    ).encode(),
                runBlocking { settings.get(AppearanceSettingKeys.FEEDS_SELECTED_SOURCE) },
            )
        }

    @Test fun groupMenusAndNewGroupUseExistingDestinations() =
        runComposeUiTest {
            val id = createGroup("tech")
            setLibraryRoute()
            onNodeWithText("Groups").performClick()
            onNodeWithContentDescription("tech").performTouchInput { longClick() }
            onNodeWithText("Edit group").performClick()
            assertEquals(RecordingAppNavigator.Call.Push(GroupEditKey(id)), navigator.calls.last())
            onNodeWithContentDescription("tech").performMouseInput {
                click(button = androidx.compose.ui.test.MouseButton.Secondary)
            }
            onNodeWithText("Manage groups").performClick()
            assertEquals(RecordingAppNavigator.Call.Push(GroupsManageKey), navigator.calls.last())
            onNodeWithContentDescription("tech").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            onNodeWithContentDescription("tech").performKeyInput { pressKey(Key.Menu) }
            onNodeWithText("Edit group").performClick()
            assertEquals(RecordingAppNavigator.Call.Push(GroupEditKey(id)), navigator.calls.last())
            onNodeWithText("New group").performClick()
            assertEquals(RecordingAppNavigator.Call.Push(GroupEditKey(null)), navigator.calls.last())
        }

    @Test fun longPressAndControlClickSelectThenAddToGroups() =
        runComposeUiTest {
            setLibraryRoute(listOf(testLibraryTile(1, "A"), testLibraryTile(2, "B")))
            onNodeWithContentDescription("A").performCustomAccessibilityActionWithLabel("Add to groups")
            assertEquals(RecordingAppNavigator.Call.Push(AddToGroupsKey(listOf(1L))), navigator.calls.last())
            navigator.calls.clear()
            onNodeWithContentDescription("A").performTouchInput { longClick() }
            onNodeWithText("1 selected").assertIsDisplayed()
            onNodeWithContentDescription("A").assertIsSelected()
            onNodeWithContentDescription("B").assertIsNotSelected()
            onNodeWithContentDescription("B").performMultiModalInput {
                key { keyDown(Key.CtrlLeft) }
                mouse { click() }
                key { keyUp(Key.CtrlLeft) }
            }
            onNodeWithText("2 selected").assertIsDisplayed()
            onNodeWithContentDescription("Add to group…").performClick()
            assertEquals(RecordingAppNavigator.Call.Push(AddToGroupsKey(listOf(1L, 2L))), navigator.calls.single())
            onNodeWithText("2 selected").assertDoesNotExist()
        }

    @Test fun shiftClickExtendsAndEscapeExitsSelection() =
        runComposeUiTest {
            setLibraryRoute((1L..4L).map { testLibraryTile(it, "Show $it") })
            onNodeWithContentDescription("Show 1").performTouchInput { longClick() }
            onNodeWithContentDescription("Show 3").performMultiModalInput {
                key { keyDown(Key.ShiftLeft) }
                mouse { click() }
                key { keyUp(Key.ShiftLeft) }
            }
            onNodeWithText("3 selected").assertIsDisplayed()
            onRoot().performKeyInput {
                keyDown(Key.CtrlLeft)
                pressKey(Key.A)
                keyUp(Key.CtrlLeft)
            }
            onNodeWithText("4 selected").assertIsDisplayed()
            onRoot().performKeyInput { pressKey(Key.Escape) }
            onNodeWithText("4 selected").assertDoesNotExist()
            onNodeWithText("Library").assertIsDisplayed()
        }

    @Test fun bulkUnsubscribeConfirmsPodcastAndDownloadCounts() =
        runComposeUiTest {
            podcasts.downloadedIds.value = mapOf(1L to listOf(10L), 2L to listOf(20L, 21L))
            setLibraryRoute(listOf(testLibraryTile(1, "A"), testLibraryTile(2, "B")))
            onNodeWithContentDescription("A").performTouchInput { longClick() }
            onNodeWithContentDescription("B").performClick()
            onRoot().performKeyInput { pressKey(Key.Delete) }
            onNodeWithText("Unsubscribe from 2 podcasts?").assertIsDisplayed()
            onNodeWithText("3 downloaded episodes will be deleted.").assertIsDisplayed()
            assertEquals(listOf("downloadedEpisodeIds([1, 2])"), podcasts.calls)
            onNodeWithText("Unsubscribe").performClick()
            // Writes run in the ViewModel's coroutine, which waitForIdle() does not track.
            waitUntil { podcasts.calls.last() == "unsubscribe([1, 2])" }
        }

    @Test fun bulkMarkPlayedWaitsForCountedConfirmationAndRefreshUsesSelection() =
        runComposeUiTest {
            setLibraryRoute(listOf(testLibraryTile(1, "A"), testLibraryTile(2, "B")))
            onNodeWithContentDescription("A").performTouchInput { longClick() }
            onNodeWithContentDescription("B").performClick()
            onNodeWithContentDescription("Selection actions").performClick()
            onNodeWithText("Mark all as played…").performClick()
            onNodeWithText("Mark all episodes in 2 podcasts as played?").assertIsDisplayed()
            assertTrue(episodes.calls.isEmpty())
            onNodeWithText("Mark all as played").performClick()
            waitUntil { episodes.calls.size == 2 }
            assertEquals(
                listOf(
                    "markFeedPlayed(${FeedSource.Podcast(1)}, null)",
                    "markFeedPlayed(${FeedSource.Podcast(2)}, null)",
                ),
                episodes.calls,
            )
            onNodeWithContentDescription("A").performTouchInput { longClick() }
            onNodeWithContentDescription("Selection actions").performClick()
            onNodeWithText("Refresh").performClick()
            assertEquals(listOf("refreshNow(Podcasts(ids=[1]))"), refreshController.calls)
        }

    @Test fun selectionSurvivesRecreationAndPrunesRemovedTiles() =
        runComposeUiTest {
            var registry = SaveableStateRegistry(null) { true }
            setLibraryRoute(registry = registry)
            onNodeWithContentDescription("Tap Show").performTouchInput { longClick() }
            var restored: Map<String, List<Any?>> = emptyMap()
            onNodeWithText("1 selected").assertIsDisplayed()
            runOnIdle { restored = registry.performSave() }
            registry = SaveableStateRegistry(restored) { true }
            setLibraryRoute(registry = registry)
            onNodeWithText("1 selected").assertIsDisplayed()
            runOnIdle { podcasts.tiles.value = emptyList() }
            waitForIdle()
            onNodeWithText("1 selected").assertDoesNotExist()
        }

    @Test fun modifierClickEntersSelectionWithoutOpeningPodcast() =
        runComposeUiTest {
            setLibraryRoute()
            for (modifierKey in listOf(Key.CtrlLeft, Key.MetaLeft)) {
                onNodeWithContentDescription("Tap Show").performMultiModalInput {
                    key { keyDown(modifierKey) }
                    mouse { click() }
                    key { keyUp(modifierKey) }
                }
                onNodeWithText("1 selected").assertIsDisplayed()
                assertTrue(navigator.calls.isEmpty())
                onNodeWithContentDescription("Exit selection").performClick()
            }
        }

    @Test fun leavingLibraryClearsSelectionAndCancelKeepsSubscriptions() =
        runComposeUiTest {
            var visible by mutableStateOf(true)
            setLibraryRoute(showRoute = { visible })
            onNodeWithContentDescription("Tap Show").performTouchInput { longClick() }
            onNodeWithContentDescription("Unsubscribe").performClick()
            onNodeWithText("Unsubscribe from 1 podcast?").assertIsDisplayed()
            onNodeWithText("Cancel").performClick()
            assertTrue(podcasts.calls.none { it.startsWith("unsubscribe(") })
            onNodeWithText("1 selected").assertIsDisplayed()
            runOnIdle { visible = false }
            waitForIdle()
            runOnIdle { visible = true }
            waitForIdle()
            onNodeWithText("1 selected").assertDoesNotExist()
        }

    @Test fun restoredBulkDialogConfirmsBeforeTilesLoad() =
        runComposeUiTest {
            var registry = SaveableStateRegistry(null) { true }
            setLibraryRoute(registry = registry)
            onNodeWithContentDescription("Tap Show").performTouchInput { longClick() }
            onNodeWithContentDescription("Unsubscribe").performClick()
            onNodeWithText("Unsubscribe from 1 podcast?").assertIsDisplayed()
            var restored: Map<String, List<Any?>> = emptyMap()
            runOnIdle { restored = registry.performSave() }
            podcasts.libraryReadGate = CompletableDeferred()
            registry = SaveableStateRegistry(restored) { true }
            setLibraryRoute(registry = registry)
            onNodeWithText("Unsubscribe").performClick()
            waitForIdle()
            assertEquals("unsubscribe([5])", podcasts.calls.last())
            podcasts.libraryReadGate?.complete(Unit)
        }

    private fun createGroup(name: String): Long =
        runBlocking {
            assertIs<Outcome.Success<Long>>(groups.create(GroupDraft(name))).value
        }

    private fun ComposeUiTest.setLibrary(
        state: LibraryUiState,
        pendingUnsubscribe: PendingUnsubscribe? = null,
        pendingMarkAll: LibraryTile? = null,
        onSort: (LibrarySort) -> Unit = {},
        onToggleTitles: (Boolean) -> Unit = {},
        onOpenPodcast: (Long) -> Unit = {},
        onTileAction: (Long, TileAction) -> Unit = { _, _ -> },
        onConfirmUnsubscribe: (LibraryTile) -> Unit = {},
        onDismissUnsubscribe: () -> Unit = {},
        onConfirmMarkAll: (Long) -> Unit = {},
        onDismissMarkAll: () -> Unit = {},
        onAddPodcast: () -> Unit = {},
        onSearchPodcasts: () -> Unit = {},
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
                        pendingMarkAll = pendingMarkAll,
                        onSort = onSort,
                        onToggleTitles = onToggleTitles,
                        onOpenPodcast = onOpenPodcast,
                        onTileAction = onTileAction,
                        onConfirmUnsubscribe = onConfirmUnsubscribe,
                        onDismissUnsubscribe = onDismissUnsubscribe,
                        onConfirmMarkAll = onConfirmMarkAll,
                        onDismissMarkAll = onDismissMarkAll,
                        onAddPodcast = onAddPodcast,
                        onSearchPodcasts = onSearchPodcasts,
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
    private fun ComposeUiTest.setLibraryRoute(
        tiles: List<LibraryTile> = listOf(testLibraryTile(5, displayTitle = "Tap Show")),
        registry: SaveableStateRegistry = SaveableStateRegistry(null) { true },
        snackbarHost: SnackbarHostState = SnackbarHostState(),
        showRoute: () -> Boolean = { true },
    ) {
        podcasts.tiles.value = tiles
        val viewModel =
            LibraryViewModel(
                podcasts,
                episodes,
                refreshController,
                UnsubscribeUseCase(podcasts, FakeNewEpisodePoster()),
                settings,
                collator,
                network,
                groups,
                feeds,
                TestClock(),
                youTubeHealth =
                    ch.lkmc.neutrodyne.core.testing
                        .FakeYouTubeHealth(),
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
                    LocalSnackbarHost provides snackbarHost,
                    LocalSaveableStateRegistry provides registry,
                ) {
                    NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                        val holder = rememberSaveableStateHolder()
                        if (showRoute()) {
                            holder.SaveableStateProvider("library") {
                                LibraryRoute(viewModel = viewModel, isCurrentEntry = showRoute)
                            }
                        }
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

/**
 * True for a pixel that reads as the Material error red: hue near 0° with enough saturation
 * to exclude the amber primary (hue ~33°) and the neutral surfaces. The saturation floor
 * means only sufficiently saturated red pixels count — blends toward light surfaces and
 * antialiased glyph edges are rejected, not detected.
 */
private fun Color.isErrorRed(): Boolean {
    if (alpha <= 0f) return false
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val delta = max - min
    if (max == 0f || delta / max < 0.4f) return false
    if (delta == 0f) return false
    val hue =
        (
            when (max) {
                red -> 60f * (((green - blue) / delta) % 6f)
                green -> 60f * ((blue - red) / delta + 2f)
                else -> 60f * ((red - green) / delta + 4f)
            }
        ).let { if (it < 0f) it + 360f else it }
    return hue < 15f || hue > 345f
}
