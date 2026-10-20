// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.artwork

import coil3.Bitmap
import coil3.asImage
import coil3.size.Size
import coil3.transform.Transformation

/** Remove the 4:3 thumbnail's bars before a caller's square/shape transformations. */
internal class LetterboxCrop169 : Transformation() {
    override val cacheKey: String = "nd:letterbox169:v1"

    override suspend fun transform(
        input: Bitmap,
        size: Size,
    ): Bitmap {
        val image = input.asImage()
        return cropThumbnail(input, central169(image.width, image.height))
    }
}

internal data class ThumbnailCrop(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

internal fun central169(
    width: Int,
    height: Int,
): ThumbnailCrop {
    val bandHeight = minOf(height, maxOf(1, (width.toLong() * 9 / 16).toInt()))
    return ThumbnailCrop(0, (height - bandHeight) / 2, width, bandHeight)
}

internal expect fun cropThumbnail(
    input: Bitmap,
    crop: ThumbnailCrop,
): Bitmap
