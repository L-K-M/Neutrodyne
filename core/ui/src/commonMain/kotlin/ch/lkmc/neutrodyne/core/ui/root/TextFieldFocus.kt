// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.root

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged

/**
 * Whether at least one tracked text field holds focus. The root's global shortcuts stand down
 * while it is active (08 Keyboard and mouse): Compose's text input does not consume every key
 * event a field edits — a desktop Space key-down reaches the root's bubbling handler — so the
 * bubbling rule alone cannot keep the root's Space chord out of a text field.
 *
 * [onChange] reports every transition so the desktop shell can clear the window menu's
 * accelerators while a field is focused (an AWT menu accelerator is processed before Compose's
 * key input, so the root cannot suppress it by consuming the event).
 */
public class TextFieldFocusState(
    private val onChange: ((Boolean) -> Unit)? = null,
) {
    private var focused: Int = 0

    public val active: Boolean get() = focused > 0

    /** [focused] is true when the field (or a descendant) gains focus, false when it leaves. */
    public fun track(focused: Boolean) {
        val wasActive = active
        this.focused = (this.focused + if (focused) 1 else -1).coerceAtLeast(0)
        if (active != wasActive) onChange?.invoke(active)
    }
}

/** Provided by `NeutrodyneRoot`; text fields report through `Modifier.ndTextFieldFocus()`. */
public val LocalTextFieldFocusState: androidx.compose.runtime.ProvidableCompositionLocal<TextFieldFocusState> =
    staticCompositionLocalOf { TextFieldFocusState() }

/**
 * Reports this node's subtree focus to [LocalTextFieldFocusState], so the root's global
 * shortcuts stand down while a text field is focused (08 Keyboard and mouse). Apply it to a
 * text field's outer modifier: `hasFocus` is true while the field's inner focus target is
 * focused, and the initial unfocused callback is ignored so the shared count stays balanced.
 */
@Composable
public fun Modifier.ndTextFieldFocus(): Modifier {
    val state = LocalTextFieldFocusState.current
    var wasFocused by remember { mutableStateOf(false) }
    return onFocusChanged { focus ->
        val now = focus.hasFocus
        if (now != wasFocused) {
            wasFocused = now
            state.track(now)
        }
    }
}
