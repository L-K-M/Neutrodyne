// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import ch.lkmc.neutrodyne.core.model.settings.ThemeMode

/**
 * The app theme (08 Theming and colour → App scheme). `prefs` comes from the shell collecting the
 * `appearance.*` settings; `system` is the shell's read of the OS ([SystemUiState]). The theme
 * resolves dark mode, then the base scheme — Android 12+ wallpaper dynamic colour when the user
 * kept it on, otherwise [BrandColors]' scheme — then optional pure-black surfaces.
 */
@Composable
public fun NeutrodyneTheme(
    prefs: AppearancePrefs,
    system: SystemUiState,
    content: @Composable () -> Unit,
) {
    val dark =
        when (prefs.theme) {
            ThemeMode.SYSTEM -> system.dark
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }

    // platformDynamicScheme returns null off Android 12+ (or when wallpaper colours are off).
    val base =
        (if (prefs.dynamicColor) platformDynamicScheme(dark) else null)
            ?: BrandColors.scheme(dark, system.contrast)
    val scheme = if (dark && prefs.pureBlack) base.toPureBlack() else base

    CompositionLocalProvider(
        LocalArtworkTintEnabled provides prefs.artworkTint,
        LocalReducedMotion provides system.reducedMotion,
        LocalSystemUiState provides system,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = NeutrodyneType,
            shapes = NeutrodyneShapes.material,
            content = content,
        )
    }
}

/** The platform dynamic colour scheme, or `null` where the platform has none (08 Layers). */
@Composable
internal expect fun platformDynamicScheme(dark: Boolean): ColorScheme?
