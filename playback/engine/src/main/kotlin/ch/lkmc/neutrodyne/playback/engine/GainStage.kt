// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.engine

/**
 * Volume × balance on interleaved s16 PCM (11 DSP chain). The last stage
 * before the output resampler; volume 1.0/balance 0 passes through.
 */
internal class GainStage(
    @Volatile var volume: Float = 1.0f,
    @Volatile var balance: Float = 0.0f,
) {
    /** Applies gain in place to [frames] stereo frames at [offset]. */
    fun process(
        pcm: ShortArray,
        offset: Int,
        frames: Int,
        channels: Int,
    ) {
        val leftGain = volume * if (balance <= 0f) 1f else 1f - balance
        val rightGain = volume * if (balance >= 0f) 1f else 1f + balance
        if (leftGain == 1f && rightGain == 1f) return
        for (f in 0 until frames) {
            val i = offset + f * channels
            pcm[i] = (pcm[i] * leftGain).toInt().coerceIn(-32768, 32767).toShort()
            if (channels > 1) {
                pcm[i + 1] = (pcm[i + 1] * rightGain).toInt().coerceIn(-32768, 32767).toShort()
            }
        }
    }
}
