// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.radio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.chapter_seek
import ch.lkmc.neutrodyne.core.ui.resources.radio_choose_other
import ch.lkmc.neutrodyne.core.ui.resources.radio_cleared
import ch.lkmc.neutrodyne.core.ui.resources.radio_elapsed
import ch.lkmc.neutrodyne.core.ui.resources.radio_elapsed_full
import ch.lkmc.neutrodyne.core.ui.resources.radio_nothing
import ch.lkmc.neutrodyne.core.ui.resources.radio_pause
import ch.lkmc.neutrodyne.core.ui.resources.radio_paused
import ch.lkmc.neutrodyne.core.ui.resources.radio_play
import ch.lkmc.neutrodyne.core.ui.resources.radio_playing
import ch.lkmc.neutrodyne.core.ui.resources.radio_ready
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun RadioPlayer(
    state: RadioState,
    actions: RadioActions,
    compact: Boolean,
    short: Boolean,
) {
    val selected = state.selected
    val duration = selected?.durationMs ?: 0L
    val status =
        when {
            selected == null -> Res.string.radio_cleared
            state.playing -> Res.string.radio_playing
            state.hasPlayed || state.elapsedMs > 0 -> Res.string.radio_paused
            else -> Res.string.radio_ready
        }
    val statusText = stringResource(status)
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF29190E),
                            Color(0xFFB18A53),
                        ),
                    ),
                    DialOpening,
                ).padding(if (compact) 5.dp else 7.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .recess(DialOpening)
                    .radioGlass()
                    .dialPaper()
                    .padding(if (compact) 9.dp else 12.dp),
            ) {
                if (!short) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .background(
                                    Brush.radialGradient(
                                        if (state.playing) {
                                            listOf(Color(0xFFFFE9AD), Color(0xFF995017))
                                        } else {
                                            listOf(Color(0xFFBDA177), Color(0xFF664422))
                                        },
                                    ),
                                    CircleShape,
                                ).border(0.5.dp, Color(0xFF654A24), CircleShape),
                        )
                        RadioText(statusText, 11, mono = false, modifier = Modifier.weight(1f))
                        val index = state.episodes.indexOfFirst { it.id == state.selectedId }
                        if (state.complete && index >= 0) {
                            val queuePosition = "${(index + 1).toString().padStart(
                                2,
                                '0',
                            )} / ${state.episodes.size.toString().padStart(2, '0')}"
                            RadioText(queuePosition, 11)
                        }
                    }
                }
                RadioText(
                    selected?.title ?: stringResource(Res.string.radio_nothing),
                    if (short) {
                        15
                    } else if (compact) {
                        16
                    } else {
                        17
                    },
                    bold = true,
                    modifier =
                        Modifier.padding(
                            top = 5.dp,
                            bottom = 3.dp,
                        ),
                )
                RadioText(selected?.podcast ?: stringResource(Res.string.radio_choose_other), 11, mono = false)
                Needle(state.elapsedMs, duration, selected != null && duration > 0, actions.seek)
                if (!compact) RadioText(radioElapsed(duration), 11, modifier = Modifier.align(Alignment.End))
            }
        }
        MaterialPlate(
            RadioMaterial.METAL,
            Modifier.padding(top = if (compact) 6.dp else 8.dp).fillMaxWidth().border(
                1.dp,
                Brass,
            ),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(
                    vertical = 7.dp,
                    horizontal = 9.dp,
                ),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Nixie(state.elapsedMs, selected != null, compact, Modifier.weight(1f))
                val label = stringResource(if (state.playing) Res.string.radio_pause else Res.string.radio_play)
                Box(
                    Modifier
                        .size(if (compact) 64.dp else 70.dp)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    Color(0xFF0D120E),
                                    Color(0xFF5B5038),
                                ),
                            ),
                            CircleShape,
                        ).border(
                            1.dp,
                            Brass,
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        Modifier
                            .offset(y = if (state.playing) 1.dp else (-2).dp)
                            .size(if (compact) 56.dp else 60.dp)
                            .background(Brush.linearGradient(listOf(Color(0xFFFFF8E7), Color(0xFFB99B68))), CircleShape)
                            .border(1.dp, Color(0xFFFFF4D6), CircleShape)
                            .concaveIvory()
                            .clickable(enabled = selected != null, onClick = actions.togglePlay)
                            .semantics {
                                contentDescription = "$label ${selected?.title.orEmpty()}"
                                stateDescription =
                                    statusText
                            }.testTag("radio_play"),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.padding(4.dp)) {
                            Icon(
                                if (state.playing) NdIcons.Pause else NdIcons.PlayArrow,
                                null,
                                Modifier.size(22.dp),
                                tint = Ink,
                            )
                        }
                        RadioText(label, 11, mono = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun Needle(
    elapsedMs: Long,
    durationMs: Long,
    enabled: Boolean,
    onSeek: (Long) -> Unit,
) {
    val progress = if (durationMs > 0) (elapsedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val seekLabel = stringResource(Res.string.chapter_seek, radioElapsed(elapsedMs))
    Column(Modifier.padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (index in 0..4) RadioText(radioElapsed(durationMs * index / 4), 10)
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(38.dp)
                .testTag("radio_seek")
                .semantics {
                    contentDescription = seekLabel
                    progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                    setProgress {
                        if (enabled) onSeek((it.coerceIn(0f, 1f) * durationMs).toLong())
                        enabled
                    }
                }.pointerInput(durationMs, enabled) {
                    detectTapGestures {
                        if (enabled) {
                            onSeek(
                                (it.x / size.width * durationMs).toLong().coerceIn(
                                    0,
                                    durationMs,
                                ),
                            )
                        }
                    }
                }.pointerInput(durationMs, enabled) {
                    detectDragGestures { change, _ ->
                        if (enabled) {
                            change.consume()
                            onSeek((change.position.x / size.width * durationMs).toLong().coerceIn(0, durationMs))
                        }
                    }
                },
        ) {
            val baseline = size.height - 3.dp.toPx()
            for (tick in 0..40) {
                val x = size.width * tick / 40
                drawLine(
                    Color(0xFF72512B),
                    Offset(
                        x,
                        baseline,
                    ),
                    Offset(
                        x,
                        baseline - (if (tick % 10 == 0) 22.dp else 12.dp).toPx(),
                    ),
                    1.dp.toPx(),
                )
            }
            drawLine(Color(0xFF78592F), Offset(0f, baseline), Offset(size.width, baseline), 1.dp.toPx())
            drawLine(
                Color(0xFFAA3D28),
                Offset(
                    size.width * progress,
                    0f,
                ),
                Offset(
                    size.width * progress,
                    baseline,
                ),
                3.dp.toPx(),
            )
        }
    }
}

@Composable
private fun Nixie(
    elapsedMs: Long,
    active: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val glowBlur = with(LocalDensity.current) { 4.dp.toPx() }
    val elapsed = elapsedMs.coerceAtLeast(0) / 1000
    val accessible = stringResource(Res.string.radio_elapsed_full, elapsed / 3600, (elapsed / 60) % 60, elapsed % 60)
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = (if (compact) 134.dp else 150.dp) * scale).fillMaxWidth().clearAndSetSemantics {
                contentDescription =
                    accessible
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(
                        (if (compact) 44.dp else 48.dp) * scale,
                    ).background(Color(0xFF171A13))
                    .recess()
                    .radioGlass()
                    .padding(
                        horizontal = 6.dp,
                        vertical = 2.dp,
                    ),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                radioElapsed(elapsedMs).forEach { digit ->
                    if (digit == ':') {
                        Text(
                            digit.toString(),
                            color = if (active) Color(0xFFFFA054) else Color(0xFF61452D),
                            fontFamily = dosis(),
                            fontSize = 24.sp,
                        )
                    } else {
                        Box(
                            Modifier.weight(1f).fillMaxHeight().border(
                                0.5.dp,
                                Color(0x33756546),
                                RoundedCornerShape(
                                    topStart = 8.dp,
                                    topEnd = 8.dp,
                                ),
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Canvas(Modifier.matchParentSize()) {
                                var y = 2.dp.toPx()
                                while (y < size.height) {
                                    drawLine(Color(0x228D774B), Offset(0f, y), Offset(size.width, y), 0.5.dp.toPx())
                                    y += 4.dp.toPx()
                                }
                            }
                            Text(
                                CATHODE_GHOST.toString(),
                                color = Color(0x338D774B),
                                fontFamily = dosis(),
                                fontSize = 30.sp,
                            )
                            Text(
                                digit.toString(),
                                color = if (active) Color(0xFFFFAE68) else Color(0xFF61452D),
                                fontFamily = dosis(),
                                fontSize = 30.sp,
                                style =
                                    TextStyle(
                                        shadow =
                                            Shadow(
                                                if (active) Color(0xFFE85B14) else Color.Transparent,
                                                blurRadius = glowBlur,
                                            ),
                                    ),
                            )
                        }
                    }
                }
            }
            RadioText(
                stringResource(Res.string.radio_elapsed).uppercase(),
                10,
                color = Cream,
                modifier = Modifier.padding(top = 4.dp),
                mono = false,
            )
        }
    }
}

// Unlit wire behind each digit, a material glyph rather than localized copy.
private const val CATHODE_GHOST = '8'
