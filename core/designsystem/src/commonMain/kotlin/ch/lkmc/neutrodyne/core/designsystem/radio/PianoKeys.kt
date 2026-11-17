// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.radio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A bank of piano keys for two to four exclusive choices, like a radio's band selector: ivory
 * keys in a metal slot, the chosen key pressed in with an amber lamp lit above it. Each key is a
 * tab (selectable, 48 dp tall) in one selectable group; [tag] names a key for tests.
 */
@Composable
public fun <T> PianoKeys(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    tag: (T) -> String? = { null },
) {
    MaterialPlate(
        RadioMaterial.METAL,
        modifier
            .shadow(3.dp, SlotShape)
            .clip(SlotShape)
            .border(1.dp, Brass.copy(alpha = 0.8f), SlotShape),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(KEY_GAP),
        ) {
            options.forEach { option ->
                val chosen = option == selected
                Column(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .let { m -> tag(option)?.let { m.testTag(it) } ?: m }
                        .selectable(chosen, role = Role.Tab, onClick = { onSelect(option) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Lamp(chosen)
                    PianoKey(label(option), chosen)
                }
            }
        }
    }
}

@Composable
private fun Lamp(lit: Boolean) {
    Canvas(Modifier.padding(top = 1.dp, bottom = 4.dp).size(LAMP_SIZE)) {
        val r = size.minDimension / 2
        if (lit) {
            // The glow spills past the lamp's bezel onto the metal.
            drawCircle(
                Brush.radialGradient(listOf(Amber.copy(alpha = 0.55f), Color.Transparent), center, r * 2.6f),
                r * 2.6f,
            )
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF4C8), Amber, Color(0xFFB8661A)), center, r), r)
        } else {
            drawCircle(Brush.radialGradient(listOf(Color(0xFF6A5638), UnlitLamp), center, r), r)
        }
        drawCircle(Color(0xFF2A2116), r, style = Stroke(1.dp.toPx()))
    }
}

@Composable
private fun PianoKey(
    text: String,
    pressed: Boolean,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = KEY_HEIGHT)
            // A pressed key sits lower in the slot and loses most of its shadow.
            .offset(y = if (pressed) 1.5.dp else 0.dp)
            .shadow(if (pressed) 0.5.dp else 3.dp, KeyShape)
            .background(Brush.verticalGradient(if (pressed) PressedIvory else RaisedIvory), KeyShape)
            .let {
                if (pressed) {
                    it.drawWithContent {
                        drawContent()
                        drawPressShade()
                    }
                } else {
                    it
                }
            }.border(1.dp, if (pressed) Color(0xFF5E4526) else Color(0xFF9A7E4E), KeyShape)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Engraved in capitals, read as written: screen readers spell some all-caps words.
        Text(
            text.uppercase(),
            Modifier.clearAndSetSemantics { this.text = AnnotatedString(text) },
            color = Ink,
            fontFamily = barlow(),
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            lineHeight = 16.sp,
            letterSpacing = 1.5.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val SlotShape = RoundedCornerShape(6.dp)
private val KeyShape = RoundedCornerShape(3.dp)
private val Amber = Color(0xFFF3B35E)
private val UnlitLamp = Color(0xFF4F4130)
private val LitLamp = Brush.radialGradient(listOf(Color(0xFFFFF0B8), Amber, Color(0xFFB8661A)))
private val RaisedIvory = listOf(Color(0xFFF8EED6), Color(0xFFE3CFA5))
private val PressedIvory = listOf(Color(0xFFCDB27E), Color(0xFFDCC69A))
private val KEY_GAP = 6.dp
private val KEY_HEIGHT = 36.dp
private val LAMP_SIZE = 9.dp

/** The slot's shadow falls on the top of a pressed key. */
private fun DrawScope.drawPressShade() {
    drawRect(Brush.verticalGradient(listOf(Color(0x66000000), Color.Transparent), endY = 6.dp.toPx()))
}
