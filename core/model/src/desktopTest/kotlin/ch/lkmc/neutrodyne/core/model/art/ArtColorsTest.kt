// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model.art

import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import com.google.testing.junit.testparameterinjector.TestParameterValuesProvider
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 08's [ArtColorsTest] contract: every hue × [MonogramMode] keeps WCAG contrast ≥ 7.0
 * (LIGHT/DARK) and ≥ 5.3 (RASTER); monogram and group-dot tones stay in sRGB gamut and keep
 * ≥ 3:1 against the M3 baseline surfaces; six pinned ARGB values guard regressions.
 */
@RunWith(TestParameterInjector::class)
class ArtColorsTest {
    /** Every hue 0–359 the way 08's table lists them. */
    class Hues : TestParameterValuesProvider() {
        override fun provideValues(context: Context): List<Int> = (0 until 360).toList()
    }

    @Test
    fun monogramContrastMeetsFloorForEveryHue(
        @TestParameter(valuesProvider = Hues::class) hue: Int,
        @TestParameter mode: MonogramMode,
    ) {
        val (bg, fg) = MonogramSpec(initials = "TD", hue = hue.toDouble()).colors(mode)

        val floor =
            when (mode) {
                MonogramMode.LIGHT, MonogramMode.DARK -> CONTRAST_FLOOR_THEME
                MonogramMode.RASTER -> CONTRAST_FLOOR_RASTER
            }
        assertTrue(
            ArtColors.contrast(bg, fg) >= floor,
            "hue=$hue mode=$mode contrast=${ArtColors.contrast(bg, fg)} < $floor",
        )
    }

    @Test
    fun monogramTonesAreInGamut(
        @TestParameter(valuesProvider = Hues::class) hue: Int,
        @TestParameter mode: MonogramMode,
    ) {
        val (bgTone, fgTone) =
            when (mode) {
                MonogramMode.LIGHT -> 85.0 to 25.0
                MonogramMode.DARK -> 30.0 to 90.0
                MonogramMode.RASTER -> 45.0 to 100.0
            }
        val (bg, fg) = MonogramSpec(initials = "TD", hue = hue.toDouble()).colors(mode)

        for ((argb, tone) in listOf(bg to bgTone, fg to fgTone)) {
            assertTrue(argb ushr 24 == 0xFF, "hue=$hue mode=$mode $argb not opaque")
            assertEquals(tone, ArtColors.toneOf(argb), TONE_TOLERANCE, "hue=$hue mode=$mode")
        }
    }

    /** Group dots/stripes draw at tone 40 (light) / 80 (dark) on the M3 baseline surfaces. */
    @Test
    fun groupDotTonesKeepThreeToOneOnBaselineSurfaces(
        @TestParameter(valuesProvider = Hues::class) hue: Int,
    ) {
        val lightDot = ArtColors.lch(GROUP_DOT_LIGHT_TONE, GROUP_MAX_CHROMA, hue.toDouble())
        val darkDot = ArtColors.lch(GROUP_DOT_DARK_TONE, GROUP_MAX_CHROMA, hue.toDouble())

        assertTrue(
            ArtColors.contrast(lightDot, BASELINE_LIGHT_SURFACE) >= GRAPHIC_CONTRAST,
            "hue=$hue light=${ArtColors.contrast(lightDot, BASELINE_LIGHT_SURFACE)}",
        )
        assertTrue(
            ArtColors.contrast(darkDot, BASELINE_DARK_SURFACE) >= GRAPHIC_CONTRAST,
            "hue=$hue dark=${ArtColors.contrast(darkDot, BASELINE_DARK_SURFACE)}",
        )
    }

    @Test
    fun lchClampsOutOfGamutChromaWithoutMovingTone() {
        // A high-chroma request at a hue where sRGB clips hard (blue region).
        val argb = ArtColors.lch(50.0, 120.0, 280.0)
        assertEquals(50.0, ArtColors.toneOf(argb), 0.6)
    }

    @Test
    fun toneReattunesLightnessKeepingHueAndCappedChroma() {
        val seed = ArtColors.lch(60.0, 60.0, 20.0)
        val retoned = ArtColors.tone(seed, 80.0, maxChroma = 30.0)

        assertEquals(80.0, ArtColors.toneOf(retoned), 0.6)
        assertEquals(ArtColors.hueOf(seed), ArtColors.hueOf(retoned), 0.6)
        assertTrue(ArtColors.chromaOf(retoned) <= 30.0 + CHROMA_TOLERANCE)
    }

    @Test
    fun pinnedArgbValuesForSixHues() {
        // Regression pins: LIGHT bg / DARK bg / RASTER bg of hue 0,60,120,180,240,300.
        val pinned =
            mapOf(
                0 to Triple(0xFFFFC4D5.toInt(), 0xFF703247.toInt(), 0xFFA0516B.toInt()),
                60 to Triple(0xFFFFC9A5.toInt(), 0xFF663C1E.toInt(), 0xFF935D36.toInt()),
                120 to Triple(0xFFCADBA2.toInt(), 0xFF3C4C1C.toInt(), 0xFF5D7134.toInt()),
                180 to Triple(0xFF8DE4D3.toInt(), 0xFF005046.toInt(), 0xFF007869.toInt()),
                240 to Triple(0xFF98DDFF.toInt(), 0xFF004D64.toInt(), 0xFF007394.toInt()),
                300 to Triple(0xFFD8CDFF.toInt(), 0xFF47406F.toInt(), 0xFF6C629E.toInt()),
            )
        for ((hue, expected) in pinned) {
            val spec = MonogramSpec(initials = "T", hue = hue.toDouble())
            assertEquals(expected.first, spec.colors(MonogramMode.LIGHT).first, "LIGHT hue=$hue")
            assertEquals(expected.second, spec.colors(MonogramMode.DARK).first, "DARK hue=$hue")
            assertEquals(expected.third, spec.colors(MonogramMode.RASTER).first, "RASTER hue=$hue")
        }
    }

    private companion object {
        const val CONTRAST_FLOOR_THEME = 7.0
        const val CONTRAST_FLOOR_RASTER = 5.3
        const val GRAPHIC_CONTRAST = 3.0
        const val GROUP_DOT_LIGHT_TONE = 40.0
        const val GROUP_DOT_DARK_TONE = 80.0
        const val GROUP_MAX_CHROMA = 48.0
        const val CHROMA_TOLERANCE = 0.5
        const val TONE_TOLERANCE = 0.6

        // M3 baseline light/dark `surface` (baseline palette, 08).
        const val BASELINE_LIGHT_SURFACE = 0xFFFEF7FF.toInt()
        const val BASELINE_DARK_SURFACE = 0xFF14121B.toInt()
    }
}
