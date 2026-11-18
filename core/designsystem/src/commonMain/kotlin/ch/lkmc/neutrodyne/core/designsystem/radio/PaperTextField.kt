// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.radio

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A text field as a strip of paper in a brass-rimmed slot, typed in Courier like a label maker's
 * tape: an optional printed [leadingIcon], the [placeholder] until something is typed, and an
 * optional [trailingText] at the end (such as a character count). The rim turns amber while the
 * field has focus and red while there is an [errorText], which screen readers hear as the field's
 * error. Like a hint, [placeholder] names the empty field for screen readers; typed text then
 * speaks for itself. A field among others in a form takes a [label] instead, printed above the
 * strip like a form's caption, which names it whether empty or filled (two filled password
 * strips would otherwise be indistinguishable); a labelled field's placeholder is only a visible
 * hint. Callers add focus requesters and key handling through [modifier]. A
 * [visualTransformation] masks secrets (`PasswordVisualTransformation` also marks the field as a
 * password for screen readers), and [trailing] holds a small control such as a show-password key.
 */
@Composable
public fun PaperTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    label: String? = null,
    enabled: Boolean = true,
    errorText: String? = null,
    leadingIcon: ImageVector? = null,
    trailingText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    require(label != null || placeholder != null) { "A paper strip needs a label or a placeholder to name it" }
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
                    if (label == null && value.isEmpty() && placeholder != null) contentDescription = placeholder
                    if (errorText != null) error(errorText)
                },
        enabled = enabled,
        textStyle = TextStyle(color = Ink, fontFamily = courier(), fontSize = FIELD_TEXT),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        singleLine = true,
        interactionSource = interaction,
        cursorBrush = SolidColor(Ink),
        decorationBox = { field ->
            Column {
                // Inside the field, so its text merges into the field's name, as a Material label's
                // does; spelled as written for screen readers, which spell some all-caps words. It
                // wraps rather than cuts off: a name such as "Type DELETE to confirm" is the only
                // place some dialogs say what to type.
                if (label != null) {
                    Text(
                        label.uppercase(),
                        // A tight line, so a wrapped name reads as one; the padding keeps a
                        // one-line name where the theme's 24 sp line put it.
                        Modifier.padding(start = 2.dp, top = 4.5.dp, bottom = 8.5.dp).clearAndSetSemantics {
                            text = AnnotatedString(label)
                        },
                        color = Ink,
                        fontFamily = barlow(),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = LABEL_TEXT,
                        letterSpacing = LABEL_SPACING,
                        lineHeight = LABEL_LINE_HEIGHT,
                    )
                }
                PaperStrip(value, placeholder, rim, leadingIcon, trailingText, trailing, field)
            }
        },
    )
}

@Composable
private fun PaperStrip(
    value: String,
    placeholder: String?,
    rim: Color?,
    leadingIcon: ImageVector?,
    trailingText: String?,
    trailing: (@Composable () -> Unit)?,
    field: @Composable () -> Unit,
) {
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
                if (value.isEmpty() && placeholder != null) {
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
            if (trailing != null) {
                Spacer(Modifier.width(4.dp))
                trailing()
            }
        }
    }
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
private val LABEL_TEXT = 11.sp
private val LABEL_SPACING = 1.5.sp
private val LABEL_LINE_HEIGHT = 15.sp
private val FACEPLATE_KEY = 36.dp
private const val PLACEHOLDER_ALPHA = 0.6f
private const val DISABLED_ALPHA = 0.5f
