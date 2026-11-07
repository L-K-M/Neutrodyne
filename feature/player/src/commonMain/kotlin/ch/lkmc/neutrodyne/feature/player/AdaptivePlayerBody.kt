// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.model.WindowPosture

/** Each control region scrolls independently; text scaling never pushes controls under a hinge. */
@Composable
internal fun AdaptivePlayerBody(
    layout: PlayerContentLayout,
    posture: WindowPosture,
    header: @Composable () -> Unit,
    artwork: @Composable (Modifier) -> Unit,
    controls: @Composable () -> Unit,
    tabs: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    safeInsets: WindowInsets = WindowInsets.safeDrawing,
) {
    val latestHeader by rememberUpdatedState(header)
    val latestArtwork by rememberUpdatedState(artwork)
    val latestControls by rememberUpdatedState(controls)
    val latestTabs by rememberUpdatedState(tabs)
    val movingHeader = remember { movableContentOf { latestHeader() } }
    val movingArtwork = remember { movableContentOf<Modifier> { latestArtwork(it) } }
    val movingControls = remember { movableContentOf { latestControls() } }
    val movingTabs = remember { movableContentOf { latestTabs() } }
    val density = LocalDensity.current
    var top by remember { mutableFloatStateOf(0f) }
    Surface(
        modifier
            .windowInsetsPadding(
                safeInsets,
            ).onGloballyPositioned { top = it.positionInWindow().y / density.density },
        color = MaterialTheme.colorScheme.surface,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val availableWidth = maxWidth.value
            val availableHeight = maxHeight.value
            when (layout) {
                PlayerContentLayout.TABLETOP -> {
                    val hinge = checkNotNull(tabletopHinge(posture))
                    val upper = (hinge.top - top).coerceIn(0f, availableHeight)
                    val lower = (hinge.bottom - top).coerceIn(upper, availableHeight)
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxWidth().height(upper.dp), contentAlignment = Alignment.Center) {
                            val side = minOf(availableWidth - 32f, upper - 32f, 320f).coerceAtLeast(0f)
                            movingArtwork(Modifier.size(side.dp))
                        }
                        Spacer(Modifier.height((lower - upper).dp))
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            movingHeader()
                            movingControls()
                            movingTabs()
                        }
                    }
                }

                PlayerContentLayout.LANDSCAPE -> {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                            movingArtwork(
                                Modifier.size(
                                    minOf(availableWidth / 2f - 32f, availableHeight - 32f, 320f).coerceAtLeast(0f).dp,
                                ),
                            )
                        }
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            movingHeader()
                            movingControls()
                            movingTabs()
                        }
                    }
                }

                PlayerContentLayout.PORTRAIT -> {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        movingHeader()
                        movingArtwork(Modifier.size(minOf(availableWidth - 32f, 320f).coerceAtLeast(0f).dp))
                        movingControls()
                        movingTabs()
                    }
                }
            }
        }
    }
}
