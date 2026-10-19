// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.WeakHashMap

@Composable
public actual fun PlatformSystemBarAppearance(
    appearance: StatusBarAppearance,
    owner: SystemBarOwner,
) {
    val view = LocalView.current
    val window = view.context.activity()?.window
    val token = remember(view, owner) { Any() }
    // Keep the lease's insertion order through theme/colour updates under another surface.
    DisposableEffect(window, token) {
        val bars = window?.let { leases.getOrPut(it) { WindowBarLeases(WindowCompat.getInsetsController(it, view)) } }
        bars?.put(token, owner, appearance)
        onDispose {
            bars?.remove(token)
            if (bars?.isEmpty == true) leases.remove(window)
        }
    }
    SideEffect { window?.let { leases[it]?.put(token, owner, appearance) } }
}

// Composition effects run on the UI thread. One registry belongs to the existing platform actual;
// no competing controller is introduced by headers or the player.
private val leases = WeakHashMap<Window, WindowBarLeases>()

private class WindowBarLeases(
    private val controller: WindowInsetsControllerCompat,
) {
    private data class Lease(
        val owner: SystemBarOwner,
        val appearance: StatusBarAppearance,
    )

    private val originalStatus = controller.isAppearanceLightStatusBars
    private val originalNavigation = controller.isAppearanceLightNavigationBars
    private val owners = linkedMapOf<Any, Lease>()
    val isEmpty: Boolean get() = owners.isEmpty()

    fun put(
        token: Any,
        owner: SystemBarOwner,
        appearance: StatusBarAppearance,
    ) {
        owners[token] = Lease(owner, appearance)
        apply()
    }

    fun remove(token: Any) {
        owners.remove(token)
        apply()
    }

    private fun apply() {
        val status = owners.values.sortedBy { it.owner.ordinal }.lastOrNull()
        controller.isAppearanceLightStatusBars = status?.appearance?.darkIcons ?: originalStatus
        controller.isAppearanceLightNavigationBars =
            owners.values
                .lastOrNull { it.owner == SystemBarOwner.App }
                ?.appearance
                ?.darkIcons ?: originalNavigation
    }
}

private val StatusBarAppearance.darkIcons: Boolean get() = this == StatusBarAppearance.DarkIcons

private tailrec fun Context.activity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.takeUnless { it === this }?.activity()
        else -> null
    }
