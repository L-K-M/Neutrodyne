// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import androidx.media3.common.C
import androidx.media3.common.Player
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.playback.core.PlaybackExit
import ch.lkmc.neutrodyne.playback.core.PositionCapture
import ch.lkmc.neutrodyne.playback.core.PositionSaveResult
import ch.lkmc.neutrodyne.playback.core.PositionSaver
import ch.lkmc.neutrodyne.playback.core.SeekOrigin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class PositionFlushReason { PAUSE, SEEK, DESTROY }

/**
 * Player-thread confined. W8 attaches this before the projector and other state listeners.
 * Resolve database-backed constructor arguments on IO after DatabaseOpener.awaitOpen(). The
 * service owns playerScope; applicationScope must outlive it. No teardown job reads the player.
 */
internal class PositionTracker(
    private val saver: PositionSaver,
    private val clock: Clock,
    private val playerScope: CoroutineScope,
    private val applicationScope: CoroutineScope,
    private val streamSource: (Long) -> PositionSource = { PositionSource.STREAM },
    private val reportFailure: (Exception) -> Unit = { android.util.Log.w(TAG, "Position save failed") },
) : Player.Listener {
    private var player: Player? = null
    private var ticker: Job? = null
    private var coreSeekDepth = 0
    private var outgoing: PositionCapture? = null
    private var loadedId: Long? = null
    private var loadedDurationMs: Long? = null
    var currentSinceMs: Long = 0
        private set

    fun attach(player: Player) {
        check(this.player == null)
        checkPlayerThread(player)
        this.player = player
        player.addListener(this)
        ticker =
            playerScope.launch {
                while (true) {
                    delay(SAVE_INTERVAL_MS)
                    val live = this@PositionTracker.player ?: break
                    val position = live.currentPosition.coerceAtLeast(0)
                    val duration = live.knownDuration()
                    if (live.isPlaying) result(saver.onTick(position, duration, true))
                }
            }
    }

    /** Ready stays false until the durable start seek is applied, including asynchronous arrivals. */
    fun load(
        episodeId: Long,
        replay: Boolean,
        durationMs: Long? = null,
        allowed: () -> Boolean = { true },
    ) {
        loadedId = episodeId
        loadedDurationMs = durationMs
        currentSinceMs = clock.now()
        saver.load(episodeId, currentSinceMs, streamSource(episodeId), replay, allowed)
    }

    fun startPositionApplied() {
        saver.startPositionApplied()
        if (player?.isPlaying == true) onIsPlayingChanged(true)
    }

    fun clear() {
        loadedId = null
        loadedDurationMs = null
        saver.clear()
    }

    /** Suppress only synchronous setup seeks; user seeks keep the explicit reset semantics. */
    fun coreSeek(block: () -> Unit) {
        coreSeekDepth++
        try {
            block()
        } finally {
            coreSeekDepth--
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (player?.episodeIdOrNull() == loadedId) player?.knownDuration()?.let { loadedDurationMs = it }
    }

    fun capture(exit: PlaybackExit = PlaybackExit.PAUSE): PositionCapture? {
        val live = player ?: return null
        checkPlayerThread(live)
        return saver.capture(live.currentPosition.coerceAtLeast(0), live.knownDuration(), exit)
    }

    /** The projector consumes this in the same discontinuity dispatch, before loading arrival. */
    fun takeOutgoing(): PositionCapture? = outgoing.also { outgoing = null }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (oldPosition.mediaItem?.mediaId != newPosition.mediaItem?.mediaId) {
            val exit =
                if (reason ==
                    Player.DISCONTINUITY_REASON_AUTO_TRANSITION
                ) {
                    PlaybackExit.AUTO
                } else {
                    PlaybackExit.SEEK
                }
            outgoing = saver.capture(oldPosition.positionMs.coerceAtLeast(0), loadedDurationMs, exit)
            // Capture before clearing: later listeners can already see the arriving timeline.
            outgoing?.let(::persist)
            clear()
            return
        }
        if (reason != Player.DISCONTINUITY_REASON_SEEK || coreSeekDepth != 0) return
        val position = newPosition.positionMs.coerceAtLeast(0)
        val duration = player?.knownDuration()
        playerScope.launch(start = CoroutineStart.UNDISPATCHED) {
            result(saver.onSeek(position, duration, SeekOrigin.USER))
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            playerScope.launch(start = CoroutineStart.UNDISPATCHED) { result(saver.onPlaying()) }
        } else {
            flush(PositionFlushReason.PAUSE)
        }
    }

    override fun onPlayWhenReadyChanged(
        playWhenReady: Boolean,
        reason: Int,
    ) {
        if (!playWhenReady) flush(PositionFlushReason.PAUSE)
    }

    fun flush(reason: PositionFlushReason): PositionCapture? {
        val snapshot = capture() ?: return null
        if (reason != PositionFlushReason.DESTROY) persist(snapshot)
        return snapshot
    }

    /** Call with flush(DESTROY) captured on main, before releasing/cancelling the service. */
    fun detach(teardownSnapshot: PositionCapture? = capture()): Job? {
        player?.let {
            checkPlayerThread(it)
            it.removeListener(this)
        }
        player = null
        ticker?.cancel()
        ticker = null
        outgoing = null
        clear()
        return teardownSnapshot?.let { snapshot ->
            applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(NonCancellable) { result(saver.flush(snapshot)) }
            }
        }
    }

    private fun persist(snapshot: PositionCapture) {
        playerScope.launch(start = CoroutineStart.UNDISPATCHED) { result(saver.flush(snapshot)) }
    }

    private fun result(result: PositionSaveResult) {
        if (result is PositionSaveResult.Failed) reportFailure(result.cause)
    }

    private companion object {
        const val SAVE_INTERVAL_MS = 5_000L
        const val TAG = "PositionTracker"
    }
}

internal fun Player.knownDuration(): Long? = duration.takeIf { it != C.TIME_UNSET && it > 0 }

internal fun Player.episodeIdOrNull(): Long? = currentMediaItem?.mediaId?.removePrefix("episode:")?.toLongOrNull()

internal fun checkPlayerThread(player: Player) {
    check(android.os.Looper.myLooper() == player.applicationLooper) { "Playback adapter requires the player thread" }
}
