// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.radio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.resources.Res
import ch.lkmc.neutrodyne.core.designsystem.resources.radio_faceplate
import ch.lkmc.neutrodyne.core.designsystem.resources.radio_faceplate_tall
import ch.lkmc.neutrodyne.core.designsystem.resources.radio_paper_card
import ch.lkmc.neutrodyne.core.designsystem.resources.radio_paper_card_tall
import org.jetbrains.compose.resources.imageResource
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/*
 * The radio's framed panels, drawn from parts recreated from the owner's favourite mockup renders
 * (2026-11-17). Each stretches like a nine-patch, so its screws, corners and bezel keep their size
 * whatever the panel's; contents stay clear of the corners, where the screws sit.
 */

/** A dark, finely pebbled metal faceplate in a thin brass edge, with a slotted screw in each corner. */
@Composable
public fun Faceplate(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier.ninePatch(
            imageResource(Res.drawable.radio_faceplate),
            imageResource(Res.drawable.radio_faceplate_tall),
            FACEPLATE_SRC_INSET,
            FACEPLATE_INSET,
        ),
        content = content,
    )
}

/** An aged paper card in a thin brass edge, with a small screw in each corner. */
@Composable
public fun PaperCard(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier.ninePatch(
            imageResource(Res.drawable.radio_paper_card),
            imageResource(Res.drawable.radio_paper_card_tall),
            PAPER_SRC_INSET,
            PAPER_INSET,
        ),
        content = content,
    )
}

/**
 * Frames a dial in [window], the cream face under glass in its polished brass bezel; the node's
 * contents draw on the face, inside the bezel. The glass and its sheen are part of the picture.
 */
internal fun Modifier.dialWindow(window: ImageBitmap): Modifier =
    ninePatch(window, DIAL_SRC_INSET, DIAL_INSET).padding(DIAL_BEZEL).clip(DialFace)

// Source insets cover each picture's corner details (pixels of the stored picture); the drawn
// insets set their size: screws of about 6 dp, a bezel of about 4.5 dp.
private const val FACEPLATE_SRC_INSET = 60
private val FACEPLATE_INSET = 11.dp
private const val PAPER_SRC_INSET = 48
private val PAPER_INSET = 12.dp
private const val DIAL_SRC_INSET = 48
private val DIAL_INSET = 8.dp
private val DIAL_BEZEL = 4.5.dp
private val DialFace = RoundedCornerShape(3.dp)

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
): Modifier = drawBehind { drawNinePatch(image, srcInset, inset.toPx()) }

/**
 * [ninePatch] for a panel rendered twice, [wide] and [tall], with the same corner details at the
 * same scale: each draw uses the picture whose proportions are nearer the node's, so its middle
 * stretches least and the texture keeps its grain.
 */
private fun Modifier.ninePatch(
    wide: ImageBitmap,
    tall: ImageBitmap,
    srcInset: Int,
    inset: Dp,
): Modifier =
    drawBehind {
        if (size.width <= 0f || size.height <= 0f) return@drawBehind
        drawNinePatch(if (stretch(wide) <= stretch(tall)) wide else tall, srcInset, inset.toPx())
    }

/** How far [image]'s proportions are from the node's, as the size of the log of their ratio. */
private fun DrawScope.stretch(image: ImageBitmap): Float =
    abs(ln((size.height / size.width) / (image.height.toFloat() / image.width)))

private fun DrawScope.drawNinePatch(
    image: ImageBitmap,
    srcInset: Int,
    edge: Float,
) {
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
