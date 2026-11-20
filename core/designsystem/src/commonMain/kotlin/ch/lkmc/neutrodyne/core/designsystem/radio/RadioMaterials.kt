// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.radio

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.resources.Res
import ch.lkmc.neutrodyne.core.designsystem.resources.arvo_bold
import ch.lkmc.neutrodyne.core.designsystem.resources.barlow_bold
import ch.lkmc.neutrodyne.core.designsystem.resources.barlow_medium
import ch.lkmc.neutrodyne.core.designsystem.resources.barlow_regular
import ch.lkmc.neutrodyne.core.designsystem.resources.barlow_semibold
import ch.lkmc.neutrodyne.core.designsystem.resources.courierprime_bold
import ch.lkmc.neutrodyne.core.designsystem.resources.courierprime_regular
import ch.lkmc.neutrodyne.core.designsystem.resources.dosis_light
import ch.lkmc.neutrodyne.core.designsystem.resources.radio_metal
import ch.lkmc.neutrodyne.core.designsystem.resources.radio_paper
import ch.lkmc.neutrodyne.core.designsystem.resources.radio_valve_deck
import ch.lkmc.neutrodyne.core.designsystem.resources.radio_wood
import ch.lkmc.neutrodyne.core.designsystem.theme.LocalReducedMotion
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.imageResource
import org.jetbrains.compose.resources.painterResource
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Ink printed on the radio's paper and keys. */
public val Ink: Color = Color(0xFF4B3521)

/** Unprinted paper, ivory key caps and enamel lettering. */
public val Cream: Color = Color(0xFFEAD7B5)

/** Lips of routed openings, bezels and screws. */
public val Brass: Color = Color(0xFFA98C58)

/** The olive-black metal of faceplates and the valve deck. */
public val Chassis: Color = Color(0xFF302F26)

@Composable
public fun barlow(): FontFamily =
    FontFamily(
        Font(Res.font.barlow_regular, FontWeight.Normal),
        Font(Res.font.barlow_medium, FontWeight.Medium),
        Font(Res.font.barlow_semibold, FontWeight.SemiBold),
        Font(Res.font.barlow_bold, FontWeight.Bold),
    )

@Composable
public fun courier(): FontFamily =
    FontFamily(
        Font(Res.font.courierprime_regular, FontWeight.Normal),
        Font(Res.font.courierprime_bold, FontWeight.Bold),
    )

/** Heavy slab lettering printed on switch plates. */
@Composable
public fun slab(): FontFamily = FontFamily(Font(Res.font.arvo_bold, FontWeight.Bold))

@Composable
public fun dosis(): FontFamily = FontFamily(Font(Res.font.dosis_light, FontWeight.Light))

/** The physical surfaces of the radio; each has its own texture and light gradient. */
public enum class RadioMaterial { WOOD, WOOD_EDGE, PAPER, METAL }

/** Physical materials deliberately do not consume MaterialTheme or dynamic colours. */
@Composable
public fun MaterialPlate(
    material: RadioMaterial,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val texture =
        when (material) {
            RadioMaterial.WOOD, RadioMaterial.WOOD_EDGE -> Res.drawable.radio_wood
            RadioMaterial.PAPER -> Res.drawable.radio_paper
            RadioMaterial.METAL -> Res.drawable.radio_metal
        }
    Box(modifier) {
        if (material == RadioMaterial.WOOD || material == RadioMaterial.WOOD_EDGE) {
            Image(
                painterResource(texture),
                null,
                Modifier.matchParentSize(),
                contentScale =
                    if (material ==
                        RadioMaterial.WOOD_EDGE
                    ) {
                        ContentScale.Crop
                    } else {
                        ContentScale.FillBounds
                    },
            )
        } else {
            val bitmap = imageResource(texture)
            val brush = remember(bitmap) { ShaderBrush(ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated)) }
            Canvas(Modifier.matchParentSize()) {
                val tileWidth = if (material == RadioMaterial.PAPER) PAPER_TILE_WIDTH else 160.dp
                val scale = tileWidth.toPx() / bitmap.width
                withTransform({ scale(scale, scale, Offset.Zero) }) {
                    drawRect(brush, size = Size(size.width / scale, size.height / scale))
                }
            }
        }
        Box(
            Modifier.matchParentSize().background(
                Brush.linearGradient(
                    when (material) {
                        RadioMaterial.WOOD, RadioMaterial.WOOD_EDGE -> {
                            listOf(
                                Color(0x24291609),
                                Color(0x3840200D),
                                Color(0x55301407),
                            )
                        }

                        RadioMaterial.METAL -> {
                            listOf(Color(0x0FC7B58A), Color.Transparent, Color(0x260E100E))
                        }

                        RadioMaterial.PAPER -> {
                            PaperLight
                        }
                    },
                ),
            ),
        )
        content()
        if (material == RadioMaterial.METAL) {
            Canvas(Modifier.matchParentSize()) {
                for (x in listOf(4.dp.toPx(), size.width - 4.dp.toPx())) {
                    val center = Offset(x, 4.dp.toPx())
                    drawCircle(
                        Brush.radialGradient(listOf(Color(0xFFC1A16B), Color(0xFF4C371D)), center, 4.dp.toPx()),
                        3.dp.toPx(),
                        center,
                    )
                    drawLine(
                        Color(0xFF20160C),
                        center - Offset(2.dp.toPx(), -2.dp.toPx()),
                        center + Offset(2.dp.toPx(), -2.dp.toPx()),
                        1.dp.toPx(),
                    )
                }
            }
        }
    }
}

/** The radio's paper texture, for surfaces drawn with [paper]. */
@Composable
public fun paperTexture(): ImageBitmap = imageResource(Res.drawable.radio_paper)

/**
 * Draws the radio's paper ([texture], from [paperTexture]) behind a node, as [MaterialPlate] does
 * for [RadioMaterial.PAPER], for surfaces built from modifiers such as a list's slips. [tint]
 * washes over the paper, to mark a slip.
 */
public fun Modifier.paper(
    texture: ImageBitmap,
    tint: Color = Color.Transparent,
): Modifier =
    drawWithCache {
        val grain = ShaderBrush(ImageShader(texture, TileMode.Repeated, TileMode.Repeated))
        val scale = PAPER_TILE_WIDTH.toPx() / texture.width
        val light = Brush.linearGradient(PaperLight)
        onDrawBehind {
            withTransform({ scale(scale, scale, Offset.Zero) }) {
                drawRect(grain, size = Size(size.width / scale, size.height / scale))
            }
            drawRect(light)
            drawRect(tint)
        }
    }

private val PAPER_TILE_WIDTH = 256.dp
private val PaperLight = listOf(Color(0x1F695123), Color.Transparent, Color(0x2EFFF2D0))

/**
 * Light catches the cabinet's top edge and left wall; the right wall and base fall into shade. A
 * pane inside a wider cabinet passes false for a wall it does not have, so only the cabinet's
 * outer walls are lit or shaded.
 */
public fun Modifier.cabinetEdges(
    leftWall: Boolean = true,
    rightWall: Boolean = true,
): Modifier =
    drawWithContent {
        drawContent()
        // The walls turn away from the light at the sides; the shade stays on the wood and off the
        // panels, keys and dials inside.
        val wall = (CABINET_SHADE_WIDTH.toPx() / size.width).coerceAtMost(0.5f)
        drawRect(
            Brush.horizontalGradient(
                0f to if (leftWall) Color(0x88311507) else Color.Transparent,
                wall to Color.Transparent,
                1f - wall to Color.Transparent,
                1f to if (rightWall) Color(0x88230E06) else Color.Transparent,
            ),
        )
        drawLine(Color(0xBFE8B56E), Offset.Zero, Offset(size.width, 0f), 2.dp.toPx())
        drawLine(Color(0x992D1609), Offset(0f, size.height), Offset(size.width, size.height), 5.dp.toPx())
        if (leftWall) {
            drawLine(
                Color(0x88D39C58),
                Offset(5.dp.toPx(), 20.dp.toPx()),
                Offset(5.dp.toPx(), size.height),
                3.dp.toPx(),
            )
        }
        if (rightWall) {
            drawLine(
                Color(0xBB32170B),
                Offset(size.width - 5.dp.toPx(), 20.dp.toPx()),
                Offset(size.width - 5.dp.toPx(), size.height),
                5.dp.toPx(),
            )
        }
    }

private val CABINET_SHADE_WIDTH = 16.dp

/** Shadow falls inside a routed opening, over the contents beneath its wooden lip. */
public fun Modifier.recess(shape: Shape = RoundedCornerShape(3.dp)): Modifier =
    clip(shape).border(1.dp, Brass, shape).drawWithContent {
        drawContent()
        drawRect(Brush.verticalGradient(listOf(Color(0xB31C1006), Color.Transparent), endY = 10.dp.toPx()))
        drawRect(Brush.horizontalGradient(listOf(Color(0x6629180C), Color.Transparent), endX = 5.dp.toPx()))
    }

/** A reflection is drawn over the recessed scale and digits, under their retaining lip. */
public fun Modifier.radioGlass(): Modifier =
    drawWithContent {
        drawContent()
        val reflection =
            Path().apply {
                moveTo(size.width * 0.32f, 0f)
                lineTo(size.width * 0.43f, 0f)
                lineTo(size.width * 0.25f, size.height)
                lineTo(size.width * 0.14f, size.height)
                close()
            }
        drawPath(reflection, Color(0x16FFFEF3))
    }

public val DialOpening: Shape =
    GenericShape { size, _ ->
        val lip = size.height * 0.1f
        moveTo(0f, lip)
        quadraticBezierTo(size.width / 2, -lip, size.width, lip)
        lineTo(size.width, size.height)
        lineTo(0f, size.height)
        close()
    }

public fun Modifier.dialPaper(): Modifier =
    drawWithContent {
        drawRect(
            Brush.radialGradient(
                listOf(Color(0xFFFBE5A9), Color(0xFFE3CEA0), Color(0xFFBDA678)),
                center = Offset(size.width * 0.5f, size.height * 0.95f),
                radius = maxOf(size.width, size.height) * 0.65f,
            ),
        )
        drawContent()
    }

public fun Modifier.radioStage(): Modifier =
    drawBehind {
        drawRect(
            Brush.radialGradient(
                listOf(Color(0xFF494944), Color(0xFF2F332F), Color(0xFF272B27)),
                center = Offset(size.width * 0.46f, size.height * 0.22f),
                radius = maxOf(size.width, size.height) * 0.85f,
            ),
        )
    }

/** Width over height of the valve deck artwork (tubes, chassis and their shadow). */
public const val VALVE_DECK_ASPECT: Float = 1100f / 423f

/**
 * The radio's valve deck: two glowing tubes on a metal chassis, the lid across every cabinet
 * (owner decisions 2026-11-17).
 *
 * The deck fills the node's height. A node wider than the artwork gets a longer chassis rather
 * than larger tubes: the plain stretches of plate beside the outer terminals repeat, mirrored so
 * their lighting meets without seams, until the plate spans the node. A narrower node fits the
 * whole deck to its width, resting on its lower edge.
 *
 * While [lit] (the radio is ON) the filaments warm up: an amber glow fades in over about two
 * seconds and cools over one when the radio is switched off. Reduced motion switches it at once.
 * Unlit, the deck is the artwork as rendered.
 */
@Composable
public fun ValveDeck(
    modifier: Modifier = Modifier,
    lit: Boolean = false,
) {
    val art = imageResource(Res.drawable.radio_valve_deck)
    val reducedMotion = LocalReducedMotion.current
    val warmth = remember { Animatable(if (lit) 1f else 0f) }
    // Keyed on reduced motion too: switching it on mid-warm-up finishes the change at once, which
    // a spec change alone would not do to a tween in flight.
    LaunchedEffect(lit, reducedMotion) {
        warmth.animateTo(
            if (lit) 1f else 0f,
            when {
                reducedMotion -> snap()
                lit -> tween(VALVE_WARM_UP_MS, easing = LinearOutSlowInEasing)
                else -> tween(VALVE_COOL_DOWN_MS, easing = FastOutLinearInEasing)
            },
        )
    }
    Box(
        modifier.drawWithCache {
            val fit = deckFit(art, size)
            onDrawBehind {
                if (fit != null) drawValveDeck(art, fit)
            }
        },
    ) {
        // Isolate animation invalidations while drawing the translucent chassis onto its background.
        Spacer(
            Modifier.matchParentSize().graphicsLayer().drawWithCache {
                val glows = deckFit(art, size)?.let { filamentGlows(it) }.orEmpty()
                onDrawBehind {
                    val level = warmth.value
                    if (level > 0f) glows.forEach { it.draw(this, level) }
                }
            },
        )
    }
}

/** How the artwork sits in the node: its scale, the width the plain stretches grow by, its top. */
private class DeckFit(
    val scale: Float,
    val extra: Float,
    val top: Float,
)

private fun deckFit(
    art: ImageBitmap,
    size: Size,
): DeckFit? {
    if (size.width <= 0f || size.height <= 0f) return null
    // A node within half a pixel of the artwork's proportions (the home's full-width deck, sized
    // from VALVE_DECK_ASPECT) fits it whole rather than tiling a rounding error.
    val long = art.width * size.height / art.height < size.width - 0.5f
    val scale = if (long) size.height / art.height else size.width / art.width
    val extra = if (long) size.width - art.width * scale else 0f
    // A deck fitted to a narrow node rests on its lower edge, where the cabinet's roof is.
    return DeckFit(scale, extra, size.height - art.height * scale)
}

/** One filament's light: a bright core on the filament and a soft halo filling its glass. */
private class FilamentGlow(
    private val core: Brush,
    private val coreCentre: Offset,
    private val coreRadius: Float,
    private val halo: Brush,
    private val haloCentre: Offset,
    private val haloRadius: Float,
) {
    fun draw(
        scope: DrawScope,
        level: Float,
    ) {
        scope.drawCircle(halo, haloRadius, haloCentre, alpha = level, blendMode = BlendMode.Screen)
        scope.drawCircle(core, coreRadius, coreCentre, alpha = level, blendMode = BlendMode.Screen)
    }
}

private fun filamentGlows(fit: DeckFit): List<FilamentGlow> =
    FILAMENTS.map { filament ->
        // The tubes sit between the plain stretches, so a longer chassis moves them by half its
        // extra width, as drawValveDeck draws them.
        fun place(art: Offset) = Offset(art.x * fit.scale + fit.extra / 2f, fit.top + art.y * fit.scale)
        val coreCentre = place(filament)
        val haloCentre = place(filament + HALO_LIFT)
        val coreRadius = FILAMENT_CORE_RADIUS * fit.scale
        val haloRadius = FILAMENT_HALO_RADIUS * fit.scale
        FilamentGlow(
            Brush.radialGradient(listOf(FilamentCore, FilamentCore.copy(alpha = 0f)), coreCentre, coreRadius),
            coreCentre,
            coreRadius,
            Brush.radialGradient(listOf(FilamentHalo, FilamentHalo.copy(alpha = 0f)), haloCentre, haloRadius),
            haloCentre,
            haloRadius,
        )
    }

private fun DrawScope.drawValveDeck(
    art: ImageBitmap,
    fit: DeckFit,
) {
    val scale = fit.scale
    val extra = fit.extra
    val top = fit.top
    val height = art.height * scale
    // Source columns: the left end, a plain stretch, the tubes and circuit, a plain stretch, the
    // right end. Only the plain stretches grow, each by half of the extra width.
    val columns =
        intArrayOf(
            0,
            DECK_PLAIN_LEFT.first,
            DECK_PLAIN_LEFT.last,
            DECK_PLAIN_RIGHT.first,
            DECK_PLAIN_RIGHT.last,
            art.width,
        )
    var x = 0f
    for (segment in 0 until columns.size - 1) {
        val from = columns[segment]
        val to = columns[segment + 1]
        val natural = (to - from) * scale
        if ((segment == 1 || segment == 3) && extra > 0f) {
            drawMirroredTiles(art, from, to, x, top, natural + extra / 2f, height, natural)
            x += natural + extra / 2f
        } else {
            drawPart(art, from, to, x, top, natural, height, mirrored = false)
            x += natural
        }
    }
}

/** Fills [width] from [left] with copies of the [from]..[to] columns, every other one mirrored. */
private fun DrawScope.drawMirroredTiles(
    art: ImageBitmap,
    from: Int,
    to: Int,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    tile: Float,
) {
    // An odd count ends on an unmirrored copy, whose outer edge is the column the fixed artwork
    // beyond it continues from; ending on a mirrored copy would leave a seam there.
    val count = ceil(width / tile).toInt().coerceAtLeast(1).let { if (it % 2 == 0) it + 1 else it }
    val each = width / count
    for (index in 0 until count) {
        drawPart(art, from, to, left + index * each, top, each, height, mirrored = index % 2 == 1)
    }
}

private fun DrawScope.drawPart(
    art: ImageBitmap,
    from: Int,
    to: Int,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    mirrored: Boolean,
) {
    val dstLeft = left.roundToInt()
    val dstTop = top.roundToInt()
    val dstSize = IntSize((left + width).roundToInt() - dstLeft, (top + height).roundToInt() - dstTop)
    if (dstSize.width <= 0 || dstSize.height <= 0) return
    val center = Offset(dstLeft + dstSize.width / 2f, 0f)
    scale(if (mirrored) -1f else 1f, 1f, center) {
        drawImage(
            art,
            IntOffset(from, 0),
            IntSize(to - from, art.height),
            IntOffset(dstLeft, dstTop),
            dstSize,
            filterQuality = FilterQuality.High,
        )
    }
}

// Columns of plain chassis plate in the deck artwork, between each outer terminal and the plate's
// end: only plate texture and its front edge, so they repeat without showing.
private val DECK_PLAIN_LEFT = 86..100
private val DECK_PLAIN_RIGHT = 998..1010

// The two filaments in the deck artwork (pixels), where its rendered glow already sits, and the
// halo's centre above each, inside the glass.
private val FILAMENTS = listOf(Offset(270f, 214f), Offset(822f, 216f))
private val HALO_LIFT = Offset(0f, -40f)
private const val FILAMENT_CORE_RADIUS = 36f
private const val FILAMENT_HALO_RADIUS = 78f
private val FilamentCore = Color(0xF2FFB04A)
private val FilamentHalo = Color(0x73FF8A2A)
private const val VALVE_WARM_UP_MS = 1800
private const val VALVE_COOL_DOWN_MS = 900
