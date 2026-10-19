// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.playback.api.PlaybackEvent
import ch.lkmc.neutrodyne.playback.api.PlaybackIssue
import ch.lkmc.neutrodyne.playback.api.UnplayableReason
import ch.lkmc.neutrodyne.playback.core.RecoveryBudget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

/** Classification is Android-specific; automatic retry/skip limits remain in RecoveryBudget. */
internal class ErrorRecovery(
    private val budget: RecoveryBudget,
    private val network: NetworkMonitor,
    private val clock: Clock,
    private val resolver: EpisodeResolver,
    private val index: EpisodeSourceIndex,
    private val projector: QueueProjector,
    private val playerScope: CoroutineScope,
    private val isForeground: () -> Boolean,
    private val emitEvent: (PlaybackEvent) -> Unit,
) : Player.Listener {
    private data class Reconnect(
        val episodeId: Long,
        val failedAt: Long,
        val resume: Boolean,
    )

    private var player: Player? = null
    private var observing: Job? = null
    private var expiry: Job? = null
    private var reconnect: Reconnect? = null
    private val contentRetried = mutableSetOf<Long>()
    private val audioRetried = mutableSetOf<Long>()
    private val mutableIssue = MutableStateFlow<PlaybackIssue?>(null)
    val issue = mutableIssue.asStateFlow()

    fun attach(player: Player) {
        check(this.player == null)
        checkPlayerThread(player)
        this.player = player
        player.addListener(this)
        observing =
            playerScope.launch {
                network.status.collect { status ->
                    if (status.isConnected && status.isValidated) reconnectIfAllowed()
                }
            }
    }

    override fun onPlayerError(error: PlaybackException) {
        val live = player ?: return
        val id = live.episodeIdOrNull() ?: return
        checkPlayerThread(live)
        cancelReconnect()
        val causes = error.causeChain()
        val http = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
        val noMedia = causes.any { it is EpisodeGoneException || it is NoMediaException }
        when {
            noMedia -> {
                skip(id, UnplayableReason.NoMedia)
            }

            causes.any { it is ContentChangedException } || http == HTTP_RANGE_NOT_SATISFIABLE -> {
                if (contentRetried.add(
                        id,
                    )
                ) {
                    reprepare(
                        id,
                        releasePin = PinRecovery.RELEASE,
                    )
                } else {
                    skip(id, UnplayableReason.Http(HTTP_RANGE_NOT_SATISFIABLE))
                }
            }

            http == HTTP_NOT_FOUND || http == HTTP_GONE -> {
                skip(id, UnplayableReason.Http(http))
            }

            http == HTTP_UNAUTHORIZED || http == HTTP_FORBIDDEN -> {
                skip(id, UnplayableReason.AuthRequired)
            }

            error.errorCode in PARSING_OR_DECODING_CODES || causes.any { it is UnrecognizedInputFormatException } -> {
                skip(id, UnplayableReason.UnsupportedFormat)
            }

            error.errorCode in AUDIO_TRACK_CODES -> {
                mutableIssue.value = PlaybackIssue.PLAYER_ERROR
                if (audioRetried.add(id)) reprepare(id) else pause()
            }

            error.errorCode in NETWORK_CODES || causes.any { it is IOException } -> {
                mutableIssue.value = PlaybackIssue.NETWORK_LOST
                reconnect = Reconnect(id, clock.elapsedRealtime(), live.playWhenReady)
                expiry =
                    playerScope.launch {
                        delay(FOREGROUND_WINDOW_MS)
                        cancelReconnect()
                    }
            }

            else -> {
                pause()
            }
        }
    }

    private fun reconnectIfAllowed() {
        val pending = reconnect ?: return
        val live = player ?: return
        if (live.episodeIdOrNull() != pending.episodeId || !live.playWhenReady || !pending.resume ||
            !isForeground() || clock.elapsedRealtime() - pending.failedAt >= FOREGROUND_WINDOW_MS
        ) {
            cancelReconnect()
            return
        }
        cancelReconnect()
        reprepare(pending.episodeId)
    }

    private enum class PinRecovery { KEEP, RELEASE }

    private fun reprepare(
        id: Long,
        releasePin: PinRecovery = PinRecovery.KEEP,
    ) {
        val live = player ?: return
        if (!isForeground() || !budget.tryReprepare(id)) {
            pause()
            return
        }
        if (releasePin == PinRecovery.RELEASE) {
            // Cancel loaders before invalidating the rendition; a new RSS pin resets its resource.
            val position = live.currentPosition.coerceAtLeast(0)
            live.stop()
            resolver.unpin(id)
            live.seekTo(position)
        }
        live.prepare()
    }

    private fun skip(
        id: Long,
        reason: UnplayableReason,
    ) {
        val live = player ?: return
        if (!budget.trySkip()) {
            pause()
            return
        }
        emitEvent(PlaybackEvent.Skipped(id, index[id]?.title.orEmpty(), reason))
        if (!live.hasNextMediaItem()) {
            live.stop()
            mutableIssue.value = PlaybackIssue.PLAYER_ERROR
            return
        }
        projector.advance()
        // The projector prepares only after the arriving item's durable start seek/settings.
    }

    private fun pause() {
        mutableIssue.value = PlaybackIssue.PLAYER_ERROR
        player?.pause()
    }

    override fun onPlayWhenReadyChanged(
        playWhenReady: Boolean,
        reason: Int,
    ) {
        if (!playWhenReady) cancelReconnect()
    }

    override fun onMediaItemTransition(
        mediaItem: androidx.media3.common.MediaItem?,
        reason: Int,
    ) {
        cancelReconnect()
        val id = player?.episodeIdOrNull()
        contentRetried.retainAll(setOfNotNull(id))
        audioRetried.retainAll(setOfNotNull(id))
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (!isPlaying) return
        budget.onPlaying()
        mutableIssue.value = null
        cancelReconnect()
    }

    private fun cancelReconnect() {
        reconnect = null
        expiry?.cancel()
        expiry = null
    }

    fun detach() {
        player?.let {
            checkPlayerThread(it)
            it.removeListener(this)
        }
        player = null
        observing?.cancel()
        observing = null
        cancelReconnect()
        contentRetried.clear()
        audioRetried.clear()
        budget.clear()
        mutableIssue.value = null
    }

    private companion object {
        const val FOREGROUND_WINDOW_MS = 10 * 60_000L
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
        const val HTTP_GONE = 410
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
        val NETWORK_CODES =
            setOf(
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            )
        val AUDIO_TRACK_CODES =
            setOf(
                PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
                PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            )
        val PARSING_OR_DECODING_CODES =
            setOf(
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            )
    }
}

private fun Throwable.causeChain(): List<Throwable> {
    val seen = mutableSetOf<Throwable>()
    var next: Throwable? = this
    while (next != null && seen.add(next)) next = next.cause
    return seen.toList()
}
