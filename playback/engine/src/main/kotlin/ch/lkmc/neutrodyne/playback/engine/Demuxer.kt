// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.engine

import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * The decode seam (11 Engine contracts, Demux and decode). `FfDemuxer` and
 * `FfDecoder` are the MD0 implementations over the minimal FFmpeg build; the
 * seams exist so an LGPL libmpv build or OS decoders (risk L7) can replace them
 * without the engine or the controller changing.
 *
 * Both objects are single-threaded: the engine thread owns them once the prepare
 * thread has opened them, and `close` releases every native handle.
 */
interface Demuxer : AutoCloseable {
    /** The audio stream the engine plays; every other stream is discarded (video plays as audio). */
    val audioTrack: AudioTrackInfo

    /** Container duration in milliseconds, or null when unknown. */
    val durationMs: Long?

    /** Container chapters, in milliseconds from the start of the item. */
    val chapters: List<EngineChapter>

    /** Container trimming information for the audio track. */
    val gapless: Gapless

    /**
     * Seek to [positionMs] (11 Demux and decode step 3): pending reads are aborted,
     * queued PCM is dropped and the decoder is flushed, so the next [readPacket]
     * starts at or after the target.
     */
    fun seekTo(positionMs: Long)

    /**
     * The next packet of the audio track, or null at end of file. The returned
     * packet's [AudioPacket.data] is valid until the next call or [close], because
     * it views the demuxer's own reference-counted buffer.
     */
    fun readPacket(): AudioPacket?

    override fun close()
}

/** One compressed packet of the audio track. */
data class AudioPacket(
    val data: MemorySegment,
    val size: Int,
    /** Presentation timestamp in the stream time base, or null when unknown. */
    val pts: Long?,
    /** Leading samples to drop (FFmpeg's skip-samples side data). */
    val skipSamples: Int,
    /** Trailing samples to drop (FFmpeg's skip-samples side data). */
    val discardPadding: Int,
    /**
     * Samples the engine must drop from this item's decoded output, which the
     * container did not already tell the decoder to trim: the AAC priming of a
     * fragmented MP4 without an edit list (11 Demux and decode, Gapless
     * trimming). Zero for every container that carries skip-samples side data,
     * because the decoder has already been told what to drop.
     */
    val primingSamples: Int = 0,
)

/** The audio stream's decode parameters (11 Demux and decode step 5). */
data class AudioTrackInfo(
    val codec: AudioCodec,
    val sampleRate: Int,
    val channels: Int,
    val initialPadding: Int,
    val trailingPadding: Int,
    val seekPreroll: Int,
    /** True when the container carries skip-samples side data (gapless trimming applies). */
    val skipSamplesPresent: Boolean,
)

/**
 * The codecs the minimal FFmpeg build enables (D86, 11 FFmpeg build). The names
 * are FFmpeg's own, so `ffmpeg-layout.json` can carry each one's codec id and
 * the demuxer maps a stream's `codec_id` without a hard-coded number here.
 */
enum class AudioCodec {
    AAC,
    MP3,
    OPUS,
    VORBIS,
    FLAC,
    ALAC,
    PCM_S16LE,
    PCM_S24LE,
    PCM_F32LE,
    PCM_U8,
    ;

    companion object {
        /** The codec of an FFmpeg codec name, or null when this build cannot decode it. */
        fun fromName(name: String): AudioCodec? = entries.firstOrNull { it.name == name }
    }
}

/** Decodes packets of one track into interleaved 16-bit PCM (11 Demux and decode step 5). */
interface Decoder : AutoCloseable {
    /**
     * Hand one packet to the decoder. Returns false when the decoder rejected it
     * as undecodable (the engine drops the packet and counts consecutive
     * failures, 11 Engine errors).
     */
    fun send(packet: AudioPacket): Boolean

    /**
     * The next decoded block, resampled to interleaved s16 stereo at the source
     * sample rate, or null when the decoder needs another packet.
     */
    fun receive(): AudioFrame?

    /** Drain the decoder after the last packet (send a null packet, 11 Demux and decode). */
    fun flush()

    override fun close()
}

/** One decoded block of interleaved 16-bit PCM. */
data class AudioFrame(
    /** Interleaved samples, `channels` per frame; the engine's DSP chain consumes this. */
    val samples: ShortArray,
    val channels: Int,
    val sampleRate: Int,
    /** Presentation timestamp of the first sample in microseconds, or null. */
    val ptsUs: Long?,
) {
    // A data class over an array needs the structural equality written out, or
    // two frames of the same audio compare unequal.
    override fun equals(other: Any?): Boolean =
        other is AudioFrame &&
            channels == other.channels &&
            sampleRate == other.sampleRate &&
            ptsUs == other.ptsUs &&
            samples.contentEquals(other.samples)

    override fun hashCode(): Int = samples.contentHashCode() * 31 + channels

    override fun toString(): String =
        "AudioFrame(samples=${samples.size / channels}, channels=$channels, " +
            "sampleRate=$sampleRate, ptsUs=$ptsUs)"
}

/** Opens a demuxer for one resolved source (11 Engine contracts). */
fun interface DemuxerFactory {
    fun open(
        source: ByteSource,
        hint: SourceHint,
    ): Demuxer
}

/** Creates a decoder for one audio track (11 Engine contracts). */
fun interface DecoderFactory {
    fun create(track: AudioTrackInfo): Decoder
}

/** An in-memory byte source for tests and the smoke mode (11 Smoke mode step 4). */
class MemoryByteSource(
    private val data: ByteArray,
    override val length: Long? = data.size.toLong(),
) : ByteSource {
    private val segment: MemorySegment = MemorySegment.ofArray(data)

    override fun read(
        position: Long,
        dst: MemorySegment,
        maxBytes: Int,
    ): Int {
        if (position >= data.size) return 0
        val available = minOf(maxBytes.toLong(), data.size - position).toInt()
        MemorySegment.copy(segment, position, dst, 0L, available.toLong())
        return available
    }

    override fun abort() = Unit

    override fun close() = Unit
}

/**
 * A positional read-only file source (11 Sources and SpanCache). `close` releases
 * the channel; a read on a closed source returns -1 rather than throwing, so the
 * engine's abort path stays exception-free.
 */
class FileChannelByteSource(
    path: Path,
) : ByteSource {
    private val channel = FileChannel.open(path, StandardOpenOption.READ)
    override val length: Long? = channel.size()

    override fun read(
        position: Long,
        dst: MemorySegment,
        maxBytes: Int,
    ): Int {
        if (!channel.isOpen) return -1
        val buffer = ByteBuffer.allocate(minOf(maxBytes, MAX_READ))
        var read = 0
        while (read < buffer.capacity()) {
            val count = channel.read(buffer, position + read)
            if (count < 0) break
            read += count
        }
        if (read == 0) return 0
        buffer.flip()
        MemorySegment.copy(MemorySegment.ofBuffer(buffer), 0L, dst, 0L, read.toLong())
        return read
    }

    override fun abort() {
        // A positional read cannot be interrupted; the engine instead relies on the
        // bounded read size and re-checks its abort flag between reads.
    }

    override fun close() {
        channel.close()
    }

    private companion object {
        /** One read's ceiling; keeps a single AVIO callback bounded (11 FFM bindings). */
        const val MAX_READ = 256 * 1024
    }
}

/** Opens the file source the resolver hands the engine (11 Sources and SpanCache). */
class PathByteSourceFactory : FileByteSourceFactory {
    override fun open(path: Path): ByteSource = FileChannelByteSource(path)
}
