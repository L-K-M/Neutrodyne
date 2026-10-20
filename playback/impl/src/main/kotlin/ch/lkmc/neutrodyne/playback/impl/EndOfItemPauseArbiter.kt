// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import androidx.media3.exoplayer.ExoPlayer

/** Both owners contribute; neither may clear the other's end-of-item pause. Main thread only. */
internal class EndOfItemPauseArbiter(
    private val apply: (Boolean) -> Unit,
) {
    constructor(player: ExoPlayer) : this({ player.pauseAtEndOfMediaItems = it })

    var sleepTimerArmed: Boolean = false
        set(value) {
            field = value
            update()
        }
    var blockBeforeNext: Boolean = false
        set(value) {
            field = value
            update()
        }

    private fun update() {
        apply(sleepTimerArmed || blockBeforeNext)
    }
}
