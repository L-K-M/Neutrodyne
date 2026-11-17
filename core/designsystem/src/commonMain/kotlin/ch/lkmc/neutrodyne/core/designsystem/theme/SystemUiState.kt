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

/** Provided by [NeutrodyneTheme]; read by anything that must honour the OS state. */
public val LocalSystemUiState: androidx.compose.runtime.ProvidableCompositionLocal<SystemUiState> =
    staticCompositionLocalOf { SystemUiState.DEFAULT }

/** True when every animation spec must be a snap (08 Motion token). */
public val LocalReducedMotion: androidx.compose.runtime.ProvidableCompositionLocal<Boolean> =
    staticCompositionLocalOf { false }
