// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.os.Bundle
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.database.DatabaseOpener
import ch.lkmc.neutrodyne.core.domain.PlaySessionStore
import ch.lkmc.neutrodyne.core.domain.QueueRepository
import ch.lkmc.neutrodyne.core.model.AddResult
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.SettingScope
import ch.lkmc.neutrodyne.core.model.SleepTimerMode
import ch.lkmc.neutrodyne.playback.api.PlayResult
import ch.lkmc.neutrodyne.playback.api.PlaybackController
import ch.lkmc.neutrodyne.playback.api.PlaybackEvent
import ch.lkmc.neutrodyne.playback.api.PlaybackSessionCommands
import ch.lkmc.neutrodyne.playback.api.ScopeWriteResult
import ch.lkmc.neutrodyne.playback.core.StartRequest
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@SingleIn(AppScope::class)
@Inject
internal class PlaybackControllerImpl(
    private val connection: PlayerConnection,
    private val hub: PlaybackStateHub,
    private val gate: MeteredStreamingGate,
    private val opener: DatabaseOpener,
    private val queues: Provider<QueueRepository>,
    private val sessions: Provider<PlaySessionStore>,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
    @Dispatcher(NeutrodyneDispatchers.IO) private val io: CoroutineDispatcher,
) : PlaybackController {
    override suspend fun playEpisode(episodeId: Long): PlayResult = start(StartRequest.Episode(episodeId))

    override suspend fun playEpisodeAt(
        episodeId: Long,
        positionMs: Long,
    ): PlayResult =
        if (positionMs < 0) PlayResult.ServiceUnavailable else start(StartRequest.Episode(episodeId, positionMs))

    override suspend fun playFeed(
        source: FeedSource,
        filters: FeedFilters,
        order: FeedOrder,
        startEpisodeId: Long?,
    ): PlayResult = start(StartRequest.Feed(source, filters, order, startEpisodeId))

    override suspend fun play(): PlayResult = start(StartRequest.Resume)

    private suspend fun start(request: StartRequest): PlayResult =
        withContext(Dispatchers.Main.immediate) {
            val controller = connection.awaitController() ?: return@withContext PlayResult.ServiceUnavailable
            val response = command(CustomCommands.PLAY_CONTEXT, CustomCommands.encode(request))
            val result = response?.let(CustomCommands::playResult) ?: PlayResult.ServiceUnavailable
            if (result == PlayResult.Started) controller.play()
            result
        }

    override fun pause() {
        // Restore-Replace and unsubscribe must pause the actual player even without a UI binding.
        hub.bridge?.pause() ?: connection.controller?.pause()
    }

    override fun togglePlayPause() {
        if (hub.bridge?.isPlaying == true) {
            pause()
            return
        }
        scope.launch(Dispatchers.Main.immediate) {
            val result = play()
            if (result != PlayResult.Started) hub.event(PlaybackEvent.StartRefused(result))
        }
    }

    override fun seekTo(positionMs: Long) {
        connection.controller?.let { controller ->
            val duration = controller.duration.takeIf { it > 0 }
            controller.seekTo(duration?.let { positionMs.coerceIn(0, it) } ?: positionMs.coerceAtLeast(0))
        }
    }

    override fun skipBack() {
        connection.controller?.seekBack()
    }

    override fun skipForward() {
        connection.controller?.seekForward()
    }

    override fun skipToNext() {
        connection.controller?.seekToNextMediaItem()
    }

    override fun skipToPrevious() {
        connection.controller?.seekToPreviousMediaItem()
    }

    // W2/W4 install service dispatch; the W1 foundation must not pretend it ran a command.
    override fun setSleepTimer(mode: SleepTimerMode) = Unit

    override fun extendSleepTimer(minutes: Int) = Unit

    override fun nextChapter() {
        scope.launch(Dispatchers.Main.immediate) { command(PlaybackSessionCommands.CHAPTER_NEXT) }
    }

    override fun previousChapter() {
        scope.launch(Dispatchers.Main.immediate) { command(PlaybackSessionCommands.CHAPTER_PREV) }
    }

    override suspend fun setSpeed(
        speed: Float,
        scope: SettingScope,
    ): ScopeWriteResult {
        if (!speed.isFinite()) return ScopeWriteResult.FAILED
        return scopeCommand(
            CustomCommands.SPEED_SET_SCOPE,
            Bundle().apply {
                putFloat("speed", speed)
                putString("scope", scope.name)
            },
        )
    }

    override suspend fun setSkipSilence(
        enabled: Boolean,
        scope: SettingScope,
    ): ScopeWriteResult =
        scopeCommand(
            CustomCommands.SKIP_SILENCE,
            Bundle().apply {
                putBoolean("enabled", enabled)
                putString("scope", scope.name)
            },
        )

    private suspend fun scopeCommand(
        name: String,
        args: Bundle,
    ): ScopeWriteResult =
        withContext(Dispatchers.Main.immediate) {
            connection.awaitController() ?: return@withContext ScopeWriteResult.FAILED
            val result = command(name, args)?.extras?.getString(CustomCommands.RESULT)
            ScopeWriteResult.entries.firstOrNull { it.name == result } ?: ScopeWriteResult.FAILED
        }

    private suspend fun command(
        name: String,
        args: Bundle = Bundle.EMPTY,
    ): androidx.media3.session.SessionResult? =
        try {
            val controller = connection.controller
            if (controller == null) {
                null
            } else {
                withTimeout(COMMAND_TIMEOUT_MS) {
                    controller.sendCustomCommand(CustomCommands.command(name), args).await()
                }
            }
        } catch (_: TimeoutCancellationException) {
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    override suspend fun playNext(episodeIds: List<Long>): AddResult =
        withContext(io) {
            opener.awaitOpen()
            queues().addNext(episodeIds)
        }

    override suspend fun playLast(episodeIds: List<Long>): AddResult =
        withContext(io) {
            opener.awaitOpen()
            queues().addLast(episodeIds)
        }

    override fun grantMeteredStreaming() {
        gate.grant()
    }

    override suspend fun dismiss() =
        withContext(Dispatchers.Main.immediate) {
            gate.clearPending()
            if (connection.controller != null) {
                val result = command(CustomCommands.DISMISS)
                check(
                    result?.resultCode == androidx.media3.session.SessionResult.RESULT_SUCCESS,
                ) { "Playback dismissal failed" }
                return@withContext
            }
            val bridge = hub.bridge
            if (bridge != null) {
                bridge.dismiss()
                return@withContext
            }
            // Dismiss persisted state without a service bind when there is no active player.
            withContext(io) {
                opener.awaitOpen()
                sessions().clearCurrent(null, clock.now())
            }
            Unit
        }

    private companion object {
        const val COMMAND_TIMEOUT_MS = 10_000L
    }
}
