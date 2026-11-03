// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals

class SilenceSpanTest {
    @Test
    fun retainedPcmCarriesItsSourceFramesAcrossChunksAndPartialDrains() {
        val source = ByteBuffer.allocate(RATE * FRAME_BYTES).order(ByteOrder.nativeOrder())
        repeat(RATE) { frame ->
            val value = if (frame in QUIET_START until QUIET_END) 0 else 2_000 + frame % 20_000
            source.putShort(value.toShort()).putShort((-value).toShort())
        }
        for (chunk in listOf(127, 1_024, 8_192)) {
            val skip = SilenceSkipper().apply { configure(RATE, CHANNELS) }
            var retained = 0
            fun drain() {
                while (skip.hasOutput()) {
                    val out = ByteBuffer.allocate(256 * FRAME_BYTES).order(ByteOrder.nativeOrder())
                    val first = requireNotNull(skip.getOutput(out))
                    out.flip()
                    repeat(out.remaining() / FRAME_BYTES) { index ->
                        val sourceFrame = retained + if (retained >= JUMP_AT) SKIPPED else 0
                        assertEquals(sourceFrame.toLong(), first + index, "chunk=$chunk retained=$retained")
                        repeat(CHANNELS) { channel ->
                            assertEquals(source.getShort((sourceFrame * CHANNELS + channel) * 2), out.getShort())
                        }
                        retained++
                    }
                }
            }
            var offset = 0
            while (offset < source.capacity()) {
                val count = minOf(chunk * FRAME_BYTES, source.capacity() - offset)
                skip.queueInput(ByteBuffer.wrap(source.array(), offset, count).slice().order(ByteOrder.nativeOrder()))
                drain()
                offset += count
            }
            skip.queueEndOfStream()
            drain()
            assertEquals(RATE - SKIPPED, retained)
        }
    }

    private companion object {
        const val RATE = 44_100
        const val CHANNELS = 2
        const val FRAME_BYTES = CHANNELS * 2
        const val QUIET_START = 12_000
        const val QUIET_END = 31_000
        const val JUMP_AT = 18_614
        const val SKIPPED = 6_382
    }
}
