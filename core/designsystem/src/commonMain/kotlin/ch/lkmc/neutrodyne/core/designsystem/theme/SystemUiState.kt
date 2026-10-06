// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * What the OS currently asks for, mapped by each shell (08 Theming and colour): Android reads night
 * mode, `UiModeManager.getContrast()` and the animator duration scale; the desktop reads Compose's
 * `isSystemInDarkTheme()` and the per-OS contrast/reduced-motion settings. The theme itself never
 * touches platform services.
 */
@Immutable
public data class SystemUiState(
    val dark: Boolean,
    /** 0.0 = standard, 0.5 = medium, 1.0 = high (WCAG-style contrast request). */
    val contrast: Double,
    /** Android "Remove animations" / the desktop OS reduced-motion preference. */
    val reducedMotion: Boolean,
) {
    public companion object {
        /** Neutral default for tests and previews. */
        public val DEFAULT: SystemUiState = SystemUiState(dark = false, contrast = 0.0, reducedMotion = false)
    }
}

/** The appearance switches the theme reads — collected from `appearance.*` keys by the shells. */
@Immutable
public data class AppearancePrefs(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** Android 12+ wallpaper dynamic colour (`appearance.dynamic_color`, on by default). */
    val dynamicColor: Boolean = true,
    /** Dark-theme pure-black surfaces (`appearance.pure_black`; M10). */
    val pureBlack: Boolean = false,
    /** Whether artwork-driven schemes may tint the player surfaces (M10). */
    val artworkTint: Boolean = true,
)

/** The theme preference: follow the OS, or force light/dark. */
public enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Provided by [NeutrodyneTheme]; read by anything that must honour the OS state. */
public val LocalSystemUiState: androidx.compose.runtime.ProvidableCompositionLocal<SystemUiState> =
    staticCompositionLocalOf { SystemUiState.DEFAULT }

/** True when every animation spec must be a snap (08 Motion token). */
public val LocalReducedMotion: androidx.compose.runtime.ProvidableCompositionLocal<Boolean> =
    staticCompositionLocalOf { false }

/** Whether artwork-derived schemes may tint the player (M10); off means the app scheme everywhere. */
public val LocalArtworkTintEnabled: androidx.compose.runtime.ProvidableCompositionLocal<Boolean> =
    staticCompositionLocalOf { true }
