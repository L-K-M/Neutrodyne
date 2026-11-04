// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import ch.lkmc.neutrodyne.core.model.art.ArtColors
import com.materialkolor.hct.Hct
import com.materialkolor.palettes.TonalPalette
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ArtworkSchemeTest {
    @Test fun monochromeUsesAppSchemeAndPaleDarkContainersAreClamped() =
        runTest {
            val cache = ArtworkSchemeCache()
            for (seed in listOf(0xFF808080.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt())) {
                assertNull(cache.scheme(seed, true, 0.0))
            }
            val pale = Hct.from(100.0, 18.0, 94.0).toInt()
            val hct = Hct.fromInt(pale)
            val expected = TonalPalette.fromHueAndChroma(hct.hue, hct.chroma.coerceAtLeast(16.0))
            val scheme = checkNotNull(cache.scheme(pale, true, 0.0))
            assertTrue(ArtColors.toneOf(scheme.primaryContainer.toArgb()) <= 40.5)
            assertEquals(expected.tone(30), scheme.primaryContainer.toArgb())
            assertEquals(expected.tone(90), scheme.onPrimaryContainer.toArgb())
        }

    @Test fun lruHasSixteenEntriesAndRefreshesRecency() =
        runTest {
            val cache = ArtworkSchemeCache()
            val seeds = (0..16).map { Hct.from(it * 20.0, 60.0, 50.0).toInt() }
            val first = cache.scheme(seeds[0], true, 0.0)
            val second = cache.scheme(seeds[1], true, 0.0)
            for (seed in seeds.drop(2).take(14)) cache.scheme(seed, true, 0.0)
            assertSame(first, cache.scheme(seeds[0], true, 0.0))
            cache.scheme(seeds.last(), true, 0.0)
            assertSame(first, cache.scheme(seeds[0], true, 0.0))
            assertNotSame(second, cache.scheme(seeds[1], true, 0.0))
            assertNotSame(first, cache.scheme(seeds[0], false, 0.0))
            assertNotSame(first, cache.scheme(seeds[0], true, 1.0))
        }

    @Test fun pureBlackUsesExactRampAfterBrandAndArtwork() =
        runTest {
            val cache = ArtworkSchemeCache()
            val bases =
                listOf(
                    BrandColors.scheme(true, 0.0),
                    checkNotNull(cache.scheme(0xFFDD8855.toInt(), true, 0.0)),
                )
            for (base in bases) {
                val black = base.toPureBlack()
                assertEquals(
                    listOf(0, 0, 0, 0, 0x0B0B0C, 0x111113, 0x18181A, 0x1F1F22),
                    listOf(
                        black.background,
                        black.surface,
                        black.surfaceContainerLowest,
                        black.surfaceDim,
                        black.surfaceContainerLow,
                        black.surfaceContainer,
                        black.surfaceContainerHigh,
                        black.surfaceContainerHighest,
                    ).map { it.toArgb() and 0xFFFFFF },
                )
                assertEquals(base.primaryContainer, black.primaryContainer)
                assertEquals(base.onPrimaryContainer, black.onPrimaryContainer)
            }
        }

    @Test fun animatedOnColoursStayReadableAcrossHueAndContrastChanges() =
        runTest {
            val cache = ArtworkSchemeCache()
            for (dark in listOf(false, true)) {
                for (contrast in listOf(0.0, 0.5, 1.0)) {
                    val schemes =
                        listOf(0xFF0044FF.toInt(), 0xFFFF4400.toInt(), 0xFF22AA55.toInt()).map {
                            checkNotNull(cache.scheme(it, dark, contrast))
                        } + BrandColors.scheme(dark, contrast)
                    for ((from, to) in schemes.zipWithNext()) {
                        for (step in 0..20) {
                            val frame = interpolateArtworkRoles(from, to, step / 20f, contrast)
                            for ((bg, fg) in listOf(
                                frame.primary to frame.onPrimary,
                                frame.primaryContainer to frame.onPrimaryContainer,
                                frame.secondaryContainer to frame.onSecondaryContainer,
                                frame.surface to frame.onSurface,
                                frame.surfaceContainerHighest to frame.onSurfaceVariant,
                            )) {
                                assertTrue(
                                    ArtColors.contrast(bg.toArgb(), fg.toArgb()) >= 4.5,
                                    "dark=$dark contrast=$contrast step=$step bg=$bg fg=$fg",
                                )
                            }
                        }
                    }
                }
            }
        }
}
