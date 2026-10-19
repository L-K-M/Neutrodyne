// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import androidx.media3.session.MediaSession

/** Media3 performs the one set/prepare for an empty player; the provider only plans the window. */
internal class ResumptionProvider(
    private val bridge: suspend () -> ServiceBridge,
) {
    suspend fun resume(
        isForPlayback: Boolean,
        origin: StartOrigin,
    ): MediaSession.MediaItemsWithStartPosition {
        if (!isForPlayback) throw UnsupportedOperationException("Boot resumption cards arrive in M5")
        return bridge().resume(origin)
    }
}
