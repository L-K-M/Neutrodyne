// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.domain.ActivePlayback
import ch.lkmc.neutrodyne.core.domain.PlaybackSyncEvent
import ch.lkmc.neutrodyne.core.domain.PlaybackSyncPort
import ch.lkmc.neutrodyne.core.domain.PositionStore
import ch.lkmc.neutrodyne.playback.core.RemotePlayedGuard
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 06 Sync interplay: the Android `PlaybackSyncPort`. The service side publishes [active] from the
 * player and feeds the `Paused`/`Stopped`/`Transitioned` events only after their position save or
 * transition transaction has committed, so the round 10 starts carries the final position. The
 * sync side freezes position writes through [RemotePlayedGuard] (rule 3); the freeze lifts when
 * the item stops being current (the service reports current changes through the guard) or becomes
 * unplayed here again, which this bridge observes on the stored row.
 */
@SingleIn(AppScope::class)
@Inject
public class PlaybackSyncBridge(
    private val guard: RemotePlayedGuard,
    private val positions: Provider<PositionStore>,
    @param:ApplicationScope private val scope: CoroutineScope,
) : PlaybackSyncPort {
    private val activeState = MutableStateFlow<ActivePlayback?>(null)
    override val active: StateFlow<ActivePlayback?> = activeState.asStateFlow()

    private val boundaries = MutableSharedFlow<PlaybackSyncEvent>(extraBufferCapacity = 32)
    override val events = boundaries.asSharedFlow()

    /** Set while a service runtime is attached; runs on the player thread. */
    @Volatile
    private var notifyPlayer: (() -> Unit)? = null

    private val unfreezeWatches = java.util.concurrent.ConcurrentHashMap<Long, Job>()

    /** Episodes whose played row the watch has seen once; the lift accepts only after this. */
    @Volatile
    internal var seenPlayed = setOf<Long>()
        private set

    // --- service side (player thread) -----------------------------------------------------------

    /** 06 Creation and destruction step 7: called with `stateHub.attach`; [notifyPlayer] re-projects. */
    public fun attach(notifyPlayer: () -> Unit) {
        this.notifyPlayer = notifyPlayer
    }

    /** Publishes the loaded item; a null [episodeId] is "nothing loaded" (06's `ActivePlayback`). */
    public fun updateActive(
        episodeId: Long?,
        isPlaying: Boolean,
        sinceMs: Long,
    ) {
        activeState.value = episodeId?.let { ActivePlayback(it, isPlaying, sinceMs) }
    }

    public fun paused() {
        boundaries.tryEmit(PlaybackSyncEvent.Paused)
    }

    public fun stopped() {
        boundaries.tryEmit(PlaybackSyncEvent.Stopped)
    }

    public fun transitioned(episodeId: Long) {
        boundaries.tryEmit(PlaybackSyncEvent.Transition(episodeId))
    }

    /**
     * 06 `onDestroy`: `active = null` after the flush's `Stopped` — the teardown job emits it once
     * its `NonCancellable` save completes, so waiting for the job keeps the ordering.
     */
    public fun detachAfter(teardown: Job?) {
        notifyPlayer = null
        scope.launch {
            teardown?.join()
            updateActive(null, isPlaying = false, sinceMs = 0)
            guard.currentChanged(null)
        }
    }

    // --- sync side (10's apply coroutine, any thread) --------------------------------------------

    /** Rule 3: freeze synchronously, then notify the player thread; never blocks the apply. */
    override suspend fun onRemoteMarkedPlayed(
        episodeId: Long,
        deviceName: String,
    ) {
        guard.freeze(episodeId)
        watchUnfreeze(episodeId)
        scope.launch(Dispatchers.Main.immediate) { notifyPlayer?.invoke() }
    }

    /**
     * Lifts the freeze when the episode becomes unplayed here again (the user's "Mark unplayed"
     * or a newer remote unplayed/in-progress state). The call arrives before the applying
     * transaction projects the mark, so the watch arms only after the played row was seen once;
     * a lift and a repeated freeze each restart it. The store dereference runs on the
     * application scope, where the database gate blocks until the open finishes.
     */
    private fun watchUnfreeze(episodeId: Long) {
        unfreezeWatches.remove(episodeId)?.cancel()
        unfreezeWatches[episodeId] =
            scope.launch {
                try {
                    var armed = false
                    positions().observe(episodeId).collect { row ->
                        when {
                            row?.playedAt != null -> {
                                armed = true
                                seenPlayed = seenPlayed + episodeId
                            }

                            armed -> {
                                guard.lift(episodeId)
                                throw CancellationException("unfrozen")
                            }
                        }
                    }
                } catch (expected: CancellationException) {
                    // the lift itself, or a restart through a repeated freeze
                } catch (failure: Exception) {
                    // A failed read keeps the freeze; stop-being-current still lifts it.
                } finally {
                    // A newer watch may already have replaced this one; keep its entry.
                    if (unfreezeWatches[episodeId] === coroutineContext[Job]) {
                        unfreezeWatches.remove(episodeId)
                        seenPlayed = seenPlayed - episodeId
                    }
                }
            }
    }
}
