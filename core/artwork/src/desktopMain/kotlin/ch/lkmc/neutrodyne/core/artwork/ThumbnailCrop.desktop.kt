// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.artwork

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.IRect

internal actual fun cropThumbnail(
    input: Bitmap,
    crop: ThumbnailCrop,
): Bitmap {
    val output = Bitmap()
    check(input.extractSubset(output, IRect.makeXYWH(crop.left, crop.top, crop.width, crop.height)))
    return output
}
