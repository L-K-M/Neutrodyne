// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Takes Space as play/pause on a focused row (08 Keyboard, Space row) instead of the click that
 * `clickable` and `selectable` make of it. Chain it after that modifier: key events reach the
 * modifiers behind the focus target first, so this sees Space before the click does. Both key
 * halves are consumed because the click fires on the key-up. The toggle fires on that same
 * key-up, so holding Space sends a stream of auto-repeat key-downs and toggles once, like a
 * click. It is not a preview handler, so a focused child such as a play key keeps its own Space.
 */
public fun Modifier.spaceTogglesPlayback(toggle: () -> Unit): Modifier =
    onKeyEvent { event ->
        if (!event.isPlainSpace()) return@onKeyEvent false
        if (event.type == KeyEventType.KeyUp) toggle()
        true
    }

private fun KeyEvent.isPlainSpace(): Boolean =
    key == Key.Spacebar && !isCtrlPressed && !isMetaPressed && !isAltPressed && !isShiftPressed
