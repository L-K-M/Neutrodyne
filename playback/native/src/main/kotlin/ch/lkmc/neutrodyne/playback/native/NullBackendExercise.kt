// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.native

import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

/**
 * A short PCM burst through the "null" backend — the desktop AOT training journey's
 * hardware-free native proof (11 AOT cache): the FFM bindings, the device-open path and
 * the ring write all record into the archive without touching real audio hardware.
 * The FFM access stays inside this module (the banned-API rule allows `java.lang.foreign`
 * only here). Throws [NdOutputException] on a native failure; the caller decides what a
 * missing library directory means for its report.
 */
fun exerciseNullBackend(directory: Path) {
    NdOutput.open(directory, NULL_BACKEND, SAMPLE_RATE, CHANNELS, RING_MS).use { output ->
        Arena.ofConfined().use { arena ->
            val pcm = arena.allocate(FRAMES_TENTH_SECOND.toLong() * CHANNELS * Float.SIZE_BYTES)
            for (frame in 0 until FRAMES_TENTH_SECOND) {
                val sample = (sin(2.0 * PI * TONE_HZ * frame / SAMPLE_RATE) * TONE_AMPLITUDE).toFloat()
                for (channel in 0 until CHANNELS) {
                    pcm.set(
                        ValueLayout.JAVA_FLOAT,
                        (frame * CHANNELS + channel) * Float.SIZE_BYTES.toLong(),
                        sample,
                    )
                }
            }
            var written = 0
            while (written < FRAMES_TENTH_SECOND) {
                written +=
                    output.write(
                        pcm.asSlice(written * Float.SIZE_BYTES.toLong() * CHANNELS),
                        FRAMES_TENTH_SECOND - written,
                    )
            }
        }
        output.start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PLAY_TIMEOUT_S)
        while (output.framesPlayed <= 0) {
            check(System.nanoTime() < deadline) { "null backend never played a frame" }
            Thread.sleep(PLAY_POLL_MS)
        }
        output.stop()
    }
}

private const val NULL_BACKEND = "null"
private const val SAMPLE_RATE = 44_100
private const val CHANNELS = 2
private const val RING_MS = 200
private const val TONE_HZ = 440.0
private const val TONE_AMPLITUDE = 0.2f
private const val FRAMES_TENTH_SECOND = SAMPLE_RATE / 10

private const val PLAY_TIMEOUT_S = 5L
private const val PLAY_POLL_MS = 25L
