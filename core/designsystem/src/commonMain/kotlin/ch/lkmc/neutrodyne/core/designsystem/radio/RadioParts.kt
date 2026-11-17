// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.radio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * True inside a metal faceplate. Icon buttons placed there become ivory keys (08 Nd wrappers), so
 * every page header gets the radio's keys without each screen changing its actions.
 */
public val LocalRadioFaceplate: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

/**
 * The Back key a cabinet page offers its faceplate when the page names no navigation key of its
 * own; null outside a cabinet. Provided by the navigation host, which owns the back stack.
 */
public val LocalCabinetBackKey: ProvidableCompositionLocal<(@Composable () -> Unit)?> =
    staticCompositionLocalOf { null }

/**
 * A navy enamel plaque with brass rivets and cream typewriter lettering: the radio's playlist
 * sign, used as page titles. It is decoration only; a caller that makes it interactive owns the
 * click target and its 48 dp minimum.
 */
@Composable
public fun EnamelSign(
    text: String,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(4.dp)
    Row(
        modifier
            .shadow(3.dp, shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF2B3A4A), Color(0xFF16212D))), shape)
            .border(2.dp, Brush.verticalGradient(listOf(Color(0xFFE2C98F), Brass, Color(0xFF6E5530))), shape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        SignRivet()
        Text(
            text,
            Modifier.weight(1f, fill = false),
            color = Cream,
            fontFamily = courier(),
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            lineHeight = 22.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        SignRivet()
    }
}

@Composable
private fun SignRivet() {
    Canvas(Modifier.size(7.dp)) {
        drawCircle(Brush.radialGradient(listOf(Color(0xFFF6E7C1), Color(0xFF8A6D3E)), radius = size.minDimension))
        drawLine(
            Color(0xFF5A4424),
            Offset(size.width * 0.2f, size.height * 0.8f),
            Offset(size.width * 0.8f, size.height * 0.2f),
            1.dp.toPx(),
        )
    }
}

/**
 * The cap of a round ivory push key, like the home's Menu key: concave ivory in a brass ring with
 * an ink symbol scaled with [size]. The caller supplies the click target (at least 48 dp) and the
 * accessible label.
 */
@Composable
public fun IvoryKeyCap(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = IVORY_KEY_SIZE,
) {
    Box(
        modifier
            .size(size)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .shadow(2.dp, CircleShape)
            .background(Brush.radialGradient(listOf(Color(0xFFF6EBD0), Color(0xFFD4BC90))), CircleShape)
            .border(1.5.dp, Brass, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(size * ICON_FRACTION), tint = Ink)
    }
}

private val IVORY_KEY_SIZE = 36.dp
private const val DISABLED_ALPHA = 0.45f
private const val ICON_FRACTION = 20f / 36f
