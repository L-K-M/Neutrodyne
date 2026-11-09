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
 * 10 Live updates' Android gate: the events stream may run while the UI is `STARTED` or
 * playback is active, and stays open for 60 s after both stop — a quick backgrounding should
 * not cost a reconnect, while a parked app must not hold a socket open indefinitely.
 * `SyncSchedulingInitializer` calls [start].
 */
@SingleIn(AppScope::class)
class SyncEventsGate
    @Inject
    constructor(
        private val scheduler: Provider<WorkManagerSyncScheduler>,
        private val playback: PlaybackSyncPort,
        private val events: Provider<SyncEventsClient>,
    ) {
        @Volatile
        private var started = false

        fun start() {
            if (started) return
            started = true
            events().start(sseAllowed(), SSE_HEARTBEAT_ANDROID_SECONDS)
        }

        /**
         * The raw gate: `true` while the UI is started or playback runs. On `false` the
         * emission is delayed by [BACKGROUND_GRACE_MS] — a flip back to `true` inside the
         * grace cancels the pending `false` (transformLatest), so the stream never sees it.
         * `onStart` still emits an immediate `false`: without it an app started backgrounded
         * would keep the gate un-emitted for a whole grace period.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        internal fun sseAllowed(): Flow<Boolean> =
            combine(scheduler().uiVisible, playback.active) { ui, playing ->
                ui || playing.isPlaying
            }.transformLatest { allowed ->
                if (allowed) {
                    emit(true)
                } else {
                    delay(BACKGROUND_GRACE_MS)
                    emit(false)
                }
            }.onStart { emit(false) }
                .distinctUntilChanged()

        private companion object {
            /** 10 Live updates: the 60-s grace after the UI stops and playback pauses. */
            const val BACKGROUND_GRACE_MS = 60_000L
        }
    }
