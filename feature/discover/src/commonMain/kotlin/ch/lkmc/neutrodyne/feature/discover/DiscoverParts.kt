// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.discover

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.radio.Brass
import ch.lkmc.neutrodyne.core.designsystem.radio.MaterialPlate
import ch.lkmc.neutrodyne.core.designsystem.radio.PaperTextField
import ch.lkmc.neutrodyne.core.designsystem.radio.RadioMaterial
import ch.lkmc.neutrodyne.core.designsystem.radio.recess
import ch.lkmc.neutrodyne.core.ui.root.ndTextFieldFocus

/**
 * Discover's search field: a [PaperTextField] with a printed magnifier. Enter (either key) and the
 * keyboard's search action call [onSubmit].
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
    PaperTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        modifier =
            modifier
                .focusRequester(focus)
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) {
                        onSubmit()
                        true
                    } else {
                        false
                    }
                }.ndTextFieldFocus(),
        leadingIcon = NdIcons.Search,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
    )
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
private val SKELETON_HEIGHT = 80.dp
