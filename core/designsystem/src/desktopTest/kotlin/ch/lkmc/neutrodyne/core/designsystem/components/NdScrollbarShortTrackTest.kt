// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test

/**
 * 08 Desktop windows: a vertical overlay scrollbar whose track is shorter than the 24 dp minimum
 * thumb must still draw. The horizontal overload clamps its thumb to the track; the vertical
 * overloads must too, or `travel = track - thumb` goes negative and `coerceIn` throws while the
 * frame is drawn.
 */
@OptIn(ExperimentalTestApi::class)
class NdScrollbarShortTrackTest {
    @Test
    fun verticalOverloadsDrawWhenTrackIsShorterThanMinThumb() {
        runDesktopComposeUiTest(width = 240, height = 240) {
            setContent {
                val scrollState = rememberScrollState()
                val listState = rememberLazyListState()
                val gridState = rememberLazyGridState()
                val style = DesktopScrollbars.style
                val color = ndScrollbarColor(style)
                Column(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(ShortTrack)
                                .ndScrollbar(listState, style, color),
                    ) {
                        items(20) { Text("Row $it", Modifier.fillMaxWidth()) }
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(1),
                        state = gridState,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(ShortTrack)
                                .ndScrollbar(gridState, style, color),
                    ) {
                        items(20) { Text("Cell $it", Modifier.fillMaxWidth()) }
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .height(ShortTrack)
                            .ndScrollbar(scrollState, style, color)
                            .verticalScroll(scrollState),
                    ) {
                        repeat(20) { Text("Line $it", Modifier.fillMaxWidth()) }
                    }
                }
            }
            waitForIdle()
            onRoot().captureToImage()
        }
    }
}

/** Shorter than the 24 dp [NdScrollbarStyle.minThumb] default. */
private val ShortTrack = 12.dp
