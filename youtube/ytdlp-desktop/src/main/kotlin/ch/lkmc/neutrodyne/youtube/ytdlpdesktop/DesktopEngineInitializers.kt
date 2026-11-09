// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlpdesktop

import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.youtube.engine.EnginePlatformPreflight
import ch.lkmc.neutrodyne.youtube.engine.EnginePreparation
import ch.lkmc.neutrodyne.youtube.engine.EngineSettings
import ch.lkmc.neutrodyne.youtube.engine.EngineStore
import ch.lkmc.neutrodyne.youtube.engine.YtxControlRequest
import ch.lkmc.neutrodyne.youtube.engine.YtxDeadline
import ch.lkmc.neutrodyne.youtube.engine.YtxLimits
import ch.lkmc.neutrodyne.youtube.engine.YtxMethod
import ch.lkmc.neutrodyne.youtube.engine.YtxTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Restores the store's record/compiled state across restarts, before any host start. */
class DesktopEngineStatusInitializer(
    private val store: EngineStore,
) : AppInitializer {
    override val order = 150

    override suspend fun run() = store.loadStatus()
}

/**
 * The desktop's order-210 preparation observer: the same shape as Android's
 * `EnginePreparationObserver`, driving the prepare+ping pass itself because the desktop has
 * no WorkManager (11). The pass runs on the application scope — prepareBundled, then a PING
 * inside the compile+start budget so the child's one-time bytecode compile and the marker
 * write finish off the playback path — once per enabled transition. A failed pass leaves the
 * store's own state and the first real call retries; there is no work-retry lane this slice.
 */
class DesktopEnginePreparationObserver(
    private val store: EngineStore,
    private val transport: YtxTransport,
    private val settings: EngineSettings,
    private val preflight: EnginePlatformPreflight,
    private val pingRequest: () -> YtxControlRequest,
    private val clock: Clock,
    private val scope: CoroutineScope,
) : AppInitializer {
    override val order = 210

    override suspend fun run() {
        scope.launch {
            settings.awaitLoaded()
            settings.state.map { it.enabled }.distinctUntilChanged().collect { enabled ->
                if (enabled) prepare()
            }
        }
    }

    private suspend fun prepare() {
        try {
            if (preflight.limitation() != null) return
            if (store.preparation.value == EnginePreparation.COMPILED) return
            store.prepareBundled()
            transport.call(
                YtxMethod.PING,
                pingRequest().toJson(),
                YtxDeadline(clock.elapsedRealtime() + YtxLimits.COMPILE_MS + YtxLimits.START_MS),
            )
            store.loadStatus()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A failed prepare leaves the store's own state; the first real call retries.
        }
    }
}
