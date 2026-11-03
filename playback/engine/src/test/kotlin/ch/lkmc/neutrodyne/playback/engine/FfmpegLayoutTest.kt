// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.engine

import kotlin.test.Test
import kotlin.test.assertFailsWith

class FfmpegLayoutTest {
    @Test
    fun acceptsTheGeneratedTargetWidthsAndMajors() {
        val layout = layout()
        layout.validate()
        layout.verifyMajors(layout.libraries)
    }

    @Test
    fun refusesEachMismatchedMajorBeforeReadingAnyStruct() {
        val layout = layout()
        for (library in layout.libraries.keys) {
            val actual = layout.libraries + (library to requireNotNull(layout.libraries[library]) + 1)
            assertFailsWith<IllegalArgumentException> { layout.verifyMajors(actual) }
        }
    }

    @Test
    fun refusesInvalidPointersAlignmentsAndOffsets() {
        val valid = layout()
        val invalidLayouts =
            listOf(
                valid.copy(pointerSize = 4),
                valid.copy(sizeTSize = 4),
                valid.copy(libraries = emptyMap()),
                valid.copy(structs = mapOf("frame" to StructLayout(16, 3, emptyMap()))),
                valid.copy(structs = mapOf("frame" to StructLayout(16, 8, mapOf("data" to 16)))),
            )
        for (invalid in invalidLayouts) {
            assertFailsWith<IllegalArgumentException> { invalid.validate() }
        }
    }

    private fun layout() =
        FfmpegLayout(
            ffmpegVersion = "9.0.2",
            pointerSize = 8,
            sizeTSize = 8,
            libraries = mapOf("avcodec" to 63, "avformat" to 63, "avutil" to 61, "swresample" to 7),
            structs = mapOf("frame" to StructLayout(16, 8, mapOf("data" to 8))),
            codecIds = emptyMap(),
            constants = emptyMap(),
        )
}
