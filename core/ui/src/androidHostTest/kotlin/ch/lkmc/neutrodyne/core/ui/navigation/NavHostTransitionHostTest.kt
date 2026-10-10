// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
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
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import ch.lkmc.neutrodyne.core.navigation.PodcastKey
import ch.lkmc.neutrodyne.core.navigation.UpNextKey
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
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

    private fun setFixedHost() {
        rule.setContent {
            val state = rememberNavigationState()
            navigation = state
            dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
            CompositionLocalProvider(LocalAppNavigator provides state) {
                NeutrodyneNavHost(
                    state = state,
                    installers = HostInstallers,
                    directive =
                        calculatePaneScaffoldDirective(
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

private val FEEDS_COLOR = Color(0xFFD32F2F)
private val LIBRARY_COLOR = Color(0xFF2E7D32)
private val PODCAST_COLOR = Color(0xFF1565C0)

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
    }

private val DefaultsProvider =
    entryProvider<NavKey> {
        entry<FeedsKey> { Sentinel(FEEDS_COLOR, FEEDS_TAG) }
        entry<LibraryKey> { Sentinel(LIBRARY_COLOR, LIBRARY_TAG) }
        entry<PodcastKey> { Sentinel(PODCAST_COLOR, PODCAST_TAG) }
    }

@Composable
private fun Sentinel(
    color: Color,
    tag: String,
) {
    Box(Modifier.fillMaxSize().background(color).testTag(tag))
}
