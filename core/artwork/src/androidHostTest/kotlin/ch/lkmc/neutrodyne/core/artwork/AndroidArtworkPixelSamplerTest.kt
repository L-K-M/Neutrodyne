// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.artwork

import android.graphics.Bitmap
import android.graphics.ColorSpace
import coil3.asImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidArtworkPixelSamplerTest {
    private val sampler = AndroidArtworkPixelSampler()

    @Test fun androidPixelsMatchCommonReferenceInputsWithoutUpscaling() {
        for (reference in ArtworkColorInputs.samples) {
            val source = Bitmap.createBitmap(reference.width, reference.height, Bitmap.Config.ARGB_8888)
            source.setPixels(reference.copyArgb(), 0, reference.width, 0, 0, reference.width, reference.height)
            val sampled = sampler.sample(source.asImage())
            assertEquals(reference.width, sampled.width)
            assertEquals(reference.height, sampled.height)
            // RGB underneath alpha zero is unspecified after premultiplication; compare extraction.
            assertEquals(ArtworkColorExtractor.extract(reference), ArtworkColorExtractor.extract(sampled))
            assertFalse(source.isRecycled)
            source.recycle()
        }
    }

    @Test fun oversizeAndThinArtKeepAspectAndBoundEveryAxis() {
        for ((width, height) in listOf(2240 to 1120, 1 to 3000, 3000 to 1)) {
            val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            source.eraseColor(ArtworkColorInputs.red)
            val result = sampler.sample(source.asImage())
            assertEquals(112, maxOf(result.width, result.height))
            if (width == 2240) assertEquals(56, result.height)
            assertContentEquals(IntArray(result.width * result.height) { ArtworkColorInputs.red }, result.copyArgb())
            assertFalse(source.isRecycled)
            source.recycle()
        }
    }

    @Test fun hardwareInputRemainsOwnedByCoil() {
        val software = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        software.eraseColor(ArtworkColorInputs.blue)
        val hardware = requireNotNull(software.copy(Bitmap.Config.HARDWARE, false))
        val result = sampler.sample(hardware.asImage())
        assertEquals(ArtworkColorInputs.blue, ArtworkColorExtractor.extract(result).avgArgb)
        assertFalse(hardware.isRecycled)
        hardware.recycle()
        software.recycle()
    }

    @Test fun wideGamutIsConvertedToSrgb() {
        val source =
            Bitmap.createBitmap(
                1,
                1,
                Bitmap.Config.RGBA_F16,
                true,
                ColorSpace.get(ColorSpace.Named.DISPLAY_P3),
            )
        source.eraseColor(
            android.graphics.Color
                .valueOf(0.8f, 0.2f, 0.1f, 1f, source.colorSpace!!)
                .pack(),
        )
        val sample = sampler.sample(source.asImage())
        val reference = source.getColor(0, 0).convert(ColorSpace.get(ColorSpace.Named.SRGB)).toArgb()
        assertEquals(reference, sample.copyArgb().single())
        assertFalse(source.isRecycled)
        source.recycle()
    }
}
