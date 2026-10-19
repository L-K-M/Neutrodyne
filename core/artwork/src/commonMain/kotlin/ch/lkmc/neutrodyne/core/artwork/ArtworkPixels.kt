// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.artwork

import coil3.Image

/**
 * W2's codec-side adapter samples decoded images, including legacy local files, on Default.
 * Preserve aspect ratio, downscale to at most 112 per axis, never upscale tiny inputs.
 * Read straight (unpremultiplied) sRGB ARGB. The caller owns the Image; do not recycle/close it.
 * Implementations own and release temporary software bitmaps. Failures propagate, never return
 * empty pixels as success. Kept separate from encoding so W1 needs no production adapter stub.
 */
interface ArtworkPixelSampler {
    fun sample(image: Image): ArtworkPixels
}

/** Bounded, owned snapshot: neither a platform bitmap nor a mutable shared pixel buffer escapes. */
class ArtworkPixels(
    val width: Int,
    val height: Int,
    argb: IntArray,
) {
    private val pixels: IntArray

    init {
        require(width in 1..MAX_SIDE && height in 1..MAX_SIDE) { "Sample dimensions must be 1..112" }
        require(argb.size == width * height) { "Sample must contain width * height pixels" }
        pixels = argb.copyOf()
    }

    fun copyArgb(): IntArray = pixels.copyOf()

    companion object {
        const val MAX_SIDE = 112
    }
}

/**
 * Successful extraction always has an average: zero denotes no alpha >= 128 pixels.
 * Filter alpha first and make survivors opaque before MCU; average their sRGB channels.
 * A null seed is a valid completed monochrome result, never a retry signal.
 */
data class ExtractedArtworkColors(
    val seedArgb: Int?,
    val avgArgb: Int,
)
