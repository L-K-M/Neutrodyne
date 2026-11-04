// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.domain.YouTubeAvailabilityRecorder
import ch.lkmc.neutrodyne.core.model.ExternalReason
import ch.lkmc.neutrodyne.playback.api.PlaybackEvent
import ch.lkmc.neutrodyne.playback.api.PlaybackIssue
import ch.lkmc.neutrodyne.playback.api.StreamKind
import ch.lkmc.neutrodyne.playback.api.UnplayableReason
import ch.lkmc.neutrodyne.playback.core.RecoveryBudget
import ch.lkmc.neutrodyne.youtube.api.ResolveResult
import ch.lkmc.neutrodyne.youtube.api.TransientKind
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.youtube.api.YouTubeResolveException
import kotlinx.coroutines.CancellationException
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
    private val reportMissing: suspend (Long) -> Unit = {},
    private val recorder: YouTubeAvailabilityRecorder,
    private val capabilities: YouTubeCapabilitiesSource,
    private val coreSeek: (() -> Unit) -> Unit,
    private val allowStream: suspend (Long) -> Boolean = { true },
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
    private val formatRetried = mutableSetOf<Long>()
    private var occurrence = 0L
    private val audioRetried = mutableSetOf<Long>()
    private val localRetried = mutableSetOf<Long>()
    private var missingRecovery: Job? = null
    private var availabilityRecovery: Job? = null
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
        availabilityRecovery?.cancel()
        availabilityRecovery = null
        val causes = error.causeChain()
        val http = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
        val changed = causes.filterIsInstance<YouTubeFormatChangedException>().firstOrNull()
        val youtube = causes.filterIsInstance<YouTubeResolveException>().firstOrNull()
        if (changed != null) {
            if (formatRetried.add(id)) {
                reprepare(id, PinRecovery.RELEASE, changed)
            } else {
                skip(id, UnplayableReason.YouTubeExtraction)
            }
            return
        }
        if (youtube != null) {
            when (val result = youtube.result) {
                is ResolveResult.Unavailable -> {
                    val captured = occurrence
                    availabilityRecovery?.cancel()
                    availabilityRecovery =
                        playerScope.launch {
                            try {
                                recorder.record(id, result.reason)
                                if (player === live && live.episodeIdOrNull() == id && captured == occurrence) {
                                    skip(id, UnplayableReason.YouTube(result.reason))
                                }
                            } catch (
                                cancelled: CancellationException,
                            ) {
                                throw cancelled
                            } catch (
                                _: Exception,
                            ) {
                                if (player === live && captured == occurrence) pause()
                            }
                        }
                }

                is ResolveResult.Transient -> {
                    when (result.kind) {
                        TransientKind.EXTRACTION -> {
                            skip(id, UnplayableReason.YouTubeExtraction)
                        }

                        TransientKind.BREAKER_OPEN -> {
                            mutableIssue.value = PlaybackIssue.YOUTUBE_BREAKER_OPEN
                            skip(id, UnplayableReason.YouTubeExtraction, skipRemoteYouTube = true)
                        }

                        TransientKind.RATE_LIMITED -> {
                            mutableIssue.value = PlaybackIssue.YOUTUBE_RATE_LIMITED
                            live.pause()
                        }

                        TransientKind.NETWORK, TransientKind.TIMEOUT, TransientKind.ENGINE_UNAVAILABLE -> {
                            if (network.status.value.isConnected && network.status.value.isValidated) {
                                pause()
                            } else {
                                awaitNetwork(live, id)
                            }
                        }
                    }
                }

                ResolveResult.Unsupported -> {
                    skip(
                        id,
                        UnplayableReason.YouTubeExternal(
                            capabilities.capabilities.value.externalReason ?: ExternalReason.NOT_YET_AVAILABLE,
                        ),
                    )
                }

                is ResolveResult.Ok -> {
                    pause()
                }
            }
            return
        }
        if (resolver.streamKind(id) == StreamKind.YOUTUBE && http != null) {
            // Exhausted media URL retries are never RSS authentication or missing-feed errors.
            pause()
            return
        }
        val noMedia = causes.any { it is EpisodeGoneException || it is NoMediaException }
        when {
            error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND &&
                resolver.streamKind(id) == StreamKind.LOCAL -> {
                mutableIssue.value = PlaybackIssue.LOCAL_FILE_MISSING
                if (missingRecovery?.isActive == true) return
                if (!localRetried.add(id) || !budget.tryReprepare(id)) {
                    live.pause()
                    return
                }
                val position = live.currentPosition.coerceAtLeast(0)
                live.stop()
                resolver.unpin(id)
                missingRecovery =
                    playerScope.launch {
                        try {
                            // Recheck/journal before another open; never retry the same vanished pin.
                            reportMissing(id)
                            if (player !== live || live.episodeIdOrNull() != id) return@launch
                            if (!network.status.value.isConnected || !allowStream(id)) {
                                live.pause()
                                return@launch
                            }
                            coreSeek { live.seekTo(position) }
                            live.prepare()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            if (player === live && live.episodeIdOrNull() == id) live.pause()
                        }
                    }
            }

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
                awaitNetwork(live, id)
            }

            else -> {
                pause()
            }
        }
    }

    private fun awaitNetwork(
        live: Player,
        id: Long,
    ) {
        mutableIssue.value = PlaybackIssue.NETWORK_LOST
        reconnect = Reconnect(id, clock.elapsedRealtime(), live.playWhenReady)
        expiry =
            playerScope.launch {
                delay(FOREGROUND_WINDOW_MS)
                cancelReconnect()
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
        change: YouTubeFormatChangedException? = null,
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
            resolver.repin(id, change)
            coreSeek { live.seekTo(position) }
        }
        live.prepare()
    }

    private fun skip(
        id: Long,
        reason: UnplayableReason,
        skipRemoteYouTube: Boolean = false,
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
        if (skipRemoteYouTube) {
            projector.advancePastRemoteYouTube { next ->
                if (!budget.trySkip()) {
                    pause()
                    false
                } else {
                    emitEvent(PlaybackEvent.Skipped(next, index[next]?.title.orEmpty(), reason))
                    true
                }
            }
        } else {
            projector.advance()
        }
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
        occurrence++
        availabilityRecovery?.cancel()
        availabilityRecovery = null
        cancelReconnect()
        missingRecovery?.cancel()
        missingRecovery = null
        val id = player?.episodeIdOrNull()
        contentRetried.retainAll(setOfNotNull(id))
        formatRetried.clear()
        audioRetried.retainAll(setOfNotNull(id))
        localRetried.retainAll(setOfNotNull(id))
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
        occurrence++
        availabilityRecovery?.cancel()
        availabilityRecovery = null
        formatRetried.clear()
        contentRetried.clear()
        audioRetried.clear()
        localRetried.clear()
        missingRecovery?.cancel()
        missingRecovery = null
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

internal fun Throwable.causeChain(): List<Throwable> {
    val seen = mutableSetOf<Throwable>()
    var next: Throwable? = this
    while (next != null && seen.size < 64 && seen.add(next)) next = next.cause
    return seen.toList()
}
