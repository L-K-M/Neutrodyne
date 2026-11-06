// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.engine

/**
 * Overflow-safe conversions between a stream's time base and wall-clock units.
 *
 * A corrupt container can carry a huge tick value or a malformed rational, and
 * the naive `ticks * num * scale / den` wraps silently before the division. The
 * engine must fail loudly or clamp, never publish a wrapped timestamp (11 Demux
 * and decode). Every helper here saturates at the [Long] range instead.
 */
internal object FfmpegTime {
    const val MICROS_PER_SECOND = 1_000_000L
    const val MILLISECONDS_PER_SECOND = 1_000L

    /** `ticks * numerator / denominator` in [scale] units per second, saturated. */
    fun ticksToScaled(
        ticks: Long,
        numerator: Int,
        denominator: Int,
        scale: Long,
    ): Long {
        if (denominator <= 0) return 0L
        return try {
            Math.multiplyExact(Math.multiplyExact(ticks, numerator.toLong()), scale) / denominator
        } catch (_: ArithmeticException) {
            if (ticks >= 0) Long.MAX_VALUE else Long.MIN_VALUE
        }
    }

    /** A stream position in milliseconds back to the stream's own ticks, saturated. */
    fun msToTicks(
        positionMs: Long,
        numerator: Int,
        denominator: Int,
    ): Long {
        if (numerator <= 0 || denominator <= 0) return 0L
        return try {
            val scale = Math.multiplyExact(MILLISECONDS_PER_SECOND, numerator.toLong())
            Math.multiplyExact(positionMs, denominator.toLong()) / scale
        } catch (_: ArithmeticException) {
            if (positionMs >= 0) Long.MAX_VALUE else Long.MIN_VALUE
        }
    }
}
