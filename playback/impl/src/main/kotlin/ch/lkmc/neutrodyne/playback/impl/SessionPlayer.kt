// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import ch.lkmc.neutrodyne.core.model.settings.HardwareButtons
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.guava.future

/** Session transports go through this wrapper; internal projection uses the underlying player. */
internal class SessionPlayer private constructor(
    private val exo: Player,
    private val scope: CoroutineScope,
    private val beforePlay: suspend () -> Boolean,
    private val previous: suspend () -> Unit,
    private val stopAction: suspend () -> Unit,
    private val sessionSource: SessionSource,
    private val hardwareButtons: () -> HardwareButtons,
    private val userPaused: () -> Unit,
    private val transportProtocol: () -> TransportProtocol,
) : ForwardingSimpleBasePlayer(sessionSource) {
    constructor(
        exo: Player,
        scope: CoroutineScope,
        beforePlay: suspend () -> Boolean,
        previous: suspend () -> Unit,
        stopAction: suspend () -> Unit,
        hardwareButtons: () -> HardwareButtons = { HardwareButtons.EPISODE },
        userPaused: () -> Unit = {},
        transportProtocol: () -> TransportProtocol = { TransportProtocol.MEDIA3 },
    ) : this(
        exo,
        scope,
        beforePlay,
        previous,
        stopAction,
        SessionSource(exo),
        hardwareButtons,
        userPaused,
        transportProtocol,
    )

    /** The session exists during database open, but its callbacks must follow position capture. */
    fun orderAfterPlaybackAdapters() {
        sessionSource.orderAfterPlaybackAdapters()
    }

    override fun getState(): State {
        val state = super.getState()
        val commands =
            state.availableCommands
                .buildUpon()
                .remove(Player.COMMAND_SET_REPEAT_MODE)
                .remove(Player.COMMAND_SET_SHUFFLE_MODE)
                .remove(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)
        if (exo.mediaItemCount > 0) {
            commands.addAll(
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            )
        }
        return state.buildUpon().setAvailableCommands(commands.build()).build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (!playWhenReady) {
            userPaused()
            exo.pause()
            return Futures.immediateVoidFuture()
        }

        return scope.future {
            if (beforePlay()) exo.play()
            Unit
        }
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        // Media3 1.11 legacy skip controls prefer MEDIA_ITEM commands. Legacy has no separate
        // explicit episode transport, so recover its hardware meaning at this boundary.
        val command =
            if (transportProtocol() == TransportProtocol.LEGACY) {
                when (seekCommand) {
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> Player.COMMAND_SEEK_TO_NEXT
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> Player.COMMAND_SEEK_TO_PREVIOUS
                    else -> seekCommand
                }
            } else {
                seekCommand
            }
        return when (command) {
            Player.COMMAND_SEEK_TO_PREVIOUS -> {
                if (hardwareButtons() == HardwareButtons.SKIP) {
                    exo.seekBack()
                    Futures.immediateVoidFuture()
                } else {
                    scope.future { previous() }
                }
            }

            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                scope.future { previous() }
            }

            Player.COMMAND_SEEK_TO_NEXT -> {
                if (hardwareButtons() == HardwareButtons.SKIP) {
                    exo.seekForward()
                } else if (exo.hasNextMediaItem()) {
                    exo.seekToNextMediaItem()
                }
                Futures.immediateVoidFuture()
            }

            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                if (exo.hasNextMediaItem()) exo.seekToNextMediaItem()
                Futures.immediateVoidFuture()
            }

            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM -> {
                val duration = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 }
                exo.seekTo(duration?.let { positionMs.coerceIn(0, it) } ?: positionMs.coerceAtLeast(0))
                Futures.immediateVoidFuture()
            }

            else -> {
                super.handleSeek(mediaItemIndex, positionMs, seekCommand)
            }
        }
    }

    override fun handleStop(): ListenableFuture<*> = scope.future { stopAction() }
}

/** Media3 installs its forwarding listener during construction, before the database-backed adapters. */
private class SessionSource(
    player: Player,
) : ForwardingPlayer(player) {
    private val listeners = linkedSetOf<Player.Listener>()

    override fun addListener(listener: Player.Listener) {
        listeners += listener
        super.addListener(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners -= listener
        super.removeListener(listener)
    }

    fun orderAfterPlaybackAdapters() {
        listeners.forEach {
            super.removeListener(it)
            super.addListener(it)
        }
    }
}

internal enum class TransportProtocol { MEDIA3, LEGACY }

internal fun MediaSession?.transportProtocol(): TransportProtocol =
    if (this?.controllerForCurrentRequest?.controllerVersion == MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION) {
        TransportProtocol.LEGACY
    } else {
        TransportProtocol.MEDIA3
    }
