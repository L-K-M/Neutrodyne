// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.EpisodeKey
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.LicencesKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import ch.lkmc.neutrodyne.core.navigation.SettingsHomeKey
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

@Composable
@Suppress("FunctionNaming") // Compose functions use PascalCase.
private fun BarePage(label: String) {
    Box(Modifier.fillMaxSize().testTag(label))
}

/** Legacy judge probe: preserve its unflushed frame timing and assertions. */
@OptIn(androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions") // Keep the legacy cases and timing helpers together.
class PredictivePinProbe {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var nav: NavigationState
    private lateinit var dispatcher: NavigationEventDispatcher
    private val installers: Set<EntryProviderInstaller> =
        setOf({
            for (tab in TOP_LEVEL_TABS) entry(tab, metadata = NdSceneMetadata.paneList()) { BarePage("root-$tab") }
            entry<SettingsHomeKey>(metadata = NdSceneMetadata.paneList()) { BarePage("home") }
            entry<SettingsKey>(metadata = NdSceneMetadata.paneDetail()) { BarePage("about") }
            entry<LicencesKey>(metadata = NdSceneMetadata.paneDetail()) { BarePage("licences") }
            entry<EpisodeKey>(metadata = NdSceneMetadata.paneExtra()) { BarePage("extra") }
        })

    private fun settle() {
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
    }

    private fun setup() {
        rule.setContent {
            nav = rememberNavigationState()
            dispatcher = requireNotNull(LocalNavigationEventDispatcherOwner.current).navigationEventDispatcher
            CompositionLocalProvider(LocalAppNavigator provides nav) {
                NeutrodyneNavHost(nav, installers, PaneScaffoldDirective.Default.copy(maxHorizontalPartitions = 2))
            }
        }
        rule.runOnUiThread {
            nav.push(SettingsHomeKey)
            nav.push(SettingsKey(SettingsPage.ABOUT))
        }
        settle()
        rule.runOnUiThread { nav.push(LicencesKey) }
        settle()
    }

    private fun observe(label: String) {
        println(
            "STATE $label stack=${nav.stack(FeedsKey).toList()} " +
                "licences=${rule.onAllNodesWithTag("licences").fetchSemanticsNodes().size} " +
                "about=${rule.onAllNodesWithTag("about").fetchSemanticsNodes().size}",
        )
    }

    private fun gesture(
        pinned: Boolean,
        cancelled: Boolean = false,
    ) {
        setup()
        rule.runOnUiThread { nav.pop() }
        if (pinned) repeat(4) { rule.mainClock.advanceTimeByFrame() } else settle()
        observe("start")
        val input = DirectNavigationEventInput()
        rule.runOnUiThread {
            dispatcher.addInput(input)
            input.backStarted(NavigationEvent(NavigationEvent.EDGE_LEFT, 0f, 16f, 400f))
            input.backProgressed(NavigationEvent(NavigationEvent.EDGE_LEFT, 0.4f, 300f, 400f))
        }
        repeat(2) { rule.mainClock.advanceTimeByFrame() }
        observe("progress")
        rule.runOnUiThread { if (cancelled) input.backCancelled() else input.backCompleted() }
        repeat(12) { rule.mainClock.advanceTimeByFrame() }
        settle()
        observe("end")
        assertEquals(if (cancelled) SettingsKey(SettingsPage.ABOUT) else SettingsHomeKey, nav.stack(FeedsKey).last())
    }

    @Test fun pinnedCommit() = gesture(true)

    @Test fun pinnedCancel() = gesture(true, true)

    @Test fun settledCommitControl() = gesture(false)

    @Test fun settledSystemBackControl() {
        setup()
        rule.runOnUiThread { nav.pop() }
        settle()
        observe("before-system-back")
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        settle()
        observe("after-system-back")
        assertEquals(SettingsHomeKey, nav.stack(FeedsKey).last())
    }

    @Test fun pinnedSystemBack() {
        setup()
        rule.runOnUiThread { nav.pop() }
        repeat(4) { rule.mainClock.advanceTimeByFrame() }
        observe("before-system-back")
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        settle()
        observe("after-system-back")
        assertEquals(SettingsHomeKey, nav.stack(FeedsKey).last())
    }

    private fun boundaryGesture(
        pinned: Boolean,
        cancelled: Boolean = false,
    ) {
        setup()
        rule.runOnUiThread { nav.pop() }
        settle()
        rule.runOnUiThread { nav.push(EpisodeKey(1)) }
        settle()
        rule.runOnUiThread { nav.push(EpisodeKey(2)) }
        settle()
        rule.runOnUiThread { nav.pop() }
        if (pinned) repeat(4) { rule.mainClock.advanceTimeByFrame() } else settle()
        observe("extra-start")
        val input = DirectNavigationEventInput()
        rule.runOnUiThread {
            dispatcher.addInput(input)
            input.backStarted(NavigationEvent(NavigationEvent.EDGE_LEFT, 0f, 16f, 400f))
            input.backProgressed(NavigationEvent(NavigationEvent.EDGE_LEFT, 0.4f, 300f, 400f))
        }
        repeat(2) { rule.mainClock.advanceTimeByFrame() }
        observe("extra-progress")
        rule.runOnUiThread { if (cancelled) input.backCancelled() else input.backCompleted() }
        repeat(12) { rule.mainClock.advanceTimeByFrame() }
        settle()
        observe("extra-end")
        assertEquals(
            if (cancelled) EpisodeKey(1) else SettingsKey(SettingsPage.ABOUT),
            nav.stack(FeedsKey).last(),
            "scaffold counted the transient pin as a real back entry",
        )
    }

    @Test fun pinnedBoundaryCommit() = boundaryGesture(true)

    @Test fun pinnedBoundaryCancel() = boundaryGesture(true, true)

    @Test fun settledBoundaryCommitControl() = boundaryGesture(false)
}
