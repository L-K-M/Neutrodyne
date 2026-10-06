// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem

import androidx.compose.runtime.Composable

/**
 * System-bar icon contrast (08 Status bar and system bars). The bars themselves stay transparent
 * (edge-to-edge is set once by the Android shell); this only switches icon brightness.
 */
public enum class StatusBarAppearance {
    /** Dark icons, for light surfaces. */
    DarkIcons,

    /** Light icons, for dark surfaces. */
    LightIcons,
}

/**
 * Applies [appearance] to the platform status/navigation bars. Android delegates to the window's
 * insets controller; on desktop it is a no-op.
 */
@Composable
public expect fun PlatformSystemBarAppearance(appearance: StatusBarAppearance)

/** Appearance matching the scheme: dark icons on the light scheme, light icons on dark. */
public fun statusBarAppearanceFor(dark: Boolean): StatusBarAppearance =
    if (dark) StatusBarAppearance.LightIcons else StatusBarAppearance.DarkIcons
