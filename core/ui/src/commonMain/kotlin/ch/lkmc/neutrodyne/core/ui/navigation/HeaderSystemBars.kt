// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import ch.lkmc.neutrodyne.core.designsystem.PlatformSystemBarAppearance
import ch.lkmc.neutrodyne.core.designsystem.SystemBarOwner
import ch.lkmc.neutrodyne.core.designsystem.statusBarAppearanceFor

/** Only the committed, single-pane foreground entry owns the status strip. */
internal val LocalHeaderSystemBarsEnabled = compositionLocalOf { true }

/** [background] is the painted top bar/scrim, not an artwork average below the bar. */
@Composable
public fun HeaderSystemBars(background: Color) {
    if (LocalHeaderSystemBarsEnabled.current) {
        PlatformSystemBarAppearance(statusBarAppearanceFor(background), SystemBarOwner.Header)
    }
}
