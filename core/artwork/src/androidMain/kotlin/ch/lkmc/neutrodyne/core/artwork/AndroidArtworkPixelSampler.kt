// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.artwork

import android.graphics.Bitmap
import ch.lkmc.neutrodyne.core.common.AppScope
import coil3.BitmapImage
import coil3.Image
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.math.roundToInt

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class AndroidArtworkPixelSampler : ArtworkPixelSampler {
    override fun sample(image: Image): ArtworkPixels {
        require(image.width > 0 && image.height > 0) { "Artwork has no pixels" }
        val scale = minOf(1.0, ArtworkPixels.MAX_SIDE.toDouble() / maxOf(image.width, image.height))
        val width = maxOf(1, (image.width * scale).roundToInt())
        val height = maxOf(1, (image.height * scale).roundToInt())
        // Always draw into an owned sRGB software bitmap. This also handles hardware/wide-gamut
        // inputs without changing or recycling Coil's shared bitmap; getPixels unpremultiplies.
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        var hardwareCopy: Bitmap? = null
        try {
            hardwareCopy =
                (image as? BitmapImage)
                    ?.bitmap
                    ?.takeIf { it.config == Bitmap.Config.HARDWARE }
                    ?.copy(Bitmap.Config.ARGB_8888, false)
            val canvas = android.graphics.Canvas(bitmap)
            canvas.scale(width.toFloat() / image.width, height.toFloat() / image.height)
            if (hardwareCopy != null) canvas.drawBitmap(hardwareCopy, 0f, 0f, null) else image.draw(canvas)
            val argb = IntArray(width * height)
            bitmap.getPixels(argb, 0, width, 0, 0, width, height)
            return ArtworkPixels(width, height, argb)
        } finally {
            bitmap.recycle()
            hardwareCopy?.recycle()
        }
    }
}
