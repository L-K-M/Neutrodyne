// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.engine

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * `FfDecoder`: decodes one track into interleaved 16-bit PCM at the source
 * sample rate (11 Demux and decode step 5).
 *
 * The decoder runs with `thread_count = 1`, so FFmpeg starts no thread and the
 * pipeline's memory is touched by one thread at a time. Format conversion is
 * `swresample`'s: the decoder's own output format and channel layout go in, and
 * interleaved s16 stereo at the source rate comes out through a buffer this
 * class owns, so the engine never sees a plane pointer.
 *
 * Gapless trimming: a packet's [AudioPacket.skipSamples] has already been
 * applied by FFmpeg's own decoders, so it is only reported here. The demuxer's
 * [AudioPacket.primingSamples] is trimmed here, because no container side data
 * carried it.
 */
class FfDecoder
    internal constructor(
        private val library: FfmpegLibrary,
        private val arena: Arena,
        private val demuxer: FfDemuxer,
    ) : Decoder {
        /** The stream's decode parameters, which carry the codec's private data. */
        private val codecParameters: MemorySegment = demuxer.codecParameters

        private val timeBaseNum: Int = demuxer.streamTimeBase.first

        private val timeBaseDen: Int = demuxer.streamTimeBase.second
        private val layout = library.layout

        private val codec = library.findDecoder(layout.codecId(demuxer.audioTrack.codec))

        private val contextSlot: MemorySegment = arena.allocate(ValueLayout.ADDRESS)

        private val context: MemorySegment

        private val frameSlot: MemorySegment = library.allocFrameSlot(arena)

        /** The packet `avcodec_send_packet` reads; the demuxer's own packet is not sent. */
        private val sendSlot: MemorySegment = library.allocPacketSlot(arena)

        private val resamplerSlot: MemorySegment = arena.allocate(ValueLayout.ADDRESS)

        /** The resampler's input format, mask and rate; a change reconfigures it. */
        private var inputFormat = -1
        private var inputMask = 0L
        private var inputRate = 0

        /**
         * The decoded samples still to drop for this item's priming, or
         * UNDECIDED until the first packet has said which it is: the demuxer only
         * knows a container's priming once it has seen the container's own
         * skip-samples side data.
         */
        private var remainingPriming = UNDECIDED

        /**
         * Total decoded samples this decoder has produced, after trimming.
         * Cumulative for the whole pipeline, not per seek: the corpus reference
         * counts are the decoder's lifetime totals.
         */
        var decodedSamples: Long = 0
            private set

        /** Total samples the decoders emitted, before any trimming; also cumulative. */
        var rawDecodedSamples: Long = 0
            private set

        /** Consecutive packets the decoder refused; a seek resets the streak. */
        private var consecutivePacketErrors = 0

        /** The source sample rate the decoded PCM arrives at. */
        val sampleRate: Int = demuxer.audioTrack.sampleRate

        /** The decoded PCM's channel count: the DSP chain runs on stereo. */
        val channels: Int = STEREO_CHANNELS

        /** The output buffer, reused for every frame of this item. */
        private val outputBuffer: MemorySegment =
            arena.allocate(OUTPUT_BUFFER_SAMPLES.toLong() * STEREO_CHANNELS * Short.SIZE_BYTES)

        /**
         * `swr_convert`'s out argument is `uint8_t **` — an array of plane
         * pointers — so this slot holds [outputBuffer]'s address; passing the
         * buffer itself would have swresample read its first PCM bytes as the
         * plane pointer.
         */
        private val outputSlot: MemorySegment =
            arena.allocate(ValueLayout.ADDRESS).also {
                it.set(ValueLayout.ADDRESS, 0L, outputBuffer)
            }

        init {
            try {
                require(demuxer.audioTrack.sampleRate > 0) { "the track has no sample rate" }
                val created = library.allocCodecContext(codec)
                contextSlot.set(ValueLayout.ADDRESS, 0L, created)
                context = created
                // The stream's parameters carry the codec's private data — the AAC
                // config, the Vorbis headers — without which the decoder will not open.
                require(library.parametersToContext(context, codecParameters) >= 0) {
                    "the decoder would not take the stream's parameters"
                }
                require(library.setDecoderThreads(context, SINGLE_THREAD) >= 0) {
                    "the decoder refused a single thread"
                }
                val opened = library.openCodec(context, codec)
                require(opened >= 0) {
                    "the ${demuxer.audioTrack.codec} decoder would not open: ${library.errorString(opened)}"
                }
                resamplerSlot.set(ValueLayout.ADDRESS, 0L, library.allocResampler())
            } catch (failure: Throwable) {
                close()
                throw failure
            }
        }

        /**
         * Hands one packet to the decoder. True means the packet was accepted or
         * dropped as corrupt; false means backpressure (drain and retry the same
         * packet). A run of [MAX_CONSECUTIVE_PACKET_ERRORS] refused packets throws
         * [IllegalArgumentException], which the engine maps to
         * [EngineError.UnsupportedFormat] (11 Engine errors and recovery).
         */
        override fun send(packet: AudioPacket): Boolean {
            if (remainingPriming == UNDECIDED) remainingPriming = packet.primingSamples
            val target = requireNotNull(sendSlot.dereference()) { "the send packet was released" }
            library.unrefPacket(target)
            // av_packet_ref hands the decoder the demuxer's packet by reference:
            // the compressed data and every property FFmpeg carries — pts and
            // the skip-samples side data its decoders trim by — which copying
            // data bytes alone would silently drop.
            val referenced = library.refPacket(target, demuxer.currentPacket)
            check(referenced >= 0) { "packet reference failed: ${library.errorString(referenced)}" }
            val result = library.sendPacket(context, target)
            if (result == layout.intConstant("AVERROR_EAGAIN")) return false
            if (result >= 0) {
                consecutivePacketErrors = 0
                return true
            }
            if (++consecutivePacketErrors >= MAX_CONSECUTIVE_PACKET_ERRORS) {
                throw IllegalArgumentException(
                    "the decoder refused $consecutivePacketErrors consecutive packets: " +
                        library.errorString(result),
                )
            }
            return true
        }

        override fun receive(): AudioFrame? {
            val frame = requireNotNull(frameSlot.dereference()) { "the frame was released" }
            while (true) {
                library.unrefFrame(frame)
                val result = library.receiveFrame(context, frame)
                if (result < 0) {
                    if (result == layout.intConstant("AVERROR_EAGAIN") ||
                        result == layout.intConstant("AVERROR_EOF")
                    ) {
                        return null
                    }
                    error("decoding failed: ${library.errorString(result)}")
                }
                // A resampler may buffer a frame without emitting PCM. Keep
                // draining the decoder before declaring that it needs input.
                frameToPcm(frame)?.let { return it }
            }
        }

        /** Sends a null packet so the decoder emits the frames it is still holding. */
        override fun flush() {
            val result = library.sendPacket(context, null)
            check(result >= 0 || result == layout.intConstant("AVERROR_EOF")) {
                "decoder drain failed: ${library.errorString(result)}"
            }
        }

        /**
         * Drops every buffered frame, as a seek requires (11 Demux and decode step 3).
         * The sample counters stay cumulative (they measure the decoder's lifetime
         * output, which the corpus reference counts read); only the corrupt-packet
         * streak resets with the new position.
         */
        fun flushBuffers() {
            library.flushCodecBuffers(context)
            remainingPriming = UNDECIDED
            consecutivePacketErrors = 0
        }

        /**
         * Discards the samples the resampler is still holding, as a seek requires:
         * they belong to the position the engine has just left.
         */
        fun discardPendingSamples() {
            val resampler = resamplerSlot.dereference() ?: return
            // inputRate is set only by reconfigure: an allocated resampler that
            // was never initialised has nothing buffered, and swr_convert on it
            // would dereference a resample function that does not exist yet.
            if (inputRate <= 0) return
            while (library.convert(resampler, outputSlot, OUTPUT_BUFFER_SAMPLES, null, 0) > 0) {
                // Draining: each call returns what was buffered, until nothing is left.
            }
        }

        /** Drains the resampler's delay buffer after the decoder has been flushed. */
        fun drainResampler(): AudioFrame? {
            val resampler = resamplerSlot.dereference() ?: return null
            if (inputRate <= 0) return null
            val written = library.convert(resampler, outputSlot, OUTPUT_BUFFER_SAMPLES, null, 0)
            if (written <= 0) return null
            return pcmOf(written, 0)
        }

        private fun frameToPcm(frame: MemorySegment): AudioFrame? {
            val samples = frame.readInt(layout, "AVFrame", "nb_samples")
            if (samples <= 0) return null
            rawDecodedSamples += samples

            val format = frame.readInt(layout, "AVFrame", "format")
            val rate = frame.readInt(layout, "AVFrame", "sample_rate")
            val mask =
                frame.readChannelMask(layout, "AVFrame")
                    ?: defaultChannelMask(frame.readChannelCount(layout, "AVFrame"))
            if (format != inputFormat || mask != inputMask || rate != inputRate) {
                reconfigure(format, mask, rate)
            }

            val resampler = requireNotNull(resamplerSlot.dereference()) { "the resampler was released" }
            val written =
                library.convert(
                    resampler,
                    outputSlot,
                    OUTPUT_BUFFER_SAMPLES,
                    frame.readExtendedData(layout),
                    samples,
                )
            if (written < 0) error("resampling failed: ${library.errorString(written)}")
            if (written == 0) return null
            return pcmOf(written, 0, frame.readLong(layout, "AVFrame", "pts"))
        }

        /** Rebuilds the resampler when the decoder's output format changes mid-item. */
        private fun reconfigure(
            format: Int,
            mask: Long,
            rate: Int,
        ) {
            library.freeResampler(resamplerSlot)
            val created = library.allocResampler()
            library.setResamplerOptions(
                created,
                layout.constant("AV_CHANNEL_LAYOUT_STEREO_MASK"),
                sampleRate,
                mask,
                format,
                rate,
            )
            resamplerSlot.set(ValueLayout.ADDRESS, 0L, created)
            val initialised = library.initResampler(created)
            require(initialised >= 0) { "the resampler would not initialise: ${library.errorString(initialised)}" }
            inputFormat = format
            inputMask = mask
            inputRate = rate
        }

        /**
         * Copies [written] samples per channel out of the resampler's buffer,
         * dropping [dropped] of them first. The frame's timestamp is converted
         * from the stream's time base to microseconds.
         */
        private fun pcmOf(
            written: Int,
            dropped: Int,
            pts: Long = NO_TIMESTAMP,
        ): AudioFrame {
            var samples = written - dropped
            var leading = dropped
            if (remainingPriming > 0) {
                val trim = minOf(remainingPriming, samples)
                remainingPriming -= trim
                samples -= trim
                leading += trim
            }
            decodedSamples += samples
            val total = samples * STEREO_CHANNELS
            if (total <= 0) return AudioFrame(ShortArray(0), STEREO_CHANNELS, sampleRate, null)
            val pcm = ShortArray(total)
            val leadingSamples = leading * STEREO_CHANNELS
            val source = outputBuffer.reinterpret((leadingSamples + total).toLong() * Short.SIZE_BYTES)
            for (index in 0 until total) {
                pcm[index] =
                    source.get(ValueLayout.JAVA_SHORT, (leadingSamples + index).toLong() * Short.SIZE_BYTES)
            }
            val ptsUs =
                if (pts == NO_TIMESTAMP) {
                    null
                } else {
                    FfmpegTime.ticksToScaled(pts, timeBaseNum, timeBaseDen, FfmpegTime.MICROS_PER_SECOND) +
                        leading * FfmpegTime.MICROS_PER_SECOND / sampleRate
                }
            return AudioFrame(pcm, STEREO_CHANNELS, sampleRate, ptsUs)
        }

        /** Releases the FFmpeg objects this decoder owns; the caller's arena stays open. */
        override fun close() {
            resamplerSlot.dereference()?.let { library.freeResampler(resamplerSlot) }
            library.freeCodecContext(contextSlot)
            library.freeFrame(frameSlot)
            library.freePacket(sendSlot)
        }

        private companion object {
            /** One decoder thread, so FFmpeg starts none (11 FFM bindings). */
            const val SINGLE_THREAD = 1

            const val STEREO_CHANNELS = 2

            /** The output buffer, in samples per channel: one large frame plus headroom. */
            const val OUTPUT_BUFFER_SAMPLES = 1 shl 16

            const val NO_TIMESTAMP = Long.MIN_VALUE

            /** The priming is not known until the first packet arrives. */
            const val UNDECIDED = -1
        }
    }

/** 11 Engine errors and recovery: 50 consecutive refusals end the item. */
internal const val MAX_CONSECUTIVE_PACKET_ERRORS = 50

/**
 * The mask swresample assumes for a channel count when a frame reports no
 * native layout: FFmpeg 9's `av_channel_layout_default`, which takes the first
 * entry of its channel-layout table for the count (mono, stereo, 3.0, 4.0,
 * 5.0, 5.1, 6.1, 7.1). The values are the `AV_CH_LAYOUT_*` masks of the
 * channel_layout.h the FFmpeg build ships; a count outside 1..8 falls back to
 * stereo, as the previous code did.
 */
internal fun defaultChannelMask(channels: Int): Long =
    when (channels) {
        1 -> 0x4L

        // AV_CH_LAYOUT_MONO
        2 -> 0x3L

        // AV_CH_LAYOUT_STEREO
        3 -> 0x7L

        // AV_CH_LAYOUT_SURROUND (3.0)
        4 -> 0x107L

        // AV_CH_LAYOUT_4POINT0
        5 -> 0x37L

        // AV_CH_LAYOUT_5POINT0_BACK (5.0)
        6 -> 0x3FL

        // AV_CH_LAYOUT_5POINT1_BACK (5.1)
        7 -> 0x70FL

        // AV_CH_LAYOUT_6POINT1 (6.1)
        8 -> 0x63FL

        // AV_CH_LAYOUT_7POINT1 (7.1)
        else -> 0x3L
    }
