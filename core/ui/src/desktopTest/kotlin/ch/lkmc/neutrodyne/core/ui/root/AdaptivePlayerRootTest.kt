// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.ui.root

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.navigation.DiscoverKey
import ch.lkmc.neutrodyne.core.navigation.DownloadsKey
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import ch.lkmc.neutrodyne.core.navigation.Route
import ch.lkmc.neutrodyne.core.navigation.UpNextKey
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AdaptivePlayerRootTest {
    @Test
    fun playingUsesPanelAndIdleDoesNotComposeAPlayer() =
        runDesktopComposeUiTest(width = 1200, height = 900) {
            var playing by mutableStateOf(true)
            var last: PlayerSlotState? = null
            var instances = 0
            val slots =
                RootSlots(player = {
                    last = it
                    androidx.compose.runtime.DisposableEffect(Unit) {
                        instances++
                        onDispose { instances-- }
                    }
                }, userMessages = emptyFlow())
            setContent {
                NeutrodyneRoot(
                    state =
                        RootUiState.READY.copy(
                            hasNowPlaying = playing,
                            posture =
                                ch.lkmc.neutrodyne.core.model.WindowPosture(
                                    kotlinx.collections.immutable.persistentListOf(
                                        ch.lkmc.neutrodyne.core.model.WindowHinge(
                                            ch.lkmc.neutrodyne.core.model
                                                .WindowRectDp(0f, 400f, 1200f, 424f),
                                            ch.lkmc.neutrodyne.core.model.HingeOrientation.HORIZONTAL,
                                            ch.lkmc.neutrodyne.core.model.HingeState.HALF_OPENED,
                                            true,
                                        ),
                                    ),
                                ),
                        ),
                    actions = RootActions({}, {}, {}, {}, { false }, {}, {}),
                    slots = slots,
                    installers = RootTestEntries,
                    platform = BuildInfo.Platform.DESKTOP,
                )
            }
            runOnIdle {
                assertTrue(requireNotNull(last).layout.playerPanel)
                assertTrue(requireNotNull(last).posture.hinges.isEmpty())
                assertEquals(692, last?.layout?.contentWidthDp)
                assertEquals(1, instances)
                playing = false
            }
            runOnIdle { assertEquals(0, instances) }
        }

    @Test
    fun resizingAndPanelCallbacksKeepOneSlotAndNavigationScroll() =
        runDesktopComposeUiTest(width = 1800, height = 900) {
            var width by androidx.compose.runtime.mutableIntStateOf(839)
            var hidden by mutableStateOf(false)
            var playing by mutableStateOf(true)
            var current: PlayerSlotState? = null
            var instances = 0
            var closeCalls = 0
            var reopenCalls = 0
            val slots =
                RootSlots(player = { slot ->
                    current = slot
                    androidx.compose.foundation.layout
                        .Box(Modifier.fillMaxSize().testTag("probePlayer"))
                    androidx.compose.runtime.DisposableEffect(Unit) {
                        instances++
                        onDispose { instances-- }
                    }
                }, userMessages = emptyFlow())
            setContent {
                val actual = androidx.compose.ui.platform.LocalWindowInfo.current
                val injected =
                    androidx.compose.runtime.remember(actual) {
                        object : androidx.compose.ui.platform.WindowInfo by actual {
                            override val containerSize: androidx.compose.ui.unit.IntSize
                                get() =
                                    androidx.compose.ui.unit
                                        .IntSize(width, 900)
                        }
                    }
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalWindowInfo provides injected,
                    androidx.compose.ui.platform.LocalDensity provides
                        androidx.compose.ui.unit
                            .Density(1f),
                ) {
                    androidx.compose.foundation.layout.Box(Modifier.size(width.dp, 900.dp)) {
                        NeutrodyneRoot(
                            state = RootUiState.READY.copy(hasNowPlaying = playing, playerPanelHidden = hidden),
                            actions =
                                RootActions(
                                    {},
                                    {},
                                    {},
                                    {},
                                    { false },
                                    {
                                        closeCalls++
                                        hidden = true
                                    },
                                    {
                                        reopenCalls++
                                        hidden = false
                                    },
                                ),
                            slots = slots,
                            installers = RootTestEntries,
                            platform = BuildInfo.Platform.DESKTOP,
                        )
                    }
                }
            }
            onNodeWithTag("rootList").performScrollToIndex(20)
            onNodeWithText("entry-20").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            onNodeWithText("entry-20").assertIsFocused()
            val lostFocus = mutableListOf<Int>()
            for ((next, content, panelWidth) in listOf(
                Triple(840, 384, 360),
                Triple(1055, 599, 360),
                Triple(1056, 600, 360),
                Triple(1199, 743, 360),
                Triple(1200, 692, 412),
                Triple(1708, 1200, 412),
            )) {
                runOnIdle { width = next }
                waitForIdle()
                runOnIdle {
                    assertEquals(1, instances)
                    assertEquals(content, current?.layout?.contentWidthDp)
                }
                val bounds = onNodeWithTag("probePlayer").fetchSemanticsNode().boundsInRoot
                assertEquals(panelWidth.toFloat(), bounds.width)
                assertEquals(next.toFloat(), bounds.right)
                onNodeWithText("entry-20").assertIsDisplayed()
                if (!onNodeWithText("entry-20")
                        .fetchSemanticsNode()
                        .config[androidx.compose.ui.semantics.SemanticsProperties.Focused]
                ) {
                    lostFocus += next
                }
            }
            runOnIdle { requireNotNull(current).onClosePanel() }
            runOnIdle {
                assertTrue(hidden)
                assertTrue(requireNotNull(current).reopenPanelFromMini)
                assertEquals(1, closeCalls)
                playing = false
            }
            runOnIdle {
                assertEquals(0, instances)
                playing = true
            }
            runOnIdle {
                assertEquals(1, instances)
                assertEquals(false, current?.layout?.playerPanel)
                requireNotNull(current).onReopenPanel()
            }
            runOnIdle {
                assertEquals(1, reopenCalls)
                assertEquals(true, current?.layout?.playerPanel)
                assertEquals(emptyList<Int>(), lostFocus, "Window widths that lost entry focus")
            }
        }

    @Test
    fun compactHeightUsesTheActualBarWithoutSubtractingARail() =
        runDesktopComposeUiTest(width = 960, height = 400) {
            var current: PlayerSlotState? = null
            setContent {
                NeutrodyneRoot(
                    state = RootUiState.READY.copy(hasNowPlaying = true),
                    actions = RootActions({}, {}, {}, {}, { false }, {}, {}),
                    slots = RootSlots(player = { current = it }, userMessages = emptyFlow()),
                    installers = RootTestEntries,
                    platform = BuildInfo.Platform.DESKTOP,
                )
            }
            runOnIdle {
                assertEquals(600, current?.layout?.contentWidthDp)
                assertEquals(2, current?.layout?.partitions)
                assertEquals(true, current?.layout?.playerPanel)
            }
        }

    @Test
    fun twoContentPanesStayOperableAt600DpWithDoubleFont() =
        runDesktopComposeUiTest(width = 1056, height = 900) {
            var slot: PlayerSlotState? = null
            var clicks = 0
            val detail: EntryProviderInstaller = {
                entry<ch.lkmc.neutrodyne.core.navigation.PodcastKey>(metadata = NdSceneMetadata.paneDetail()) {
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().testTag("detailPane")) {
                        androidx.compose.material3.TextButton(onClick = { clicks++ }) {
                            androidx.compose.material3.Text("Details action")
                        }
                    }
                }
            }
            setContent {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides
                        androidx.compose.ui.unit
                            .Density(1f, 2f),
                ) {
                    NeutrodyneRoot(
                        state = RootUiState.READY.copy(hasNowPlaying = true),
                        actions = RootActions({}, {}, {}, {}, { false }, {}, {}),
                        slots = RootSlots(player = { slot = it }, userMessages = emptyFlow()),
                        installers = RootTestEntries + detail,
                        platform = BuildInfo.Platform.DESKTOP,
                    )
                }
            }
            runOnIdle {
                requireNotNull(slot).navigate(
                    ch.lkmc.neutrodyne.core.navigation
                        .PodcastKey(3),
                )
            }
            val list = onNodeWithTag("rootList").fetchSemanticsNode().boundsInRoot
            val pane = onNodeWithTag("detailPane").fetchSemanticsNode().boundsInRoot
            assertTrue(list.width > 0f && pane.width > 0f)
            assertTrue(list.left >= 96f && pane.right <= 696f)
            assertTrue(list.right <= pane.left && list.width + pane.width <= 600f)
            onNodeWithText("Details action").assertIsDisplayed().performClick()
            runOnIdle { assertEquals(1, clicks) }
        }

    @Test
    fun expansionAcknowledgementSurvivesIdle() {
        runDesktopComposeUiTest(width = 900, height = 800) {
            var playing by mutableStateOf(true)
            var slot: PlayerSlotState? = null
            val pending = Channel<Route>(Channel.UNLIMITED)
            val routes = pending.receiveAsFlow()
            setContent {
                NeutrodyneRoot(
                    state = RootUiState.READY.copy(hasNowPlaying = playing, playerPanelHidden = true),
                    actions = RootActions({}, {}, {}, {}, { false }, {}, {}),
                    slots = RootSlots(player = { slot = it }, userMessages = emptyFlow()),
                    routes = routes,
                    installers = RootTestEntries,
                    platform = BuildInfo.Platform.DESKTOP,
                )
            }
            runOnIdle { pending.trySend(Route.ExpandPlayer) }
            runOnIdle {
                assertEquals(1, slot?.expandRequest)
                assertEquals(0, slot?.initialConsumedExpandRequest)
                requireNotNull(slot).onExpandRequestConsumed(1)
            }
            runOnIdle { playing = false }
            runOnIdle { playing = true }
            runOnIdle {
                assertEquals(1, slot?.expandRequest)
                assertEquals(1, slot?.initialConsumedExpandRequest)
                assertTrue(requireNotNull(slot).reopenPanelFromMini)
            }
            runOnIdle { pending.trySend(Route.ExpandPlayer) }
            runOnIdle {
                assertEquals(2, slot?.expandRequest)
                assertEquals(1, slot?.initialConsumedExpandRequest)
            }
        }
    }
}

private val RootTestEntries: Set<EntryProviderInstaller> =
    setOf({
        entry<FeedsKey>(metadata = NdSceneMetadata.paneList()) {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().testTag("rootList")) {
                items(100) { androidx.compose.material3.Text("entry-$it", Modifier.size(100.dp, 48.dp).focusable()) }
            }
        }
        entry<LibraryKey> { androidx.compose.material3.Text("library") }
        entry<UpNextKey> { androidx.compose.material3.Text("queue") }
        entry<DownloadsKey> { androidx.compose.material3.Text("downloads") }
        entry<DiscoverKey> { androidx.compose.material3.Text("discover") }
    })
