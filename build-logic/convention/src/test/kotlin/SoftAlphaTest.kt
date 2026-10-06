// SPDX-License-Identifier: Unlicense
import java.awt.Color
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The soft-alpha separation against the measured navy (08 Brand assets: divisor 96, cut-off 0.10). */
class SoftAlphaTest {
    @Test
    fun navyItselfIsTransparent() {
        assertEquals(0.0, BrandAssets.softAlphaAgainstNavy(0x00, 0x19, 0x2E), 0.0)
    }

    @Test
    fun alphaIsTheLargestChannelDifferenceOver96ClampedToOne() {
        assertEquals(1.0, BrandAssets.softAlphaAgainstNavy(243, 136, 28), 0.0) // amber saturates
        assertEquals(1.0, BrandAssets.softAlphaAgainstNavy(0, 25, 46 + 96), 0.0)
        assertEquals(0.5, BrandAssets.softAlphaAgainstNavy(0, 25, 46 + 48), 0.0)
        assertEquals(48.0 / 96.0, BrandAssets.softAlphaAgainstNavy(48, 25, 46), 0.0)
    }

    @Test
    fun pixelsBelowTheCutOffBecomeTransparent() {
        // an increase of 3 is 0.03125 < 0.10, so the pixel is dropped
        assertTrue(BrandAssets.softAlphaAgainstNavy(3, 25, 46) < BrandAssets.ALPHA_CUTOFF)
        assertTrue(BrandAssets.softAlphaAgainstNavy(0, 25, 56) >= BrandAssets.ALPHA_CUTOFF) // 10/96 >= 0.10
        assertTrue(BrandAssets.softAlphaAgainstNavy(5, 13, 19) < BrandAssets.ALPHA_CUTOFF) // darker vignette is background
    }

    @Test
    fun unblendRecoversTheForegroundColour() {
        assertEquals(96, BrandAssets.unblendChannel(48, 0, 0.5))
        assertEquals(136, BrandAssets.unblendChannel(136, 25, 1.0))
    }

    @Test
    fun unblendClampsOutOfGamutChannels() {
        assertEquals(255, BrandAssets.unblendChannel(255, 0, 0.5)) // 510 clamps to 255
        assertEquals(0, BrandAssets.unblendChannel(0, 46, 0.5)) // -46 clamps to 0
    }

    @Test
    fun separationKeepsSoftPixelsAndDropsTheBackground() {
        val source = BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB)
        val navy = Color(BrandAssets.NAVY_RGB)
        val amber = Color(BrandAssets.AMBER_RGB)
        for (y in 0 until 3) {
            for (x in 0 until 3) source.setRGB(x, y, navy.rgb)
        }
        source.setRGB(1, 1, amber.rgb)
        source.setRGB(0, 1, Color(48, navy.green, navy.blue).rgb) // only red differs by 48 -> alpha 0.5

        val mark = BrandAssets.separateMarkFromNavy(source)

        // the kept pixels are the centre and the soft left neighbour (exclusive bounds)
        assertEquals(0.0, mark.bounds.minX, 0.0)
        assertEquals(1.0, mark.bounds.minY, 0.0)
        assertEquals(2.0, mark.bounds.maxX, 0.0)
        assertEquals(2.0, mark.bounds.maxY, 0.0)
        assertEquals(0, mark.image.getRGB(2, 2) ushr 24) // pure navy corner drops out
        assertEquals(255, mark.image.getRGB(1, 1) ushr 24) // amber stays opaque
        val soft = mark.image.getRGB(0, 1)
        assertEquals(128, soft ushr 24) // round(0.5 * 255)
        assertEquals(96, (soft shr 16) and 0xFF) // fg = (48 - 0.5*0)/0.5
        assertEquals(25, (soft shr 8) and 0xFF) // fg = (25 - 0.5*25)/0.5
        assertEquals(46, soft and 0xFF)
    }
}
