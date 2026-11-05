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
        private var draining = false
        private var resamplerDrained = false

        /** One streaming frame, preserving decoder backpressure and EOF drain state. */
        fun readFrame(): AudioFrame? {
            while (true) {
                val frame = decoder.receive()
                if (frame != null) {
                    if (frame.samples.isEmpty()) continue
                    return frame
                }
                if (draining) {
                    if (resamplerDrained) return null
                    resamplerDrained = true
                    return decoder.drainResampler()?.takeIf { it.samples.isNotEmpty() }
                }
                val packet = demuxer.readPacket()
                if (packet == null) {
                    decoder.flush()
                    draining = true
                } else {
                    check(decoder.send(packet)) { "decoder backpressure after draining output" }
                }
            }
        }

        /** The number of decoded samples per channel, after the item's priming trim. */
        val decodedSamples: Long get() = decoder.decodedSamples

        /** The number of samples the decoders emitted, before any trim. */
        val rawDecodedSamples: Long get() = decoder.rawDecodedSamples

        /** Decodes the whole item and returns the PCM of every block in order. */
        fun decodeAll(): List<AudioFrame> {
            // The MD0 corpus also rewinds the exposed demuxer/decoder directly.
            // Start a fresh bulk drain at their cursor, independent of the last EOF.
            draining = false
            resamplerDrained = false
            val blocks = mutableListOf<AudioFrame>()
            while (true) blocks += readFrame() ?: break
            return blocks
        }

        /**
         * Seeks to [positionMs] and decodes from there, returning the position of
         * the first audible sample in milliseconds. MD0 AC1's accuracy goal is
         * ±50 ms, measured against the reference manifest.
         */
        fun seekAndDecode(positionMs: Long): Long =
            requireNotNull(seekFrame(positionMs)?.ptsUs) {
                "the seek to $positionMs ms produced no timestamped audio"
            } / MICROS_PER_MS

        /** Return the actual cropped PCM, or null when no audio remains. */
        fun seekFrame(positionMs: Long): AudioFrame? {
            demuxer.seekTo(positionMs)
            decoder.flushBuffers()
            decoder.discardPendingSamples()
            val targetUs = Math.multiplyExact(positionMs, MICROS_PER_MS)
            draining = false
            resamplerDrained = false
            while (true) {
                val frame = readFrame() ?: return null
                val ptsUs = requireNotNull(frame.ptsUs) { "the seek produced audio without a timestamp" }
                val count = frame.samples.size / frame.channels
                val delta = (targetUs - ptsUs).coerceAtLeast(0)
                val dropped =
                    ((delta * frame.sampleRate + MICROS_PER_SECOND - 1) / MICROS_PER_SECOND)
                        .coerceAtMost(count.toLong())
                        .toInt()
                if (dropped == count) continue
                return frame.copy(
                    samples = frame.samples.copyOfRange(dropped * frame.channels, frame.samples.size),
                    ptsUs = ptsUs + dropped * MICROS_PER_SECOND / frame.sampleRate,
                )
            }
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
            private const val MICROS_PER_SECOND = 1_000_000L
        }
    }
