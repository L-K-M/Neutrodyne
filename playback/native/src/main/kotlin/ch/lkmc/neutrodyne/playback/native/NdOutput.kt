// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.native

import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.file.Path

/**
 * One `nd_out*` output behind the FFM ABI (11 ndmedia interface): a miniaudio
 * device plus the SPSC ring the engine thread writes into. [framesPlayed] is
 * advanced only by the device callback, so position reporting reads frames
 * that really left the device.
 *
 * Errors come back as ndmedia's negative `ND_E_*` codes; every public call
 * throws [NdOutputException] instead of returning them.
 */
class NdOutput private constructor(
    private val library: NdmediaLibrary,
    private val handle: MemorySegment,
) : AutoCloseable {
    private val open =
        library.downcall("nd_out_open", NdmediaAbi.OPEN)
    private val write =
        library.downcall("nd_out_write", NdmediaAbi.WRITE)
    private val freeFrames =
        library.downcall("nd_out_free_frames", NdmediaAbi.FRAMES_INT)
    private val framesPlayedCall =
        library.downcall("nd_out_frames_played", NdmediaAbi.FRAMES_LONG)
    private val underrunFramesCall =
        library.downcall("nd_out_underrun_frames", NdmediaAbi.FRAMES_LONG)
    private val start =
        library.downcall("nd_out_start", NdmediaAbi.CONTROL)
    private val stop =
        library.downcall("nd_out_stop", NdmediaAbi.CONTROL)
    private val clear =
        library.downcall("nd_out_clear", NdmediaAbi.CONTROL)
    private val closeCall =
        library.downcall("nd_out_close", NdmediaAbi.CLOSE)
    private val describe =
        library.downcall("nd_out_describe", NdmediaAbi.DESCRIBE)

    private var closed = false

    /** Media frames consumed by the device callback; underrun silence excluded. */
    val framesPlayed: Long
        get() = framesPlayedCall.invokeExact(handle) as Long

    /** Frames the device asked for while the ring was empty. */
    val underrunFrames: Long
        get() = underrunFramesCall.invokeExact(handle) as Long

    /** Frames of ring space the engine may still write. */
    fun freeFrames(): Int = check("free_frames", freeFrames.invokeExact(handle) as Int)

    /**
     * Writes up to [frames] frames of interleaved f32 PCM from [pcm]. Returns
     * the frames accepted — the caller drains the rest once [freeFrames]
     * recovers; never blocks.
     */
    fun write(
        pcm: MemorySegment,
        frames: Int,
    ): Int = check("write", write.invokeExact(handle, pcm, frames) as Int)

    fun start() = check("start", start.invokeExact(handle) as Int)

    fun stop() = check("stop", stop.invokeExact(handle) as Int)

    /** Drops queued audio after a seek; returns the frames dropped. */
    fun clear(): Int = check("clear", clear.invokeExact(handle) as Int)

    /** `nd_out_describe`'s JSON: rate, channels, backend, device name, period. */
    fun describe(): String {
        val buffer = library.allocate(DESCRIBE_CAP.toLong())
        val written = check("describe", describe.invokeExact(handle, buffer, DESCRIBE_CAP) as Int)
        return buffer.getString(0, ValueLayout.JAVA_BYTE, DESCRIBE_CAP.toLong()).let {
            it.substring(0, written)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        closeCall.invokeExact(handle)
        library.close()
    }

    private fun check(
        what: String,
        code: Int,
    ): Int {
        if (code < 0) throw NdOutputException("nd_out_$what failed with $code")
        return code
    }

    companion object {
        private const val DESCRIBE_CAP = 512

        /**
         * Opens one output on [backend] ("null", "wasapi", "coreaudio",
         * "pulseaudio", "alsa"; null = miniaudio's default order) using the
         * ndmedia library in [directory].
         */
        fun open(
            directory: Path,
            backend: String?,
            sampleRate: Int,
            channels: Int,
            ringMs: Int,
        ): NdOutput {
            val library = NdmediaLibrary.load(directory)
            val config = library.allocate(NdmediaAbi.CONFIG_SIZE)
            config.set(
                NdmediaAbi.ADDRESS,
                NdmediaAbi.CONFIG_BACKEND_OFFSET,
                if (backend == null) MemorySegment.NULL else library.cString(backend),
            )
            config.set(ValueLayout.JAVA_INT, NdmediaAbi.CONFIG_SAMPLE_RATE_OFFSET, sampleRate)
            config.set(ValueLayout.JAVA_INT, NdmediaAbi.CONFIG_CHANNELS_OFFSET, channels)
            config.set(ValueLayout.JAVA_INT, NdmediaAbi.CONFIG_RING_MS_OFFSET, ringMs)
            val slot = library.allocate(NdmediaAbi.ADDRESS.byteSize())
            val probe = library.downcall("nd_out_open", NdmediaAbi.OPEN)
            val rc = probe.invokeExact(config, slot) as Int
            if (rc < 0) {
                library.close()
                throw NdOutputException("nd_out_open failed with $rc")
            }
            return NdOutput(library, slot.get(NdmediaAbi.ADDRESS, 0))
        }
    }
}

class NdOutputException(message: String) : Exception(message)
