// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The brand palette (08 Brand assets; D97). M0a ships a **placeholder scheme**: M3 baseline schemes
 * tinted with the measured seed colours, so components read as Neutrodyne's amber/navy without the
 * material-color-utilities port. M0b swaps the bodies of [scheme] for the MCU `SchemeContent`
 * construction (amber primary/secondary/tertiary palettes over navy-hue neutrals) — call sites do
 * not change. Every value below is derived from the two measured constants.
 */
public object BrandColors {
    /** The amber of the icon's glowing pixels (D97). */
    public const val SEED_AMBER: Int = 0xFFF3881C.toInt()

    /** The icon background; splash and adaptive-icon background too (D97). */
    public const val NAVY: Int = 0xFF00192E.toInt()

    private val seedAmber = Color(SEED_AMBER)
    private val navy = Color(NAVY)

    /**
     * The app colour scheme. [contrast] is the OS contrast request (0.0–1.0); the placeholder keeps
     * a single ramp until M0b, which maps it onto MCU's contrast curves.
     */
    public fun scheme(
        dark: Boolean,
        contrast: Double,
    ): ColorScheme = if (dark) darkScheme else lightScheme

    // M0a placeholder: quiet navy-tinted surfaces, amber accents. M0b replaces both schemes with
    // the MCU-derived ones (six cached variants: light/dark × contrast 0.0/0.5/1.0).
    private val lightScheme =
        lightColorScheme(
            primary = Color(0xFF8A4B00),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = seedAmber,
            onPrimaryContainer = Color(0xFF301400),
            secondary = Color(0xFF725C45),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFFDDDBB),
            onSecondaryContainer = Color(0xFF291803),
            tertiary = Color(0xFF54624A),
            onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFD8E7CB),
            onTertiaryContainer = Color(0xFF131F0C),
            background = Color(0xFFFFF8F3),
            onBackground = Color(0xFF211A13),
            surface = Color(0xFFFFF8F3),
            onSurface = Color(0xFF211A13),
            surfaceVariant = Color(0xFFF1E0D1),
            onSurfaceVariant = Color(0xFF50453A),
            outline = Color(0xFF837468),
            outlineVariant = Color(0xFFD4C4B5),
            inverseSurface = Color(0xFF372F27),
            inverseOnSurface = Color(0xFFFDEEE1),
            inversePrimary = seedAmber,
            surfaceTint = Color(0xFF8A4B00),
            error = Color(0xFFBA1A1A),
            onError = Color(0xFFFFFFFF),
            errorContainer = Color(0xFFFFDAD6),
            onErrorContainer = Color(0xFF410002),
            surfaceContainerLowest = Color(0xFFFFFFFF),
            surfaceContainerLow = Color(0xFFFFF1E7),
            surfaceContainer = Color(0xFFF7EBE0),
            surfaceContainerHigh = Color(0xFFF1E5DA),
            surfaceContainerHighest = Color(0xFFECDFD4),
        )

    private val darkScheme =
        darkColorScheme(
            primary = seedAmber,
            onPrimary = Color(0xFF4A2800),
            primaryContainer = Color(0xFF6A3A00),
            onPrimaryContainer = Color(0xFFFFDCB8),
            secondary = Color(0xFFE1C0A3),
            onSecondary = Color(0xFF402E19),
            secondaryContainer = Color(0xFF59452F),
            onSecondaryContainer = Color(0xFFFDDDBB),
            tertiary = Color(0xFFBCCBB0),
            onTertiary = Color(0xFF27341F),
            tertiaryContainer = Color(0xFF3D4A35),
            onTertiaryContainer = Color(0xFFD8E7CB),
            background = navy,
            onBackground = Color(0xFFEDE0D6),
            surface = navy,
            onSurface = Color(0xFFEDE0D6),
            surfaceVariant = Color(0xFF50453A),
            onSurfaceVariant = Color(0xFFD5C3B5),
            outline = Color(0xFF9D8E82),
            outlineVariant = Color(0xFF50453A),
            inverseSurface = Color(0xFFEDE0D6),
            inverseOnSurface = Color(0xFF352F27),
            inversePrimary = Color(0xFF8A4B00),
            surfaceTint = seedAmber,
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005),
            errorContainer = Color(0xFF93000A),
            onErrorContainer = Color(0xFFFFDAD6),
            surfaceContainerLowest = Color(0xFF001221),
            surfaceContainerLow = Color(0xFF001E35),
            surfaceContainer = Color(0xFF002541),
            surfaceContainerHigh = Color(0xFF05304F),
            surfaceContainerHighest = Color(0xFF0E3B5C),
        )
}

/** Pure-black dark surfaces (08 Layers; M10 switch `appearance.pure_black`). Containers keep tint. */
internal fun ColorScheme.toPureBlack(): ColorScheme =
    copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color.Black,
        surfaceContainer = Color.Black,
        surfaceContainerHigh = Color(0xFF0A0A0A),
        surfaceContainerHighest = Color(0xFF141414),
    )
