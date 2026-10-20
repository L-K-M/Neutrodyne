// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.artwork

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import ch.lkmc.neutrodyne.core.model.art.MonogramMode
import ch.lkmc.neutrodyne.core.model.art.MonogramSpec
import coil3.BitmapImage
import coil3.ColorImage
import coil3.asImage
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [AndroidArtworkCodec] and [AndroidMonogramRenderer] (08 "Artwork pipeline"): long-side scaling
 * to 1024 px, AUTO format choice on alpha, forced PNG, deterministic output and the raster
 * monogram draw. Fixtures are synthetic `Bitmap`/`Canvas` images; no files or network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidArtworkCodecTest {
    private val codec = AndroidArtworkCodec()
    private val monograms = AndroidMonogramRenderer()

    @Test
    fun `tall image scales to 1024 on the long side`() {
        val encoded = codec.encode(bitmapImage(1400, 5000, opaque = true) { drawColor(SOLID) })

        assertThat(encoded.extension).isEqualTo("jpg")
        assertThat(encoded.width).isEqualTo(287) // 1400 * 1024 / 5000, aspect preserved
        assertThat(encoded.height).isEqualTo(1024)
        val decoded = decode(encoded)
        assertThat(decoded.width).isEqualTo(287)
        assertThat(decoded.height).isEqualTo(1024)
    }

    @Test
    fun `wide image scales to 1024 on the long side`() {
        val encoded = codec.encode(bitmapImage(5000, 1400, opaque = true) { drawColor(SOLID) })

        assertThat(encoded.width).isEqualTo(1024)
        assertThat(encoded.height).isEqualTo(287)
    }

    @Test
    fun `image within limits is not upscaled`() {
        val encoded = codec.encode(bitmapImage(300, 200, opaque = true) { drawColor(SOLID) })

        assertThat(encoded.width).isEqualTo(300)
        assertThat(encoded.height).isEqualTo(200)
    }

    @Test
    fun `image with alpha encodes png and keeps transparency`() {
        val image =
            bitmapImage(200, 100, opaque = false) {
                drawColor(Color.TRANSPARENT)
                drawRect(0f, 0f, 100f, 100f, Paint().apply { color = SOLID })
            }

        val encoded = codec.encode(image)

        assertThat(encoded.extension).isEqualTo("png")
        assertThat(encoded.bytes[0].toInt() and 0xFF).isEqualTo(0x89)
        assertThat(encoded.bytes[1]).isEqualTo('P'.code.toByte())
        val decoded = decode(encoded)
        assertThat(Color.alpha(decoded.getPixel(150, 50))).isEqualTo(0)
        assertThat(decoded.getPixel(50, 50)).isEqualTo(SOLID) // PNG is lossless
    }

    @Test
    fun `opaque image encodes jpeg`() {
        val encoded = codec.encode(bitmapImage(300, 200, opaque = true) { drawColor(SOLID) })

        assertThat(encoded.extension).isEqualTo("jpg")
        assertThat(encoded.bytes[0].toInt() and 0xFF).isEqualTo(0xFF)
        assertThat(encoded.bytes[1].toInt() and 0xFF).isEqualTo(0xD8)
        val decoded = decode(encoded)
        val pixel = decoded.getPixel(150, 100)
        assertThat(Color.alpha(pixel)).isEqualTo(0xFF)
        assertThat(Color.red(pixel)).isWithin(8).of(Color.red(SOLID))
        assertThat(Color.green(pixel)).isWithin(8).of(Color.green(SOLID))
        assertThat(Color.blue(pixel)).isWithin(8).of(Color.blue(SOLID))
    }

    @Test
    fun `forced png wins over opaque jpeg`() {
        val encoded = codec.encode(bitmapImage(64, 64, opaque = true) { drawColor(SOLID) }, ArtworkEncoding.PNG)

        assertThat(encoded.extension).isEqualTo("png")
        assertThat(encoded.bytes[0].toInt() and 0xFF).isEqualTo(0x89)
        assertThat(encoded.bytes[1]).isEqualTo('P'.code.toByte())
    }

    @Test
    fun `encoding is deterministic`() {
        val image = bitmapImage(256, 128, opaque = true) { drawColor(SOLID) }

        val first = codec.encode(image)
        val second = codec.encode(image)

        assertThat(first.bytes).isEqualTo(second.bytes)
        assertThat(first.width).isEqualTo(second.width)
        assertThat(first.height).isEqualTo(second.height)
    }

    @Test
    fun `non-bitmap image is drawn then encoded`() {
        val encoded = codec.encode(ColorImage(SOLID, 64, 32))

        assertThat(encoded.width).isEqualTo(64)
        assertThat(encoded.height).isEqualTo(32)
        assertThat(decode(encoded).getPixel(10, 10)).isEqualTo(SOLID)
    }

    @Test
    fun `monogram draws centred initials on the raster tone`() {
        val spec = MonogramSpec("AB", hue = 25.0)
        val image = monograms.render(spec)

        assertThat(image.width).isEqualTo(512)
        assertThat(image.height).isEqualTo(512)
        val bitmap = (image as BitmapImage).bitmap
        val (background, foreground) = spec.colors(MonogramMode.RASTER)
        assertThat(bitmap.getPixel(4, 4)).isEqualTo(background)
        // 08: the raster is opaque so AUTO encodes it as JPEG.
        assertThat(bitmap.hasAlpha()).isFalse()

        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertThat(pixels.count { it == foreground }).isGreaterThan(0)
    }

    @Test
    fun `monogram honours explicit size`() {
        assertThat(monograms.render(MonogramSpec("AB", hue = 200.0), size = 256).width).isEqualTo(256)
    }

    @Test
    fun `monogram falls back to hash when glyphs are missing`() {
        val spec = MonogramSpec(MISSING_GLYPHS, hue = 300.0)
        val rendered = (monograms.render(spec) as BitmapImage).bitmap

        val probe =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 512 * 0.38f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
        if (!probe.hasGlyph(MISSING_GLYPHS)) {
            val hashOnly = (monograms.render(MonogramSpec("#", hue = 300.0)) as BitmapImage).bitmap
            assertThat(rendered.sameAs(hashOnly)).isTrue()
        } else {
            // The test font happens to cover the code point; only the render itself is checkable.
            assertThat(rendered.width).isEqualTo(512)
        }
    }

    @Test
    fun `two supported initials are not replaced by hash`() {
        val initials = (monograms.render(MonogramSpec("AB", hue = 200.0)) as BitmapImage).bitmap
        val hash = (monograms.render(MonogramSpec("#", hue = 200.0)) as BitmapImage).bitmap
        assertThat(initials.sameAs(hash)).isFalse()
    }

    /** An ARGB bitmap wrapped as a Coil [Image]; [opaque] clears the alpha flag (JPEG path). */
    private fun bitmapImage(
        width: Int,
        height: Int,
        opaque: Boolean,
        draw: Canvas.() -> Unit,
    ): BitmapImage {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).draw()
        if (opaque) bitmap.setHasAlpha(false)
        return bitmap.asImage()
    }

    private fun decode(encoded: EncodedArtwork): Bitmap {
        val bytes = encoded.bytes.toByteArray()
        return checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }

    private companion object {
        const val SOLID: Int = 0xFF3366CC.toInt()

        // Unassigned code points: no sane font covers them (Paint.hasGlyph → false).
        const val MISSING_GLYPHS = "\u0378\u0378"
    }
}
