// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.materialkolor.hct.Hct
import com.materialkolor.palettes.TonalPalette
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeContent

/** Amber content palettes over quiet navy-hue neutrals (08 App scheme, D97). */
public object BrandColors {
    public const val SEED_AMBER: Int = 0xFFF3881C.toInt()
    public const val NAVY: Int = 0xFF00192E.toInt()

    private val schemes =
        listOf(false, true).associateWith { dark ->
            listOf(0.0, 0.5, 1.0).associateWith { contrast -> build(dark, contrast) }
        }

    /** Cache the six standard variants; preserve continuous OS contrast requests too. */
    public fun scheme(
        dark: Boolean,
        contrast: Double,
    ): ColorScheme {
        val level = contrast.normalizedContrast()
        return schemes.getValue(dark)[level] ?: build(dark, level)
    }

    private fun build(
        dark: Boolean,
        contrast: Double,
    ): ColorScheme {
        val amber = Hct.fromInt(SEED_AMBER)
        val navy = Hct.fromInt(NAVY)
        val content = SchemeContent(amber, dark, contrast)
        return DynamicScheme(
            sourceColorHct = amber,
            variant = content.variant,
            isDark = dark,
            contrastLevel = contrast,
            primaryPalette = content.primaryPalette,
            secondaryPalette = content.secondaryPalette,
            tertiaryPalette = content.tertiaryPalette,
            neutralPalette = TonalPalette.fromHueAndChroma(navy.hue, 6.0),
            neutralVariantPalette = TonalPalette.fromHueAndChroma(navy.hue, 8.0),
            specVersion = content.specVersion,
        ).toComposeColorScheme()
    }
}

internal fun Double.normalizedContrast(): Double = if (isFinite()) coerceIn(0.0, 1.0) else 0.0

/** Apply after either app or artwork construction, without changing accent containers. */
internal fun ColorScheme.toPureBlack(): ColorScheme =
    copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceDim = Color.Black,
        surfaceContainerLow = Color(0xFF0B0B0C),
        surfaceContainer = Color(0xFF111113),
        surfaceContainerHigh = Color(0xFF18181A),
        surfaceContainerHighest = Color(0xFF1F1F22),
    )
