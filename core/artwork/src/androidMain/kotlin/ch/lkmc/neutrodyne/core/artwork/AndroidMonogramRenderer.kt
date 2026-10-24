// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.artwork

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.art.Graphemes
import ch.lkmc.neutrodyne.core.model.art.MonogramMode
import ch.lkmc.neutrodyne.core.model.art.MonogramSpec
import coil3.Image
import coil3.asImage
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Android [MonogramRenderer] on `android.graphics` (08 "Artwork pipeline"): the RASTER monogram
 * colours (theme-independent, for system surfaces), the initials centred at [TEXT_FRACTION] of
 * the side in medium sans, `#` when the typeface lacks the glyphs.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class AndroidMonogramRenderer : MonogramRenderer {
    override fun render(
        spec: MonogramSpec,
        size: Int,
    ): Image {
        val (background, foreground) = spec.colors(MonogramMode.RASTER)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(background)

        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = foreground
                textSize = size * TEXT_FRACTION
                typeface = Typeface.create(TYPEFACE_MEDIUM_SANS, Typeface.NORMAL)
                textAlign = Paint.Align.CENTER
            }
        val initials = if (hasGlyphs(paint, spec.initials)) spec.initials else FALLBACK
        // ascent() is negative; this puts the font's visual centre on the bitmap's centre.
        val baseline = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(initials, size / 2f, baseline, paint)

        // The fill is fully opaque; flagging it lets AUTO encoding store the raster as JPEG (08).
        bitmap.setHasAlpha(false)
        return bitmap.asImage()
    }

    private fun hasGlyphs(
        paint: Paint,
        initials: String,
    ): Boolean {
        if (initials.isEmpty()) return false
        var remaining = initials
        while (remaining.isNotEmpty()) {
            val cluster = Graphemes.first(remaining)
            // hasGlyph checks a single glyph/ligature, so "AB" must be checked per cluster.
            if (!paint.hasGlyph(cluster)) return false
            remaining = remaining.drop(cluster.length)
        }
        return true
    }

    private companion object {
        const val TEXT_FRACTION = 0.38f
        const val TYPEFACE_MEDIUM_SANS = "sans-serif-medium"
        const val FALLBACK = "#"
    }
}
