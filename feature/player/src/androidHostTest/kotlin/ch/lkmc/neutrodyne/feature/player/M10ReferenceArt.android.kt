// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.player

import android.graphics.Bitmap
import coil3.Image
import coil3.asImage

internal actual fun referenceImage(art: ReferenceArt): Image =
    Bitmap.createBitmap(referencePixels(art), REFERENCE_ART_SIDE, REFERENCE_ART_SIDE, Bitmap.Config.ARGB_8888).asImage()
