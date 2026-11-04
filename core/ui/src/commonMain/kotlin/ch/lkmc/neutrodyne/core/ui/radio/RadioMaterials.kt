// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.radio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.barlow_bold
import ch.lkmc.neutrodyne.core.ui.resources.barlow_medium
import ch.lkmc.neutrodyne.core.ui.resources.barlow_regular
import ch.lkmc.neutrodyne.core.ui.resources.barlow_semibold
import ch.lkmc.neutrodyne.core.ui.resources.courierprime_bold
import ch.lkmc.neutrodyne.core.ui.resources.courierprime_regular
import ch.lkmc.neutrodyne.core.ui.resources.dosis_light
import ch.lkmc.neutrodyne.core.ui.resources.radio_metal
import ch.lkmc.neutrodyne.core.ui.resources.radio_paper
import ch.lkmc.neutrodyne.core.ui.resources.radio_wood
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.imageResource
import org.jetbrains.compose.resources.painterResource

internal val Ink = Color(0xFF4B3521)
internal val Cream = Color(0xFFEAD7B5)
internal val Brass = Color(0xFFA98C58)
internal val Chassis = Color(0xFF302F26)

@Composable
internal fun barlow(): FontFamily =
    FontFamily(
        Font(Res.font.barlow_regular, FontWeight.Normal),
        Font(Res.font.barlow_medium, FontWeight.Medium),
        Font(Res.font.barlow_semibold, FontWeight.SemiBold),
        Font(Res.font.barlow_bold, FontWeight.Bold),
    )

@Composable
internal fun courier(): FontFamily =
    FontFamily(
        Font(Res.font.courierprime_regular, FontWeight.Normal),
        Font(Res.font.courierprime_bold, FontWeight.Bold),
    )

@Composable
internal fun dosis(): FontFamily = FontFamily(Font(Res.font.dosis_light, FontWeight.Light))

internal enum class RadioMaterial { WOOD, WOOD_EDGE, PAPER, METAL }

/** Physical materials deliberately do not consume MaterialTheme or dynamic colours. */
@Composable
internal fun MaterialPlate(
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
                val tileWidth = if (material == RadioMaterial.PAPER) 256.dp else 160.dp
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
                            listOf(Color(0x1F695123), Color.Transparent, Color(0x2EFFF2D0))
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

/** Shadow falls inside a routed opening, over the contents beneath its wooden lip. */
internal fun Modifier.recess(shape: Shape = RoundedCornerShape(3.dp)): Modifier =
    clip(shape).border(1.dp, Brass, shape).drawWithContent {
        drawContent()
        drawRect(Brush.verticalGradient(listOf(Color(0xB31C1006), Color.Transparent), endY = 10.dp.toPx()))
        drawRect(Brush.horizontalGradient(listOf(Color(0x6629180C), Color.Transparent), endX = 5.dp.toPx()))
    }

/** A reflection is drawn over the recessed scale and digits, under their retaining lip. */
internal fun Modifier.radioGlass(): Modifier =
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

internal val DialOpening: Shape =
    GenericShape { size, _ ->
        val lip = size.height * 0.1f
        moveTo(0f, lip)
        quadraticBezierTo(size.width / 2, -lip, size.width, lip)
        lineTo(size.width, size.height)
        lineTo(0f, size.height)
        close()
    }

internal fun Modifier.concaveIvory(): Modifier =
    drawWithContent {
        drawCircle(
            Brush.radialGradient(
                listOf(Color(0xFFFFF5D9), Color(0xFFEFE1BF), Color(0xFFB69E76)),
                center = Offset(size.width * 0.61f, size.height * 0.77f),
                radius = size.width * 0.7f,
            ),
            radius = size.width / 2 - 5.dp.toPx(),
        )
        drawContent()
    }

internal fun Modifier.dialPaper(): Modifier =
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

internal fun Modifier.radioStage(): Modifier =
    drawBehind {
        drawRect(
            Brush.radialGradient(
                listOf(Color(0xFF494944), Color(0xFF2F332F), Color(0xFF272B27)),
                center = Offset(size.width * 0.46f, size.height * 0.22f),
                radius = maxOf(size.width, size.height) * 0.85f,
            ),
        )
    }
