// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.engine

import java.lang.foreign.Arena
import java.nio.file.Path

/**
 * Opens one item's demuxer and decoder over the minimal FFmpeg build (11 Engine
 * contracts, Demux and decode). The engine's MD0 prototype and the corpus suite
 * both drive an item through this: open, decode to the end, measure.
 *
 * [directory] is the FFmpeg build of the host this runs on — the four libraries,
 * their headers and `ffmpeg-layout.json` that `buildFfmpeg` produced — because a
 * shared library cannot be loaded across a target boundary.
 */
class FfmpegPipeline
    private constructor(
        val library: FfmpegLibrary,
        private val arena: Arena,
        val demuxer: FfDemuxer,
        val decoder: FfDecoder,
    ) : AutoCloseable {
        /** The number of decoded samples per channel, after the item's priming trim. */
        val decodedSamples: Long get() = decoder.decodedSamples

        /** The number of samples the decoders emitted, before any trim. */
        val rawDecodedSamples: Long get() = decoder.rawDecodedSamples

        /** Decodes the whole item and returns the PCM of every block in order. */
        fun decodeAll(): List<AudioFrame> {
            val blocks = mutableListOf<AudioFrame>()
            while (true) {
                val packet = demuxer.readPacket() ?: break
                if (!decoder.send(packet)) {
                    while (true) {
                        val frame = decoder.receive() ?: break
                        if (frame.samples.isNotEmpty()) blocks += frame
                    }
                    check(decoder.send(packet)) { "decoder remained blocked after draining" }
                }
                while (true) {
                    val frame = decoder.receive() ?: break
                    if (frame.samples.isNotEmpty()) blocks += frame
                }
            }
            decoder.flush()
            while (true) {
                val frame = decoder.receive() ?: break
                if (frame.samples.isNotEmpty()) blocks += frame
            }
            decoder.drainResampler()?.let { if (it.samples.isNotEmpty()) blocks += it }
            return blocks
        }

        /**
         * Seeks to [positionMs] and decodes from there, returning the position of
         * the first audible sample in milliseconds. MD0 AC1's accuracy goal is
         * ±50 ms, measured against the reference manifest.
         */
        fun seekAndDecode(positionMs: Long): Long {
            demuxer.seekTo(positionMs)
            decoder.flushBuffers()
            decoder.discardPendingSamples()
            val targetUs = positionMs * MICROS_PER_MS
            while (true) {
                val packet = demuxer.readPacket() ?: break
                check(decoder.send(packet)) { "seek decoder backpressure after draining" }
                while (true) {
                    val frame = decoder.receive() ?: break
                    if (frame.samples.isEmpty()) continue
                    val ptsUs = requireNotNull(frame.ptsUs) { "the seek produced audio without a timestamp" }
                    // Ogg seeks by page boundary: the demuxer's first packets
                    // after a seek belong to the page it landed on and can
                    // precede the target by the whole page duration. Whole
                    // frames that end before the target are dropped, as the
                    // consuming engine would drop them before output.
                    val endUs =
                        ptsUs + frame.samples.size.toLong() * MICROS_PER_MS /
                            (frame.channels.toLong() * frame.sampleRate)
                    if (endUs <= targetUs) continue
                    return ptsUs / 1000
                }
            }
            error("the seek to $positionMs ms produced no audio")
        }

        override fun close() {
            decoder.close()
            demuxer.close()
            arena.close()
            library.close()
        }

        companion object {
            /**
             * Opens [source] against the FFmpeg build in [directory]. Throws when
             * the build is missing, of a different major, or cannot decode the
             * item — a spike must fail loudly rather than report nothing.
             */
            fun open(
                source: ByteSource,
                directory: Path,
            ): FfmpegPipeline {
                val library = FfmpegLibrary.load(directory)
                val arena = Arena.ofShared()
                try {
                    val demuxer = FfDemuxer(library, arena, source)
                    try {
                        val decoder = FfDecoder(library, arena, demuxer)
                        return FfmpegPipeline(library, arena, demuxer, decoder)
                    } catch (failure: Throwable) {
                        demuxer.close()
                        throw failure
                    }
                } catch (failure: Throwable) {
                    arena.close()
                    library.close()
                    throw failure
                }
            }

            private const val MICROS_PER_MS = 1_000L
        }
    }
