// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ReportVisibleEpisodeIdsTest {
    @Test fun stoppingTheLifecycleReleasesAndResumingRestoresTheWindow() =
        runDesktopComposeUiTest(
            width =
            200,
            height = 200,
        ) {
            var reported = emptySet<Long>()
            val owner =
                object : LifecycleOwner {
                    override val lifecycle =
                        LifecycleRegistry.createUnsafe(this).apply {
                            currentState = Lifecycle.State.RESUMED
                        }
                }
            setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    val list = rememberLazyListState()
                    ReportVisibleEpisodeIds(list, { 20 }, { it + 1L }, { reported = it }, margin = 0)
                    LazyColumn(state = list, modifier = Modifier.height(60.dp).width(200.dp)) {
                        items(20) { Box(Modifier.height(20.dp).width(200.dp)) }
                    }
                }
            }
            waitForIdle()
            val beforeStop = reported
            kotlin.test.assertTrue(beforeStop.isNotEmpty())
            runOnIdle { owner.lifecycle.currentState = Lifecycle.State.CREATED }
            runOnIdle { assertEquals(emptySet(), reported) }
            runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
            waitForIdle()
            assertEquals(beforeStop, reported)
        }

    @Test
    fun visibleWindowIncludesTenItemMarginAndMovesOnScroll() =
        runDesktopComposeUiTest(width = 200, height = 200) {
            lateinit var listState: LazyListState
            lateinit var scope: CoroutineScope
            var reported = emptySet<Long>()
            setContent {
                listState = rememberLazyListState()
                scope = rememberCoroutineScope()
                ReportVisibleEpisodeIds(
                    listState = listState,
                    itemCount = { 100 },
                    idAt = { it + 1L },
                    onIds = { reported = it },
                )
                LazyColumn(state = listState, modifier = Modifier.height(60.dp).width(200.dp)) {
                    items(100) { Box(Modifier.height(20.dp).width(200.dp)) }
                }
            }
            waitForIdle()
            assertEquals((1L..13L).toSet(), reported)

            runOnIdle { scope.launch { listState.scrollToItem(40) } }
            waitForIdle()
            assertEquals((31L..53L).toSet(), reported)
        }

    @Test
    fun skipsHeadersAndPlaceholdersAndReactsToPagingUpdates() =
        runDesktopComposeUiTest(width = 200, height = 200) {
            // Two leading headers, an episode, a placeholder, then more episodes and a footer.
            var ids by mutableStateOf(listOf(null, null, 11L, null, 12L, 13L, 14L, null))
            var reported = emptySet<Long>()
            setContent {
                val listState = rememberLazyListState()
                ReportVisibleEpisodeIds(
                    listState = listState,
                    itemCount = { ids.size },
                    idAt = { ids[it] },
                    margin = 2,
                    onIds = { reported = it },
                )
                LazyColumn(state = listState, modifier = Modifier.height(60.dp).width(200.dp)) {
                    items(ids.size) { Box(Modifier.height(20.dp).width(200.dp)) }
                }
            }
            waitForIdle()
            assertEquals(setOf(11L, 12L), reported)

            runOnIdle { ids = ids.toMutableList().also { it[3] = 15L } }
            waitForIdle()
            assertEquals(setOf(11L, 12L, 15L), reported)
        }

    @Test
    fun leavingCompositionReleasesIdsAndEmptyListReportsEmpty() =
        runDesktopComposeUiTest(width = 200, height = 200) {
            var mounted by mutableStateOf(true)
            var count by mutableIntStateOf(20)
            var reported = emptySet<Long>()
            setContent {
                if (mounted) {
                    val listState = rememberLazyListState()
                    ReportVisibleEpisodeIds(
                        listState = listState,
                        itemCount = { count },
                        idAt = { it.toLong() },
                        onIds = { reported = it },
                    )
                    LazyColumn(state = listState, modifier = Modifier.height(60.dp).width(200.dp)) {
                        items(count) { Box(Modifier.height(20.dp).width(200.dp)) }
                    }
                }
            }
            waitForIdle()
            assertEquals((0L..12L).toSet(), reported)
            runOnIdle { count = 0 }
            waitForIdle()
            assertEquals(emptySet(), reported)

            runOnIdle { count = 20 }
            waitForIdle()
            assertEquals((0L..12L).toSet(), reported)
            runOnIdle { mounted = false }
            waitForIdle()
            assertEquals(emptySet(), reported)
        }
}
