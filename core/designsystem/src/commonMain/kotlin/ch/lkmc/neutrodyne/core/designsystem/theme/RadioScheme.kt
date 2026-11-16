// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import ch.lkmc.neutrodyne.core.designsystem.radio.barlow
import ch.lkmc.neutrodyne.core.designsystem.radio.courier

/**
 * The one app scheme (owner decision 2026-11-17, 08 Theming and colour): every page is paper
 * typed in ink inside the radio's cabinet, so Material components that are not yet radio parts
 * still read as paper, ink, enamel and brass. There is no dark variant, wallpaper colour or
 * artwork tint. A system contrast request darkens ink and outlines.
 *
 * Text roles keep WCAG AA (4.5:1) and outlines 3:1 on every paper tone, from surface to
 * surfaceContainerHighest; RadioThemeTest checks each pair.
 */
public object RadioScheme {
    /** Behind the cabinet and under the system bars: the home radio's charcoal stage. */
    public val Stage: Color = Color(0xFF272B27)

    /** The enamel of signs and primary keys. */
    public val Enamel: Color = Color(0xFF1F2E4A)

    private val Paper = Color(0xFFEAD7B5)
    private val PaperInk = Color(0xFF2E2116)

    /** Cached per contrast level: the theme is rebuilt only when the OS contrast request changes. */
    public fun colors(contrast: Double): ColorScheme = if (contrast >= HIGH_CONTRAST) high else standard

    private val standard: ColorScheme = scheme(highContrast = false)
    private val high: ColorScheme = scheme(highContrast = true)

    private fun scheme(highContrast: Boolean): ColorScheme =
        lightColorScheme(
            primary = if (highContrast) Color(0xFF14203A) else Enamel,
            onPrimary = Color(0xFFF6EBD3),
            primaryContainer = Color(0xFFD6C7A2),
            onPrimaryContainer = Color(0xFF14203A),
            inversePrimary = Color(0xFFF3B35E),
            secondary = if (highContrast) Color(0xFF4F3B1E) else Color(0xFF6E5530),
            onSecondary = Color(0xFFF6EBD3),
            secondaryContainer = Color(0xFFDCC392),
            onSecondaryContainer = Color(0xFF3A2A12),
            tertiary = Color(0xFF8A2A1A),
            onTertiary = Color(0xFFFBEFDD),
            tertiaryContainer = Color(0xFFF0C4AE),
            onTertiaryContainer = Color(0xFF4A1208),
            background = Paper,
            onBackground = if (highContrast) Color(0xFF1A120A) else PaperInk,
            surface = Paper,
            onSurface = if (highContrast) Color(0xFF1A120A) else PaperInk,
            surfaceVariant = Color(0xFFDDC79E),
            onSurfaceVariant = if (highContrast) Color(0xFF3B2B1A) else Color(0xFF5B452D),
            surfaceTint = Enamel,
            inverseSurface = Color(0xFF302F26),
            inverseOnSurface = Paper,
            error = Color(0xFF8F2016),
            onError = Color(0xFFFFF4EC),
            errorContainer = Color(0xFFF3C8BA),
            onErrorContainer = Color(0xFF4A0F07),
            outline = if (highContrast) Color(0xFF5E4A30) else Color(0xFF6F5B3E),
            outlineVariant = Color(0xFFC9B186),
            scrim = Color.Black,
            surfaceBright = Color(0xFFF2E3C6),
            surfaceDim = Color(0xFFD5BE92),
            surfaceContainerLowest = Color(0xFFF5E8CD),
            surfaceContainerLow = Color(0xFFEFDFC0),
            surfaceContainer = Color(0xFFE7D3AE),
            surfaceContainerHigh = Color(0xFFE1CBA3),
            surfaceContainerHighest = Color(0xFFDAC398),
        )

    private const val HIGH_CONTRAST = 0.5
}

/**
 * The radio's type (PO-54, extended to every screen 2026-11-17): Courier Prime for headings and
 * titles, as typed on the slips and signs; Barlow for body text and labels. The M3 sizes stay, so
 * layouts and font scaling behave as before.
 */
@Composable
internal fun radioTypography(): Typography {
    val courier = courier()
    val barlow = barlow()
    return remember(courier, barlow) { radioTypography(courier, barlow) }
}

private fun radioTypography(
    courier: FontFamily,
    barlow: FontFamily,
): Typography {
    val base = Typography()

    fun TextStyle.typed() = copy(fontFamily = courier, fontWeight = FontWeight.Bold)

    fun TextStyle.printed(weight: FontWeight = FontWeight.Normal) = copy(fontFamily = barlow, fontWeight = weight)

    return Typography(
        displayLarge = base.displayLarge.typed(),
        displayMedium = base.displayMedium.typed(),
        displaySmall = base.displaySmall.typed(),
        headlineLarge = base.headlineLarge.typed(),
        headlineMedium = base.headlineMedium.typed(),
        headlineSmall = base.headlineSmall.typed(),
        titleLarge = base.titleLarge.typed(),
        titleMedium = base.titleMedium.typed(),
        titleSmall = base.titleSmall.typed(),
        bodyLarge = base.bodyLarge.printed(),
        bodyMedium = base.bodyMedium.printed(),
        bodySmall = base.bodySmall.printed(),
        labelLarge = base.labelLarge.printed(FontWeight.SemiBold),
        labelMedium = base.labelMedium.printed(FontWeight.SemiBold),
        labelSmall = base.labelSmall.printed(FontWeight.Medium),
    )
}
