// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.navigation

import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import ch.lkmc.neutrodyne.core.navigation.EpisodeKey
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.LicencesKey
import ch.lkmc.neutrodyne.core.navigation.SettingsHomeKey
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class PaneBoundaryBackHostTest : NdNavHostHarness() {
    @Test
    fun boundaryCompletionReadsMutationsMadeInTheSameUiAction() {
        val about = SettingsKey(SettingsPage.ABOUT)
        setFixedHost(installers = ProbedInstallers, directive = TwoPaneDirective)
        rule.runOnUiThread {
            navigation.open(FeedsKey, listOf(SettingsHomeKey, about, EpisodeKey(1), EpisodeKey(2)))
        }
        settle()
        val input = DirectNavigationEventInput()
        rule.runOnUiThread {
            requireNotNull(dispatcher).addInput(input)
            input.backStarted(NavigationEvent(NavigationEvent.EDGE_LEFT, 0f, 16f, 400f))
            input.backProgressed(NavigationEvent(NavigationEvent.EDGE_LEFT, .4f, 300f, 400f))
        }
        advanceFrames(4)
        rule.runOnUiThread {
            navigation.pop()
            input.backCompleted()
        }
        settle()
        assertEquals(about, navigation.stack(FeedsKey).last())
    }

    @Test
    fun consecutiveIdenticalGesturesEachRemoveOneLiveEntry() {
        val about = SettingsKey(SettingsPage.ABOUT)
        setFixedHost(installers = ProbedInstallers, directive = TwoPaneDirective)
        rule.runOnUiThread { navigation.open(FeedsKey, listOf(SettingsHomeKey, about, LicencesKey)) }
        settle()
        val input = DirectNavigationEventInput()
        rule.runOnUiThread {
            requireNotNull(dispatcher).addInput(input)
            repeat(2) {
                input.backStarted(NavigationEvent(NavigationEvent.EDGE_LEFT, 0f, 16f, 400f))
                input.backProgressed(NavigationEvent(NavigationEvent.EDGE_LEFT, .4f, 300f, 400f))
                input.backCompleted()
            }
        }
        settle()
        assertEquals(SettingsHomeKey, navigation.stack(FeedsKey).last())
    }

    @Test
    fun boundaryGestureStartedBeforeTheFirstPopFrameUsesTheLiveCount() {
        val about = SettingsKey(SettingsPage.ABOUT)
        setFixedHost(installers = ProbedInstallers, directive = TwoPaneDirective)
        rule.runOnUiThread {
            navigation.open(FeedsKey, listOf(SettingsHomeKey, about, EpisodeKey(1), EpisodeKey(2)))
        }
        settle()
        val input = DirectNavigationEventInput()
        rule.runOnUiThread {
            requireNotNull(dispatcher).addInput(input)
            navigation.pop()
            input.backStarted(NavigationEvent(NavigationEvent.EDGE_LEFT, 0f, 16f, 400f))
            input.backProgressed(NavigationEvent(NavigationEvent.EDGE_LEFT, .4f, 300f, 400f))
        }
        advanceFrames(4)
        println("EARLY BOUNDARY beforeCommit=${navigation.stack(FeedsKey).toList()}")
        rule.runOnUiThread { input.backCompleted() }
        settle()
        println("EARLY BOUNDARY afterCommit=${navigation.stack(FeedsKey).toList()}")
        assertEquals(about, navigation.stack(FeedsKey).last(), "the retained scaffold used the pre-pop removal count")
    }
}
