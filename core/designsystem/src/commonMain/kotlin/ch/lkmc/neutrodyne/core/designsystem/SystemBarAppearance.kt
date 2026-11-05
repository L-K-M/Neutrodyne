// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

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
 * Applies a scoped icon lease. Only [SystemBarOwner.App] controls navigation icons; header and
 * player leases affect status icons only. Disposal restores the next live owner, or the window's
 * original flags. Updating an obscured owner must not steal the foreground lease.
 */
@Composable
public expect fun PlatformSystemBarAppearance(
    appearance: StatusBarAppearance,
    owner: SystemBarOwner = SystemBarOwner.App,
)

/** Explicit surface precedence, independent of sibling effect/disposal order. */
public enum class SystemBarOwner { App, Header, Player }

/** Choose the greater WCAG contrast with opaque black/white icons against the painted strip. */
public fun statusBarAppearanceFor(background: Color): StatusBarAppearance =
    if (background.luminance() >
        ICON_CONTRAST_CROSSOVER
    ) {
        StatusBarAppearance.DarkIcons
    } else {
        StatusBarAppearance.LightIcons
    }

// (L + .05) / .05 == 1.05 / (L + .05), the black/white contrast crossover.
private const val ICON_CONTRAST_CROSSOVER = 0.17912878f

/** Appearance matching the scheme: dark icons on the light scheme, light icons on dark. */
public fun statusBarAppearanceFor(dark: Boolean): StatusBarAppearance =
    if (dark) StatusBarAppearance.LightIcons else StatusBarAppearance.DarkIcons
