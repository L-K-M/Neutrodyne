// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.native

import java.lang.foreign.MemorySegment

/**
 * The engine's output seam (11 ndmedia interface). [NdOutput] is the miniaudio
 * implementation; the engine holds this interface so its device-fault paths
 * (open, write and start failures) can be exercised with an injected fake
 * instead of real hardware.
 */
interface AudioOutput : AutoCloseable {
    /** Media frames consumed by the device callback; underrun silence excluded. */
    val framesPlayed: Long

    /** Frames the device asked for while the ring was empty. */
    val underrunFrames: Long

    /** Frames of ring space the engine may still write. */
    fun freeFrames(): Int

    /**
     * Writes up to [frames] frames of interleaved f32 PCM from [pcm]. Returns
     * the frames accepted; never blocks.
     */
    fun write(
        pcm: MemorySegment,
        frames: Int,
    ): Int

    fun start(): Int

    fun stop(): Int

    /** Drops queued audio after a seek; returns the frames dropped. */
    fun clear(): Int

    /** `nd_out_describe`'s JSON: rate, channels, backend, device name, period. */
    fun describe(): String

    /** The clock reader captures storage, never a device that close could free. */
    fun clockReader(): () -> Long

    /** The underrun reader captures the same storage as [clockReader]. */
    fun underrunReader(): () -> Long

    override fun close()
}
