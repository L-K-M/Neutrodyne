// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons

/**
 * A mechanical counter on a metal panel: [label] engraved above, [value] printed on cream number
 * wheels behind a recessed window, and two small ivory keys that step it up and down. A null
 * [onIncrease] or [onDecrease] dims that key (the end of the range). The label and value read
 * together; each key is a button named by [increaseLabel] or [decreaseLabel]. [onWindowClick],
 * when set, makes the window a button too (for example to type an exact value). [spokenValue]
 * replaces an abbreviated [value] for screen readers.
 */
@Composable
public fun StepCounter(
    label: String,
    value: String,
    onDecrease: (() -> Unit)?,
    onIncrease: (() -> Unit)?,
    decreaseLabel: String,
    increaseLabel: String,
    modifier: Modifier = Modifier,
    onWindowClick: (() -> Unit)? = null,
    spokenValue: String = value,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // The window reads the label with the value.
        PanelEngraving(label, Modifier.clearAndSetSemantics {})
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                Modifier
                    .widthIn(min = WINDOW_MIN_WIDTH)
                    .heightIn(min = WINDOW_HEIGHT)
                    .recess(WindowShape)
                    .background(Brush.verticalGradient(WheelPaper), WindowShape)
                    .let {
                        if (onWindowClick !=
                            null
                        ) {
                            it.clickable(role = Role.Button, onClick = onWindowClick)
                        } else {
                            it
                        }
                    }.semantics(mergeDescendants = true) { contentDescription = "$label, $spokenValue" }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    value,
                    color = Ink,
                    fontFamily = courier(),
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    maxLines = 1,
                )
            }
            Column {
                StepKey(UP_DEGREES, increaseLabel, onIncrease)
                StepKey(DOWN_DEGREES, decreaseLabel, onDecrease)
            }
        }
    }
}

/** A label engraved in cream capitals on a metal panel, read as written. */
@Composable
public fun PanelLabel(
    text: String,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
) {
    PanelEngraving(text, modifier.clearAndSetSemantics { this.text = AnnotatedString(text) }, textAlign)
}

@Composable
private fun PanelEngraving(
    text: String,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
) {
    Text(
        text.uppercase(),
        modifier,
        color = Cream,
        fontFamily = barlow(),
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        letterSpacing = 1.5.sp,
        textAlign = textAlign,
        maxLines = 3,
    )
}

@Composable
private fun StepKey(
    degrees: Float,
    description: String,
    onClick: (() -> Unit)?,
) {
    Box(
        Modifier
            .size(STEP_TARGET)
            .clip(CircleShape)
            .clickable(enabled = onClick != null, role = Role.Button) { onClick?.invoke() }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        IvoryKeyCap(
            NdIcons.ArrowForwardIos,
            contentDescription = null,
            modifier = Modifier.rotate(degrees),
            enabled = onClick != null,
            size = STEP_KEY,
        )
    }
}

private val WindowShape = RoundedCornerShape(4.dp)
private val WheelPaper = listOf(Color(0xFFD9C7A0), Color(0xFFF8EFD8), Color(0xFFD9C7A0))
private val WINDOW_MIN_WIDTH = 88.dp
private val WINDOW_HEIGHT = 56.dp

// Two keys stack beside the window; each keeps a 40 dp target around the cap's 30 dp. Two 48 dp
// targets would stand 96 dp tall beside the 56 dp window and stop reading as one counter.
private val STEP_TARGET = 40.dp
private val STEP_KEY = 30.dp
private const val UP_DEGREES = -90f
private const val DOWN_DEGREES = 90f
