// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.radio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/*
 * The radio's finish: how its parts catch the light. Light falls from the top left, as on the
 * home radio, so raised parts are lit on their top and left edges and shaded on the bottom and
 * right, and openings throw shadow down onto whatever sits inside them.
 */

/** A raised part's edges: a lit top-left rim and a shaded bottom-right rim, inside [shape]. */
public fun Modifier.bevel(
    shape: Shape,
    depth: Dp = 1.5.dp,
    light: Color = Color(0x73FFF8E6),
    shade: Color = Color(0x8C1A1006),
): Modifier =
    innerShadow(shape, Shadow(radius = depth * 1.5f, color = light, offset = DpOffset(depth, depth)))
        .innerShadow(shape, Shadow(radius = depth * 2f, color = shade, offset = DpOffset(-depth, -depth)))

/** A turned brass rim around [shape]: bright where it faces the light, dark where it turns away. */
public fun Modifier.brassRim(
    shape: Shape,
    width: Dp = 2.dp,
): Modifier =
    drawWithContent {
        drawContent()
        val outline = shape.createOutline(size, layoutDirection, this)
        val stroke = width.toPx()
        drawOutline(
            outline,
            Brush.linearGradient(
                listOf(Color(0xFFF4DDA2), Color(0xFFB8955A), Color(0xFF6B4F27), Color(0xFFC9A869)),
                Offset.Zero,
                Offset(size.width, size.height),
            ),
            style = Stroke(stroke),
        )
        drawOutline(outline, Color(0x80271A0A), style = Stroke(0.75.dp.toPx()))
    }

/** A domed rivet head of [radius] at [center]. */
public fun DrawScope.drawRivet(
    center: Offset,
    radius: Float,
) {
    drawCircle(Color(0x59000000), radius, center + Offset(radius * 0.18f, radius * 0.28f))
    drawCircle(
        Brush.radialGradient(
            listOf(Color(0xFFFFF0C8), Color(0xFFC29D5C), Color(0xFF5E4420)),
            center - Offset(radius * 0.35f, radius * 0.4f),
            radius * 1.5f,
        ),
        radius,
        center,
    )
}

/**
 * Draws a rendered part stretched to the node like a nine-patch: the [srcInset] pixels at each
 * edge of [image] keep their proportions and become [inset] at each edge of the node (so screws
 * and rounded corners never distort); the edges stretch along their length and the middle both
 * ways.
 */
public fun Modifier.ninePatch(
    image: ImageBitmap,
    srcInset: Int,
    inset: Dp,
): Modifier =
    drawBehind {
        val edge = inset.toPx()
        drawSlices(
            image,
            intArrayOf(0, srcInset, image.width - srcInset, image.width),
            intArrayOf(0, srcInset, image.height - srcInset, image.height),
            floatArrayOf(0f, edge, size.width - edge, size.width),
            floatArrayOf(0f, edge, size.height - edge, size.height),
        )
    }

/**
 * Draws a rendered part scaled to the node's height, stretching only its middle sideways: the
 * [srcCap] pixels at its left and right ends keep their proportions (a key's rounded ends).
 */
public fun Modifier.threePatch(
    image: ImageBitmap,
    srcCap: Int,
): Modifier =
    drawBehind {
        val cap = srcCap * size.height / image.height
        drawSlices(
            image,
            intArrayOf(0, srcCap, image.width - srcCap, image.width),
            intArrayOf(0, image.height),
            floatArrayOf(0f, cap, size.width - cap, size.width),
            floatArrayOf(0f, size.height),
        )
    }

private fun DrawScope.drawSlices(
    image: ImageBitmap,
    srcX: IntArray,
    srcY: IntArray,
    dstX: FloatArray,
    dstY: FloatArray,
) {
    for (row in 0 until srcY.size - 1) {
        for (column in 0 until srcX.size - 1) {
            val srcWidth = srcX[column + 1] - srcX[column]
            val srcHeight = srcY[row + 1] - srcY[row]
            val left = dstX[column].roundToInt()
            val top = dstY[row].roundToInt()
            val width = dstX[column + 1].roundToInt() - left
            val height = dstY[row + 1].roundToInt() - top
            if (srcWidth <= 0 || srcHeight <= 0 || width <= 0 || height <= 0) continue
            drawImage(
                image,
                IntOffset(srcX[column], srcY[row]),
                IntSize(srcWidth, srcHeight),
                IntOffset(left, top),
                IntSize(width, height),
                filterQuality = FilterQuality.High,
            )
        }
    }
}
