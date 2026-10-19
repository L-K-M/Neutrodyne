// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.artwork

import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.score.Score

/** Pure bounded CPU work. The artwork lane calls this on Default, outside file publication. */
internal object ArtworkColorExtractor {
    fun extract(pixels: ArtworkPixels): ExtractedArtworkColors {
        val opaque =
            pixels
                .copyArgb()
                .filter { (it ushr 24) >= MIN_ALPHA }
                .map { it or OPAQUE }
                .toIntArray()
        if (opaque.isEmpty()) return ExtractedArtworkColors(null, 0)

        var red = 0
        var green = 0
        var blue = 0
        for (pixel in opaque) {
            red += (pixel ushr 16) and CHANNEL_MASK
            green += (pixel ushr 8) and CHANNEL_MASK
            blue += pixel and CHANNEL_MASK
        }
        val average = OPAQUE or ((red / opaque.size) shl 16) or ((green / opaque.size) shl 8) or (blue / opaque.size)
        val seed =
            Score
                .score(
                    QuantizerCelebi.quantize(opaque, MAX_COLORS),
                    desired = 1,
                    fallbackColorArgb = null,
                ).firstOrNull()
        return ExtractedArtworkColors(seed, average)
    }

    private const val MIN_ALPHA = 128
    private const val MAX_COLORS = 128
    private const val CHANNEL_MASK = 255
    private const val OPAQUE = -0x1000000
}
