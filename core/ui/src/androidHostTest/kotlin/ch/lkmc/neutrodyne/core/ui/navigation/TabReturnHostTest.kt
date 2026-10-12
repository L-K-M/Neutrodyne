// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.navigation

import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.navigation3.runtime.NavKey
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import ch.lkmc.neutrodyne.core.navigation.EpisodeKey
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.LicencesKey
import ch.lkmc.neutrodyne.core.navigation.SettingsHomeKey
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import ch.lkmc.neutrodyne.core.navigation.TopLevelKey
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class TabReturnHostTest : NdNavHostHarness() {
    private val about = SettingsKey(SettingsPage.ABOUT)
    private val detailHistory = listOf(SettingsHomeKey, about, LicencesKey)
    private val boundaryHistory = listOf(SettingsHomeKey, about, EpisodeKey(1), EpisodeKey(2))

    @Test
    fun detailReturnCannotReviveGesture() = reproduce(detailHistory, FeedsKey, SwitchTiming.Composed)

    @Test
    fun boundaryReturnCannotReviveGesture() = reproduce(boundaryHistory, FeedsKey, SwitchTiming.Composed)

    @Test
    fun libraryReturnCannotReviveGesture() = reproduce(detailHistory, LibraryKey, SwitchTiming.Composed)

    @Test
    fun twoSelectionsBeforeCompositionCannotReviveGesture() =
        reproduce(detailHistory, FeedsKey, SwitchTiming.BeforeComposition)

    @Test
    fun roundTripAndCompletionBeforeCompositionCannotReviveGesture() =
        reproduce(detailHistory, FeedsKey, SwitchTiming.CompleteBeforeComposition)

    private fun reproduce(
        history: List<NavKey>,
        source: TopLevelKey,
        timing: SwitchTiming,
    ) {
        val destination = if (source == FeedsKey) LibraryKey else FeedsKey
        setFixedHost(installers = ProbedInstallers, directive = TwoPaneDirective)
        rule.runOnUiThread {
            navigation.open(destination, detailHistory)
            navigation.open(source, history)
        }
        settle()
        val sourceBefore = navigation.stack(source).toList()
        val destinationBefore = navigation.stack(destination).toList()
        val resting = rule.onRoot().captureToImage().toPixelMap()
        val event = DirectNavigationEventInput()
        rule.runOnUiThread {
            requireNotNull(dispatcher).addInput(event)
            beginGesture(event)
        }
        advanceFrames(COMPOSITION_FRAMES)
        if (timing == SwitchTiming.CompleteBeforeComposition) {
            rule.runOnUiThread {
                navigation.selectTab(destination)
                navigation.selectTab(source)
                event.backCompleted()
            }
        } else {
            rule.runOnUiThread { navigation.selectTab(destination) }
            if (timing == SwitchTiming.Composed) advanceFrames(COMPOSITION_FRAMES)
            rule.runOnUiThread { navigation.selectTab(source) }
            if (timing == SwitchTiming.Composed) advanceFrames(SETTLED_FRAMES)
        }
        val returned = rule.onRoot().captureToImage().toPixelMap()
        println(
            "TAB_RETURN returned source=$source timing=$timing selected=${navigation.selectedTab} " +
                "sourceStack=${navigation.stack(source).toList()} " +
                "destinationStack=${navigation.stack(destination).toList()} " +
                "licencesLeft=${leftEdgeOf(returned, LICENCES_COLOR)} " +
                "aboutLeft=${leftEdgeOf(returned, ABOUT_COLOR)} extraLeft=${leftEdgeOf(returned, EXTRA_COLOR)}",
        )
        if (timing != SwitchTiming.CompleteBeforeComposition) rule.runOnUiThread { event.backCompleted() }
        advanceFrames(SETTLED_FRAMES)
        println(
            "TAB_RETURN after-completion source=$source timing=$timing selected=${navigation.selectedTab} " +
                "sourceStack=${navigation.stack(source).toList()} " +
                "destinationStack=${navigation.stack(destination).toList()}",
        )
        assertEquals(destinationBefore, navigation.stack(destination).toList())
        assertEquals(
            sourceBefore,
            navigation.stack(source).toList(),
            "a tab switch must invalidate the pending gesture even after returning to its origin",
        )
        assertEquals(source, navigation.selectedTab)
        if (timing == SwitchTiming.Composed) assertRestingPixels(resting, returned)
        assertRestingPixels(resting, rule.onRoot().captureToImage().toPixelMap())

        // The invalidated completion is consumed; a distinct new gesture must still navigate.
        rule.runOnUiThread {
            beginGesture(event)
            event.backCompleted()
        }
        advanceFrames(SETTLED_FRAMES)
        assertEquals(listOf(source, SettingsHomeKey, about), navigation.stack(source).toList())
        assertEquals(destinationBefore, navigation.stack(destination).toList())
        println("TAB_RETURN restingPixels=PASS freshBack=PASS source=$source timing=$timing")
    }

    private fun beginGesture(input: DirectNavigationEventInput) {
        input.backStarted(NavigationEvent(NavigationEvent.EDGE_LEFT, 0f, 16f, 400f))
        input.backProgressed(NavigationEvent(NavigationEvent.EDGE_LEFT, .65f, 300f, 400f))
    }

    private fun assertRestingPixels(
        expected: PixelMap,
        actual: PixelMap,
    ) {
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        var changed = 0
        var samples = 0
        for (y in listOf(expected.height / 4, expected.height / 2, expected.height * 3 / 4)) {
            for (x in 0 until expected.width step VISUAL_SAMPLE_STRIDE) {
                val before = expected[x, y]
                val after = actual[x, y]
                val difference =
                    maxOf(
                        abs(before.red - after.red),
                        abs(before.green - after.green),
                        abs(before.blue - after.blue),
                        abs(before.alpha - after.alpha),
                    )
                samples++
                if (difference > VISUAL_CHANNEL_TOLERANCE) changed++
            }
        }
        val fraction = changed.toDouble() / samples
        println("TAB_RETURN changedPixels=$changed/$samples fraction=$fraction")
        // Match the review's 0.5% tolerance: scaffold rounding can move an edge by two columns.
        assertTrue(fraction < VISUAL_MISMATCH_TOLERANCE, "cancel left a partial pane: changed fraction=$fraction")
    }

    private enum class SwitchTiming {
        Composed,
        BeforeComposition,
        CompleteBeforeComposition,
    }

    private companion object {
        const val COMPOSITION_FRAMES = 4
        const val SETTLED_FRAMES = 30
        const val VISUAL_SAMPLE_STRIDE = 3
        const val VISUAL_CHANNEL_TOLERANCE = 5f / 255f
        const val VISUAL_MISMATCH_TOLERANCE = .005
    }
}
