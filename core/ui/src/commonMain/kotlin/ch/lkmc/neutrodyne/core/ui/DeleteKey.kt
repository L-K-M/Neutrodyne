// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key

/**
 * True for the keyboard's delete control. A Mac's key labelled "delete" reaches Compose Desktop
 * as [Key.Backspace] (AWT `VK_BACK_SPACE`); [Key.Delete] is forward delete (fn+delete), which
 * compact Mac keyboards do not have. List handlers use this so a focused row can be discarded
 * or removed with the key a Mac actually labels "delete".
 */
public fun KeyEvent.isDeleteKey(): Boolean = key == Key.Delete || key == Key.Backspace
