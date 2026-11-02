// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.engine

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * The timeline clock of 11 Clock and drift compensation: position from frames
 * the device callback confirmed, not from wall time.
 *
 * The engine thread pushes one [Marker] per block written to the ring; readers
 * map `framesPlayed` back through the markers. Position must not advance
 * through underrun silence, which is why [framesPlayed] counts media frames
 * only (ndmedia counts underrun frames separately).
 */
internal class TimelineClock(
    private val deviceRate: Int,
    private val latencyFrames: Long,
    private val framesPlayed: () -> Long,
) {
    /** One written block: which media time entered the ring at which outFrame. */
    data class Marker(
        val outFrame: Long,
        val episodeId: String,
        val mediaTimeUs: Long,
        val speed: Float,
    )

    private val markers = AtomicReferenceArray<Marker?>(MARKER_COUNT)
    private val writeIndex = AtomicInteger(0)
    private val pausedPositionUs = AtomicLong(0)

    // -1 while playing; the item's end position once the source is consumed.
    private val endPositionUs = AtomicLong(-1)

    fun push(marker: Marker) {
        markers.set(writeIndex.getAndIncrement() % MARKER_COUNT, marker)
    }

    /** Position in ms: the latest marker at or before played-minus-latency. */
    fun positionMs(): Long {
        // Once the item ended, position is its consumed media time: skipped
        // silence writes no markers, so nothing anchors the tail otherwise.
        val end = endPositionUs.get()
        if (end >= 0) return end / 1000
        val played = framesPlayed() - latencyFrames
        var best: Marker? = null
        for (i in 0 until MARKER_COUNT) {
            val m = markers.get(i) ?: continue
            if (m.outFrame <= played && (best == null || m.outFrame > best.outFrame)) best = m
        }
        val anchor = best ?: return pausedPositionUs.get() / 1000
        val delta =
            (
                (played - anchor.outFrame).coerceAtLeast(0) *
                    (anchor.speed.toDouble() * 1_000_000 / deviceRate)
            ).toLong()
        return (anchor.mediaTimeUs + delta) / 1000
    }

    fun pause() {
        pausedPositionUs.set(positionMs() * 1000)
    }

    /** Pins the position to [endUs]: the consumed media time at end of stream. */
    fun finish(endUs: Long) {
        endPositionUs.set(endUs)
    }

    fun clear() {
        for (i in 0 until MARKER_COUNT) markers.set(i, null)
        writeIndex.set(0)
        endPositionUs.set(-1)
    }

    private companion object {
        const val MARKER_COUNT = 512
    }
}
