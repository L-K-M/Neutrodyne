// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import ch.lkmc.neutrodyne.playback.api.NowPlaying
import ch.lkmc.neutrodyne.playback.native.NativeMediaSession
import ch.lkmc.neutrodyne.playback.native.NativeSessionEvent
import ch.lkmc.neutrodyne.playback.native.NativeSessionEventKind
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Windows/macOS [SystemMediaSession] over `ndmedia`: one process-wide native session
 * whose events this poller drains (the shim never enters the JVM). Commands surface on
 * [commands]; suspend, resume and route notices on [systemEvents] feed `OsPowerMonitor` and
 * later the route monitor; [failure] reports the last poll or publish failure without
 * stopping playback (11 OS-integration failure modes).
 */
class NativeSystemMediaSession private constructor(
    private val native: NativeMediaSession,
) : SystemMediaSession {
    private val commandChannel = Channel<RemoteCommand>(BUFFER)
    private val eventChannel = Channel<NativeSessionEvent>(BUFFER)
    private val closing = AtomicBoolean(false)
    private val lastFailure = MutableStateFlow<String?>(null)
    private val scope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO +
                CoroutineExceptionHandler { _, failure ->
                    if (!closing.get()) lastFailure.value = failure.toString()
                },
        )
    override val commands = commandChannel.receiveAsFlow()
    val systemEvents = eventChannel.receiveAsFlow()
    val failure: StateFlow<String?> = lastFailure

    init {
        scope.launch {
            while (true) {
                val event = native.poll()
                if (event == null) {
                    delay(POLL_MS)
                    continue
                }
                when (val routing = routeNativeEvent(event.kind)) {
                    is NativeEventRouting.Command -> commandChannel.send(routing.command)
                    NativeEventRouting.SystemEvent -> eventChannel.send(event)
                }
            }
        }
    }

    override fun publish(
        nowPlaying: NowPlaying?,
        skipBackMs: Long,
        skipForwardMs: Long,
        speedPresets: List<Float>,
    ) {
        native.publish(
            nowPlaying?.title,
            nowPlaying?.podcastTitle.orEmpty(),
            nowPlaying?.position?.durationMs ?: 0,
            nowPlaying?.position?.positionMs ?: 0,
            nowPlaying?.position?.speed ?: 1f,
            nowPlaying?.isPlaying ?: false,
        )
    }

    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        scope.cancel()
        native.close()
        commandChannel.close()
        eventChannel.close()
    }

    companion object {
        private const val BUFFER = 64
        private const val POLL_MS = 20L

        fun open(directory: Path): NativeSystemMediaSession {
            val os = System.getProperty("os.name")
            require(os.startsWith("Windows") || os == "Mac OS X") { "Linux uses LinuxMprisSession" }
            if (os == "Mac OS X") {
                check(!GraphicsEnvironment.isHeadless()) { "Now Playing needs an AppKit session" }
                // AWT starts Cocoa's main loop; native registration dispatches to that loop.
                Toolkit.getDefaultToolkit()
            }
            return NativeSystemMediaSession(NativeMediaSession.open(directory))
        }
    }
}

class WindowsSmtcSession private constructor(
    private val delegate: NativeSystemMediaSession,
) : SystemMediaSession by delegate {
    val systemEvents get() = delegate.systemEvents
    val failure get() = delegate.failure

    companion object {
        fun open(directory: Path): WindowsSmtcSession {
            check(System.getProperty("os.name").startsWith("Windows"))
            return WindowsSmtcSession(NativeSystemMediaSession.open(directory))
        }
    }
}

class MacNowPlayingSession private constructor(
    private val delegate: NativeSystemMediaSession,
) : SystemMediaSession by delegate {
    val systemEvents get() = delegate.systemEvents
    val failure get() = delegate.failure

    companion object {
        fun open(directory: Path): MacNowPlayingSession {
            check(System.getProperty("os.name") == "Mac OS X")
            return MacNowPlayingSession(NativeSystemMediaSession.open(directory))
        }
    }
}

/** How one polled native session event leaves the session: a command or a system event. */
internal sealed interface NativeEventRouting {
    data class Command(
        val command: RemoteCommand,
    ) : NativeEventRouting

    data object SystemEvent : NativeEventRouting
}

/**
 * Classifies a native session event kind for the poll loop. Exhaustive on purpose: a kind
 * added to the native enum without a decision here is a compile error in this module, not a
 * silently dropped event (the MD0 review's "unknown event" finding). The *raw* code-to-kind
 * parse — where an unknown native code really appears — is `NativeMediaSession.poll` in
 * `:playback:native`, out of this milestone's reach; 11's dated note records the residual.
 */
internal fun routeNativeEvent(
    kind: NativeSessionEventKind,
    seekValueMs: Long = 0,
): NativeEventRouting =
    when (kind) {
        NativeSessionEventKind.PLAY -> NativeEventRouting.Command(RemoteCommand.Play)

        NativeSessionEventKind.PAUSE -> NativeEventRouting.Command(RemoteCommand.Pause)

        NativeSessionEventKind.TOGGLE -> NativeEventRouting.Command(RemoteCommand.Toggle)

        NativeSessionEventKind.NEXT -> NativeEventRouting.Command(RemoteCommand.Next)

        NativeSessionEventKind.PREVIOUS -> NativeEventRouting.Command(RemoteCommand.Previous)

        NativeSessionEventKind.SEEK -> NativeEventRouting.Command(RemoteCommand.SeekTo(seekValueMs))

        NativeSessionEventKind.SUSPEND,
        NativeSessionEventKind.RESUME,
        NativeSessionEventKind.REMOVED,
        NativeSessionEventKind.DEFAULT_CHANGED,
        -> NativeEventRouting.SystemEvent
    }
