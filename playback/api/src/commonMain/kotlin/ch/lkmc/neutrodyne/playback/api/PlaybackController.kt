// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.playback.api

/**
 * The common player API (06 PlaybackController). M3 declares only [pause] — 05's
 * restore-Replace confirmation is its first caller — and [NoPlaybackController], the
 * no-op binding until M4 lands the real controller and the rest of this interface.
 */
interface PlaybackController {
    /** Pause playback; fire-and-forget on the main thread, safe with nothing playing (06). */
    fun pause()
}

/** The pre-M4 binding: nothing plays yet, so pausing does nothing (05 Replace restore). */
object NoPlaybackController : PlaybackController {
    override fun pause() = Unit
}
