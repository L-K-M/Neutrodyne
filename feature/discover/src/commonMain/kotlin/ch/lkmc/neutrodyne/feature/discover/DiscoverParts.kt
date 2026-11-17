// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.discover

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.radio.Brass
import ch.lkmc.neutrodyne.core.designsystem.radio.Ink
import ch.lkmc.neutrodyne.core.designsystem.radio.MaterialPlate
import ch.lkmc.neutrodyne.core.designsystem.radio.PianoKey
import ch.lkmc.neutrodyne.core.designsystem.radio.PianoKeySlot
import ch.lkmc.neutrodyne.core.designsystem.radio.RadioMaterial
import ch.lkmc.neutrodyne.core.designsystem.radio.courier
import ch.lkmc.neutrodyne.core.designsystem.radio.recess
import ch.lkmc.neutrodyne.core.ui.root.ndTextFieldFocus

/**
 * Discover's search field as a strip of paper in a brass-rimmed slot, typed in Courier like a
 * label: a printed magnifier, the [placeholder] until something is typed, and an amber rim while
 * the field has focus. Enter (either key) and the keyboard's search action call [onSubmit]. Like a
 * hint, [placeholder] names the empty field for screen readers; typed text then speaks for itself.
 */
@Composable
internal fun SearchStrip(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    placeholder: String,
    focus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier =
            modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) {
                        onSubmit()
                        true
                    } else {
                        false
                    }
                }.ndTextFieldFocus()
                .semantics { if (value.isEmpty()) contentDescription = placeholder },
        textStyle = TextStyle(color = Ink, fontFamily = courier(), fontSize = STRIP_TEXT),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        singleLine = true,
        interactionSource = interaction,
        cursorBrush = SolidColor(Ink),
        decorationBox = { field ->
            MaterialPlate(
                RadioMaterial.PAPER,
                Modifier
                    .fillMaxWidth()
                    .recess(StripShape)
                    .then(if (focused) Modifier.border(2.dp, FocusAmber, StripShape) else Modifier),
            ) {
                Row(
                    Modifier.heightIn(min = STRIP_HEIGHT).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(NdIcons.Search, contentDescription = null, tint = Ink, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) {
                            Text(
                                placeholder,
                                Modifier.clearAndSetSemantics {},
                                color = Ink.copy(alpha = PLACEHOLDER_ALPHA),
                                fontFamily = courier(),
                                fontSize = STRIP_TEXT,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        field()
                    }
                }
            }
        },
    )
}

/** A row of momentary piano keys that scrolls sideways when the labels outgrow the slot. */
@Composable
internal fun KeyBank(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    PianoKeySlot(modifier.horizontalScroll(rememberScrollState()), content = content)
}

@Composable
internal fun MomentaryKey(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PianoKey(label, pressed = false, onClick = onClick, modifier = modifier, role = Role.Button)
}

/** A blank slip that holds a chart's place while it loads. */
@Composable
internal fun SkeletonSlip(modifier: Modifier = Modifier) {
    MaterialPlate(
        RadioMaterial.PAPER,
        modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .recess(SlipShape)
            .heightIn(min = SKELETON_HEIGHT)
            .border(0.5.dp, Brass.copy(alpha = 0.7f), SlipShape),
    ) {}
}

internal val SlipShape = RoundedCornerShape(4.dp)
private val StripShape = RoundedCornerShape(6.dp)
private val FocusAmber = Color(0xFFF3B35E)
private val STRIP_HEIGHT = 52.dp
private val SKELETON_HEIGHT = 80.dp
private val STRIP_TEXT = 16.sp
private const val PLACEHOLDER_ALPHA = 0.6f
