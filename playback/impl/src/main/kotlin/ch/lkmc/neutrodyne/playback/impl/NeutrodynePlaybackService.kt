// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import ch.lkmc.neutrodyne.core.common.GraphHolder
import ch.lkmc.neutrodyne.playback.api.PlaybackIssue
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** One player per service. Framework creation and session connection do not synchronously open Room. */
public class NeutrodynePlaybackService : MediaLibraryService() {
    @Inject public lateinit var access: PlaybackServiceAccess
    private var session: MediaLibrarySession? = null
    private var sessionPlayer: SessionPlayer? = null
    private var exo: ExoPlayer? = null
    private var bridge: AndroidServiceBridge? = null
    private var starting: Deferred<AndroidServiceBridge>? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var startReserved = false

    override fun onCreate() {
        super.onCreate()
        ((applicationContext as GraphHolder).graph as PlaybackServiceInjector).inject(this)
        val app = applicationContext as Application
        val notification = TapToResumeNotifier(app)
        PlaybackChannels.create(app)
        access.resolver.clearPins()
        val factory = access.playerFactory()
        val player = factory.create().also { exo = it }
        factory.bindPrefs(player, serviceScope)
        val buttons = MediaButtons(access.prefs) { player.playbackParameters.speed }
        val runtime: suspend () -> ServiceBridge = { checkNotNull(starting).await() }
        val wrapper =
            SessionPlayer(
                player,
                serviceScope,
                beforePlay = {
                    val origin = requestOrigin()
                    runtime().beforePlay(origin)
                },
                previous = { runtime().previous() },
                stopAction = {
                    runtime().stop()
                    pauseAllPlayersAndStopSelf()
                },
            ).also { sessionPlayer = it }
        val callback =
            SessionCallback(
                runtime,
                ResumptionProvider(runtime),
                access.tree,
                buttons,
                serviceScope,
                access.io,
                ControllerHostIdentity(packageManager),
                beforeStart = {
                    startReserved = true
                    bridge?.reserveStart()
                },
            )
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider
                .Builder(this)
                .setChannelId(PlaybackChannels.PLAYBACK)
                .setChannelName(R.string.channel_playback)
                .setNotificationId(
                    PlaybackChannels.NOTIF_ID_PLAYBACK,
                ).build()
                .apply { setSmallIcon(R.drawable.ic_stat_neutrodyne) },
        )
        setListener(
            object : MediaSessionService.Listener {
                override fun onForegroundServiceStartNotAllowedException() {
                    // Pause immediately, before any suspend or permission-dependent recovery UI.
                    bridge?.pause() ?: player.pause()
                    bridge?.issue(PlaybackIssue.PLAYER_ERROR)
                    notification.post()
                }
            },
        )
        PlaybackTestHooks.foregroundServiceTimeoutMs?.let(::setForegroundServiceTimeoutMs)
        session =
            MediaLibrarySession
                .Builder(this, wrapper, callback)
                .setId(SESSION_ID)
                .setSessionActivity(
                    TapToResumeNotifier.openPlayer(app),
                ).setMediaButtonPreferences(buttons.current())
                .build()
        starting =
            serviceScope.async {
                val ports = access.open()
                AndroidServiceBridge(
                    player,
                    ports,
                    access,
                    serviceScope,
                    isForeground = {
                        isPlaybackOngoing ||
                            ProcessLifecycleOwner
                                .get()
                                .lifecycle.currentState
                                .isAtLeast(Lifecycle.State.STARTED)
                    },
                    stopService = { pauseAllPlayersAndStopSelf() },
                ).also { live ->
                    bridge = live
                    live.attach(hasPendingStart = startReserved)
                    wrapper.orderAfterPlaybackAdapters()
                    player.addListener(
                        object : Player.Listener {
                            override fun onIsPlayingChanged(isPlaying: Boolean) {
                                if (isPlaying) notification.cancel()
                            }
                        },
                    )
                    access.hub.attach(live)
                    serviceScope.launch {
                        access.prefs.current.collect {
                            session?.setMediaButtonPreferences(
                                buttons.current(player.playbackParameters.speed),
                            )
                        }
                    }
                    serviceScope.launch {
                        live.effectivePlayback.collect {
                            session?.setMediaButtonPreferences(
                                buttons.current(it?.speed?.value ?: 1f),
                            )
                        }
                    }
                }
            }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    private fun requestOrigin(): StartOrigin {
        val session = session ?: return StartOrigin.EXTERNAL
        val controller = session.controllerForCurrentRequest ?: return StartOrigin.EXTERNAL
        return if (!session.isMediaNotificationController(controller) &&
            controller.uid == android.os.Process.myUid()
        ) {
            StartOrigin.UI
        } else {
            StartOrigin.EXTERNAL
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        starting?.cancel()
        bridge?.let { live ->
            live.detach()
            access.hub.detach(live)
        }
        bridge = null
        session?.release()
        session = null
        sessionPlayer?.release()
        sessionPlayer = null
        exo = null
        if (::access.isInitialized) access.resolver.clearPins()
        super.onDestroy()
    }

    public companion object {
        public const val SESSION_ID: String = "neutrodyne"
    }
}

/** Null in all normal runs. Tests arm it before framework creation; no runtime setting exposes it. */
@androidx.annotation.VisibleForTesting
public object PlaybackTestHooks {
    @Volatile public var foregroundServiceTimeoutMs: Long? = null
}
