// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.model.SleepTimerMode
import ch.lkmc.neutrodyne.core.model.SleepTimerState
import ch.lkmc.neutrodyne.playback.api.PlaybackEvent
import ch.lkmc.neutrodyne.playback.core.SleepTimerCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Service-owned adapter. Account elapsed time before advancement changes. */
internal class SleepTimer(
    private val player: Player,
    private val arbiter: EndOfItemPauseArbiter,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val publish: (SleepTimerState) -> Unit,
    private val event: (PlaybackEvent) -> Unit,
) : Player.Listener {
    private val core = SleepTimerCore()
    private var sampledAt = 0L
    private var ticker: Job? = null

    fun attach() {
        checkPlayerThread(player)
        sampledAt = clock.elapsedRealtime()
        core.advancing(player.isPlaying)
        player.addListener(this)
        ticker =
            scope.launch {
                while (true) {
                    delay(SleepTimerCore.STEP_MS)
                    sample()
                }
            }
    }

    fun set(mode: SleepTimerMode) {
        if (mode is SleepTimerMode.Minutes) require(SleepTimerMode.isValidMinutes(mode.minutes))
        sample()
        apply(core.set(mode, player.episodeIdOrNull()))
    }

    fun extend(minutes: Int) {
        require(SleepTimerMode.isValidMinutes(minutes))
        sample()
        apply(core.extend(minutes))
    }

    private fun sample() {
        val now = clock.elapsedRealtime()
        val elapsed = (now - sampledAt).coerceAtLeast(0)
        sampledAt = now
        apply(core.tick(elapsed))
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        sample()
        apply(core.advancing(isPlaying))
    }

    override fun onPlayWhenReadyChanged(
        playWhenReady: Boolean,
        reason: Int,
    ) {
        sample()
        if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
            apply(core.endOfItem())
        } else if (!playWhenReady) {
            apply(core.paused())
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) apply(core.endOfItem())
    }

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
    ) {
        sample()
        apply(core.currentChanged(player.episodeIdOrNull()))
    }

    private fun apply(effects: List<SleepTimerCore.Effect>) {
        effects.forEach {
            when (it) {
                is SleepTimerCore.Effect.Fade -> player.volume = it.factor
                is SleepTimerCore.Effect.ArmEndOfItem -> arbiter.sleepTimerArmed = it.armed
                SleepTimerCore.Effect.Pause -> player.pause()
                SleepTimerCore.Effect.Fired -> event(PlaybackEvent.SleepTimerFired)
            }
        }
        publish(core.state)
    }

    fun detach() {
        ticker?.cancel()
        ticker = null
        player.removeListener(this)
        apply(core.set(SleepTimerMode.Off, null))
    }
}
