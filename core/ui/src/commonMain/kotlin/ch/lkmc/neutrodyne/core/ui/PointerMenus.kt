// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Secondary-click detection for row/tile context menus (08 Context menus; desktop, and mice on
 * Android). CMP 1.12 has no `PointerMatcher`/`Modifier.onClick(matcher)` in common code — this
 * reads `buttons.isSecondaryPressed` on the press event, like the platform's context menu
 * detector does.
 */
internal fun Modifier.onSecondaryClick(onSecondaryClick: () -> Unit): Modifier =
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
