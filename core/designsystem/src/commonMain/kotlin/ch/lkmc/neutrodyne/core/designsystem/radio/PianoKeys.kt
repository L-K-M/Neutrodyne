// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.radio

import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
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
import ch.lkmc.neutrodyne.core.designsystem.resources.Res
import ch.lkmc.neutrodyne.core.designsystem.resources.radio_jewel_dark
import ch.lkmc.neutrodyne.core.designsystem.resources.radio_jewel_lit
import ch.lkmc.neutrodyne.core.designsystem.resources.radio_key_piano_down
import ch.lkmc.neutrodyne.core.designsystem.resources.radio_key_piano_up
import ch.lkmc.neutrodyne.core.designsystem.resources.radio_key_slot
import org.jetbrains.compose.resources.imageResource
import org.jetbrains.compose.resources.painterResource

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
    enabled: Boolean = true,
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
                enabled = enabled,
            )
        }
    }
}

/**
 * The metal slot that holds a bank of [PianoKey]s side by side. Keys that should share the width
 * take `Modifier.weight(1f)`; a bank of keys whose labels vary in length can pass a [scroll]
 * state instead, so long translations stay whole: the keys then slide sideways inside the slot's
 * opening while its frame stays put.
 */
@Composable
public fun PianoKeySlot(
    modifier: Modifier = Modifier,
    scroll: ScrollState? = null,
    content: @Composable RowScope.() -> Unit,
) {
    // The rendered riveted slot (owner-approved renders, 2026-11-17), stretched without
    // distorting its screwed corners.
    Box(modifier.ninePatch(imageResource(Res.drawable.radio_key_slot), SLOT_SRC_INSET, SLOT_INSET)) {
        val keys =
            if (scroll == null) {
                Modifier.padding(SlotPadding)
            } else {
                Modifier
                    .padding(horizontal = SLOT_OPENING_INSET)
                    .horizontalScroll(scroll)
                    .padding(ScrolledKeysPadding)
            }
        Row(
            keys.selectableGroup(),
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
 * A disabled key is dimmed and ignores clicks. At least 48 dp tall. A [destructive] key prints
 * its label in signal red (08 Dialogs: destructive confirms stand out).
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
    destructive: Boolean = false,
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
        KeyCap(label, spokenLabel, pressed, if (destructive) MaterialTheme.colorScheme.error else Ink)
    }
}

/** A bank of momentary piano keys in a slot that scrolls sideways when the labels outgrow it. */
@Composable
public fun KeyBank(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    PianoKeySlot(modifier, rememberScrollState(), content)
}

/** A momentary piano key: a button that springs back, without a lamp; see [PianoKey]. */
@Composable
public fun MomentaryKey(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    PianoKey(
        label,
        pressed = false,
        onClick = onClick,
        modifier = modifier,
        role = Role.Button,
        enabled = enabled,
        destructive = destructive,
    )
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
        Layout(content, Modifier.padding(ActionSlotPadding)) { measurables, constraints ->
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
            .threePatch(imageResource(Res.drawable.radio_key_piano_up), KEY_SRC_CAP)
            .clip(KeyShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = RAISED_LIP + 5.dp),
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
    Box(
        Modifier.padding(top = 1.dp, bottom = LAMP_PADDING - 1.dp).size(LAMP_SIZE),
        contentAlignment = Alignment.Center,
    ) {
        // The picture leaves room around the bezel for the lit jewel's glow, which spills onto the slot.
        Image(
            painterResource(if (lit) Res.drawable.radio_jewel_lit else Res.drawable.radio_jewel_dark),
            null,
            Modifier.requiredSize(LAMP_SIZE * JEWEL_PICTURE_SCALE),
        )
    }
}

@Composable
private fun KeyCap(
    text: String,
    spokenText: String,
    pressed: Boolean,
    color: Color,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = KEY_HEIGHT)
            .widthIn(min = KEY_MIN_WIDTH)
            .threePatch(
                imageResource(if (pressed) Res.drawable.radio_key_piano_down else Res.drawable.radio_key_piano_up),
                KEY_SRC_CAP,
            )
            // The label sits on the key's top face, above its front edge.
            .padding(
                start = 6.dp,
                end = 6.dp,
                top = if (pressed) KEY_DROP + 5.dp else 5.dp,
                bottom = (if (pressed) PRESSED_LIP else RAISED_LIP) + 4.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Engraved in capitals, read as written: screen readers spell some all-caps words. A label
        // that would not fit two lines of a narrow key (three keys on a phone, longer
        // translations) is engraved smaller before it is cut short.
        Text(
            text.uppercase(),
            Modifier.clearAndSetSemantics { this.text = AnnotatedString(spokenText) },
            color = color,
            // The key's content width is measured at fontSize, so it stays the full size: without
            // it a content-sized key would be as wide as a label in the theme's larger body text.
            fontSize = KEY_LABEL_SIZE,
            autoSize = TextAutoSize.StepBased(minFontSize = KEY_LABEL_MIN_SIZE, maxFontSize = KEY_LABEL_SIZE),
            fontFamily = barlow(),
            fontWeight = FontWeight.SemiBold,
            lineHeight = 14.sp,
            letterSpacing = 1.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val KEY_LABEL_SIZE = 12.sp
private val KEY_LABEL_MIN_SIZE = 9.sp
private val SlotShape = RoundedCornerShape(6.dp)
private val KeyShape = RoundedCornerShape(3.dp)
private val KEY_GAP = 6.dp

// The rendered parts' fixed borders, in source pixels: the slot's screwed frame and a key's ends.
private const val SLOT_SRC_INSET = 56
private val SLOT_INSET = 14.dp
private const val KEY_SRC_CAP = 30
private val RAISED_LIP = 4.5.dp
private val PRESSED_LIP = 1.5.dp
private val KEY_DROP = 2.dp
private val SlotPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)

// The slot's opening starts this far inside the picture; scrolled keys are cut off there, under
// the frame, rather than at the picture's outer edge.
private val SLOT_OPENING_INSET = 8.dp
private val ScrolledKeysPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp)

// The action bank keeps its drawn metal edge, whose thin rim needs less room than the rendered
// slot's frame, so a phone's four episode actions still share one row.
private val ActionSlotPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
private val KEY_HEIGHT = 36.dp
private val LAMP_SIZE = 9.dp

// The bezel fills about 87% of the jewel picture (the margin holds the glow), so the picture
// is drawn larger than the lamp for the bezel to span about 11 dp.
private const val JEWEL_PICTURE_SCALE = 1.4f
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
