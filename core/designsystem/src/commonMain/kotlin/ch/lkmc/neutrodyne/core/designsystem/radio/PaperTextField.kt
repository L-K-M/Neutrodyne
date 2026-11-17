// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.radio

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A text field as a strip of paper in a brass-rimmed slot, typed in Courier like a label maker's
 * tape: an optional printed [leadingIcon], the [placeholder] until something is typed, and an
 * optional [trailingText] at the end (such as a character count). The rim turns amber while the
 * field has focus and red while there is an [errorText], which screen readers hear as the field's
 * error. Like a hint, [placeholder] names the empty field for screen readers; typed text then
 * speaks for itself. Callers add focus requesters and key handling through [modifier].
 */
@Composable
public fun PaperTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    errorText: String? = null,
    leadingIcon: ImageVector? = null,
    trailingText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val rim =
        when {
            errorText != null -> ErrorRed
            focused -> FocusAmber
            else -> null
        }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier =
            modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .semantics {
                    if (value.isEmpty()) contentDescription = placeholder
                    if (errorText != null) error(errorText)
                },
        enabled = enabled,
        textStyle = TextStyle(color = Ink, fontFamily = courier(), fontSize = FIELD_TEXT),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = true,
        interactionSource = interaction,
        cursorBrush = SolidColor(Ink),
        decorationBox = { field ->
            MaterialPlate(
                RadioMaterial.PAPER,
                Modifier
                    .fillMaxWidth()
                    .recess(FieldShape)
                    .then(if (rim != null) Modifier.border(2.dp, rim, FieldShape) else Modifier),
            ) {
                Row(
                    Modifier.heightIn(min = FIELD_HEIGHT).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (leadingIcon != null) {
                        Icon(leadingIcon, contentDescription = null, tint = Ink, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                    }
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) {
                            Text(
                                placeholder,
                                Modifier.clearAndSetSemantics {},
                                color = Ink.copy(alpha = PLACEHOLDER_ALPHA),
                                fontFamily = courier(),
                                fontSize = FIELD_TEXT,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        field()
                    }
                    if (trailingText != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(trailingText, color = Ink, fontFamily = courier(), fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
        },
    )
}

/**
 * An ivory key on the faceplate with its name engraved beside it, for header actions whose word
 * matters, such as Save. The key and its name are one button, read by the name.
 */
@Composable
public fun FaceplateKey(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            Modifier.alpha(if (enabled) 1f else DISABLED_ALPHA),
            color = Cream,
            fontFamily = barlow(),
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            maxLines = 1,
        )
        IvoryKeyCap(icon, contentDescription = null, enabled = enabled, size = FACEPLATE_KEY)
    }
}

private val FieldShape = RoundedCornerShape(6.dp)
private val FocusAmber = Color(0xFFF3B35E)
private val ErrorRed = Color(0xFFB3261E)
private val FIELD_HEIGHT = 52.dp
private val FIELD_TEXT = 16.sp
private val FACEPLATE_KEY = 36.dp
private const val PLACEHOLDER_ALPHA = 0.6f
private const val DISABLED_ALPHA = 0.5f
