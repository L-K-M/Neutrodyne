// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.os.Bundle
import android.os.Process
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.MediaSession.ControllerInfo
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import ch.lkmc.neutrodyne.core.model.SettingScope
import ch.lkmc.neutrodyne.playback.api.PlayResult
import ch.lkmc.neutrodyne.playback.core.StartRequest
import com.google.common.collect.ImmutableList
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
    private val hosts: ControllerHostIdentity,
    private val beforeStart: () -> Unit = {},
) : MediaLibrarySession.Callback {
    private val commands = Mutex()

    override fun onConnectAsync(
        session: MediaSession,
        controller: ControllerInfo,
    ): ListenableFuture<ConnectionResult> {
        val builder = ConnectionResult.AcceptedResultBuilder(session, controller)
        if (canControl(session, controller)) {
            val custom = ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            CustomCommands.names.forEach { custom.add(CustomCommands.command(it)) }
            builder
                .setAvailablePlayerCommands(ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                .setAvailableSessionCommands(custom.build())
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
                require(mediaItems.size == 1) { "M4 accepts one episode start" }
                val id = parseBrowseEpisode(mediaItems.single().mediaId)
                val position = startPositionMs.takeIf { it != C.TIME_UNSET }
                require(position == null || position >= 0)
                beforeStart()
                val live = bridge()
                live.setMediaItems(StartRequest.Episode(id, position), origin(session, controller))
            }
        }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(tree.root(), params))

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        scope.future(io) {
            try {
                LibraryResult.ofItemList(tree.children(parentId, page, pageSize), params)
            } catch (_: IllegalArgumentException) {
                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            }
        }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        scope.future(io) {
            tree.item(mediaId)?.let { LibraryResult.ofItem(it, null) }
                ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

    private fun canControl(
        session: MediaSession,
        controller: ControllerInfo,
    ): Boolean =
        controller.isTrusted ||
            session.isMediaNotificationController(controller) ||
            hosts.isAuthorizedAutoHost(session, controller)

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
        fun parseBrowseEpisode(id: String): Long {
            require(id.matches(Regex("episode:[1-9][0-9]*(@upnext)?")))
            return id.removePrefix("episode:").substringBefore('@').toLong()
        }
    }
}
