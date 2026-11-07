// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import ch.lkmc.neutrodyne.playback.api.NowPlaying
import kotlinx.coroutines.flow.emptyFlow

/**
 * The session the graph binds when the OS one cannot be created (no session bus, a refused
 * SMTC window, a missing native library; 11 OS-integration failure modes): playback works,
 * media keys do not, and [reason] is what diagnostics shows. Publishes are dropped and no
 * command ever arrives.
 */
class UnavailableSystemMediaSession(
    val reason: String,
) : SystemMediaSession {
    override fun publish(
        nowPlaying: NowPlaying?,
        skipBackMs: Long,
        skipForwardMs: Long,
        speedPresets: List<Float>,
        artworkPath: String?,
    ) = Unit

    override val commands = emptyFlow<RemoteCommand>()

    override fun close() = Unit
}
