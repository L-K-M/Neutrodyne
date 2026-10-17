// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Secondary-click detection for row/tile context menus (08 Context menus; desktop, and mice on
 * Android). CMP 1.12 has no `PointerMatcher`/`Modifier.onClick(matcher)` in common code — this
 * reads `buttons.isSecondaryPressed` on the press event, like the platform's context menu
 * detector does.
 */
public fun Modifier.onSecondaryClick(onSecondaryClick: () -> Unit): Modifier =
    pointerInput(onSecondaryClick) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                    onSecondaryClick()
                }
            }
        }
    }

/** Consume modified pointer clicks before combinedClickable can also open the episode. */
@Composable
internal fun Modifier.onEpisodePointerGesture(
    onFocus: () -> Unit,
    onMenu: () -> Unit,
    onSelect: () -> Unit,
    onExtend: () -> Unit,
): Modifier {
    val focus by rememberUpdatedState(onFocus)
    val menu by rememberUpdatedState(onMenu)
    val select by rememberUpdatedState(onSelect)
    val extend by rememberUpdatedState(onExtend)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            var pending: (() -> Unit)? = null
            var pressedAt = androidx.compose.ui.geometry.Offset.Zero
            var moved = false
            var focusOnRelease = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press) {
                    val keys = event.keyboardModifiers
                    val primary = event.buttons.isPrimaryPressed
                    pending =
                        when {
                            event.buttons.isSecondaryPressed -> menu
                            primary && keys.isShiftPressed -> extend
                            primary && (keys.isCtrlPressed || keys.isMetaPressed) -> select
                            else -> null
                        }
                    pressedAt = event.changes.first().position
                    moved = false
                    focusOnRelease = event.changes.first().type == PointerType.Mouse
                }
                if (pending == null) {
                    if (event.type == PointerEventType.Release && focusOnRelease) focus()
                    continue
                }
                if ((event.changes.first().position - pressedAt).getDistance() > viewConfiguration.touchSlop) {
                    moved = true
                }
                event.changes.forEach { it.consume() }
                if (event.type == PointerEventType.Release) {
                    if (!moved) {
                        focus()
                        pending?.invoke()
                    }
                    pending = null
                }
            }
        }
    }
}
