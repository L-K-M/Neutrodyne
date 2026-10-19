// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.artwork

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class ArtworkPixelsTest {
    @Test fun sampleOwnsInputAndReturnedBuffers() {
        val original = intArrayOf(0x80FF0000.toInt(), 0)
        val sample = ArtworkPixels(2, 1, original)
        original[0] = 0
        val copy = sample.copyArgb()
        copy[0] = 0
        assertContentEquals(intArrayOf(0x80FF0000.toInt(), 0), sample.copyArgb())
    }

    @Test fun sampleRejectsEmptyOversizeAndMismatchedPixels() {
        assertFailsWith<IllegalArgumentException> { ArtworkPixels(0, 0, intArrayOf()) }
        assertFailsWith<IllegalArgumentException> { ArtworkPixels(113, 1, IntArray(113)) }
        assertFailsWith<IllegalArgumentException> { ArtworkPixels(1, 113, IntArray(113)) }
        assertFailsWith<IllegalArgumentException> { ArtworkPixels(2, 2, IntArray(3)) }
    }
}
