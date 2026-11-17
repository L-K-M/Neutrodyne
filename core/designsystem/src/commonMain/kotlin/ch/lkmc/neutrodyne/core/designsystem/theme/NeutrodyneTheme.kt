// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * The app theme (08 Theming and colour → App scheme). Since the owner's 2026-11-17 decision every
 * screen wears the radio's materials, so the scheme is [RadioScheme]'s paper and ink whatever the
 * OS dark mode or wallpaper colours say; only the OS contrast request and reduced motion are
 * honoured. The retired `appearance.theme`, `dynamic_color`, `pure_black` and `artwork_tint` keys
 * stay in settings and backups but nothing reads them.
 */
@Composable
public fun NeutrodyneTheme(
    system: SystemUiState,
    content: @Composable () -> Unit,
) {
    val scheme = RadioScheme.colors(system.contrast)

    CompositionLocalProvider(
        LocalReducedMotion provides system.reducedMotion,
        LocalSystemUiState provides system,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = radioTypography(),
            shapes = NeutrodyneShapes.material,
            content = content,
        )
    }
}
