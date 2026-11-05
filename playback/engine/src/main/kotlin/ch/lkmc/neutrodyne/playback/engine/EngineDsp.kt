// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One item's streaming DSP state. The engine alone owns this object. */
internal class EngineDsp(
    private val sampleRate: Int,
    private val channels: Int,
    private val deviceRate: Int,
    private val episodeId: Long,
    /**
     * The ring frame index this item's first output frame lands at. A gapless
     * chain keeps one clock across items, so markers stay in the ring's global
     * frame coordinates instead of restarting at zero for every item.
     */
    private val outFrameBase: Long,
    private val clock: TimelineClock,
    private val write: (ShortArray) -> Unit,
    initialSpeed: Float,
    initialSkipSilence: Boolean,
    private val volume: () -> Float,
    private val silenceSkipped: (Long, Long, Long) -> Unit,
) {
    private var speed = initialSpeed
    private var skipEnabled = initialSkipSilence
    private var sonic = Sonic(sampleRate, channels, speed, 1f, deviceRate)
    private var skipper = newSkipper()
    private var skipOrigin = 0L
    private var inputFrames = 0L
    private var firstPtsUs: Long? = null
    private var outputCursor = 0.0
    private var previousSourceEnd = 0L
    private val gain = GainStage()
    private val buffer = ByteBuffer.allocate(BUFFER_BYTES).order(ByteOrder.nativeOrder())

    val endUs: Long get() = (firstPtsUs ?: 0L) + inputFrames * MICROS_PER_SECOND / sampleRate

    fun configure(
        newSpeed: Float,
        skipSilence: Boolean,
    ) {
        if (speed == newSpeed && skipEnabled == skipSilence) return
        // Flush the old processor before replacing it: its buffered PCM still
        // belongs to the old rate and must retain the old timeline slope.
        skipper?.queueEndOfStream()
        drainSkipper()
        sonic.queueEndOfStream()
        drainSonic()
        speed = newSpeed
        skipEnabled = skipSilence
        sonic = Sonic(sampleRate, channels, speed, 1f, deviceRate)
        skipOrigin = inputFrames
        skipper = newSkipper()
    }

    fun accept(frame: AudioFrame) {
        if (frame.samples.isEmpty()) return
        if (firstPtsUs == null) firstPtsUs = frame.ptsUs ?: 0L
        val start = inputFrames
        inputFrames += frame.samples.size / channels
        val input = ByteBuffer.allocate(frame.samples.size * Short.SIZE_BYTES).order(ByteOrder.nativeOrder())
        input.asShortBuffer().put(frame.samples)
        val skip = skipper
        if (skip == null) {
            queueSonic(input, start)
        } else {
            skip.queueInput(input)
            drainSkipper()
        }
    }

    fun finish() {
        skipper?.queueEndOfStream()
        drainSkipper()
        sonic.queueEndOfStream()
        drainSonic()
    }

    private fun newSkipper(): SilenceSkipper? =
        SilenceSkipper().takeIf { skipEnabled }?.apply { configure(sampleRate, channels) }

    private fun drainSkipper() {
        val skip = skipper ?: return
        while (skip.hasOutput()) {
            buffer.clear()
            val sourceFrame = requireNotNull(skip.getOutput(buffer)) + skipOrigin
            buffer.flip()
            queueSonic(buffer, sourceFrame)
        }
    }

    private fun queueSonic(
        input: ByteBuffer,
        sourceFrame: Long,
    ) {
        val frames = input.remaining() / (channels * Short.SIZE_BYTES)
        if (frames == 0) return
        val firstPts = firstPtsUs ?: 0L
        if (sourceFrame > previousSourceEnd) {
            silenceSkipped(
                outputCursor.toLong(),
                firstPts + previousSourceEnd * MICROS_PER_SECOND / sampleRate,
                firstPts + sourceFrame * MICROS_PER_SECOND / sampleRate,
            )
        }
        previousSourceEnd = sourceFrame + frames
        clock.push(
            TimelineClock.Marker(
                outFrameBase + outputCursor.toLong(),
                episodeId.toString(),
                (firstPtsUs ?: 0L) + sourceFrame * MICROS_PER_SECOND / sampleRate,
                speed,
            ),
        )
        outputCursor += frames * deviceRate / (sampleRate * speed.toDouble())
        sonic.queueInput(input)
        drainSonic()
    }

    private fun drainSonic() {
        while (sonic.outputSize > 0) {
            // Separate from the skipper output buffer: queueInput consumes its
            // input before this scratch buffer is reused for Sonic's output.
            val output = ByteBuffer.allocate(minOf(sonic.outputSize, BUFFER_BYTES)).order(ByteOrder.nativeOrder())
            sonic.getOutput(output)
            output.flip()
            val pcm = ShortArray(output.remaining() / Short.SIZE_BYTES) { output.short }
            gain.volume = volume()
            gain.process(pcm, 0, pcm.size / channels, channels)
            write(pcm)
        }
    }

    private companion object {
        const val BUFFER_BYTES = 1 shl 20
        const val MICROS_PER_SECOND = 1_000_000L
    }
}
