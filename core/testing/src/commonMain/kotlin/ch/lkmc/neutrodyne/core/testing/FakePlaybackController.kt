// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.playback.api.PlaybackController

/** Counts [pause] calls — M3 declares no other member yet (06 PlaybackController). */
class FakePlaybackController : PlaybackController {
    var pauseCalls = 0
        private set

    override fun pause() {
        pauseCalls++
    }
}
