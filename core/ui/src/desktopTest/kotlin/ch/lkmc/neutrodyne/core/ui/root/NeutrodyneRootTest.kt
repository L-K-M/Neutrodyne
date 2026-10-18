// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.root

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import ch.lkmc.neutrodyne.core.designsystem.components.NdButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdEmptyState
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.navigation.AddPodcastKey
import ch.lkmc.neutrodyne.core.navigation.DiscoverKey
import ch.lkmc.neutrodyne.core.navigation.DownloadsKey
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.ExportKey
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.ImportKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import ch.lkmc.neutrodyne.core.navigation.PodcastKey
import ch.lkmc.neutrodyne.core.navigation.Route
import ch.lkmc.neutrodyne.core.navigation.SettingsHomeKey
import ch.lkmc.neutrodyne.core.navigation.UpNextKey
import ch.lkmc.neutrodyne.core.ui.platform.ExternalUrlOpener
import ch.lkmc.neutrodyne.core.ui.platform.FilePicker
import ch.lkmc.neutrodyne.core.ui.platform.FileSaver
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.OpenResult
import ch.lkmc.neutrodyne.core.ui.platform.PlatformActions
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.nav_feeds
import ch.lkmc.neutrodyne.core.ui.resources.nav_library
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * S9's `runComposeUiTest` checks of `NeutrodyneRoot` on the desktop JVM (01 S9): the five labelled
 * destinations, per-tab state keeping, Settings open/back, a sheet and a dialog above content,
 * Escape as back, and S11's locale switch. The destination entries are test stubs — the feature
 * modules' own installers look identical.
 */
@OptIn(ExperimentalTestApi::class)
class NeutrodyneRootTest {
    private lateinit var previousLocale: Locale

    @BeforeTest
    fun setUp() {
        // The shared test-task config sets the JVM to de_DE; these assertions name English labels.
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest
    fun tearDown() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun restoredExpandedPlayerMeasuresBeforeHidingSuite() =
        runComposeUiTest {
            var lastSlot: PlayerSlotState? = null
            setContent {
                TestRoot(
                    state = RootUiState.READY.copy(hasNowPlaying = true),
                    slots =
                        RootSlots(
                            player = { slot ->
                                lastSlot = slot
                                androidx.compose.runtime.SideEffect { slot.onExpandedSettled(true) }
                            },
                            userMessages = emptyFlow(),
                        ),
                )
            }
            runOnIdle { kotlin.test.assertTrue(requireNotNull(lastSlot).contentBottomPx > 0f) }
        }

    @Test
    fun rootProvidesDesktopBackDispatcherToThePlayerSlot() =
        runComposeUiTest {
            var owner: androidx.navigationevent.NavigationEventDispatcherOwner? = null
            setContent {
                TestRoot(
                    slots =
                        RootSlots(player = {
                            owner = androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner.current
                        }, userMessages = emptyFlow()),
                )
            }
            runOnIdle { kotlin.test.assertNotNull(owner) }
        }

    @Test
    fun playerSlotNavigationRequestsCollapse() =
        runComposeUiTest {
            var slot: PlayerSlotState? = null
            setContent { TestRoot(slots = RootSlots(player = { slot = it }, userMessages = emptyFlow())) }
            runOnIdle { requireNotNull(slot).navigate(PodcastKey(3)) }
            onNodeWithText("podcast-3").assertIsDisplayed()
            runOnIdle { kotlin.test.assertEquals(1, slot?.collapseRequest) }
        }

    @Test
    fun playerExpansionRouteReachesSlotAndKeepsWideLayoutAsSheet() =
        runComposeUiTest {
            val pending = Channel<Route>(Channel.UNLIMITED)
            val routes = pending.receiveAsFlow()
            var lastSlot: PlayerSlotState? = null
            setContent {
                TestRoot(
                    routes = routes,
                    state = RootUiState.READY.copy(hasNowPlaying = true),
                    slots = RootSlots(player = { slot -> lastSlot = slot }, userMessages = emptyFlow()),
                )
            }
            runOnIdle { pending.trySend(Route.ExpandPlayer) }
            waitForIdle()
            runOnIdle {
                kotlin.test.assertEquals(1, lastSlot?.expandRequest)
                kotlin.test.assertEquals(false, lastSlot?.layout?.playerPanel)
                kotlin.test.assertTrue(requireNotNull(lastSlot).contentBottomPx > 0f)
                lastSlot?.onExpandedSettled?.invoke(true)
            }
            onNodeWithTag("nav_feeds").assertDoesNotExist()
            runOnIdle { lastSlot?.onExpandedSettled?.invoke(false) }
            onNodeWithTag("nav_feeds").assertExists()
        }

    @Test
    fun screenRoutesRequestPlayerCollapse() =
        runComposeUiTest {
            val pending = Channel<Route>(Channel.UNLIMITED)
            val routes = pending.receiveAsFlow()
            var lastSlot: PlayerSlotState? = null
            setContent {
                TestRoot(
                    routes = routes,
                    slots = RootSlots(player = { slot -> lastSlot = slot }, userMessages = emptyFlow()),
                )
            }
            runOnIdle { pending.trySend(Route.Push(PodcastKey(3))) }
            onNodeWithText("podcast-3").assertIsDisplayed()
            runOnIdle { kotlin.test.assertEquals(1, lastSlot?.collapseRequest) }
        }

    @Test
    fun groupNotificationSelectsFeedsInsteadOfDroppingTheRoute() =
        runComposeUiTest {
            val pending = Channel<Route>(Channel.UNLIMITED)
            val routes = pending.receiveAsFlow()
            val selected = mutableListOf<String?>()
            setContent {
                TestRoot(routes = routes, selectFeedSource = {
                    selected += it
                    true
                })
            }
            onNodeWithTag("nav_library").performClick()
            onNodeWithText("count:0").assertIsDisplayed()
            val route =
                ch.lkmc.neutrodyne.core.navigation.IntentRouter.route(
                    ch.lkmc.neutrodyne.core.navigation.RouteInput(
                        ch.lkmc.neutrodyne.core.navigation.RouteInput.Action.VIEW,
                        "neutrodyne://open/group/tech",
                        internal = true,
                    ),
                )
            kotlin.test.assertEquals(Route.SelectFeed("tech"), route)
            runOnIdle { pending.trySend(route) }
            onNodeWithText("count:0").assertDoesNotExist()
            onNodeWithText("feeds-content").assertIsDisplayed()
            runOnIdle { kotlin.test.assertEquals<List<String?>>(listOf("tech"), selected) }
        }

    @Test
    fun selectingAllFeedsClearsTheFeedsDetailStack() =
        runComposeUiTest {
            val pending = Channel<Route>(Channel.UNLIMITED)
            val selected = mutableListOf<String?>()
            setContent {
                TestRoot(routes = pending.receiveAsFlow(), selectFeedSource = {
                    selected += it
                    true
                })
            }
            onNodeWithText("open-podcast").performClick()
            onNodeWithText("podcast-3").assertIsDisplayed()

            runOnIdle { pending.trySend(Route.SelectFeed(null)) }
            onNodeWithText("podcast-3").assertDoesNotExist()
            onNodeWithText("feeds-content").assertIsDisplayed()
            runOnIdle { kotlin.test.assertEquals<List<String?>>(listOf(null), selected) }
        }

    @Test
    fun failedFeedSelectionKeepsTheCurrentTabAndReportsTheFailure() =
        runComposeUiTest {
            val pending = Channel<Route>(Channel.UNLIMITED)
            setContent { TestRoot(routes = pending.receiveAsFlow(), selectFeedSource = { false }) }
            onNodeWithTag("nav_library").performClick()

            runOnIdle { pending.trySend(Route.SelectFeed("tech")) }
            onNodeWithText("count:0").assertIsDisplayed()
            onNodeWithText("Couldn't save the change").assertIsDisplayed()
        }

    @Test
    fun importRoutesWaitForStartupAndWarmDeliveryReplacesThePreview() =
        runComposeUiTest {
            val pending = Channel<Route>(Channel.UNLIMITED)
            val routes = pending.receiveAsFlow()
            var startup by mutableStateOf<StartupGateState>(StartupGateState.Pending)
            pending.trySend(Route.Navigate(LibraryKey, listOf(ImportKey("42"))))
            setContent { TestRoot(startup, routes) }
            onNodeWithText("import-42").assertDoesNotExist()

            runOnIdle { startup = StartupGateState.Ready }
            onNodeWithText("import-42").assertIsDisplayed()
            runOnIdle { pending.trySend(Route.Navigate(LibraryKey, listOf(ImportKey("43")))) }
            onNodeWithText("import-43").assertIsDisplayed()
            onNodeWithText("import-42").assertDoesNotExist()
            pressEscape()
            onNodeWithText("count:0").assertIsDisplayed()
        }

    @Test
    fun fiveDestinationsAreLabelled() =
        runComposeUiTest {
            setRootContent()
            // The labels appear in the rail items (tagged nav_*) and again in the stubs' top bars.
            for (tag in listOf("nav_feeds", "nav_library", "nav_up_next", "nav_downloads", "nav_discover")) {
                onNodeWithTag(tag).assertIsDisplayed()
            }
            onNodeWithText("feeds-content").assertIsDisplayed()
        }

    @Test
    fun tabSwitchKeepsEntryState() =
        runComposeUiTest {
            setRootContent()
            onNodeWithTag("nav_library").performClick()
            onNodeWithText("inc").performClick()
            onNodeWithText("count:1").assertIsDisplayed()

            // Library's entries leave composition entirely; rememberSaveable must restore the count.
            onNodeWithTag("nav_feeds").performClick()
            onNodeWithText("count:1").assertDoesNotExist()

            onNodeWithTag("nav_library").performClick()
            onNodeWithText("count:1").assertIsDisplayed()
        }

    @Test
    fun settingsOpensAndBackReturns() =
        runComposeUiTest {
            setRootContent()
            // Two gears exist in a rail suite: the footer gear and the stub's top-bar one.
            onAllNodesWithContentDescription("Settings").onFirst().performClick()
            onNodeWithText("settings-home").assertIsDisplayed()

            pressEscape()
            onNodeWithText("settings-home").assertDoesNotExist()
            onNodeWithText("feeds-content").assertIsDisplayed()
        }

    @Test
    fun reTappingTheSelectedDestinationReturnsToItsRoot() =
        runComposeUiTest {
            setRootContent()
            onNodeWithText("open-podcast").performClick()
            waitForIdle()
            onNodeWithText("podcast-3").assertIsDisplayed()

            // 08 Re-tap: the second tap on the shown destination pops its stack to the root.
            onNodeWithTag("nav_feeds").performClick()
            waitForIdle()
            onNodeWithText("podcast-3").assertDoesNotExist()
            onNodeWithText("feeds-content").assertIsDisplayed()
        }

    @Test
    fun sheetRendersAboveContent() =
        runComposeUiTest {
            setRootContent()
            onNodeWithText("open-sheet").performClick()
            onNodeWithText("add-podcast-sheet").assertIsDisplayed()
            onNodeWithText("feeds-content").assertIsDisplayed()
        }

    @Test
    fun sheetWindowDismissClosesOnlyOverlay() =
        runComposeUiTest {
            setRootContent()
            onNodeWithText("open-sheet").performClick()
            onAllNodesWithContentDescription("Close sheet").onFirst().performTouchInput { click(Offset(4f, 4f)) }
            waitForIdle()
            onNodeWithText("add-podcast-sheet").assertDoesNotExist()
            onNodeWithText("feeds-content").assertIsDisplayed()
        }

    @Test
    fun dialogRendersAboveContent() =
        runComposeUiTest {
            setRootContent()
            onNodeWithText("open-dialog").performClick()
            onNodeWithText("export-dialog").assertIsDisplayed()
            onNodeWithText("feeds-content").assertIsDisplayed()
        }

    @Test
    fun aPoppedDialogsSnackbarSurvivesOnTheRootScope() =
        runComposeUiTest {
            // The export dialog's post-pop snackbar rides `LocalRootScope`: it must outlive the
            // disposed entry but still end when the root leaves composition.
            var rootAlive by mutableStateOf(true)
            setContent { if (rootAlive) TestRoot() }

            onNodeWithText("open-dialog").performClick()
            onNodeWithText("export-done").performClick()

            onNodeWithText("export-dialog").assertDoesNotExist()
            onNodeWithText("Saved").assertIsDisplayed()

            runOnIdle { rootAlive = false }
            waitUntil(timeoutMillis = 5_000) { capturedRootScope?.isActive == false }
        }

    @Test
    fun escapePopsAnEntry() =
        runComposeUiTest {
            setRootContent()
            onNodeWithText("open-podcast").performClick()
            waitForIdle()
            onNodeWithText("podcast-3").assertIsDisplayed()

            pressEscape()
            onNodeWithText("podcast-3").assertDoesNotExist()
            onNodeWithText("feeds-content").assertIsDisplayed()
        }

    /**
     * Owner requirement: tab switches settle in zero animation frames. The outgoing tab's content
     * is gone three frames after the tap (two frames still cover the composition swap
     * mechanics) — the 700 ms default fade would still render it.
     */
    @Test
    fun tabSwitchIsImmediate() =
        runComposeUiTest {
            setRootContent()
            mainClock.autoAdvance = false
            onNodeWithTag("nav_library").performClick()
            repeat(3) { mainClock.advanceTimeByFrame() }

            onNodeWithText("feeds-content").assertDoesNotExist()
            onNodeWithText("count:0").assertIsDisplayed()
            mainClock.autoAdvance = true
        }

    /**
     * Tapping Feeds while Library is selected shrinks the rendered entries to a strict prefix —
     * the same shape a back-to-Feeds produces — so only the recorded action can keep this tap
     * from animating like a pop. The outgoing tab must still leave immediately.
     */
    @Test
    fun tappingFeedsFromAnotherTabIsImmediate() =
        runComposeUiTest {
            setRootContent()
            onNodeWithTag("nav_library").performClick()
            waitForIdle()

            mainClock.autoAdvance = false
            onNodeWithTag("nav_feeds").performClick()
            repeat(3) { mainClock.advanceTimeByFrame() }

            onNodeWithText("count:0").assertDoesNotExist()
            onNodeWithText("feeds-content").assertIsDisplayed()
            mainClock.autoAdvance = true
        }

    /**
     * Owner requirement: a back pop reveals the previous entry and does not leave the outgoing
     * scene lingering — it is gone within the few frames any content swap needs on this harness.
     * The horizontal wipe animation itself renders only on device; its spec mapping is pinned by
     * `NdSceneTransitionTest.backPopWipesTheOutgoingScene`.
     */
    @Test
    fun backPopRevealsThePreviousEntryImmediately() =
        runComposeUiTest {
            setRootContent()
            onNodeWithText("open-podcast").performClick()
            waitForIdle()
            onNodeWithText("podcast-3").assertIsDisplayed()

            mainClock.autoAdvance = false
            onNodeWithTag("root-box").performKeyInput {
                keyDown(Key.Escape)
                keyUp(Key.Escape)
            }
            repeat(3) { mainClock.advanceTimeByFrame() }

            onNodeWithText("podcast-3").assertDoesNotExist()
            onNodeWithText("feeds-content").assertIsDisplayed()
            mainClock.autoAdvance = true
        }

    @Test
    fun localeSwitchRelabelsTheSuite() =
        runComposeUiTest {
            var localeTag by mutableStateOf("")
            setContent {
                key(localeTag) { TestRoot() }
            }
            onNodeWithText("Library").assertIsDisplayed()

            runOnIdle {
                Locale.setDefault(Locale.GERMAN)
                localeTag = "de"
            }
            onNodeWithText("Bibliothek").assertIsDisplayed()
            onNodeWithText("Als Nächstes").assertIsDisplayed()
        }

    @Test fun importSlotStaysOnRootsAndNeverStacksPreviews() =
        runComposeUiTest {
            val slots =
                RootSlots(player = {}, userMessages = emptyFlow(), importResume = { available, open ->
                    if (available) {
                        NdButton(
                            "review-import",
                            onClick = {
                                open(1)
                            },
                        )
                    }
                    available
                })
            setContent { TestRoot(slots = slots) }
            onNodeWithText("review-import").assertExists()
            onNodeWithTag("nav_library").performClick()
            onNodeWithText("review-import").assertExists()
            onNodeWithText("review-import").performClick()
            onNodeWithText("import-1").assertExists()
            onNodeWithText("review-import").assertDoesNotExist()
            pressEscape()
            onNodeWithText("review-import").assertExists()
            onNodeWithTag("nav_discover").performClick()
            onNodeWithText("review-import").assertDoesNotExist()
        }

    @Test fun closedStartupGateNeverComposesImportServices() =
        runComposeUiTest {
            var state by mutableStateOf(RootUiState.READY.copy(startup = StartupGateState.Pending))
            var calls = 0
            val slots =
                RootSlots(player = {}, userMessages = emptyFlow(), importResume = { _, _ ->
                    calls++
                    false
                })
            setContent { TestRoot(slots = slots, state = state) }
            waitForIdle()
            kotlin.test.assertEquals(0, calls)
            runOnIdle { state = RootUiState.READY }
            waitForIdle()
            kotlin.test.assertTrue(calls > 0)
        }

    @Test fun importPriorityKeepsTheRootAtTwoBanners() =
        runComposeUiTest {
            var state by mutableStateOf(
                RootUiState.READY.copy(
                    heldChanges = HeldChangesBanner(1, "Other device", 2, 1),
                    notice = RootNotice.FIRST_RUN_CHOICE,
                ),
            )
            val slots =
                RootSlots(player = {}, userMessages = emptyFlow(), importResume = { available, _ ->
                    if (available) Text("import-banner")
                    available
                })
            setContent { TestRoot(slots = slots, state = state) }
            onNodeWithText("import-banner").assertExists()
            onNodeWithText("Turn off").assertDoesNotExist()
            runOnIdle { state = state.copy(heldChanges = null) }
            onNodeWithText("import-banner").assertExists()
            onNodeWithText("Turn off").assertExists()
        }

    private fun ComposeUiTest.setRootContent() {
        setContent { TestRoot() }
    }

    private fun ComposeUiTest.pressEscape() {
        // Key events dispatch through the focused chain up to the tagged box's onKeyEvent.
        onNodeWithTag("root-box").performKeyInput {
            keyDown(Key.Escape)
            keyUp(Key.Escape)
        }
        waitForIdle()
    }
}

/** Minimal `PlatformActions` for the desktop test composition (the shells' contract). */
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

private fun testActions(selectFeedSource: suspend (String?) -> Boolean = { true }): RootActions =
    RootActions(
        retryStartup = {},
        dismissNotice = {},
        continueHere = {},
        dismissRemoteSession = {},
        playbackKey = { false },
        selectFeedSource = selectFeedSource,
    )

private val TestSlots = RootSlots(player = {}, userMessages = emptyFlow())

/**
 * The root under test with the shells' `LocalPlatformActions` in place, exactly as `NeutrodyneWindow`
 * composes it.
 */
@Composable
private fun TestRoot(
    startup: StartupGateState = StartupGateState.Ready,
    routes: Flow<Route> = emptyFlow(),
    selectFeedSource: suspend (String?) -> Boolean = { true },
    slots: RootSlots = TestSlots,
    state: RootUiState = RootUiState.READY.copy(startup = startup),
) {
    CompositionLocalProvider(LocalPlatformActions provides TestPlatformActions) {
        NeutrodyneRoot(
            state = state,
            routes = routes,
            actions = testActions(selectFeedSource),
            slots = slots,
            installers = TestInstallers,
            platform = BuildInfo.Platform.DESKTOP,
            modifier = Modifier.testTag("root-box"),
        )
    }
}

private val TestInstallers: Set<EntryProviderInstaller> =
    buildSet {
        add { entry<FeedsKey>(metadata = NdSceneMetadata.paneList()) { FeedsStub() } }
        add { entry<LibraryKey>(metadata = NdSceneMetadata.paneList()) { LibraryStub() } }
        add {
            entry<UpNextKey>(metadata = NdSceneMetadata.paneList()) {
                TabStub("up-next-content", NdIcons.QueueMusic)
            }
        }
        add {
            entry<DownloadsKey>(metadata = NdSceneMetadata.paneList()) {
                TabStub("downloads-content", NdIcons.Download)
            }
        }
        add {
            entry<DiscoverKey>(metadata = NdSceneMetadata.paneList()) {
                TabStub("discover-content", NdIcons.Explore)
            }
        }
        add { entry<ImportKey>(metadata = NdSceneMetadata.paneDetail()) { Text("import-${it.sessionId}") } }
        add { entry<SettingsHomeKey>(metadata = NdSceneMetadata.paneList()) { Text("settings-home") } }
        add {
            entry<PodcastKey>(metadata = NdSceneMetadata.paneDetail()) { key ->
                Text("podcast-${key.podcastId}")
            }
        }
        add { entry<AddPodcastKey>(metadata = NdSceneMetadata.bottomSheet()) { Text("add-podcast-sheet") } }
        add { entry<ExportKey>(metadata = NdSceneMetadata.dialog()) { ExportDialogStub(it) } }
    }

@Composable
private fun FeedsStub() {
    val navigator = LocalAppNavigator.current
    Column(Modifier.fillMaxSize()) {
        TestTopBar(Res.string.nav_feeds)
        Text("feeds-content")
        NdButton(label = "open-sheet", onClick = { navigator.push(AddPodcastKey(null)) })
        NdButton(label = "open-dialog", onClick = { navigator.push(ExportKey(null)) })
        NdButton(label = "open-podcast", onClick = { navigator.push(PodcastKey(3)) })
    }
}

@Composable
private fun LibraryStub() {
    var count by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TestTopBar(Res.string.nav_library)
        Text("count:$count")
        NdButton(label = "inc", onClick = { count++ })
    }
}

/**
 * The last `LocalRootScope` seen by [ExportDialogStub] — the assertion that the root-owned job
 * ends when the root leaves composition needs the scope itself, not the dismissed snackbar.
 */
private var capturedRootScope: CoroutineScope? = null

/**
 * Mirrors `ExportRoute`'s terminal flow for the seam test: pop the entry, then post the "Saved"
 * snackbar on `LocalRootScope` — the work must survive the entry disposal the pop causes.
 */
@Composable
private fun ExportDialogStub(key: ExportKey) {
    val navigator = LocalAppNavigator.current
    val snackbar = LocalSnackbarHost.current
    val rootScope = LocalRootScope.current
    capturedRootScope = rootScope
    Column {
        Text("export-dialog")
        NdButton(
            label = "export-done",
            onClick = {
                navigator.pop(key)
                rootScope.launch { snackbar.showSnackbar("Saved", "Show in folder") }
            },
        )
    }
}

@Composable
private fun TabStub(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Column(Modifier.fillMaxSize()) {
        NdEmptyState(icon = icon, title = text, body = text)
    }
}

@Composable
private fun TestTopBar(title: StringResource) {
    NdTopAppBar(
        title = stringResource(title),
        actions = { SettingsGearButton() },
    )
}
