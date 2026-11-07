// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.player

import coil3.Image
import coil3.asImage
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

internal actual fun referenceImage(art: ReferenceArt): Image {
    val pixels = referencePixels(art)
    val rgba = ByteArray(pixels.size * 4)
    pixels.forEachIndexed { index, pixel ->
        rgba[index * 4] = (pixel ushr 16).toByte()
        rgba[index * 4 + 1] = (pixel ushr 8).toByte()
        rgba[index * 4 + 2] = pixel.toByte()
        rgba[index * 4 + 3] = 0xFF.toByte()
    }
    return Bitmap()
        .apply {
            check(
                installPixels(
                    ImageInfo(
                        REFERENCE_ART_SIDE,
                        REFERENCE_ART_SIDE,
                        ColorType.RGBA_8888,
                        ColorAlphaType.OPAQUE,
                    ),
                    rgba,
                    REFERENCE_ART_SIDE * 4,
                ),
            )
        }.asImage()
}
