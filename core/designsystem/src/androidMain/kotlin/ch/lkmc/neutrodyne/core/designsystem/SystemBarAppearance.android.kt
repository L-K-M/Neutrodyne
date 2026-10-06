// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
public actual fun PlatformSystemBarAppearance(appearance: StatusBarAppearance) {
    val view = LocalView.current
    DisposableEffect(view, appearance) {
        val window = (view.context as? Activity)?.window
        if (window == null) {
            onDispose { }
        } else {
            val controller = WindowCompat.getInsetsController(window, view)
            val darkIcons = appearance == StatusBarAppearance.DarkIcons
            controller.isAppearanceLightStatusBars = darkIcons
            controller.isAppearanceLightNavigationBars = darkIcons
            onDispose { }
        }
    }
}
