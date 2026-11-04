// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.radio

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import ch.lkmc.neutrodyne.core.ui.resources.dosis_variable
import ch.lkmc.neutrodyne.core.ui.resources.radio_metal
import ch.lkmc.neutrodyne.core.ui.resources.radio_paper
import ch.lkmc.neutrodyne.core.ui.resources.radio_wood
import org.jetbrains.compose.resources.Font
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
internal fun dosis(): FontFamily = FontFamily(Font(Res.font.dosis_variable, FontWeight.Light))

internal enum class RadioMaterial { WOOD, PAPER, METAL }

/** Physical materials deliberately do not consume MaterialTheme or dynamic colours. */
@Composable
internal fun MaterialPlate(
    material: RadioMaterial,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val texture =
        when (material) {
            RadioMaterial.WOOD -> Res.drawable.radio_wood
            RadioMaterial.PAPER -> Res.drawable.radio_paper
            RadioMaterial.METAL -> Res.drawable.radio_metal
        }
    Box(modifier) {
        Image(painterResource(texture), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        Box(
            Modifier.matchParentSize().background(
                Brush.linearGradient(
                    listOf(Color(0x20FFE3AC), Color.Transparent, Color(0x40301507)),
                    end = Offset(900f, 1200f),
                ),
            ),
        )
        content()
    }
}

/** Shadow falls inside a routed opening, over the contents beneath its wooden lip. */
internal fun Modifier.recess(): Modifier =
    clip(RoundedCornerShape(3.dp)).border(1.dp, Brass, RoundedCornerShape(3.dp)).drawWithContent {
        drawContent()
        drawRect(Brush.verticalGradient(listOf(Color(0xB31C1006), Color.Transparent), endY = 10.dp.toPx()))
        drawRect(Brush.horizontalGradient(listOf(Color(0x6629180C), Color.Transparent), endX = 5.dp.toPx()))
    }
