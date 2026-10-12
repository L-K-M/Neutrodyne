// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import ch.lkmc.neutrodyne.core.designsystem.theme.LocalReducedMotion
import ch.lkmc.neutrodyne.core.navigation.AddPodcastKey
import ch.lkmc.neutrodyne.core.navigation.DiscoverKey
import ch.lkmc.neutrodyne.core.navigation.DownloadsKey
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.EpisodeKey
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.LicencesKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import ch.lkmc.neutrodyne.core.navigation.PodcastKey
import ch.lkmc.neutrodyne.core.navigation.SettingsHomeKey
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.UpNextKey
import org.junit.Rule
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * The shared Robolectric host for the navigation tests: the real [NeutrodyneNavHost] wiring
 * under native graphics — frames advance through the test clock and [captureToImage] reads the
 * pixels Skia actually drew, so mid-transition state is observable here even though the desktop
 * Skiko harness settles every spec instantly. Suites derive fixtures through public behavior
 * only: [NavigationState] calls, semantics nodes, pixel columns and the probe page's
 * `viewModel()`/`rememberSaveable` triple — never a production implementation detail.
 */
@Suppress("TooManyFunctions") // shared harness: host + clock + pixel helpers accumulate here
internal abstract class NdNavHostHarness {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    internal lateinit var navigation: NavigationState
    internal var dispatcher: NavigationEventDispatcher? = null
    internal var hostContent: (@Composable () -> Unit)? = null

    /** The live saveable registry when [setFixedHost] runs with [restoredValues] support. */
    internal var saveableRegistry: SaveableStateRegistry? = null

    /**
     * Builds [hostContent] and returns the directive state — assigning a new directive resizes
     * the live host. When [standaloneRegistry] is set, the host roots a standalone
     * [SaveableStateRegistry] seeded with [restoredValues] (a fresh-process import fixture);
     * the parcel-export stage must pass it too, since saveable auto-keys are positional and
     * both stages need the same composition shape. Left off, the ambient activity registry
     * applies — the path activity recreation restores through.
     */
    internal fun buildFixedHost(
        installers: Set<EntryProviderInstaller> = HostInstallers,
        directive: PaneScaffoldDirective? = null,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        reducedMotion: Boolean = false,
        standaloneRegistry: Boolean = false,
        restoredValues: Map<String, List<Any?>>? = null,
    ): MutableState<PaneScaffoldDirective?> {
        val directiveState = mutableStateOf(directive)
        hostContent = {
            val body: @Composable () -> Unit = {
                saveableRegistry = LocalSaveableStateRegistry.current
                val state = rememberNavigationState()
                navigation = state
                dispatcher =
                    LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
                CompositionLocalProvider(
                    LocalAppNavigator provides state,
                    LocalLayoutDirection provides layoutDirection,
                    LocalReducedMotion provides reducedMotion,
                ) {
                    NeutrodyneNavHost(
                        state = state,
                        installers = installers,
                        directive =
                            directiveState.value
                                ?: calculatePaneScaffoldDirective(
                                    currentWindowAdaptiveInfo(
                                        supportLargeAndXLargeWidth = true,
                                    ),
                                ),
                    )
                }
            }
            if (standaloneRegistry) {
                val registry =
                    remember { SaveableStateRegistry(restoredValues) { true } }
                CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                    body()
                }
            } else {
                body()
            }
        }
        return directiveState
    }

    /** Builds [hostContent] and installs it through the test rule. */
    internal fun setFixedHost(
        installers: Set<EntryProviderInstaller> = HostInstallers,
        directive: PaneScaffoldDirective? = null,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        reducedMotion: Boolean = false,
        standaloneRegistry: Boolean = false,
        restoredValues: Map<String, List<Any?>>? = null,
    ): MutableState<PaneScaffoldDirective?> {
        val directiveState =
            buildFixedHost(
                installers,
                directive,
                layoutDirection,
                reducedMotion,
                standaloneRegistry,
                restoredValues,
            )
        rule.setContent(requireNotNull(hostContent))
        settle()
        return directiveState
    }

    /**
     * The pre-fix call site verbatim: `NavDisplay` with no transition specs, so the Android
     * runtime's `defaultTransitionSpec`/`defaultPopTransitionSpec` (fadeIn/fadeOut 700 ms) apply.
     */
    internal fun setDefaultsHost(backStack: NavBackStack<NavKey>) {
        rule.setContent {
            val entries =
                rememberDecoratedNavEntries(
                    backStack = backStack,
                    entryDecorators =
                        listOf(
                            rememberSaveableStateHolderNavEntryDecorator(),
                            rememberViewModelStoreNavEntryDecorator(),
                        ),
                    entryProvider = { key -> DefaultsProvider(key) },
                )
            SharedTransitionLayout {
                NavDisplay(
                    entries = entries,
                    onBack = { backStack.removeAt(backStack.lastIndex) },
                    sceneStrategies =
                        listOf(
                            rememberListDetailSceneStrategy(
                                directive =
                                    calculatePaneScaffoldDirective(
                                        currentWindowAdaptiveInfo(
                                            supportLargeAndXLargeWidth = true,
                                        ),
                                    ),
                            ),
                        ),
                    sharedTransitionScope = this,
                )
            }
        }
        settle()
    }

    internal fun advanceFrames(frames: Int) {
        // The semantics fetch after each frame forces pending recompositions to apply — plain
        // advanceTimeByFrame leaves a queued pass un-run until the next sync point.
        repeat(frames) {
            rule.mainClock.advanceTimeByFrame()
            rule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        }
    }

    /** Pumps one frame at a time and reports whether [condition] held within [maxFrames]. */
    internal fun advanceFramesUntil(
        maxFrames: Int,
        condition: () -> Boolean,
    ): Boolean {
        repeat(maxFrames) {
            rule.mainClock.advanceTimeByFrame()
            if (condition()) return true
        }
        return false
    }

    /** Advances one frame at a time; frames until [tag] has no node left, or [maxFrames]. */
    internal fun framesUntilAbsent(
        tag: String,
        maxFrames: Int,
    ): Int {
        repeat(maxFrames) { frame ->
            rule.mainClock.advanceTimeByFrame()
            rule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
            if (rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) return frame + 1
        }
        return maxFrames
    }

    /** Advances one frame at a time; frames until [tag] first has a node, or [maxFrames]. */
    internal fun framesUntilPresent(
        tag: String,
        maxFrames: Int,
    ): Int {
        repeat(maxFrames) { frame ->
            rule.mainClock.advanceTimeByFrame()
            rule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
            if (rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) return frame + 1
        }
        return maxFrames
    }

    internal fun settle() {
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
    }

    /** Leftmost column whose pixels are mostly [sentinel], or -1 when the sentinel is gone. */
    internal fun leftEdgeOf(
        pixels: PixelMap,
        sentinel: Color,
    ): Int {
        for (x in 0 until pixels.width) {
            var hits = 0
            for (y in 0 until pixels.height step PIXEL_STRIDE) {
                if (pixels[x, y].isCloseTo(sentinel)) hits++
            }
            if (hits * PIXEL_STRIDE > pixels.height / 2) return x
        }
        return -1
    }

    /** Rightmost column whose pixels are mostly [sentinel], or -1 when the sentinel is gone. */
    internal fun rightEdgeOf(
        pixels: PixelMap,
        sentinel: Color,
    ): Int {
        for (x in pixels.width - 1 downTo 0) {
            var hits = 0
            for (y in 0 until pixels.height step PIXEL_STRIDE) {
                if (pixels[x, y].isCloseTo(sentinel)) hits++
            }
            if (hits * PIXEL_STRIDE > pixels.height / 2) return x
        }
        return -1
    }

    /** Any pixel that is a blend of two sentinels — a fade signature, never a wipe's. */
    internal fun hasBlendedPixels(
        pixels: PixelMap,
        a: Color,
        b: Color,
    ): Boolean {
        for (y in 0 until pixels.height step PIXEL_STRIDE) {
            for (x in 0 until pixels.width step PIXEL_STRIDE) {
                val p = pixels[x, y]
                if (!p.isCloseTo(a) && !p.isCloseTo(b) && p.alpha > 0.9f) return true
            }
        }
        return false
    }

    internal fun Color.isCloseTo(other: Color): Boolean =
        alpha > 0.9f &&
            abs(red - other.red) < COLOR_TOLERANCE &&
            abs(green - other.green) < COLOR_TOLERANCE &&
            abs(blue - other.blue) < COLOR_TOLERANCE

    internal companion object {
        /** 128 ms — long past an instant swap, nowhere near a 700 ms fade. */
        const val INSTANT_FRAME_BOUND = 8

        /** Entry teardown can trail the visual swap by a few frames on this runtime. */
        const val TEARDOWN_FRAME_BOUND = 12

        /** 256 ms — the wipe motion measured ~13 frames here. */
        const val WIPE_FRAME_BOUND = 16
        const val GESTURE_FRAME_BOUND = 16

        /** 640 ms — still inside the 700 ms default fade. */
        const val FADE_FRAME_BOUND = 40

        /** A retargeted swap is dead by the next pass — this window only watches ghosts. */
        const val GHOST_WATCH_FRAME_BOUND = 60

        /** 384 ms — a collapsed-pane commit takes a few frames; the stale stall was ~544 ms. */
        const val PROMPT_RELEASE_FRAME_BOUND = 24

        /** 768 ms — the collapse cover plus pop wipe together. */
        const val COLLAPSE_EXIT_FRAME_BOUND = 48

        const val PIXEL_STRIDE = 4
        const val COLOR_TOLERANCE = 0.15f
    }
}

internal const val FEEDS_TAG = "feeds-pane"
internal const val LIBRARY_TAG = "library-pane"
internal const val PODCAST_TAG = "podcast-pane"
internal const val PODCAST2_TAG = "podcast-pane-2"
internal const val PODCAST3_TAG = "podcast-pane-3"
internal const val SETTINGS_TAG = "settings-pane"
internal const val ABOUT_TAG = "about-pane"
internal const val LICENCES_TAG = "licences-pane"
internal const val EXTRA_TAG = "extra-pane"
internal const val ABOUT_PAGE_TAG = "page-ABOUT"
internal const val ADD_PODCAST_TAG = "add-podcast-sheet"
internal const val PODCAST2_ID = 2L
internal const val PODCAST3_ID = 3L

internal val FEEDS_COLOR = Color(0xFFD32F2F)
internal val LIBRARY_COLOR = Color(0xFF2E7D32)
internal val PODCAST_COLOR = Color(0xFF1565C0)
internal val PODCAST2_COLOR = Color(0xFF004D40)
internal val PODCAST3_COLOR = Color(0xFFBF360C)
internal val SETTINGS_COLOR = Color(0xFFF9A825)
internal val ABOUT_COLOR = Color(0xFF00695C)
internal val LICENCES_COLOR = Color(0xFF4E342E)
internal val EXTRA_COLOR = Color(0xFF37474F)
internal val SHEET_COLOR = Color(0xFF5D4037)

/** Forces two partitions regardless of the test window size — the expanded scaffold case. */
internal val TwoPaneDirective = PaneScaffoldDirective.Default.copy(maxHorizontalPartitions = 2)

private fun MutableSet<EntryProviderInstaller>.addRootPages() {
    add { entry<FeedsKey>(metadata = NdSceneMetadata.paneList()) { Sentinel(FEEDS_COLOR, FEEDS_TAG) } }
    add {
        entry<LibraryKey>(metadata = NdSceneMetadata.paneList()) {
            Sentinel(LIBRARY_COLOR, LIBRARY_TAG)
        }
    }
    add {
        entry<UpNextKey>(metadata = NdSceneMetadata.paneList()) {
            Sentinel(Color(0xFF6A1B9A), "up-next-pane")
        }
    }
    add {
        entry<DownloadsKey>(metadata = NdSceneMetadata.paneList()) {
            Sentinel(Color(0xFF00838F), "downloads-pane")
        }
    }
    add {
        entry<DiscoverKey>(metadata = NdSceneMetadata.paneList()) {
            Sentinel(Color(0xFFEF6C00), "discover-pane")
        }
    }
}

internal val HostInstallers: Set<EntryProviderInstaller> =
    buildSet {
        addRootPages()
        add {
            entry<PodcastKey>(metadata = NdSceneMetadata.paneDetail()) {
                Sentinel(PODCAST_COLOR, PODCAST_TAG)
            }
        }
        add {
            entry<SettingsHomeKey>(metadata = NdSceneMetadata.paneList()) {
                Sentinel(SETTINGS_COLOR, SETTINGS_TAG)
            }
        }
        add {
            entry<SettingsKey>(metadata = NdSceneMetadata.paneDetail()) {
                Sentinel(ABOUT_COLOR, ABOUT_TAG)
            }
        }
        add {
            entry<LicencesKey>(metadata = NdSceneMetadata.paneDetail()) {
                Sentinel(LICENCES_COLOR, LICENCES_TAG)
            }
        }
    }

/**
 * The expanded chain with owner probes on every roled page — Settings pages report under
 * `page-<page>` tags, episodes under [EXTRA_TAG] — so interrupted swaps can assert which
 * emission stayed composed. Probe pages fill their pane with the sentinel color too, so pixel
 * checks see them exactly like plain sentinels.
 */
internal val ProbedInstallers: Set<EntryProviderInstaller> =
    buildSet {
        addRootPages()
        add {
            entry<PodcastKey>(metadata = NdSceneMetadata.paneDetail()) { key ->
                when (key.podcastId) {
                    PODCAST2_ID -> Sentinel(PODCAST2_COLOR, PODCAST2_TAG)
                    PODCAST3_ID -> Sentinel(PODCAST3_COLOR, PODCAST3_TAG)
                    else -> Sentinel(PODCAST_COLOR, PODCAST_TAG)
                }
            }
        }
        add {
            entry<SettingsHomeKey>(metadata = NdSceneMetadata.paneList()) {
                Sentinel(SETTINGS_COLOR, SETTINGS_TAG)
            }
        }
        add {
            entry<SettingsKey>(metadata = NdSceneMetadata.paneDetail()) { key ->
                OwnerProbePage("page-${key.page}", ABOUT_COLOR)
            }
        }
        add {
            entry<LicencesKey>(metadata = NdSceneMetadata.paneDetail()) {
                OwnerProbePage(LICENCES_TAG, LICENCES_COLOR)
            }
        }
        add {
            entry<EpisodeKey>(metadata = NdSceneMetadata.paneExtra()) {
                OwnerProbePage(EXTRA_TAG, EXTRA_COLOR)
            }
        }
        add {
            entry<AddPodcastKey>(metadata = NdSceneMetadata.bottomSheet()) {
                Sentinel(SHEET_COLOR, ADD_PODCAST_TAG)
            }
        }
    }

/** The same settings keys with no pane roles — each entry lands in its own scene. */
internal val DistinctInstallers: Set<EntryProviderInstaller> =
    buildSet {
        add { entry<FeedsKey> { Sentinel(FEEDS_COLOR, FEEDS_TAG) } }
        add { entry<LibraryKey> { Sentinel(LIBRARY_COLOR, LIBRARY_TAG) } }
        add { entry<UpNextKey> { Sentinel(Color(0xFF6A1B9A), "up-next-pane") } }
        add { entry<DownloadsKey> { Sentinel(Color(0xFF00838F), "downloads-pane") } }
        add { entry<DiscoverKey> { Sentinel(Color(0xFFEF6C00), "discover-pane") } }
        add { entry<SettingsHomeKey> { Sentinel(SETTINGS_COLOR, SETTINGS_TAG) } }
        add { entry<SettingsKey> { Sentinel(ABOUT_COLOR, ABOUT_TAG) } }
        add { entry<LicencesKey> { Sentinel(LICENCES_COLOR, LICENCES_TAG) } }
    }

internal val DefaultsProvider =
    entryProvider<NavKey> {
        entry<FeedsKey> { Sentinel(FEEDS_COLOR, FEEDS_TAG) }
        entry<LibraryKey> { Sentinel(LIBRARY_COLOR, LIBRARY_TAG) }
        entry<PodcastKey> { Sentinel(PODCAST_COLOR, PODCAST_TAG) }
    }

/** Records the live owner triple per page tag so frames can assert continuity. */
internal object OwnerProbe {
    val nextIdentity = AtomicInteger(1)
    val cleared = mutableListOf<Int>()
    val snapshots = mutableMapOf<String, Snapshot>()

    internal data class Snapshot(
        val vm: Int,
        val owner: Int,
        val saved: Int,
    )

    fun reset() {
        cleared.clear()
        snapshots.clear()
    }
}

private class OwnerProbeVm : ViewModel() {
    val identity = OwnerProbe.nextIdentity.getAndIncrement()

    override fun onCleared() {
        OwnerProbe.cleared += identity
    }
}

@Suppress("FunctionNaming") // composables are PascalCase
@Composable
internal fun OwnerProbePage(
    tag: String,
    color: Color,
) {
    val vm = viewModel { OwnerProbeVm() }
    val owner = LocalViewModelStoreOwner.current
    val saved = rememberSaveable { mutableIntStateOf(0) }
    SideEffect {
        OwnerProbe.snapshots[tag] =
            OwnerProbe.Snapshot(vm.identity, System.identityHashCode(owner), saved.intValue)
    }
    Column(Modifier.fillMaxSize().background(color).testTag(tag)) {
        Text("$tag|saved=${saved.intValue}")
        Button(onClick = { saved.intValue++ }, modifier = Modifier.testTag("$tag-inc")) {
            Text("increment")
        }
    }
}

@Suppress("FunctionNaming") // composables are PascalCase
@Composable
internal fun Sentinel(
    color: Color,
    tag: String,
) {
    Box(Modifier.fillMaxSize().background(color).testTag(tag))
}
