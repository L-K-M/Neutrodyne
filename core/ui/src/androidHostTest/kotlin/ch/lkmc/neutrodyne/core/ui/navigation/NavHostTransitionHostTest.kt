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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import ch.lkmc.neutrodyne.core.navigation.DiscoverKey
import ch.lkmc.neutrodyne.core.navigation.DownloadsKey
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.LicencesKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import ch.lkmc.neutrodyne.core.navigation.PodcastKey
import ch.lkmc.neutrodyne.core.navigation.SettingsHomeKey
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import ch.lkmc.neutrodyne.core.navigation.UpNextKey
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The owner-facing transition contract (08 Transitions and shared elements) exercised against the
 * real Android `NavDisplay` runtime under Robolectric native graphics: frames advance through the
 * test clock and [captureToImage] reads the pixels Skia actually drew, so mid-transition state is
 * observable here even though the desktop Skiko harness settles every spec instantly.
 *
 * `defaultsHarness*` reproduces the pre-fix wiring verbatim — `NavDisplay` without transition
 * specs, which is what shipped when the owner reported "everything seems to be fades" — as
 * pinned-down diagnostics of the runtime defaults the fix replaced. The fixed-host tests cover
 * the three owner requirements: tab taps show no transition, a real back pops with a horizontal
 * wipe that reveals the previous entry underneath, and a predictive-back gesture seeks that same
 * wipe and restores the current scene exactly on cancel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions") // test suites accumulate test + fixture functions
class NavHostTransitionHostTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navigation: NavigationState
    private var dispatcher: NavigationEventDispatcher? = null

    // region fixed host (the real NeutrodyneNavHost wiring)

    @Test
    fun tabSwitchSettlesWithinASwapFrame() {
        setFixedHost()

        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { navigation.selectTab(LibraryKey) }

        // Instant: the outgoing tab's pixels are gone within a few frames and no blend ever
        // appears. The pre-fix 700 ms fade blended for ~28 frames and lingered ~47.
        var blendSeen = false
        val swapped =
            advanceFramesUntil(INSTANT_FRAME_BOUND) {
                val pixels = rule.onRoot().captureToImage().toPixelMap()
                if (hasBlendedPixels(pixels, FEEDS_COLOR, LIBRARY_COLOR)) blendSeen = true
                leftEdgeOf(pixels, FEEDS_COLOR) == -1 && leftEdgeOf(pixels, LIBRARY_COLOR) == 0
            }
        assertTrue(swapped, "tab switch still in flight after $INSTANT_FRAME_BOUND frames")
        assertTrue(!blendSeen, "tab switch cross-faded")

        // Composition teardown trails the visual swap by a couple of frames on this runtime.
        assertTrue(
            advanceFramesUntil(TEARDOWN_FRAME_BOUND) {
                rule.onAllNodesWithTag(FEEDS_TAG).fetchSemanticsNodes().isEmpty()
            },
            "feeds entry still composed",
        )
        assertTrue(
            rule.onAllNodesWithTag(LIBRARY_TAG).fetchSemanticsNodes().isNotEmpty(),
            "library entry not composed",
        )
    }

    @Test
    fun backPopWipesRightAndRevealsThePreviousEntry() {
        setFixedHost()
        rule.runOnUiThread { navigation.push(PodcastKey(3)) }
        settle()
        rule.mainClock.autoAdvance = false

        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }

        // A wipe: at some mid-transition frame the outgoing detail is translated right of its
        // rest edge, fully opaque, with the previous tab revealed underneath from the left.
        // A fade (the pre-fix default) never moves it and blends every pixel.
        val wiped =
            advanceFramesUntil(WIPE_FRAME_BOUND) {
                val pixels = rule.onRoot().captureToImage().toPixelMap()
                val left = leftEdgeOf(pixels, PODCAST_COLOR)
                left > 0 &&
                    leftEdgeOf(pixels, FEEDS_COLOR) == 0 &&
                    pixels[left + 2, pixels.height / 2].isCloseTo(PODCAST_COLOR)
            }
        assertTrue(wiped, "no frame showed the detail wiping right over the revealed feeds")

        settle()
        assertTrue(rule.onAllNodesWithTag(PODCAST_TAG).fetchSemanticsNodes().isEmpty())
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        assertEquals(-1, leftEdgeOf(pixels, PODCAST_COLOR), "detail still painted after the pop")
        assertEquals(0, leftEdgeOf(pixels, FEEDS_COLOR), "feeds does not fill the window")
    }

    @Test
    fun predictiveBackSeeksTheWipeAndCancelRestoresTheScene() {
        setFixedHost()
        rule.runOnUiThread { navigation.push(PodcastKey(3)) }
        settle()
        val input = DirectNavigationEventInput()
        val eventDispatcher =
            assertNotNull(dispatcher, "no NavigationEventDispatcher owner in the host")
        rule.runOnUiThread { eventDispatcher.addInput(input) }
        rule.mainClock.autoAdvance = false

        // The same event path a system predictive-back gesture drives: started, then progress.
        rule.runOnUiThread {
            input.backStarted(NavigationEvent(NavigationEvent.EDGE_LEFT, 0f, 16f, 400f))
            input.backProgressed(NavigationEvent(NavigationEvent.EDGE_LEFT, 0.45f, 300f, 400f))
        }

        val seeked =
            advanceFramesUntil(GESTURE_FRAME_BOUND) {
                val pixels = rule.onRoot().captureToImage().toPixelMap()
                leftEdgeOf(pixels, PODCAST_COLOR) > 0 && leftEdgeOf(pixels, FEEDS_COLOR) == 0
            }
        assertTrue(seeked, "predictive progress did not seek the wipe")

        rule.runOnUiThread { input.backCancelled() }

        // Cancel restores exactly: the detail returns to its rest edge and feeds is not visible.
        val restored =
            advanceFramesUntil(GESTURE_FRAME_BOUND) {
                val pixels = rule.onRoot().captureToImage().toPixelMap()
                leftEdgeOf(pixels, PODCAST_COLOR) == 0 && leftEdgeOf(pixels, FEEDS_COLOR) == -1
            }
        assertTrue(restored, "cancel did not restore the detail over feeds")

        settle()
        assertTrue(rule.onAllNodesWithTag(FEEDS_TAG).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithTag(PODCAST_TAG).fetchSemanticsNodes().isNotEmpty())
    }

    // endregion

    // region expanded two-pane (Settings Home -> About -> Licences)

    /**
     * The owner's expanded-layout case: in a two-pane scaffold a Settings Home -> About -> Licences
     * chain keeps one `ThreePaneScaffoldScene`, so a single-entry pop swaps detail-pane content
     * inside the same scene — the outer scene transform cannot cover it. `navigation.pop()` is
     * the in-app back path here (a system back goes through the scaffold's
     * `PopUntilScaffoldValueChange` handler, which pops to a scaffold boundary instead). The pane
     * wipe must translate the outgoing detail toward the layout end and reveal the previous
     * detail underneath, while the list pane stays anchored (a whole-master swipe would move it).
     */
    @Test
    fun expandedBackPopWipesInsideTheDetailPane() {
        setFixedHost(directive = TwoPaneDirective)
        rule.runOnUiThread { pushSettingsChain() }
        settle()
        val rest = rule.onRoot().captureToImage().toPixelMap()
        val detailRest = leftEdgeOf(rest, LICENCES_COLOR)
        assertTrue(detailRest > 0, "expected a two-pane layout")

        rule.runOnUiThread { navigation.pop() }

        val wiped =
            advanceFramesUntil(WIPE_FRAME_BOUND) {
                val pixels = rule.onRoot().captureToImage().toPixelMap()
                leftEdgeOf(pixels, LICENCES_COLOR) > detailRest &&
                    leftEdgeOf(pixels, ABOUT_COLOR) == detailRest &&
                    leftEdgeOf(pixels, SETTINGS_COLOR) == 0
            }
        assertTrue(wiped, "no frame showed the detail wiping right inside its pane")

        settle()
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        assertEquals(-1, leftEdgeOf(pixels, LICENCES_COLOR), "detail still painted after the pop")
        assertEquals(detailRest, leftEdgeOf(pixels, ABOUT_COLOR))
        assertEquals(0, leftEdgeOf(pixels, SETTINGS_COLOR))
    }

    /** The covering half of the pair: a detail push slides the new pane content in from the end. */
    @Test
    fun expandedDetailPushCoversThePane() {
        setFixedHost(directive = TwoPaneDirective)
        rule.runOnUiThread {
            navigation.push(SettingsHomeKey)
            navigation.push(SettingsKey(SettingsPage.ABOUT))
        }
        settle()
        val rest = rule.onRoot().captureToImage().toPixelMap()
        val detailRest = leftEdgeOf(rest, ABOUT_COLOR)
        assertTrue(detailRest > 0, "expected a two-pane layout")

        rule.runOnUiThread { navigation.push(LicencesKey) }

        val covered =
            advanceFramesUntil(WIPE_FRAME_BOUND) {
                val pixels = rule.onRoot().captureToImage().toPixelMap()
                val entering = leftEdgeOf(pixels, LICENCES_COLOR)
                entering > detailRest &&
                    leftEdgeOf(pixels, ABOUT_COLOR) == detailRest &&
                    leftEdgeOf(pixels, SETTINGS_COLOR) == 0
            }
        assertTrue(covered, "no frame showed the new detail covering toward the pane start")

        settle()
        assertEquals(detailRest, leftEdgeOf(rule.onRoot().captureToImage().toPixelMap(), LICENCES_COLOR))
    }

    /**
     * Positive control: the same back action with plain (unroled) entries still wipes through the
     * outer scene transition — proving the fixture observes animation whenever one runs.
     */
    @Test
    fun backPopWithoutPaneRolesStillWipesTheScene() {
        setFixedHost(installers = DistinctInstallers, directive = TwoPaneDirective)
        rule.runOnUiThread { pushSettingsChain() }
        settle()

        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }

        val wiped =
            advanceFramesUntil(WIPE_FRAME_BOUND) {
                val pixels = rule.onRoot().captureToImage().toPixelMap()
                leftEdgeOf(pixels, LICENCES_COLOR) > 0 && leftEdgeOf(pixels, ABOUT_COLOR) == 0
            }
        assertTrue(wiped, "distinct-scene pop did not produce the outer wipe")
    }

    /**
     * A predictive-back gesture on this stack is scaffold-owned, not a pane wipe: the strategy's
     * `PopUntilScaffoldValueChange` lands on a scaffold-value boundary (here the detail pane
     * closes and the pop skips both detail entries), so the pane-content boundary must NOT
     * preview an in-pane wipe — About must never be revealed under the gesture — and cancelling
     * restores the scene exactly.
     */
    @Test
    fun expandedPredictiveBackIsScaffoldOwnedNotAPaneWipe() {
        setFixedHost(directive = TwoPaneDirective)
        rule.runOnUiThread { pushSettingsChain() }
        settle()
        val input = DirectNavigationEventInput()
        val eventDispatcher =
            assertNotNull(dispatcher, "no NavigationEventDispatcher owner in the host")
        rule.runOnUiThread { eventDispatcher.addInput(input) }
        rule.mainClock.autoAdvance = false

        rule.runOnUiThread {
            input.backStarted(NavigationEvent(NavigationEvent.EDGE_LEFT, 0f, 16f, 400f))
            input.backProgressed(NavigationEvent(NavigationEvent.EDGE_LEFT, 0.45f, 300f, 400f))
        }

        // The list pane stays anchored and no wrong landing is previewed underneath.
        var aboutSeen = false
        var settingsMoved = false
        repeat(GESTURE_FRAME_BOUND) {
            rule.mainClock.advanceTimeByFrame()
            val pixels = rule.onRoot().captureToImage().toPixelMap()
            if (leftEdgeOf(pixels, ABOUT_COLOR) != -1) aboutSeen = true
            if (leftEdgeOf(pixels, SETTINGS_COLOR) != 0) settingsMoved = true
        }
        assertTrue(!aboutSeen, "the gesture previewed a wrong in-pane landing")
        assertTrue(!settingsMoved, "the list pane moved during the gesture")
        assertTrue(
            rule.onAllNodesWithTag(LICENCES_TAG).fetchSemanticsNodes().isNotEmpty(),
            "licences left composition while the gesture was still cancellable",
        )

        rule.runOnUiThread { input.backCancelled() }
        settle()
        assertTrue(rule.onAllNodesWithTag(SETTINGS_TAG).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithTag(LICENCES_TAG).fetchSemanticsNodes().isNotEmpty())
    }

    /**
     * The outgoing pane's owners are load-bearing: while the wipe draws it, the outgoing entry
     * must still be the same composed emission — same ViewModel, same ViewModelStoreOwner, same
     * `rememberSaveable` value — with no disappearance/re-entry frame (the destroyed-and-
     * recreated signature), and the popped entry's ViewModel must be cleared exactly once, after
     * the wipe exits. The single-pane case needs no assertion here: `NavDisplay`'s own scene
     * transition already holds the outgoing emission composed.
     */
    @Test
    fun expandedBackPopKeepsTheOutgoingOwnerUntilTheWipeExits() {
        OwnerProbe.reset()
        setFixedHost(installers = OwnerInstallers, directive = TwoPaneDirective)
        rule.runOnUiThread { pushSettingsChain() }
        settle()
        rule.onNodeWithTag(INCREMENT_TAG).performClick()
        settle()
        val original = assertNotNull(OwnerProbe.snapshot, "licences page never composed")
        assertEquals(1, original.saved)

        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { navigation.pop() }

        var exited = false
        var outgoingSeen = false
        repeat(WIPE_FRAME_BOUND * 2) {
            rule.mainClock.advanceTimeByFrame()
            val present = rule.onAllNodesWithTag(LICENCES_TAG).fetchSemanticsNodes().isNotEmpty()
            if (present) {
                assertTrue(!exited, "licences re-entered composition mid-wipe")
                outgoingSeen = true
                val now = OwnerProbe.snapshot
                assertEquals(original.vm, now?.vm, "outgoing ViewModel was recreated")
                assertEquals(original.owner, now?.owner, "outgoing ViewModelStoreOwner changed")
                assertEquals(1, now?.saved, "outgoing rememberSaveable state was reset")
            } else {
                exited = true
            }
            assertTrue(
                exited || original.vm !in OwnerProbe.cleared,
                "outgoing ViewModel cleared while the wipe still drew it",
            )
        }
        assertTrue(outgoingSeen, "the wipe never drew the outgoing page")

        settle()
        assertTrue(rule.onAllNodesWithTag(LICENCES_TAG).fetchSemanticsNodes().isEmpty())
        assertEquals(
            1,
            OwnerProbe.cleared.count { it == original.vm },
            "original ViewModel must clear exactly once after the wipe exits",
        )
        assertEquals(
            SettingsKey(SettingsPage.ABOUT),
            navigation.stack(FeedsKey).last(),
            "the pop must land on About in the same stack",
        )
        assertTrue(rule.onAllNodesWithTag(SETTINGS_TAG).fetchSemanticsNodes().isNotEmpty())
    }

    /** RTL resolves End to the left: the wipe recedes through the pane's start side. */
    @Test
    fun expandedBackPopWipesTowardTheLayoutEnd() {
        setFixedHost(directive = TwoPaneDirective, layoutDirection = LayoutDirection.Rtl)
        rule.runOnUiThread { pushSettingsChain() }
        settle()
        val rest = rule.onRoot().captureToImage().toPixelMap()
        val restRight = rightEdgeOf(rest, LICENCES_COLOR)
        assertTrue(restRight > 0, "expected licences painted")

        rule.runOnUiThread { navigation.pop() }

        val wiped =
            advanceFramesUntil(WIPE_FRAME_BOUND) {
                val pixels = rule.onRoot().captureToImage().toPixelMap()
                val receding = rightEdgeOf(pixels, LICENCES_COLOR)
                receding in 1 until restRight && leftEdgeOf(pixels, ABOUT_COLOR) != -1
            }
        assertTrue(wiped, "no frame showed the detail receding toward the layout end in RTL")
    }

    private fun pushSettingsChain() {
        navigation.push(SettingsHomeKey)
        navigation.push(SettingsKey(SettingsPage.ABOUT))
        navigation.push(LicencesKey)
    }

    // endregion

    // region platform defaults (pre-fix diagnostics)

    /**
     * The shipped runtime's `defaultTransitionSpec` on the identical entry swap: the outgoing
     * entry blends in place for hundreds of ms and never moves horizontally — the "everything
     * seems to be fades" the owner reported. Failing loudly when the platform default changes
     * keeps this an honest before-picture, not a strawman.
     */
    @Test
    fun platformDefaultsFadeTabSwitch() {
        val stack = NavBackStack<NavKey>(FeedsKey)
        setDefaultsHost(stack)
        rule.mainClock.autoAdvance = false

        rule.runOnUiThread {
            stack.removeAt(stack.lastIndex)
            stack.add(LibraryKey)
        }
        // Past the instant-swap bound the outgoing entry is still composed and blending.
        advanceFrames(TEARDOWN_FRAME_BOUND)
        assertTrue(
            rule.onAllNodesWithTag(FEEDS_TAG).fetchSemanticsNodes().isNotEmpty(),
            "feeds entry left immediately; the default spec changed",
        )
        var blendSeen = false
        val movedRight =
            advanceFramesUntil(FADE_FRAME_BOUND) {
                val pixels = rule.onRoot().captureToImage().toPixelMap()
                if (hasBlendedPixels(pixels, FEEDS_COLOR, LIBRARY_COLOR)) blendSeen = true
                leftEdgeOf(pixels, FEEDS_COLOR) > 0
            }
        assertTrue(blendSeen, "the default navigate spec produced no blend")
        assertTrue(!movedRight, "the default navigate spec moved the outgoing entry horizontally")

        settle()
        assertTrue(rule.onAllNodesWithTag(FEEDS_TAG).fetchSemanticsNodes().isEmpty())
    }

    /** The same for `defaultPopTransitionSpec`: a fade on back, not a horizontal wipe. */
    @Test
    fun platformDefaultsFadeBackPop() {
        val stack = NavBackStack<NavKey>(FeedsKey, PodcastKey(3))
        setDefaultsHost(stack)
        rule.mainClock.autoAdvance = false

        rule.runOnUiThread { stack.removeAt(stack.lastIndex) }
        advanceFrames(TEARDOWN_FRAME_BOUND)
        assertTrue(
            rule.onAllNodesWithTag(PODCAST_TAG).fetchSemanticsNodes().isNotEmpty(),
            "detail entry left immediately; the default pop spec changed",
        )
        var blendSeen = false
        val movedRight =
            advanceFramesUntil(FADE_FRAME_BOUND) {
                val pixels = rule.onRoot().captureToImage().toPixelMap()
                if (hasBlendedPixels(pixels, PODCAST_COLOR, FEEDS_COLOR)) blendSeen = true
                leftEdgeOf(pixels, PODCAST_COLOR) > 0
            }
        assertTrue(blendSeen, "the default pop spec produced no blend")
        assertTrue(!movedRight, "the default pop spec moved the outgoing entry horizontally")

        settle()
        assertTrue(rule.onAllNodesWithTag(PODCAST_TAG).fetchSemanticsNodes().isEmpty())
    }

    // endregion

    // region hosts

    private fun setFixedHost(
        installers: Set<EntryProviderInstaller> = HostInstallers,
        directive: PaneScaffoldDirective? = null,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) {
        rule.setContent {
            val state = rememberNavigationState()
            navigation = state
            dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
            CompositionLocalProvider(
                LocalAppNavigator provides state,
                LocalLayoutDirection provides layoutDirection,
            ) {
                NeutrodyneNavHost(
                    state = state,
                    installers = installers,
                    directive =
                        directive
                            ?: calculatePaneScaffoldDirective(
                                currentWindowAdaptiveInfo(supportLargeAndXLargeWidth = true),
                            ),
                )
            }
        }
        settle()
    }

    /**
     * The pre-fix call site verbatim: `NavDisplay` with no transition specs, so the Android
     * runtime's `defaultTransitionSpec`/`defaultPopTransitionSpec` (fadeIn/fadeOut 700 ms) apply.
     */
    private fun setDefaultsHost(backStack: NavBackStack<NavKey>) {
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

    // endregion

    // region clock and pixels

    private fun advanceFrames(frames: Int) {
        repeat(frames) { rule.mainClock.advanceTimeByFrame() }
    }

    /** Pumps one frame at a time and reports whether [condition] held within [maxFrames]. */
    private fun advanceFramesUntil(
        maxFrames: Int,
        condition: () -> Boolean,
    ): Boolean {
        repeat(maxFrames) {
            rule.mainClock.advanceTimeByFrame()
            if (condition()) return true
        }
        return false
    }

    private fun settle() {
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
    }

    /** Leftmost column whose pixels are mostly [sentinel], or -1 when the sentinel is gone. */
    private fun leftEdgeOf(
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
    private fun rightEdgeOf(
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
    private fun hasBlendedPixels(
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

    private fun Color.isCloseTo(other: Color): Boolean =
        alpha > 0.9f &&
            abs(red - other.red) < COLOR_TOLERANCE &&
            abs(green - other.green) < COLOR_TOLERANCE &&
            abs(blue - other.blue) < COLOR_TOLERANCE

    // endregion

    private companion object {
        /** 128 ms — long past an instant swap, nowhere near a 700 ms fade. */
        const val INSTANT_FRAME_BOUND = 8

        /** Entry teardown can trail the visual swap by a few frames on this runtime. */
        const val TEARDOWN_FRAME_BOUND = 12

        /** 256 ms — the wipe motion measured ~13 frames here. */
        const val WIPE_FRAME_BOUND = 16
        const val GESTURE_FRAME_BOUND = 16

        /** 640 ms — still inside the 700 ms default fade. */
        const val FADE_FRAME_BOUND = 40

        const val PIXEL_STRIDE = 4
        const val COLOR_TOLERANCE = 0.15f
    }
}

private const val FEEDS_TAG = "feeds-pane"
private const val LIBRARY_TAG = "library-pane"
private const val PODCAST_TAG = "podcast-pane"
private const val SETTINGS_TAG = "settings-pane"
private const val ABOUT_TAG = "about-pane"
private const val LICENCES_TAG = "licences-pane"

private val FEEDS_COLOR = Color(0xFFD32F2F)
private val LIBRARY_COLOR = Color(0xFF2E7D32)
private val PODCAST_COLOR = Color(0xFF1565C0)
private val SETTINGS_COLOR = Color(0xFFF9A825)
private val ABOUT_COLOR = Color(0xFF00695C)
private val LICENCES_COLOR = Color(0xFF4E342E)

/** Forces two partitions regardless of the test window size — the expanded scaffold case. */
private val TwoPaneDirective = PaneScaffoldDirective.Default.copy(maxHorizontalPartitions = 2)

private val HostInstallers: Set<EntryProviderInstaller> =
    buildSet {
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

/** The expanded settings chain with a real `viewModel()`/`rememberSaveable` licences page. */
private val OwnerInstallers: Set<EntryProviderInstaller> =
    buildSet {
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
            entry<LicencesKey>(metadata = NdSceneMetadata.paneDetail()) { OwnerProbePage() }
        }
    }

/** The same settings keys with no pane roles — each entry lands in its own scene. */
private val DistinctInstallers: Set<EntryProviderInstaller> =
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

private val DefaultsProvider =
    entryProvider<NavKey> {
        entry<FeedsKey> { Sentinel(FEEDS_COLOR, FEEDS_TAG) }
        entry<LibraryKey> { Sentinel(LIBRARY_COLOR, LIBRARY_TAG) }
        entry<PodcastKey> { Sentinel(PODCAST_COLOR, PODCAST_TAG) }
    }

private const val INCREMENT_TAG = "licences-increment"

/** Records the live owner triple of the licences page so frames can assert continuity. */
private object OwnerProbe {
    val nextIdentity = AtomicInteger(1)
    val cleared = mutableListOf<Int>()
    var snapshot: Snapshot? = null

    data class Snapshot(
        val vm: Int,
        val owner: Int,
        val saved: Int,
    )

    fun reset() {
        cleared.clear()
        snapshot = null
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
private fun OwnerProbePage() {
    val vm = viewModel { OwnerProbeVm() }
    val owner = LocalViewModelStoreOwner.current
    val saved = rememberSaveable { mutableIntStateOf(0) }
    SideEffect {
        OwnerProbe.snapshot =
            OwnerProbe.Snapshot(vm.identity, System.identityHashCode(owner), saved.intValue)
    }
    Column(Modifier.fillMaxSize().testTag(LICENCES_TAG)) {
        Text("licences|saved=${saved.intValue}")
        Button(onClick = { saved.intValue++ }, modifier = Modifier.testTag(INCREMENT_TAG)) {
            Text("increment")
        }
    }
}

@Suppress("FunctionNaming") // composables are PascalCase
@Composable
private fun Sentinel(
    color: Color,
    tag: String,
) {
    Box(Modifier.fillMaxSize().background(color).testTag(tag))
}
