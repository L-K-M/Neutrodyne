// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.artwork

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Generated samples shared by both targets and the Android pixel parity test. */
internal object ArtworkColorInputs {
    val red = 0xffd02020.toInt()
    val blue = 0xff2040d0.toInt()
    val samples: List<ArtworkPixels> =
        listOf(
            ArtworkPixels(2, 2, intArrayOf(red, red, red, blue)),
            ArtworkPixels(3, 1, intArrayOf(0xff000000.toInt(), 0xff888888.toInt(), 0xffffffff.toInt())),
            ArtworkPixels(1, 1, intArrayOf(0xfffffafa.toInt())),
            ArtworkPixels(2, 1, intArrayOf(0x00112233, 0x7fffffff)),
            ArtworkPixels(2, 1, intArrayOf(0x80ff0000.toInt(), 0x7f0000ff)),
        )
}

class ArtworkColorExtractorTest {
    @Test fun colourfulArtUsesOneRealSeedAndSrgbAverage() {
        val result = ArtworkColorExtractor.extract(ArtworkColorInputs.samples.first())
        assertNotNull(result.seedArgb)
        assertEquals(0xffa4284c.toInt(), result.avgArgb)
    }

    @Test fun greyscaleAndNearWhiteHaveNoSyntheticSeed() {
        assertNull(ArtworkColorExtractor.extract(ArtworkColorInputs.samples[1]).seedArgb)
        val white = ArtworkColorExtractor.extract(ArtworkColorInputs.samples[2])
        assertNull(white.seedArgb)
        assertEquals(0xfffffafa.toInt(), white.avgArgb)
    }

    @Test fun alphaBelow128DoesNotContributeToSeedOrAverage() {
        assertEquals(ExtractedArtworkColors(null, 0), ArtworkColorExtractor.extract(ArtworkColorInputs.samples[3]))
        assertEquals(0xffff0000.toInt(), ArtworkColorExtractor.extract(ArtworkColorInputs.samples[4]).avgArgb)
        assertEquals(0xffff0000.toInt(), ArtworkColorExtractor.extract(ArtworkColorInputs.samples[4]).seedArgb)
    }

    @Test fun maximumSampleIsDeterministicAndDoesNotOverflowChannelSums() {
        val pixels = ArtworkPixels(112, 112, IntArray(112 * 112) { ArtworkColorInputs.red })
        val expected = ExtractedArtworkColors(ArtworkColorInputs.red, ArtworkColorInputs.red)
        repeat(3) { assertEquals(expected, ArtworkColorExtractor.extract(pixels)) }
    }
}
