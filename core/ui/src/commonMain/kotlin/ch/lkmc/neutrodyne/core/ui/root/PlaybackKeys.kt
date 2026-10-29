// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.root

import ch.lkmc.neutrodyne.playback.api.NowPlaying
import ch.lkmc.neutrodyne.playback.api.PlaybackController

/** Both shells use the same transports; chapter, volume and feed-page keys have separate owners. */
public fun dispatchPlaybackKey(
    key: PlaybackKey,
    controller: PlaybackController,
    now: NowPlaying?,
): Boolean {
    when (key) {
        PlaybackKey.TOGGLE -> {
            // Buffering is still a play request, so Space must cancel it rather than restart it.
            if (now?.let { it.isPlaying || it.playWhenReady } ==
                true
            ) {
                controller.pause()
            } else {
                controller.togglePlayPause()
            }
        }

        PlaybackKey.SKIP_BACK -> {
            controller.skipBack()
        }

        PlaybackKey.SKIP_FORWARD -> {
            controller.skipForward()
        }

        PlaybackKey.PREVIOUS_EPISODE -> {
            controller.skipToPrevious()
        }

        PlaybackKey.NEXT_EPISODE -> {
            controller.skipToNext()
        }

        else -> {
            return false
        }
    }
    return true
}
