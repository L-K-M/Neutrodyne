// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.os.Bundle
import android.os.Process
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.MediaSession.ControllerInfo
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import ch.lkmc.neutrodyne.core.model.SettingScope
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class SessionCallback(
    private val bridge: suspend () -> ServiceBridge,
    private val resumption: ResumptionProvider,
    private val tree: MediaLibraryTree,
    private val buttons: MediaButtons,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val beforeStart: () -> Unit = {},
    private val chapterCommand: (String) -> SessionResult? = { null },
) : LibraryCallbacks(tree, scope, io) {
    private val commands = Mutex()

    override fun onConnectAsync(
        session: MediaSession,
        controller: ControllerInfo,
    ): ListenableFuture<ConnectionResult> {
        val builder =
            ConnectionResult
                .AcceptedResultBuilder(session, controller)
                .setAvailablePlayerCommands(androidx.media3.common.Player.Commands.EMPTY)
                .setAvailableSessionCommands(
                    ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                        .buildUpon()
                        .remove(
                            SessionCommand.COMMAND_CODE_SESSION_SET_RATING,
                        ).build(),
                )
        if (canControl(session, controller)) {
            val custom = ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            CustomCommands.names.forEach { custom.add(CustomCommands.command(it)) }
            builder
                .setAvailablePlayerCommands(
                    ConnectionResult.DEFAULT_PLAYER_COMMANDS
                        .buildUpon()
                        .remove(
                            androidx.media3.common.Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS,
                        ).build(),
                ).setAvailableSessionCommands(custom.build())
            if (session.isMediaNotificationController(controller)) builder.setMediaButtonPreferences(buttons.current())
        }
        return Futures.immediateFuture(builder.build())
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        if (!canControl(
                session,
                controller,
            )
        ) {
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
        }
        return scope.future {
            commands.withLock {
                try {
                    val request =
                        if (customCommand.customAction == CustomCommands.PLAY_CONTEXT) {
                            CustomCommands.decode(args).also { beforeStart() }
                        } else {
                            null
                        }
                    val live = bridge()
                    SleepTimerCommands.handle(customCommand.customAction, args, live)?.let { return@withLock it }
                    chapterCommand(customCommand.customAction)?.let { return@withLock it }
                    when (customCommand.customAction) {
                        CustomCommands.PLAY_CONTEXT -> {
                            CustomCommands.result(live.start(checkNotNull(request), origin(session, controller)))
                        }

                        CustomCommands.SPEED_SET_SCOPE -> {
                            require(args.containsKey("speed"))
                            scopeResult(live.setSpeed(args.getFloat("speed"), settingScope(args)))
                        }

                        CustomCommands.SKIP_SILENCE -> {
                            require(args.containsKey("enabled"))
                            scopeResult(live.setSkipSilence(args.getBoolean("enabled"), settingScope(args)))
                        }

                        CustomCommands.SPEED_CYCLE -> {
                            scopeResult(live.cycleSpeed())
                        }

                        CustomCommands.DISMISS -> {
                            live.dismiss()
                            SessionResult(SessionResult.RESULT_SUCCESS)
                        }

                        else -> {
                            SessionResult(SessionError.ERROR_NOT_SUPPORTED)
                        }
                    }
                } catch (_: IllegalArgumentException) {
                    SessionResult(SessionError.ERROR_BAD_VALUE)
                }
            }
        }
    }

    override fun onPlaybackResumption(
        session: MediaSession,
        controller: ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
        scope.future {
            require(canControl(session, controller))
            commands.withLock {
                if (isForPlayback) beforeStart()
                resumption.resume(isForPlayback, origin(session, controller))
            }
        }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> =
        Futures.immediateFailedFuture(UnsupportedOperationException("Use the durable Up next commands"))

    override fun onSetMediaItems(
        session: MediaSession,
        controller: ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
        scope.future {
            require(canControl(session, controller))
            commands.withLock {
                val request =
                    kotlinx.coroutines.withContext(
                        io,
                    ) { tree.startRequest(mediaItems, startIndex, startPositionMs) }
                beforeStart()
                val live = bridge()
                live.setMediaItems(request, origin(session, controller))
            }
        }

    private fun canControl(
        session: MediaSession,
        controller: ControllerInfo,
    ): Boolean =
        controller.isTrusted ||
            session.isMediaNotificationController(controller) || session.isAutoCompanionController(controller) ||
            session.isAutomotiveController(controller)

    private fun origin(
        session: MediaSession,
        controller: ControllerInfo,
    ): StartOrigin =
        if (!session.isMediaNotificationController(controller) &&
            controller.uid == Process.myUid()
        ) {
            StartOrigin.UI
        } else {
            StartOrigin.EXTERNAL
        }

    private fun settingScope(args: Bundle): SettingScope = SettingScope.valueOf(requireNotNull(args.getString("scope")))

    private fun scopeResult(result: ch.lkmc.neutrodyne.playback.api.ScopeWriteResult): SessionResult =
        SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply { putString(CustomCommands.RESULT, result.name) })

    companion object {
        fun parseBrowseEpisode(id: String): Long = MediaLibraryTree.parseEpisode(id).id
    }
}
