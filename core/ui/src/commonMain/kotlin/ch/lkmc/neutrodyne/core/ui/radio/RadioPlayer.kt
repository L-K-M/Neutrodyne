// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.radio

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.radio.Brass
import ch.lkmc.neutrodyne.core.designsystem.radio.Cream
import ch.lkmc.neutrodyne.core.designsystem.radio.DialOpening
import ch.lkmc.neutrodyne.core.designsystem.radio.MaterialPlate
import ch.lkmc.neutrodyne.core.designsystem.radio.RadioMaterial
import ch.lkmc.neutrodyne.core.designsystem.radio.barlow
import ch.lkmc.neutrodyne.core.designsystem.radio.dialPaper
import ch.lkmc.neutrodyne.core.designsystem.radio.dosis
import ch.lkmc.neutrodyne.core.designsystem.radio.radioGlass
import ch.lkmc.neutrodyne.core.designsystem.radio.recess
import ch.lkmc.neutrodyne.core.designsystem.theme.LocalReducedMotion
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.chapter_seek
import ch.lkmc.neutrodyne.core.ui.resources.radio_choose_other
import ch.lkmc.neutrodyne.core.ui.resources.radio_cleared
import ch.lkmc.neutrodyne.core.ui.resources.radio_elapsed
import ch.lkmc.neutrodyne.core.ui.resources.radio_elapsed_full
import ch.lkmc.neutrodyne.core.ui.resources.radio_nothing
import ch.lkmc.neutrodyne.core.ui.resources.radio_paused
import ch.lkmc.neutrodyne.core.ui.resources.radio_play
import ch.lkmc.neutrodyne.core.ui.resources.radio_playing
import ch.lkmc.neutrodyne.core.ui.resources.radio_ready
import ch.lkmc.neutrodyne.core.ui.resources.radio_switch_off
import ch.lkmc.neutrodyne.core.ui.resources.radio_switch_on
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun RadioPlayer(
    state: RadioState,
    actions: RadioActions,
    compact: Boolean,
    short: Boolean,
    modifier: Modifier = Modifier,
) {
    val selected = state.selected
    // Retain the reference cap through 1.3x text; larger type needs a larger physical key.
    val capScale = (LocalDensity.current.fontScale / FIXED_CAP_FONT_SCALE).coerceAtLeast(1f)
    val duration = selected?.durationMs ?: 0L
    val status =
        when {
            selected == null -> Res.string.radio_cleared
            state.playing -> Res.string.radio_playing
            state.hasPlayed || state.elapsedMs > 0 -> Res.string.radio_paused
            else -> Res.string.radio_ready
        }
    val statusText = stringResource(status)
    Column(modifier.fillMaxWidth()) {
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
                    maxLines = 2,
                    modifier =
                        Modifier.padding(
                            top = 5.dp,
                            bottom = 3.dp,
                        ),
                )
                RadioText(
                    selected?.podcast ?: stringResource(Res.string.radio_choose_other),
                    11,
                    mono = false,
                    maxLines = 1,
                )
                Needle(state.elapsedMs, duration, selected != null && duration > 0, actions.seek)
                if (!compact) RadioText(radioTimeline(duration), 11, modifier = Modifier.align(Alignment.End))
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
                PlaySwitch(
                    on = state.playing,
                    enabled = selected != null,
                    description = "${stringResource(Res.string.radio_play)} ${selected?.title.orEmpty()}".trim(),
                    status = statusText,
                    compact = compact,
                    scale = capScale,
                    onToggle = actions.togglePlay,
                )
            }
        }
    }
}

/**
 * Play/pause is a panel toggle switch: a chrome bat lever through a hex nut on a plate
 * printed ON (red) and OFF (black). Lever up means playing. Tap, or flick the lever toward
 * the other label.
 */
@Composable
private fun PlaySwitch(
    on: Boolean,
    enabled: Boolean,
    description: String,
    status: String,
    compact: Boolean,
    scale: Float,
    onToggle: () -> Unit,
) {
    val reducedMotion = LocalReducedMotion.current
    // -1 is lever up (ON), +1 is lever down (OFF).
    val lever by animateFloatAsState(
        if (on) -1f else 1f,
        if (reducedMotion) snap() else spring(dampingRatio = 0.55f, stiffness = 900f),
    )
    val currentOn by rememberUpdatedState(on)
    val toggle by rememberUpdatedState(onToggle)
    // The switch keeps the footprint of the key it replaced, so the radio's layout budget is unchanged.
    val width = (if (compact) 54.dp else 58.dp) * scale
    val height = (if (compact) 64.dp else 70.dp) * scale
    val tab = height * SWITCH_TAB_FRACTION
    Box(
        Modifier
            .size(width, height)
            .alpha(if (enabled) 1f else 0.56f)
            .toggleable(on, enabled = enabled, role = Role.Switch) { toggle() }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                var travel = 0f
                detectVerticalDragGestures(
                    onDragStart = { travel = 0f },
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        travel += amount
                    },
                    // Crossing touch slop already cancelled the tap, so any flick toward the other
                    // label toggles; a further threshold would swallow short flicks. On a scrolling
                    // (very short) radio, a drag that starts on the switch moves the lever, not the page.
                    onDragEnd = {
                        if ((travel < 0f && !currentOn) || (travel > 0f && currentOn)) toggle()
                    },
                )
            }.semantics {
                contentDescription = description
                stateDescription = status
            }.testTag("radio_play"),
    ) {
        Canvas(Modifier.matchParentSize()) { drawSwitchPlate(tab.toPx()) }
        Column(Modifier.matchParentSize()) {
            SwitchLabel(stringResource(Res.string.radio_switch_on), tab)
            Spacer(Modifier.weight(1f))
            SwitchLabel(stringResource(Res.string.radio_switch_off), tab)
        }
        Canvas(Modifier.matchParentSize()) { drawSwitchLever(lever) }
    }
}

@Composable
private fun SwitchLabel(
    text: String,
    height: Dp,
) {
    Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
        // Printed on the plate, so it scales with the plate rather than with the text size.
        Text(
            text,
            color = Color(0xFFF7F1E3),
            fontFamily = barlow(),
            fontWeight = FontWeight.Bold,
            fontSize = (height.value * 0.52f / LocalDensity.current.fontScale).sp,
            lineHeight = (height.value * 0.6f / LocalDensity.current.fontScale).sp,
            maxLines = 1,
            softWrap = false,
        )
    }
}

private fun DrawScope.drawSwitchPlate(tab: Float) {
    val corner = CornerRadius(5.dp.toPx())
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFFF1F0EA), Color(0xFFB4B2A8), Color(0xFFDCDAD2), Color(0xFF9C9A90))),
        cornerRadius = corner,
    )
    drawRoundRect(Color(0xFF6E6C64), cornerRadius = corner, style = Stroke(1.dp.toPx()))
    val inset = 3.dp.toPx()
    drawRoundRect(
        Color(0xFFCB2A22),
        Offset(inset, inset),
        Size(size.width - inset * 2, tab - inset),
        CornerRadius(3.dp.toPx()),
    )
    drawRoundRect(
        Color(0xFF1C1B19),
        Offset(inset, size.height - tab),
        Size(size.width - inset * 2, tab - inset),
        CornerRadius(3.dp.toPx()),
    )
    val center = Offset(size.width / 2, size.height / 2)
    val nut = minOf(size.width, size.height - tab * 2) * 0.46f
    val hexagon =
        Path().apply {
            for (corner in 0..5) {
                val angle = (corner * 60.0) * PI / 180.0
                val point = Offset(center.x + nut * cos(angle).toFloat(), center.y + nut * sin(angle).toFloat())
                if (corner == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
            }
            close()
        }
    drawPath(hexagon, Color(0x55000000), style = Stroke(2.dp.toPx()))
    drawPath(
        hexagon,
        Brush.linearGradient(
            listOf(Color(0xFFFAFAF6), Color(0xFFA7A59C), Color(0xFFE6E4DC), Color(0xFF7F7D74)),
            center - Offset(nut, nut),
            center + Offset(nut, nut),
        ),
    )
    drawPath(hexagon, Color(0xFF6A685F), style = Stroke(0.75.dp.toPx()))
    // Threaded bushing: concentric rings around the lever's pivot hole.
    val bushing = nut * 0.68f
    drawCircle(
        Brush.radialGradient(listOf(Color(0xFFE9E7E0), Color(0xFF8D8B82)), center, bushing),
        bushing,
        center,
    )
    for (ring in 1..3) {
        drawCircle(Color(0x66504E46), bushing * (1f - ring * 0.18f), center, style = Stroke(0.75.dp.toPx()))
    }
    drawCircle(Color(0xFF2A2925), bushing * 0.34f, center)
}

private fun DrawScope.drawSwitchLever(position: Float) {
    val center = Offset(size.width / 2, size.height / 2)
    // The tip stops just short of the printed tabs so ON and OFF stay legible.
    val reach = size.height * 0.17f
    val base = size.width * 0.07f
    val tip = size.width * 0.11f
    val end = Offset(center.x, center.y + position * reach)
    val shadow = Offset(1.5.dp.toPx(), 2.dp.toPx())

    fun bat(offset: Offset): Path =
        Path().apply {
            moveTo(center.x - base + offset.x, center.y + offset.y)
            lineTo(end.x - tip + offset.x, end.y + offset.y)
            lineTo(end.x + tip + offset.x, end.y + offset.y)
            lineTo(center.x + base + offset.x, center.y + offset.y)
            close()
        }
    drawPath(bat(shadow), Color(0x40000000))
    drawCircle(Color(0x40000000), tip, end + shadow)
    val chrome =
        Brush.horizontalGradient(
            listOf(Color(0xFF6F6D66), Color(0xFFFBFBF8), Color(0xFFBDBBB3), Color(0xFF5E5C55)),
            end.x - tip,
            end.x + tip,
        )
    drawCircle(chrome, base * 1.3f, center)
    drawPath(bat(Offset.Zero), chrome)
    drawCircle(chrome, tip, end)
    drawCircle(Color(0xCCFFFFFF), tip * 0.35f, end - Offset(tip * 0.3f, tip * 0.3f))
}

@Composable
private fun Needle(
    elapsedMs: Long,
    durationMs: Long,
    enabled: Boolean,
    onSeek: (Long) -> Unit,
) {
    val progress = if (durationMs > 0) (elapsedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val seekLabel = stringResource(Res.string.chapter_seek, radioTimeline(elapsedMs))
    Column(Modifier.padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (index in 0..4) RadioText(radioTimeline(durationMs * index / 4), 10)
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

private const val SWITCH_TAB_FRACTION = 0.22f

// Unlit wire behind each digit, a material glyph rather than localized copy.
private const val CATHODE_GHOST = '8'

private const val FIXED_CAP_FONT_SCALE = 1.3f
