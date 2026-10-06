// SPDX-License-Identifier: Unlicense
import java.awt.Color
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end smoke test of the generator on synthetic sources: every declared output is produced, and two
 * runs are byte-identical (01 Brand-asset generator: reproducible, no timestamps).
 */
class BrandAssetsBuildTest {
    @Test
    fun producesEveryDeclaredOutputAndIsByteReproducible() {
        val iconPng = syntheticIconPng()
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" width="1254" height="1254" viewBox="0 0 1254 1254">
              <path fill="#000000" d="M300 300 L300 1000 L450 1000 L450 300 Z M800 300 L800 1000 L950 1000 L950 300 Z"/>
            </svg>
        """.trimIndent()

        val first = BrandAssets.build(iconPng, svg)
        val second = BrandAssets.build(iconPng, svg)

        assertEquals(BrandAssets.OUTPUT_PATHS.toSet(), first.files.keys)
        assertTrue(first.files.keys.all { path -> first.files.getValue(path).contentEquals(second.files.getValue(path)) })
        assertTrue(first.files.values.all { it.isNotEmpty() })
        // the raster outputs really are PNGs
        assertTrue((first.files.getValue(BrandAssets.BRAND_MARK_PATH)[0].toInt() and 0xFF) == 0x89)
        // the vector XML outputs carry the viewport and the SPDX header
        val monochrome = first.files.getValue("app/src/main/res/drawable/ic_launcher_monochrome.xml").decodeToString()
        assertTrue(monochrome.contains("SPDX-License-Identifier: Unlicense"))
        assertTrue(monochrome.contains("android:viewportWidth=\"108\""))
        val notification = first.files.getValue("app/src/main/res/drawable/ic_stat_neutrodyne.xml").decodeToString()
        assertTrue(notification.contains("android:viewportWidth=\"24\""))
        // the adaptive icons declare the monochrome layer (Android 13 themed icons)
        val adaptive = first.files.getValue("app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml").decodeToString()
        assertTrue(adaptive.contains("<monochrome"))
    }

    /** A navy square with an amber disc: enough shape for the separation to find a mark. */
    private fun syntheticIconPng(): ByteArray {
        val image = java.awt.image.BufferedImage(256, 256, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.color = Color(BrandAssets.NAVY_RGB)
            g.fillRect(0, 0, 256, 256)
            g.color = Color(BrandAssets.AMBER_RGB)
            g.fillOval(64, 64, 128, 128)
        } finally {
            g.dispose()
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }
}
