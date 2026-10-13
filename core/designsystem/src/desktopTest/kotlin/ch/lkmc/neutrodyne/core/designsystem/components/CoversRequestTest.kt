// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.components

import androidx.compose.ui.unit.Density
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import coil3.PlatformContext
import coil3.request.crossfadeMillis
import coil3.size.Size
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoversRequestTest {
    private val ref = ArtworkRef(key = "u:abcdef", url = "https://img.example/cover.jpg", version = 3)
    private val context = PlatformContext.INSTANCE
    private val density = Density(2f)

    @Test
    fun `thumb and hero have separate versioned memory keys`() {
        val thumb = Covers.request(context, ref, CoverTier.THUMB, density)
        val hero = Covers.request(context, ref, CoverTier.HERO, density)
        assertEquals("art:u:abcdef:v3:t", thumb.memoryCacheKey)
        assertEquals("art:u:abcdef:v3:h", hero.memoryCacheKey)
        assertNull(thumb.placeholderMemoryCacheKey)
        assertEquals("art:u:abcdef:v3:t", hero.placeholderMemoryCacheKey?.key)
    }

    @Test
    fun `wide thumb requests mqdefault dimensions`() =
        runTest {
            val request = Covers.request(context, ref, CoverTier.THUMB, density, aspect = CoverAspect.WIDE_16_9)
            assertEquals(Size(320, 180), request.sizeResolver.size())
        }

    @Test
    fun `square thumbs follow density up to the cap and heroes request 1024 pixels`() =
        runTest {
            for (scale in listOf(1f, 2f, 4f)) {
                val density = Density(scale)
                val expected = (128 * scale).toInt().coerceAtMost(384)
                val thumb = Covers.request(context, ref, CoverTier.THUMB, density)
                assertEquals(expected, Covers.thumbPx(density))
                assertEquals(Size(expected, expected), thumb.sizeResolver.size())
            }
            val hero = Covers.request(context, ref, CoverTier.HERO, density, aspect = CoverAspect.WIDE_16_9)
            assertEquals(Size(1024, 1024), hero.sizeResolver.size())
        }

    @Test
    fun `reduced motion disables crossfade per request`() {
        val animated = Covers.request(context, ref, CoverTier.THUMB, density, crossfade = true)
        val reduced = Covers.request(context, ref, CoverTier.THUMB, density, crossfade = false)
        assertEquals(200, animated.crossfadeMillis)
        assertEquals(0, reduced.crossfadeMillis)
    }
}
