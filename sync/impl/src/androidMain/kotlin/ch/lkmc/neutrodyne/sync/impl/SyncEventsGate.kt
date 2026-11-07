// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.domain.PlaybackSyncPort
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest

/**
 * Android's events-stream gate (10 Live updates, MS3 acceptance 4): the SSE connection is
 * allowed while `ProcessLifecycleOwner` is at least `STARTED` or playback runs, and it closes
 * 60 s after both became false — a brief home-screen hop keeps the stream. `hb=90` (risk SR7).
 */
@SingleIn(AppScope::class)
class SyncEventsGate
    @Inject
    constructor(
        private val workScheduler: Provider<WorkManagerSyncScheduler>,
        private val playback: PlaybackSyncPort,
        private val events: Provider<SyncEventsClient>,
    ) {
        /** The scheduling initializer calls this once its own collectors run. */
        fun start() {
            events().start(sseAllowed(), SSE_HEARTBEAT_ANDROID_SECONDS)
        }

        /** `internal` so the host tests drive the two inputs without `ProcessLifecycleOwner`. */
        @OptIn(ExperimentalCoroutinesApi::class)
        internal fun sseAllowed(): Flow<Boolean> =
            combine(workScheduler().uiVisible, playback.active) { ui, active ->
                ui || active?.isPlaying == true
            }.distinctUntilChanged()
                .transformLatest { on ->
                    if (on) {
                        emit(true)
                    } else {
                        delay(SSE_DISCONNECT_MS)
                        emit(false)
                    }
                }
                // The transform only emits after the grace delay on `false` — the downstream's
                // initial "closed" comes from here instead of waiting 60 s for it.
                .onStart { emit(false) }
                .distinctUntilChanged()

        private companion object {
            /** 10 Live updates: disconnect this long after UI and playback both stopped. */
            const val SSE_DISCONNECT_MS = 60_000L
        }
    }
