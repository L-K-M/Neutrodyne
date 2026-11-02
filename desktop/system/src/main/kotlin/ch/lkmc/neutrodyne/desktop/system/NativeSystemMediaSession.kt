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

/** Windows/macOS spike transport. It owns its poller; the production graph stays unavailable. */
class NativeSystemMediaSession private constructor(
    private val native: NativeMediaSession,
) : SystemMediaSession {
    private val commandChannel = Channel<RemoteCommand>(BUFFER)
    private val eventChannel = Channel<NativeSessionEvent>(BUFFER)
    private val closing = AtomicBoolean(false)
    private val lastFailure = MutableStateFlow<String?>(null)
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, failure ->
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
                if (event == null) { delay(POLL_MS); continue }
                val command = when (event.kind) {
                    NativeSessionEventKind.PLAY -> RemoteCommand.Play
                    NativeSessionEventKind.PAUSE -> RemoteCommand.Pause
                    NativeSessionEventKind.TOGGLE -> RemoteCommand.Toggle
                    NativeSessionEventKind.NEXT -> RemoteCommand.Next
                    NativeSessionEventKind.PREVIOUS -> RemoteCommand.Previous
                    NativeSessionEventKind.SEEK -> RemoteCommand.SeekTo(event.value)
                    else -> null
                }
                if (command != null) commandChannel.send(command) else eventChannel.send(event)
            }
        }
    }

    override fun publish(nowPlaying: NowPlaying?, skipBackMs: Long, skipForwardMs: Long, speedPresets: List<Float>) {
        native.publish(
            nowPlaying?.title, nowPlaying?.podcastTitle.orEmpty(),
            nowPlaying?.position?.durationMs ?: 0, nowPlaying?.position?.positionMs ?: 0,
            nowPlaying?.position?.speed ?: 1f, nowPlaying?.isPlaying ?: false,
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
