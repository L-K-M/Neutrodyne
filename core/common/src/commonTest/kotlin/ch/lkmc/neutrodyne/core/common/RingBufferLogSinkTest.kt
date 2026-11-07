// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RingBufferLogSinkTest {
    private val clock =
        object : Clock {
            override fun now(): Long = 0L

            override fun elapsedRealtime(): Long = 0L
        }

    @Test
    fun `a multi-line message becomes one ring entry per rendered line`() {
        val sink = RingBufferLogSink(clock)
        sink.log(LogLevel.ERROR, "Tag", "first\nsecond\nthird")

        val lines = sink.linesFlow.value
        assertEquals(3, lines.size)
        assertTrue(lines.all { it.contains(" Tag ") }, "every rendered line keeps its tag")
        assertTrue(lines[0].endsWith("first"))
        assertTrue(lines[1].endsWith("second"))
        assertTrue(lines[2].endsWith("third"))
    }

    @Test
    fun `ring capacity counts rendered lines`() {
        val sink = RingBufferLogSink(clock, capacity = 3)
        sink.log(LogLevel.ERROR, "Tag", "a\nb\nc\nd")

        assertEquals(3, sink.linesFlow.value.size)
        assertTrue(sink.linesFlow.value.first().endsWith("b"), "oldest rendered line drops out")
    }
}
