// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.radio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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
    PianoKeySlot(modifier) {
        options.forEach { option ->
            PianoKey(
                label(option),
                pressed = option == selected,
                onClick = { onSelect(option) },
                modifier = Modifier.weight(1f).let { m -> tag(option)?.let { m.testTag(it) } ?: m },
                role = Role.Tab,
            )
        }
    }
}

/**
 * The metal slot that holds a bank of [PianoKey]s side by side. Keys that should share the width
 * take `Modifier.weight(1f)`; a bank of latching keys whose labels vary in length can pass a
 * `horizontalScroll` [modifier] instead so long translations stay whole.
 */
@Composable
public fun PianoKeySlot(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    MaterialPlate(RadioMaterial.METAL, modifier.slotFrame()) {
        Row(
            Modifier.padding(SlotPadding).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(KEY_GAP),
            content = content,
        )
    }
}

/**
 * One piano key with its lamp: raised, or [pressed] in with the lamp lit. [role] is [Role.Tab] for
 * exclusive banks and [Role.Checkbox] for latching keys, which expose `selected`; [Role.Button]
 * makes a momentary key that never latches. A short printed [label] can be read as a fuller
 * [spokenLabel] that contains it (WCAG 2.5.3).
 * A disabled key is dimmed and ignores clicks. At least 48 dp tall.
 */
@Composable
public fun PianoKey(
    label: String,
    pressed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    role: Role = Role.Tab,
    enabled: Boolean = true,
    spokenLabel: String = label,
) {
    Column(
        modifier
            // Content-sized unless the bank fixes the width (weights): a lone key never
            // stretches across its slot.
            .width(IntrinsicSize.Max)
            .heightIn(min = 48.dp)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .let {
                // A momentary key (Clear, a sort key) is a plain button; it never latches.
                if (role == Role.Button) {
                    it.clickable(enabled = enabled, role = role, onClick = onClick)
                } else {
                    it.selectable(pressed, enabled = enabled, role = role, onClick = onClick)
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Momentary keys never latch, so they carry no lamp; the empty socket keeps every cap in
        // a bank at the same height.
        if (role != Role.Button) Lamp(pressed) else Spacer(Modifier.height(LAMP_SIZE + LAMP_PADDING))
        KeyCap(label, spokenLabel, pressed)
    }
}

/**
 * The metal slot for a bank of [ActionKey]s, which share its width equally. When a key's longest
 * word would not fit its share (large text, a narrow window), the bank wraps into rows of half as
 * many keys, down to one per row, so no label is cut short (08: every action stays whole at 200 %
 * text). The keys in a row take its tallest key's height.
 */
@Composable
public fun ActionKeySlot(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    MaterialPlate(RadioMaterial.METAL, modifier.slotFrame()) {
        Layout(content, Modifier.padding(SlotPadding)) { measurables, constraints ->
            if (measurables.isEmpty()) return@Layout layout(0, 0) {}
            val gap = KEY_GAP.roundToPx()
            val minWidths = measurables.map { it.minIntrinsicWidth(Constraints.Infinity) }
            val width =
                if (constraints.hasBoundedWidth) {
                    constraints.maxWidth
                } else {
                    minWidths.sum() + gap * (measurables.size - 1)
                }

            fun share(keys: Int) = (width - gap * (keys - 1)) / keys

            var perRow = measurables.size
            while (perRow > 1 && minWidths.any { it > share(perRow) }) perRow = (perRow + 1) / 2

            val rows =
                measurables.chunked(perRow).map { row ->
                    val keyWidth = share(row.size).coerceAtLeast(0)
                    val height = row.maxOf { it.maxIntrinsicHeight(keyWidth) }
                    row.map { it.measure(Constraints(keyWidth, keyWidth, height, Constraints.Infinity)) }
                }
            val heights = rows.map { row -> row.maxOf { it.height } }
            layout(width, heights.sum() + gap * (rows.size - 1)) {
                var y = 0
                rows.forEachIndexed { index, row ->
                    var x = 0
                    row.forEach { key ->
                        key.placeRelative(x, y)
                        x += key.width + gap
                    }
                    y += heights[index] + gap
                }
            }
        }
    }
}

/**
 * A tall momentary key for a bank of actions, like a tape deck's transport keys: a symbol over
 * its printed label on raised ivory. Read as a button named [label]; [enabled] dims it. Put
 * several in an [ActionKeySlot]. The [icon] slot draws in ink.
 */
@Composable
public fun ActionKey(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: @Composable () -> Unit,
) {
    Column(
        modifier
            .heightIn(min = ACTION_KEY_HEIGHT)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .shadow(3.dp, KeyShape)
            .clip(KeyShape)
            .background(Brush.verticalGradient(RaisedIvory))
            .border(1.dp, Color(0xFF9A7E4E), KeyShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        CompositionLocalProvider(LocalContentColor provides Ink) {
            Box(Modifier.size(ACTION_ICON_SIZE), contentAlignment = Alignment.Center) { icon() }
        }
        Text(
            label.uppercase(),
            Modifier.clearAndSetSemantics { this.text = AnnotatedString(label) },
            color = Ink,
            fontFamily = barlow(),
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            letterSpacing = 1.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Lamp(lit: Boolean) {
    Canvas(Modifier.padding(top = 1.dp, bottom = LAMP_PADDING - 1.dp).size(LAMP_SIZE)) {
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
private fun KeyCap(
    text: String,
    spokenText: String,
    pressed: Boolean,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = KEY_HEIGHT)
            .widthIn(min = KEY_MIN_WIDTH)
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
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Engraved in capitals, read as written: screen readers spell some all-caps words.
        Text(
            text.uppercase(),
            Modifier.clearAndSetSemantics { this.text = AnnotatedString(spokenText) },
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
private val RaisedIvory = listOf(Color(0xFFF8EED6), Color(0xFFE3CFA5))
private val PressedIvory = listOf(Color(0xFFCDB27E), Color(0xFFDCC69A))
private val KEY_GAP = 6.dp
private val SlotPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
private val KEY_HEIGHT = 36.dp
private val LAMP_SIZE = 9.dp
private val LAMP_PADDING = 5.dp
private val KEY_MIN_WIDTH = 64.dp
private val ACTION_KEY_HEIGHT = 64.dp
private val ACTION_ICON_SIZE = 28.dp
private const val DISABLED_ALPHA = 0.5f

/** The slot's metal edge: a brass rim with a shadow below. */
private fun Modifier.slotFrame(): Modifier =
    shadow(3.dp, SlotShape)
        .clip(SlotShape)
        .border(1.dp, Brass.copy(alpha = 0.8f), SlotShape)

/** The slot's shadow falls on the top of a pressed key. */
private fun DrawScope.drawPressShade() {
    drawRect(Brush.verticalGradient(listOf(Color(0x66000000), Color.Transparent), endY = 6.dp.toPx()))
}
