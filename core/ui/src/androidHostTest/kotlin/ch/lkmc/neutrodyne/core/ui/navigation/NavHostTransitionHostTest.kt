// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.navigation

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.LicencesKey
import ch.lkmc.neutrodyne.core.navigation.PodcastKey
import ch.lkmc.neutrodyne.core.navigation.SettingsHomeKey
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The owner-facing outer-scene transition contract (08 Transitions and shared elements):
 * tab taps show no transition, a real back pops with a horizontal wipe that reveals the
 * previous entry underneath, and a predictive-back gesture seeks that same wipe and restores
 * the current scene exactly on cancel.
 *
 * `defaultsHarness*` reproduces the pre-fix wiring verbatim — `NavDisplay` without transition
 * specs, which is what shipped when the owner reported "everything seems to be fades" — as
 * pinned-down diagnostics of the runtime defaults the fix replaced. In-pane swaps inside a
 * shared scaffold scene are gated separately by [PaneSwapGateTest], which asserts public
 * behavior only so it judges any implementation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class NavHostTransitionHostTest : NdNavHostHarness() {
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

    /**
     * Positive control: the same back action with plain (unroled) entries still wipes through the
     * outer scene transition — proving the fixture observes animation whenever one runs.
     */
    @Test
    fun backPopWithoutPaneRolesStillWipesTheScene() {
        setFixedHost(installers = DistinctInstallers, directive = TwoPaneDirective)
        rule.runOnUiThread {
            navigation.push(SettingsHomeKey)
            navigation.push(SettingsKey(SettingsPage.ABOUT))
            navigation.push(LicencesKey)
        }
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
     * 08 promises a 0 ms snap under reduced motion and the pane swap already honours it;
     * the outer scene cover/wipe must use the same spec — a default spring would still run.
     * Unroled entries isolate the outer transform: the cover slides in from the end edge,
     * so a mid-transit frame shows the incoming's left edge still inside the window.
     */
    @Test
    fun reducedMotionSnapsTheOuterCover() {
        setFixedHost(installers = DistinctInstallers, reducedMotion = true)
        rule.runOnUiThread { navigation.push(SettingsKey(SettingsPage.ABOUT)) }
        rule.mainClock.autoAdvance = false
        val windowEnd =
            rule
                .onRoot()
                .captureToImage()
                .toPixelMap()
                .width - 1

        val midTransit =
            advanceFramesUntil(WIPE_FRAME_BOUND) {
                leftEdgeOf(rule.onRoot().captureToImage().toPixelMap(), ABOUT_COLOR)
                    .let { it in 1 until windowEnd }
            }
        assertTrue(!midTransit, "the outer cover kept animating under reduced motion")
        settle()
        assertTrue(rule.onAllNodesWithTag(ABOUT_TAG).fetchSemanticsNodes().isNotEmpty())
    }

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
}
