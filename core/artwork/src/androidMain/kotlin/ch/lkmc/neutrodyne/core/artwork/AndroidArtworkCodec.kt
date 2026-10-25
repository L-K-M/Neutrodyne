// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.artwork

import android.graphics.Bitmap
import ch.lkmc.neutrodyne.core.common.AppScope
import coil3.BitmapImage
import coil3.Image
import coil3.toBitmap
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Android [ArtworkCodec] on `android.graphics` (08 "Artwork pipeline"): downscales so the long
 * side fits [MAX_EDGE_PX] px, then encodes PNG when the image carries alpha or PNG was
 * requested and JPEG q88 otherwise.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class AndroidArtworkCodec : ArtworkCodec {
    override fun encode(
        image: Image,
        encoding: ArtworkEncoding,
    ): EncodedArtwork {
        // A BitmapImage's bitmap is owned by Coil; it may be shared with the memory cache and
        // must never be recycled. Any other Image (or a hardware-backed one) is drawn into a
        // new software bitmap that this method owns and recycles.
        val borrowed = (image as? BitmapImage)?.bitmap?.takeIf { it.config != Bitmap.Config.HARDWARE }
        val source = borrowed ?: image.toBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        val scaled = scaleToFit(source)
        try {
            val usePng = encoding == ArtworkEncoding.PNG || scaled.hasAlpha()
            val format = if (usePng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val out = ByteArrayOutputStream()
            // PNG is lossless and ignores the quality argument.
            check(scaled.compress(format, JPEG_QUALITY, out)) { "Bitmap.compress($format) failed" }
            val extension = if (usePng) EXTENSION_PNG else EXTENSION_JPEG
            return EncodedArtwork(out.toByteArray().toByteString(), scaled.width, scaled.height, extension)
        } finally {
            if (scaled !== source) scaled.recycle()
            if (borrowed == null) source.recycle()
        }
    }

    /** [bitmap] unchanged when its long side already fits, else an owned, aspect-preserving copy. */
    private fun scaleToFit(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val longSide = maxOf(width, height)
        if (longSide <= MAX_EDGE_PX) return bitmap
        val scale = MAX_EDGE_PX.toFloat() / longSide
        val scaledWidth = maxOf(1, (width * scale).roundToInt())
        val scaledHeight = maxOf(1, (height * scale).roundToInt())
        return Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
    }

    private companion object {
        const val MAX_EDGE_PX = 1024
        const val JPEG_QUALITY = 88
        const val EXTENSION_PNG = "png"
        const val EXTENSION_JPEG = "jpg"
    }
}
