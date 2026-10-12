// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.navigation

import androidx.compose.animation.EnterExitState
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.adaptive.layout.AnimatedPaneOverride
import androidx.compose.material3.adaptive.layout.AnimatedPaneOverrideScope
import androidx.compose.material3.adaptive.layout.LocalAnimatedPaneOverride
import androidx.compose.material3.adaptive.layout.PaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneScaffoldValue
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldRole
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import ch.lkmc.neutrodyne.core.navigation.DiscoverKey
import ch.lkmc.neutrodyne.core.navigation.DownloadsKey
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.EpisodeKey
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.LicencesKey
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import ch.lkmc.neutrodyne.core.navigation.SettingsHomeKey
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import ch.lkmc.neutrodyne.core.navigation.UpNextKey
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class PaneSceneContinuityHostTest : NdNavHostHarness() {
    @Test
    fun sharedListRestoresItsFocusedFieldAcrossSceneMoves() {
        val installers =
            setOf<EntryProviderInstaller>(
                {
                    entry<FeedsKey>(metadata = NdSceneMetadata.paneList()) {
                        var text by remember { mutableStateOf("") }
                        Column {
                            TextField(text, { text = it }, Modifier.testTag("focused-list-field"))
                        }
                    }
                    entry<LibraryKey>(metadata = NdSceneMetadata.paneList()) { Text("library") }
                    entry<UpNextKey>(metadata = NdSceneMetadata.paneList()) { Text("queue") }
                    entry<DownloadsKey>(metadata = NdSceneMetadata.paneList()) { Text("downloads") }
                    entry<DiscoverKey>(metadata = NdSceneMetadata.paneList()) { Text("discover") }
                    entry<SettingsKey>(metadata = NdSceneMetadata.paneDetail()) { Text("detail") }
                },
            )
        setFixedHost(installers = installers, directive = TwoPaneDirective)
        rule.onNodeWithTag("focused-list-field").performClick().assertIsFocused()
        rule.runOnUiThread { navigation.push(SettingsKey(SettingsPage.ABOUT)) }
        settle()
        rule.onNodeWithTag("focused-list-field").assertIsFocused()
        rule.runOnUiThread { navigation.pop() }
        settle()
        rule.onNodeWithTag("focused-list-field").assertIsFocused()
    }

    @Test
    fun drawnOutgoingKeepsItsOwnerAfterLeavingAccessibility() {
        OwnerProbe.reset()
        setFixedHost(installers = ProbedInstallers, directive = TwoPaneDirective)
        rule.runOnUiThread {
            navigation.push(SettingsHomeKey)
            navigation.push(SettingsKey(SettingsPage.ABOUT))
            navigation.push(LicencesKey)
        }
        settle()
        rule.onNodeWithTag("$LICENCES_TAG-inc").performClick()
        settle()
        val original = assertNotNull(OwnerProbe.snapshots[LICENCES_TAG])
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { navigation.pop() }

        var drawnFrames = 0
        repeat(WIPE_FRAME_BOUND) {
            advanceFrames(1)
            if (leftEdgeOf(rule.onRoot().captureToImage().toPixelMap(), LICENCES_COLOR) != -1) {
                drawnFrames++
                assertEquals(original, OwnerProbe.snapshots[LICENCES_TAG])
                assertTrue(original.vm !in OwnerProbe.cleared, "painted exit cleared its owner")
            }
        }
        assertTrue(drawnFrames >= 2)
        settle()
        assertEquals(1, OwnerProbe.cleared.count { it == original.vm })
    }

    @Test
    fun incomingPreviewRestoresItsValueBeforeEitherTouch() {
        OwnerProbe.reset()
        setFixedHost(installers = ProbedInstallers, directive = TwoPaneDirective)
        rule.runOnUiThread {
            navigation.push(SettingsHomeKey)
            navigation.push(SettingsKey(SettingsPage.ABOUT))
        }
        settle()
        rule.onNodeWithTag("$ABOUT_PAGE_TAG-inc").performClick()
        settle()
        val original = assertNotNull(OwnerProbe.snapshots[ABOUT_PAGE_TAG])
        assertEquals(1, original.saved)

        rule.runOnUiThread { navigation.push(LicencesKey) }
        settle()
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { navigation.pop() }
        advanceFrames(4)

        val preview = assertNotNull(OwnerProbe.snapshots[ABOUT_PAGE_TAG])
        assertEquals(original.vm, preview.vm)
        assertEquals(1, preview.saved, "the incoming preview did not restore its saved value")
        repeat(2) { index ->
            rule
                .onNodeWithTag("$ABOUT_PAGE_TAG-inc")
                .performTouchInput { click(Offset(8f, center.y)) }
            advanceFrames(1)
            assertEquals(index + 2, OwnerProbe.snapshots[ABOUT_PAGE_TAG]?.saved, "touch $index missed About")
        }
        settle()
        assertEquals(3, OwnerProbe.snapshots[ABOUT_PAGE_TAG]?.saved)
    }

    @Test
    fun equalRoutesKeepIndependentOwnersAndValuesAcrossTabs() {
        OwnerProbe.reset()
        setFixedHost(installers = ProbedInstallers, directive = TwoPaneDirective)
        val about = SettingsKey(SettingsPage.ABOUT)
        rule.runOnUiThread { navigation.open(FeedsKey, listOf(SettingsHomeKey, about)) }
        settle()
        rule.onNodeWithTag("$ABOUT_PAGE_TAG-inc").performClick()
        settle()
        val feeds = assertNotNull(OwnerProbe.snapshots[ABOUT_PAGE_TAG])

        rule.runOnUiThread { navigation.open(LibraryKey, listOf(SettingsHomeKey, about)) }
        settle()
        val library = assertNotNull(OwnerProbe.snapshots[ABOUT_PAGE_TAG])
        assertNotEquals(feeds.vm, library.vm)
        assertEquals(0, library.saved)
        repeat(2) { rule.onNodeWithTag("$ABOUT_PAGE_TAG-inc").performClick() }
        settle()

        rule.runOnUiThread { navigation.selectTab(FeedsKey) }
        settle()
        assertEquals(feeds.vm, OwnerProbe.snapshots[ABOUT_PAGE_TAG]?.vm)
        assertEquals(1, OwnerProbe.snapshots[ABOUT_PAGE_TAG]?.saved)
        rule.runOnUiThread { navigation.selectTab(LibraryKey) }
        settle()
        assertEquals(library.vm, OwnerProbe.snapshots[ABOUT_PAGE_TAG]?.vm)
        assertEquals(2, OwnerProbe.snapshots[ABOUT_PAGE_TAG]?.saved)
    }

    @Test
    fun adaptiveBoundaryKeepsItsTransitionAndFinishesTheSeek() {
        val probe = ScaffoldProbe()
        buildFixedHost(installers = ProbedInstallers, directive = TwoPaneDirective)
        rule.setContent {
            val upstream = LocalAnimatedPaneOverride.current
            val override = remember(upstream) { ScaffoldProbeOverride(upstream, probe) }
            CompositionLocalProvider(LocalAnimatedPaneOverride provides override) {
                requireNotNull(hostContent).invoke()
            }
        }
        settle()
        rule.runOnUiThread {
            navigation.push(SettingsHomeKey)
            navigation.push(SettingsKey(SettingsPage.ABOUT))
        }
        settle()
        val transition = assertNotNull(probe.transition)
        rule.runOnUiThread { navigation.push(EpisodeKey(1)) }
        settle()
        assertSame(transition, probe.transition, "opening the extra pane replaced scaffold state")

        val input = DirectNavigationEventInput()
        val events = assertNotNull(dispatcher)
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread {
            events.addInput(input)
            input.backStarted(NavigationEvent(NavigationEvent.EDGE_LEFT, 0f, 16f, 400f))
            input.backProgressed(NavigationEvent(NavigationEvent.EDGE_LEFT, 0.4f, 300f, 400f))
        }
        advanceFrames(4)
        assertTrue(probe.progress > 0f && probe.progress < 1f, "the scaffold did not seek its boundary")
        rule.runOnUiThread { input.backCompleted() }
        advanceFrames(1)
        assertSame(transition, probe.transition, "commit replaced the sought scaffold state")
        assertTrue(probe.progress < 1f, "commit snapped the partially sought boundary")
        settle()
        assertSame(transition, probe.transition)
        assertEquals(1f, probe.progress)
        assertEquals(SettingsKey(SettingsPage.ABOUT), navigation.stack(FeedsKey).last())
    }

    private class ScaffoldProbe {
        var transition: Any? = null
        var progress: Float = 1f
    }

    private class ScaffoldProbeOverride(
        private val upstream: AnimatedPaneOverride,
        private val probe: ScaffoldProbe,
    ) : AnimatedPaneOverride {
        @Composable
        override fun <
            Role : PaneScaffoldRole,
            Value : PaneScaffoldValue<Role>,
        > AnimatedPaneOverrideScope<Role, Value>.AnimatedPane() {
            val visible = LocalNavAnimatedContentScope.current.transition.targetState == EnterExitState.Visible
            if (scope.paneRole == ThreePaneScaffoldRole.Primary && visible) {
                val transition = scope.scaffoldStateTransition
                val progress = scope.motionProgress
                SideEffect {
                    probe.transition = transition
                    probe.progress = progress
                }
            }
            with(upstream) { this@AnimatedPane.AnimatedPane() }
        }
    }
}
