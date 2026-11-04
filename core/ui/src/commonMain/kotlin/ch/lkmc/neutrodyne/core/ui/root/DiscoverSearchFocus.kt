// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.ui.root

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/** UI-thread registration keeps Ctrl/Cmd+F safe while navigation attaches or removes the field. */
public class DiscoverSearchFocus {
    private var action: (() -> Unit)? = null

    public fun register(action: () -> Unit) {
        this.action = action
    }

    public fun unregister(action: () -> Unit) {
        if (this.action === action) this.action = null
    }

    public fun request(): Boolean {
        val current = action ?: return false
        current()
        return true
    }
}

public val LocalDiscoverSearchFocus: ProvidableCompositionLocal<DiscoverSearchFocus?> =
    staticCompositionLocalOf { null }
