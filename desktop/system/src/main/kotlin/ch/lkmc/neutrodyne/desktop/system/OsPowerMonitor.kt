// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.PowerEvent
import ch.lkmc.neutrodyne.core.common.PowerMonitor
import ch.lkmc.neutrodyne.core.model.DesktopOs
import ch.lkmc.neutrodyne.playback.native.NativeSessionEvent
import ch.lkmc.neutrodyne.playback.native.NativeSessionEventKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

/**
 * The desktop's [PowerMonitor] (11 Runner contract's port, implemented in `:desktop:system`):
 * one hot event stream every consumer shares — the runner, the network monitor and the
 * playback power policy — because the sources underneath are single-subscriber channels.
 *
 * Per OS (11 Power's notices row): Linux follows logind's `PrepareForSleep` on the system
 * bus ([LinuxSuspendMonitor]); macOS and Windows follow the `ndmedia` session's suspend and
 * resume events, the hooks the native shim already exposes (`nd_session_poll`). Where
 * neither can be taken — a headless JVM, a missing native library, a system bus that
 * refuses the connection — the monitor emits nothing and says so in [status]: wake
 * detection then rests on the runner's wall-clock drift check (11 Wake: the 90-second
 * bound), which is why construction never throws.
 */
class OsPowerMonitor private constructor(
    scope: CoroutineScope,
    events: Flow<PowerEvent>,
    private val source: AutoCloseable,
    unavailable: String?,
) : PowerMonitor,
    AutoCloseable {
    private val shared = MutableSharedFlow<PowerEvent>(extraBufferCapacity = EVENT_BUFFER)
    private val lastStatus = MutableStateFlow<String?>(unavailable)

    override val events: Flow<PowerEvent> = shared.asSharedFlow()

    /** Null while the OS source is live; otherwise why wake notices are unavailable. */
    val status: StateFlow<String?> = lastStatus.asStateFlow()

    init {
        scope.launch {
            runCatching { events.collect { shared.emit(it) } }
                .onFailure { failure ->
                    lastStatus.value = "power notices ended: $failure"
                    Log.w(TAG, failure) { "OS power source stopped" }
                }
        }
    }

    override fun close() {
        runCatching { source.close() }
            .onFailure { Log.w(TAG, it) { "power source close failed" } }
    }

    companion object {
        private const val TAG = "OsPowerMonitor"
        private const val EVENT_BUFFER = 64

        /**
         * Picks the OS's source. [nativeSession] is resolved only on macOS and Windows, so a
         * Linux graph never opens the native media session for power. Every failure — bus,
         * native library, headless AWT — falls back to the documented no-notice binding
         * instead of failing the graph.
         */
        fun create(
            os: DesktopOs?,
            scope: CoroutineScope,
            nativeSession: () -> SystemMediaSession?,
        ): OsPowerMonitor =
            when (os) {
                DesktopOs.LINUX -> {
                    runCatching { LinuxSuspendMonitor.open() }.fold(
                        onSuccess = { monitor -> fromEvents(scope, monitor.events, monitor) },
                        onFailure = { noNotices(scope, "logind system bus unavailable: ${it.message}") },
                    )
                }

                DesktopOs.WINDOWS, DesktopOs.MACOS, null -> {
                    val session =
                        runCatching(nativeSession)
                            .getOrElse { return noNotices(scope, "native media session failed: ${it.message}") }
                    val native =
                        session as? NativeSystemMediaSession
                            ?: return noNotices(
                                scope,
                                if (session == null) {
                                    "no native media session"
                                } else {
                                    "media session has no native events (${session::class.simpleName})"
                                },
                            )
                    fromEvents(scope, native.systemEvents.powerEvents())
                }
            }

        /** The tests' entry: an already-shaped source plus its teardown and reason. */
        internal fun fromEvents(
            scope: CoroutineScope,
            events: Flow<PowerEvent>,
            source: AutoCloseable = NoClose,
            unavailable: String? = null,
        ): OsPowerMonitor = OsPowerMonitor(scope, events, source, unavailable)

        private fun noNotices(
            scope: CoroutineScope,
            reason: String,
        ): OsPowerMonitor {
            // The documented no-notice binding: the runner's >90 s wall/monotonic drift
            // marks the wake, so this is a logged degradation, never an error path.
            val unavailable = "$reason; relying on the runner's wall-clock wake detection"
            Log.w(TAG) { unavailable }
            return fromEvents(scope, emptyFlow(), unavailable = unavailable)
        }

        private object NoClose : AutoCloseable {
            override fun close() = Unit
        }
    }
}

/** The shim's suspend/resume session events as [PowerEvent]s; others are ignored. */
internal fun Flow<NativeSessionEvent>.powerEvents(): Flow<PowerEvent> =
    mapNotNull { event ->
        when (event.kind) {
            NativeSessionEventKind.SUSPEND -> PowerEvent.Suspending
            NativeSessionEventKind.RESUME -> PowerEvent.Resumed
            else -> null
        }
    }
