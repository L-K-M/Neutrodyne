// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import ch.lkmc.neutrodyne.playback.api.NowPlaying
import kotlinx.coroutines.flow.Flow

/**
 * The OS media-session seam (11 Media session integration): SMTC on Windows,
 * Now Playing on macOS, MPRIS on Linux. Implementations translate OS media
 * keys and property queries into [commands]; the player answers on its own
 * thread — delivery is posted, never run on the OS callback thread.
 */
interface SystemMediaSession : AutoCloseable {
    /**
     * Publishes the current item; null clears the session. [skipBackMs] and
     * [skipForwardMs] label the skip buttons where the OS exposes them and
     * [speedPresets] the rate picker; neither is an MPRIS concept, so a Linux
     * session may ignore all three.
     */
    fun publish(
        nowPlaying: NowPlaying?,
        skipBackMs: Long,
        skipForwardMs: Long,
        speedPresets: List<Float>,
    )

    /** Commands the OS session sent; the player consumes these. */
    val commands: Flow<RemoteCommand>
}

/** Media keys and session property writes, normalised per OS. */
sealed interface RemoteCommand {
    data object Play : RemoteCommand

    data object Pause : RemoteCommand

    data object Toggle : RemoteCommand

    data object Next : RemoteCommand

    data object Previous : RemoteCommand

    data object SkipForward : RemoteCommand

    data object SkipBack : RemoteCommand

    data class SeekTo(
        val positionMs: Long,
    ) : RemoteCommand

    data class SetRate(
        val rate: Float,
    ) : RemoteCommand
}
