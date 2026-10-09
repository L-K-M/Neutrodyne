// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model.art

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 08 "ArtColors tones for monograms and groups": a pure-Kotlin CIELAB/LCh(ab) implementation
 * (common code, identical on both platforms — no JDK types, no colour library). CIE L* is the same
 * quantity as HCT tone and alone determines relative luminance, so fixing L* fixes WCAG contrast
 * whatever the hue.
 *
 * Colours are packed ARGB `Int`s (`0xAARRGGBB`). Conversion: LCh → Lab → XYZ (D65) → linear sRGB
 * (IEC 61966-2-1 matrix) → gamma-encoded sRGB; out-of-gamut results reduce chroma, never L*.
 */
public object ArtColors {
    /**
     * The colour at LCh(ab) `l`/`c`/`hDeg` as ARGB. When the requested chroma leaves the sRGB
     * gamut it is reduced by [GAMUT_BISECTION_STEPS]-step bisection (L* and hue preserved).
     */
    public fun lch(
        l: Double,
        c: Double,
        hDeg: Double,
    ): Int {
        val hRad = hDeg * PI / DEGREES_IN_HALF_TURN
        val cosH = cos(hRad)
        val sinH = sin(hRad)

        if (inGamut(labToSrgb(l, c * cosH, c * sinH))) {
            return argbOf(labToSrgb(l, c * cosH, c * sinH))
        }

        // Bisect the chroma toward 0 until the colour fits sRGB.
        var lo = 0.0
        var hi = c
        repeat(GAMUT_BISECTION_STEPS) {
            val mid = (lo + hi) / 2.0
            if (inGamut(labToSrgb(l, mid * cosH, mid * sinH))) lo = mid else hi = mid
        }
        return argbOf(labToSrgb(l, lo * cosH, lo * sinH))
    }

    /** LCh hue (degrees, `[0, 360)`) of [argb]. Achromatic colours report `0`. */
    public fun hueOf(argb: Int): Double {
        val lab = argbToLab(argb)
        val h = atan2(lab[2], lab[1]) * DEGREES_IN_HALF_TURN / PI
        return if (h < 0.0) h + DEGREES_PER_TURN else h
    }

    /** LCh chroma of [argb]. */
    public fun chromaOf(argb: Int): Double {
        val lab = argbToLab(argb)
        return sqrt(lab[1] * lab[1] + lab[2] * lab[2])
    }

    /** CIE L* ("tone") of [argb], `[0, 100]`. */
    public fun toneOf(argb: Int): Double = argbToLab(argb)[0]

    /**
     * [argb] re-toned to CIE L* [tone], keeping its hue and chroma capped at [maxChroma]
     * (08: monograms and group colours derive from palette seeds this way).
     */
    public fun tone(
        argb: Int,
        tone: Double,
        maxChroma: Double = DEFAULT_MAX_CHROMA,
    ): Int = lch(tone, min(chromaOf(argb), maxChroma), hueOf(argb))

    /** The WCAG 2 contrast ratio of [a] against [b] from relative luminance, `[1, 21]`. */
    public fun contrast(
        a: Int,
        b: Int,
    ): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val hi = max(la, lb)
        val lo = min(la, lb)
        return (hi + CONTRAST_LIFT) / (lo + CONTRAST_LIFT)
    }

    // --- Lab/XYZ/sRGB plumbing ---------------------------------------------------

    /** Lab → gamma-encoded sRGB channels (values may leave `[0, 1]` when out of gamut). */
    private fun labToSrgb(
        l: Double,
        a: Double,
        b: Double,
    ): DoubleArray {
        val fy = (l + LAB_OFFSET) / LAB_SCALE
        val fx = fy + a / LAB_A_DIVISOR
        val fz = fy - b / LAB_B_DIVISOR

        val x = labFInv(fx) * D65_X
        val y = if (l > LAB_KAPPA * LAB_EPSILON) fy * fy * fy else l / LAB_KAPPA
        val z = labFInv(fz) * D65_Z

        // XYZ (D65) → linear sRGB, IEC 61966-2-1 matrix.
        val rLin = MATRIX_R_X * x + MATRIX_R_Y * y + MATRIX_R_Z * z
        val gLin = MATRIX_G_X * x + MATRIX_G_Y * y + MATRIX_G_Z * z
        val bLin = MATRIX_B_X * x + MATRIX_B_Y * y + MATRIX_B_Z * z
        return doubleArrayOf(gammaEncode(rLin), gammaEncode(gLin), gammaEncode(bLin))
    }

    /** ARGB → Lab via linear sRGB and XYZ (D65). */
    private fun argbToLab(argb: Int): DoubleArray {
        val rLin = gammaDecode(channel(argb, SHIFT_R))
        val gLin = gammaDecode(channel(argb, SHIFT_G))
        val bLin = gammaDecode(channel(argb, SHIFT_B))

        // Linear sRGB → XYZ (D65), IEC 61966-2-1 inverse matrix.
        val x = INV_X_R * rLin + INV_X_G * gLin + INV_X_B * bLin
        val y = INV_Y_R * rLin + INV_Y_G * gLin + INV_Y_B * bLin
        val z = INV_Z_R * rLin + INV_Z_G * gLin + INV_Z_B * bLin

        val fx = labF(x / D65_X)
        val fy = labF(y)
        val fz = labF(z / D65_Z)
        return doubleArrayOf(
            LAB_SCALE * fy - LAB_OFFSET,
            LAB_A_DIVISOR * (fx - fy),
            LAB_B_DIVISOR * (fy - fz),
        )
    }

    /** The Lab helper `f(t) = t^(1/3)` with its linear toe. */
    private fun labF(t: Double): Double =
        if (t > LAB_EPSILON) t.pow(1.0 / 3.0) else (LAB_KAPPA * t + LAB_OFFSET) / LAB_SCALE

    /** The Lab helper's inverse `f⁻¹(t)`. */
    private fun labFInv(t: Double): Double {
        val cubed = t * t * t
        return if (cubed > LAB_EPSILON) cubed else (LAB_SCALE * t - LAB_OFFSET) / LAB_KAPPA
    }

    private fun gammaEncode(linear: Double): Double =
        if (linear <= SRGB_GAMMA_THRESHOLD) {
            SRGB_GAMMA_SCALE * linear
        } else {
            SRGB_GAMMA_A * linear.pow(1.0 / SRGB_GAMMA_EXPONENT) - SRGB_GAMMA_B
        }

    private fun gammaDecode(encoded: Double): Double =
        if (encoded <= SRGB_LINEAR_THRESHOLD) {
            encoded / SRGB_GAMMA_SCALE
        } else {
            ((encoded + SRGB_GAMMA_B) / SRGB_GAMMA_A).pow(SRGB_GAMMA_EXPONENT)
        }

    private fun inGamut(rgb: DoubleArray): Boolean = rgb.all { it >= -GAMUT_EPSILON && it <= 1.0 + GAMUT_EPSILON }

    private fun argbOf(rgb: DoubleArray): Int {
        val r = channelOf(rgb[0])
        val g = channelOf(rgb[1])
        val b = channelOf(rgb[2])
        return ALPHA_OPAQUE or (r shl SHIFT_R) or (g shl SHIFT_G) or (b shl SHIFT_B)
    }

    private fun channelOf(v: Double): Int = (v * CHANNEL_MAX).toInt().coerceIn(0, CHANNEL_MAX)

    private fun channel(
        argb: Int,
        shift: Int,
    ): Double = ((argb shr shift) and CHANNEL_MASK).toDouble() / CHANNEL_MAX

    /** WCAG relative luminance of an ARGB colour. */
    private fun relativeLuminance(argb: Int): Double =
        LUMA_R * gammaDecode(channel(argb, SHIFT_R)) +
            LUMA_G * gammaDecode(channel(argb, SHIFT_G)) +
            LUMA_B * gammaDecode(channel(argb, SHIFT_B))

    // CIE Lab constants (D65/2°, 08).
    private const val LAB_EPSILON: Double = 216.0 / 24389.0
    private const val LAB_KAPPA: Double = 24389.0 / 27.0
    private const val LAB_OFFSET: Double = 16.0
    private const val LAB_SCALE: Double = 116.0
    private const val LAB_A_DIVISOR: Double = 500.0
    private const val LAB_B_DIVISOR: Double = 200.0

    // D65 reference white (XYZ, Y = 1).
    private const val D65_X: Double = 0.95047
    private const val D65_Z: Double = 1.08883

    // IEC 61966-2-1 XYZ → linear sRGB.
    private const val MATRIX_R_X: Double = 3.2404542
    private const val MATRIX_R_Y: Double = -1.5371385
    private const val MATRIX_R_Z: Double = -0.4985314
    private const val MATRIX_G_X: Double = -0.9692660
    private const val MATRIX_G_Y: Double = 1.8760108
    private const val MATRIX_G_Z: Double = 0.0415560
    private const val MATRIX_B_X: Double = 0.0556434
    private const val MATRIX_B_Y: Double = -0.2040259
    private const val MATRIX_B_Z: Double = 1.0572252

    // IEC 61966-2-1 linear sRGB → XYZ.
    private const val INV_X_R: Double = 0.4123908
    private const val INV_X_G: Double = 0.3575843
    private const val INV_X_B: Double = 0.1804808
    private const val INV_Y_R: Double = 0.2126390
    private const val INV_Y_G: Double = 0.7151687
    private const val INV_Y_B: Double = 0.0721923
    private const val INV_Z_R: Double = 0.0193308
    private const val INV_Z_G: Double = 0.1191948
    private const val INV_Z_B: Double = 0.9505322

    // sRGB gamma curve.
    private const val SRGB_GAMMA_THRESHOLD: Double = 0.0031308
    private const val SRGB_LINEAR_THRESHOLD: Double = 0.04045
    private const val SRGB_GAMMA_SCALE: Double = 12.92
    private const val SRGB_GAMMA_A: Double = 1.055
    private const val SRGB_GAMMA_B: Double = 0.055
    private const val SRGB_GAMMA_EXPONENT: Double = 2.4

    private const val GAMUT_BISECTION_STEPS: Int = 12

    // Bisection is exact to ~c/4096; this slack keeps rounding noise out of the gamut test.
    private const val GAMUT_EPSILON: Double = 0.001
    private const val DEGREES_PER_TURN: Double = 360.0
    private const val DEGREES_IN_HALF_TURN: Double = 180.0
    private const val CONTRAST_LIFT: Double = 0.05
    private const val LUMA_R: Double = 0.2126
    private const val LUMA_G: Double = 0.7152
    private const val LUMA_B: Double = 0.0722
    private const val DEFAULT_MAX_CHROMA: Double = 48.0
    private const val ALPHA_OPAQUE: Int = -0x1000000
    private const val CHANNEL_MAX: Int = 255
    private const val CHANNEL_MASK: Int = 0xFF
    private const val SHIFT_R: Int = 16
    private const val SHIFT_G: Int = 8
    private const val SHIFT_B: Int = 0
}

/** The colour mode a monogram is drawn in — the app's light/dark surface, or a raster file. */
public enum class MonogramMode { LIGHT, DARK, RASTER }

/**
 * What a podcast monogram shows ([initials]) and the seed [hue] its colours are toned from
 * (08). `colors` returns `background to foreground` — the tones are fixed so WCAG contrast
 * holds for every hue ([ArtColors]).
 */
public data class MonogramSpec(
    val initials: String,
    val hue: Double,
) {
    /** `background to foreground` ARGB for [mode] (08's fixed monogram tones). */
    public fun colors(mode: MonogramMode): Pair<Int, Int> =
        when (mode) {
            MonogramMode.LIGHT -> {
                ArtColors.lch(BG_LIGHT_L, BG_LIGHT_C, hue) to
                    ArtColors.lch(FG_LIGHT_L, FG_C, hue)
            }

            MonogramMode.DARK -> {
                ArtColors.lch(BG_DARK_L, BG_DARK_C, hue) to
                    ArtColors.lch(FG_DARK_L, FG_C, hue)
            }

            // Theme-independent (system surfaces); the text is plain white.
            MonogramMode.RASTER -> {
                ArtColors.lch(BG_RASTER_L, BG_RASTER_C, hue) to RASTER_TEXT
            }
        }

    private companion object {
        const val BG_LIGHT_L: Double = 85.0
        const val BG_LIGHT_C: Double = 30.0
        const val FG_LIGHT_L: Double = 25.0
        const val BG_DARK_L: Double = 30.0
        const val BG_DARK_C: Double = 30.0
        const val FG_DARK_L: Double = 90.0
        const val BG_RASTER_L: Double = 45.0
        const val BG_RASTER_C: Double = 36.0
        const val FG_C: Double = 20.0
        const val RASTER_TEXT: Int = -0x1 // 0xFFFFFFFF
    }
}
