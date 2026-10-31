// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerMorphTest {
    private val geometry = PlayerGeometry(400f, 800f, 24f)

    @Test fun miniHasExactDimensionsAndNoFullControls() {
        val mini = PlayerMorph.at(0f, geometry)
        assertEquals(8f, mini.inset)
        assertEquals(16f, mini.corner)
        assertEquals(48f, mini.artworkSize)
        assertEquals(8f, mini.artworkX)
        assertEquals(1f, mini.miniAlpha)
        assertEquals(0f, mini.controlsAlpha)
    }

    @Test fun fullCentersSizeLimitedArtworkAndRemovesInsets() {
        val full = PlayerMorph.at(1f, geometry)
        assertEquals(352f, full.artworkSize)
        assertEquals(24f, full.artworkX)
        assertEquals(80f, full.artworkY)
        assertEquals(0f, full.inset)
        assertEquals(0f, full.corner)
        assertEquals(1f, full.controlsAlpha)
        assertEquals(0f, full.miniAlpha)
        assertEquals(480f, PlayerMorph.at(1f, PlayerGeometry(1600f, 1600f)).artworkSize)
    }

    @Test fun fullArtworkRespectsTheScaledHeader() {
        val scaled = geometry.copy(headerHeight = 80f)
        assertEquals(104f, PlayerMorph.at(1f, scaled).artworkY)
        assertEquals(8f, PlayerMorph.at(0f, scaled).artworkY)
    }

    @Test fun dragMappingIsContinuousAndFadesNeverOverlap() {
        val values = (0..100).map { PlayerMorph.at(it / 100f, geometry) }
        values.zipWithNext().forEach { (before, after) ->
            assertTrue(after.artworkSize >= before.artworkSize)
            assertTrue(after.inset <= before.inset)
            assertTrue(after.miniAlpha == 0f || after.controlsAlpha == 0f)
        }
        assertEquals(200f, PlayerMorph.at(0.5f, geometry).artworkSize)
    }

    @Test fun railDockInsetDisappearsContinuouslyForTheFullSheet() {
        val rail = PlayerGeometry(1200f, 800f, startInset = 96f)
        assertEquals(104f, PlayerMorph.at(0f, rail).inset)
        assertEquals(8f, PlayerMorph.at(0f, rail).endInset)
        assertEquals(52f, PlayerMorph.at(0.5f, rail).inset)
        assertEquals(4f, PlayerMorph.at(0.5f, rail).endInset)
        assertEquals(0f, PlayerMorph.at(1f, rail).inset)
        assertEquals(0f, PlayerMorph.at(1f, rail).endInset)
    }

    @Test fun mapsOverscrollToEndpoints() {
        assertEquals(PlayerMorph.at(0f, geometry), PlayerMorph.at(-1f, geometry))
        assertEquals(PlayerMorph.at(1f, geometry), PlayerMorph.at(2f, geometry))
    }

    @Test fun wideArtworkMorphsFromSquareToSixteenByNine() {
        val wide = geometry.copy(artworkAspect = 16f / 9f)
        assertEquals(48f, PlayerMorph.at(0f, wide).artworkHeight)
        assertEquals(198f, PlayerMorph.at(1f, wide).artworkHeight)
    }
}
