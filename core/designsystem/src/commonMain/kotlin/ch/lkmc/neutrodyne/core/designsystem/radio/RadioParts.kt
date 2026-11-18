// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.radio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.resources.Res
import ch.lkmc.neutrodyne.core.designsystem.resources.radio_enamel_sign
import ch.lkmc.neutrodyne.core.designsystem.resources.radio_key_round_up
import org.jetbrains.compose.resources.imageResource

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
    EnamelPlaque(modifier) { SignText(text, Modifier.weight(1f, fill = false)) }
}

/**
 * An enamel sign that opens a choice, like the radio's playlist sign: the plaque with a cream
 * chevron, read as a drop-down list named [text]. The caller anchors its menu to it.
 */
@Composable
public fun EnamelSelector(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clickable(enabled = enabled, role = Role.DropdownList, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        EnamelPlaque(Modifier, small = true) {
            SignText(text, Modifier.weight(1f, fill = false), small = true)
            Icon(NdIcons.ArrowForwardIos, null, Modifier.size(11.dp).rotate(90f), tint = Cream)
        }
    }
}

@Composable
private fun EnamelPlaque(
    modifier: Modifier = Modifier,
    small: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    // The rendered sign (owner-approved renders, 2026-11-17) scales its rounded, riveted ends with
    // its height, so the lettering keeps clear of the rivets by a share of that height.
    val lineHeight = with(LocalDensity.current) { (if (small) SMALL_SIGN_LINE else SIGN_LINE).toDp() }
    val vertical = if (small) 6.dp else 8.dp
    Row(
        modifier
            .dropShadow(SignShape, Shadow(radius = 5.dp, color = Color(0xB3000000), offset = DpOffset(0.dp, 2.dp)))
            .threePatch(imageResource(Res.drawable.radio_enamel_sign), SIGN_SRC_CAP)
            .padding(horizontal = (lineHeight + vertical * 2) * SIGN_RIVET_CLEARANCE, vertical = vertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (small) 5.dp else 7.dp),
        content = content,
    )
}

@Composable
private fun SignText(
    text: String,
    modifier: Modifier = Modifier,
    small: Boolean = false,
) {
    val size = if (small) SMALL_SIGN_SIZE else SIGN_SIZE
    Text(
        text,
        modifier,
        color = Cream,
        // A sign in a narrow pane letters its name smaller before it cuts it short.
        autoSize = SignLettering(min = size * SIGN_MIN_SCALE, max = size),
        fontFamily = courier(),
        fontWeight = FontWeight.Bold,
        lineHeight = if (small) SMALL_SIGN_LINE else SIGN_LINE,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Steps the lettering down from [max] until the whole name shows, and cuts it short only at
 * [min]. `TextAutoSize.StepBased` counts a name cut short with an ellipsis as fitting, since the
 * ellipsis keeps the line within its width, so it would never letter a sign smaller.
 */
private data class SignLettering(
    val min: TextUnit,
    val max: TextUnit,
) : TextAutoSize {
    override fun TextAutoSizeLayoutScope.getFontSize(
        constraints: Constraints,
        text: AnnotatedString,
    ): TextUnit {
        var size = max.value
        while (size > min.value) {
            val layout = performLayout(constraints, text, size.sp)
            if (layout.getLineEnd(0, visibleEnd = true) >= text.length) return size.sp
            size -= SIGN_SIZE_STEP
        }
        return min
    }
}

/**
 * A brass disc stamped with a count, like the radio's screw heads: the unplayed count on covers
 * and drawers. Decorative; the owner reads the count in its own description.
 */
@Composable
public fun BrassCountBadge(
    count: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .heightIn(min = BADGE_SIZE)
            .widthIn(min = BADGE_SIZE)
            .shadow(2.dp, CircleShape)
            .background(Brush.radialGradient(listOf(Color(0xFFF6E2A8), Brass, Color(0xFF8A6D3E))), CircleShape)
            .border(1.dp, Color(0xFF6E5530), CircleShape)
            .padding(horizontal = 4.dp)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > MAX_BADGE_COUNT) "$MAX_BADGE_COUNT+" else count.toString(),
            color = Ink,
            fontFamily = courier(),
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            lineHeight = 13.sp,
            maxLines = 1,
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
    Box(modifier.size(size).alpha(if (enabled) 1f else DISABLED_ALPHA), contentAlignment = Alignment.Center) {
        KeyPicture()
        // Engraved: the cut's lower wall catches the light just below the ink.
        Icon(icon, null, Modifier.size(size * ICON_FRACTION).offset(y = 0.75.dp), tint = Color(0xB3FFFBEF))
        Icon(icon, contentDescription, Modifier.size(size * ICON_FRACTION), tint = Ink)
    }
}

/**
 * A round ivory key engraved with a short [legend] (such as "30"), under an [icon] when given (an
 * arrow, as on the owner's favourite "Back" key of the mockups, 2026-11-17). The legend keeps its
 * proportion to the cap whatever the text size, so the caller scales [size]; like the symbol cap,
 * it is decorative and the caller supplies the target and label.
 */
@Composable
public fun IvoryKeyCap(
    legend: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = IVORY_KEY_SIZE,
    icon: ImageVector? = null,
) {
    val fontSize =
        with(LocalDensity.current) {
            (
                size *
                    if (icon ==
                        null
                    ) {
                        LEGEND_FRACTION
                    } else {
                        STACKED_LEGEND_FRACTION
                    }
            ).toSp()
        }
    Box(
        modifier.size(size).alpha(if (enabled) 1f else DISABLED_ALPHA).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        KeyPicture()
        // Engraved: the cut's lower wall catches the light just below the ink.
        for ((color, drop) in listOf(Color(0xB3FFFBEF) to 0.75.dp, Ink to 0.dp)) {
            Column(Modifier.offset(y = drop), horizontalAlignment = Alignment.CenterHorizontally) {
                if (icon != null) {
                    Icon(icon, null, Modifier.size(size * ARROW_FRACTION).offset(y = size * ARROW_LIFT), tint = color)
                }
                Text(
                    legend,
                    color = color,
                    fontFamily = slab(),
                    fontSize = fontSize,
                    lineHeight = fontSize,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/** The rendered key (owner-approved renders, 2026-11-17): an ivory cap in a turned brass ring. */
@Composable
private fun BoxScope.KeyPicture() {
    Image(
        imageResource(Res.drawable.radio_key_round_up),
        null,
        Modifier.matchParentSize(),
        filterQuality = FilterQuality.High,
    )
}

private val IVORY_KEY_SIZE = 36.dp
private const val DISABLED_ALPHA = 0.45f
private const val MAX_BADGE_COUNT = 99
private val BADGE_SIZE = 24.dp
private const val ICON_FRACTION = 20f / 36f
private const val LEGEND_FRACTION = 0.28f
private const val STACKED_LEGEND_FRACTION = 0.25f
private const val ARROW_FRACTION = 0.34f

// The arrow glyph has blank room below it in its box; it drops toward the legend to close the gap.
private const val ARROW_LIFT = 0.04f
private val SIGN_LINE = 22.sp
private val SMALL_SIGN_LINE = 17.sp
private val SIGN_SIZE = 18.sp
private val SMALL_SIGN_SIZE = 14.sp

// Smaller lettering than this stops reading as the sign's; the name is cut short instead.
private const val SIGN_MIN_SCALE = 0.75f
private const val SIGN_SIZE_STEP = 0.5f
private val SignShape = RoundedCornerShape(percent = 50)

// The sign picture's rounded end spans its first 85 px; its rivet ends 0.41 of the height in,
// and the lettering starts a little clear of it.
private const val SIGN_SRC_CAP = 85
private const val SIGN_RIVET_CLEARANCE = 0.6f
