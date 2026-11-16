// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * The app theme (08 Theming and colour → App scheme). Since the owner's 2026-11-17 decision every
 * screen wears the radio's materials, so the scheme is [RadioScheme]'s paper and ink whatever the
 * OS dark mode, wallpaper colours or the retired `appearance.*` theme keys say; only the OS
 * contrast request and reduced motion are honoured. [prefs] still arrives from the shells, whose
 * first-frame gate waits for it, but no field of it changes the look any more.
 */
@Composable
public fun NeutrodyneTheme(
    @Suppress("UNUSED_PARAMETER") prefs: AppearancePrefs,
    system: SystemUiState,
    content: @Composable () -> Unit,
) {
    val scheme = RadioScheme.colors(system.contrast)

    CompositionLocalProvider(
        LocalArtworkTintEnabled provides false,
        LocalPureBlack provides false,
        LocalAppColorScheme provides scheme,
        LocalReducedMotion provides system.reducedMotion,
        LocalSystemUiState provides system,
        LocalAppDarkTheme provides false,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = radioTypography(),
            shapes = NeutrodyneShapes.material,
            content = content,
        )
    }
}
