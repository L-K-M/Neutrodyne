// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.guava.future

/** Session transports go through this wrapper; internal projection uses the underlying player. */
internal class SessionPlayer(
    private val exo: Player,
    private val scope: CoroutineScope,
    private val beforePlay: suspend () -> Boolean,
    private val previous: suspend () -> Unit,
    private val stopAction: suspend () -> Unit,
) : ForwardingSimpleBasePlayer(exo) {
    override fun getState(): State {
        val state = super.getState()
        val commands =
            state.availableCommands
                .buildUpon()
                .remove(Player.COMMAND_SET_REPEAT_MODE)
                .remove(Player.COMMAND_SET_SHUFFLE_MODE)
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
    ): ListenableFuture<*> =
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                scope.future { previous() }
            }

            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
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

    override fun handleStop(): ListenableFuture<*> = scope.future { stopAction() }
}
