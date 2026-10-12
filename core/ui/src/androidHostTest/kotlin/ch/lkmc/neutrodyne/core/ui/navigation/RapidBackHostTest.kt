// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.navigation

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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions") // One case per rapid-input regression and control.
internal class RapidBackHostTest : NdNavHostHarness() {
    private val about = SettingsKey(SettingsPage.ABOUT)
    private val boundary = listOf(SettingsHomeKey, about, EpisodeKey(1), EpisodeKey(2))

    @Test
    fun twoIdenticalBoundaryGesturesWithoutACompositionBothApply() {
        setup()
        val event = input()
        rule.runOnUiThread {
            repeat(2) {
                begin(event)
                event.backCompleted()
            }
        }
        settle()
        assertEquals(listOf(FeedsKey, SettingsHomeKey), navigation.stack(FeedsKey).toList())
    }

    @Test
    fun twoSystemBacksWithoutACompositionBothApply() {
        setup(history = listOf(SettingsHomeKey, about, LicencesKey))
        val event = input()
        rule.runOnUiThread { repeat(2) { event.backCompleted() } }
        settle()
        assertEquals(listOf(FeedsKey, SettingsHomeKey), navigation.stack(FeedsKey).toList())
    }

    @Test
    fun systemBackThenGestureWithoutACompositionUsesBothLivePlans() {
        setup()
        val gesture = input()
        val system = input()
        rule.runOnUiThread {
            system.backCompleted()
            begin(gesture)
            gesture.backCompleted()
        }
        settle()
        assertEquals(listOf(FeedsKey, SettingsHomeKey), navigation.stack(FeedsKey).toList())
    }

    @Test
    fun cancelledBoundaryThenANewGestureStillCommits() {
        setup()
        val event = input()
        rule.runOnUiThread {
            begin(event)
            event.backCancelled()
            begin(event)
            event.backCompleted()
        }
        settle()
        assertEquals(listOf(FeedsKey, SettingsHomeKey, about), navigation.stack(FeedsKey).toList())
    }

    @Test
    fun twoBoundarySystemBacksWithoutACompositionUseEachLivePlan() {
        setup()
        val event = input()
        rule.runOnUiThread { repeat(2) { event.backCompleted() } }
        settle()
        println("RAPID doubleBoundarySystem=${navigation.stack(FeedsKey).toList()}")
        assertEquals(listOf(FeedsKey, SettingsHomeKey), navigation.stack(FeedsKey).toList())
    }

    @Test
    fun systemCompletionBeforeFirstPopFrameUsesTheLiveBoundary() {
        setup()
        val event = input()
        rule.runOnUiThread {
            navigation.pop()
            event.backCompleted()
        }
        settle()
        println("RAPID popThenSystem=${navigation.stack(FeedsKey).toList()}")
        assertEquals(listOf(FeedsKey, SettingsHomeKey, about), navigation.stack(FeedsKey).toList())
    }

    @Test
    fun gestureThenSystemBackWithoutACompositionUsesBothLivePlans() {
        setup()
        val gesture = input()
        val system = input()
        rule.runOnUiThread {
            begin(gesture)
            gesture.backCompleted()
            system.backCompleted()
        }
        settle()
        println("RAPID gestureThenSystem=${navigation.stack(FeedsKey).toList()}")
        assertEquals(listOf(FeedsKey, SettingsHomeKey), navigation.stack(FeedsKey).toList())
    }

    @Test
    fun boundaryGestureCannotPopAnotherTabInTheSwitchAction() = crossTab(FeedsKey, boundary, SwitchTiming.SameAction)

    @Test
    fun detailGestureCannotPopAnotherTabAfterTheSwitchComposes() =
        crossTab(FeedsKey, listOf(SettingsHomeKey, about, LicencesKey), SwitchTiming.Composed)

    @Test
    fun libraryDetailGestureCannotPopFeedsAfterTheSwitchComposes() =
        crossTab(LibraryKey, listOf(SettingsHomeKey, about, LicencesKey), SwitchTiming.Composed)

    private fun setup(
        tab: TopLevelKey = FeedsKey,
        history: List<androidx.navigation3.runtime.NavKey> = boundary,
    ) {
        setFixedHost(installers = ProbedInstallers, directive = TwoPaneDirective)
        rule.runOnUiThread { navigation.open(tab, history) }
        settle()
    }

    private fun input(): DirectNavigationEventInput {
        val result = DirectNavigationEventInput()
        rule.runOnUiThread { requireNotNull(dispatcher).addInput(result) }
        return result
    }

    private fun begin(input: DirectNavigationEventInput) {
        input.backStarted(NavigationEvent(NavigationEvent.EDGE_LEFT, 0f, 16f, 400f))
        input.backProgressed(NavigationEvent(NavigationEvent.EDGE_LEFT, .4f, 300f, 400f))
    }

    private fun crossTab(
        source: TopLevelKey,
        history: List<androidx.navigation3.runtime.NavKey>,
        timing: SwitchTiming,
    ) {
        setup(source, history)
        val destination = if (source == LibraryKey) FeedsKey else LibraryKey
        rule.runOnUiThread {
            navigation.open(destination, listOf(SettingsHomeKey, about, LicencesKey))
            navigation.selectTab(source)
        }
        settle()
        val sourceBefore = navigation.stack(source).toList()
        val targetBefore = navigation.stack(destination).toList()
        val event = input()
        rule.runOnUiThread { begin(event) }
        advanceFrames(2)
        rule.runOnUiThread { navigation.selectTab(destination) }
        if (timing == SwitchTiming.Composed) advanceFrames(4)
        rule.runOnUiThread { event.backCompleted() }
        settle()
        println(
            "CROSS_TAB source=$source timing=$timing selected=${navigation.selectedTab} " +
                "sourceStack=${navigation.stack(
                    source,
                ).toList()} targetStack=${navigation.stack(destination).toList()}",
        )
        assertEquals(
            targetBefore,
            navigation.stack(destination).toList(),
            "a completion bound to $source mutated $destination",
        )
        assertEquals(destination, navigation.selectedTab, "stale completion changed selected tab")
        assertTrue(navigation.stack(source).size <= sourceBefore.size)
    }

    private enum class SwitchTiming {
        Composed,
        SameAction,
    }
}
