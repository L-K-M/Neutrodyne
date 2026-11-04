// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.artwork

import android.graphics.Bitmap

internal actual fun cropThumbnail(
    input: Bitmap,
    crop: ThumbnailCrop,
): Bitmap = Bitmap.createBitmap(input, crop.left, crop.top, crop.width, crop.height)
