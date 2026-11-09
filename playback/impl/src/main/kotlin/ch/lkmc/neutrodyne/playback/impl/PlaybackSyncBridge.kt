// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.domain.PlaybackSyncActive
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
 * player and feeds the `Paused`/`Stopped`/`Transition` events only after their position save or
 * transition transaction has committed, so the round 10 starts carries the final position. The
 * sync side freezes position writes through [RemotePlayedGuard] (rule 3); the freeze lifts when
 * the item stops being current (the service reports current changes through the guard) or becomes
 * unplayed here again, which this bridge observes on the stored markers.
 */
@SingleIn(AppScope::class)
@Inject
public class PlaybackSyncBridge(
    private val guard: RemotePlayedGuard,
    private val positions: Provider<PositionStore>,
    @param:ApplicationScope private val scope: CoroutineScope,
) : PlaybackSyncPort {
    private val activeState = MutableStateFlow(PlaybackSyncActive(false))
    override val active: StateFlow<PlaybackSyncActive> = activeState.asStateFlow()

    private val boundaries = MutableSharedFlow<PlaybackSyncEvent>(extraBufferCapacity = 32)
    override val events = boundaries.asSharedFlow()

    /** Set while a service runtime is attached; runs on the player thread. */
    @Volatile
    private var notifyPlayer: (() -> Unit)? = null

    private val unfreezeWatches = java.util.concurrent.ConcurrentHashMap<Long, Job>()

    init {
        // A freeze lifted by a revert or an unfreeze watch drops its watcher too; a repeated mark
        // replaces it. A `currentChanged` prune keeps no watch for an item that left the player.
        scope.launch {
            guard.frozen.collect {
                for ((episodeId, job) in unfreezeWatches.entries) {
                    if (!guard.isFrozen(episodeId) && unfreezeWatches.remove(episodeId, job)) {
                        job.cancel()
                    }
                }
            }
        }
    }

    // --- service side (player thread) -----------------------------------------------------------

    /** 06 Creation and destruction: called from `attach`; [notifyPlayer] re-projects. */
    fun attach(notifyPlayer: () -> Unit) {
        this.notifyPlayer = notifyPlayer
    }

    /** Publishes the loaded item; a null [episodeId] is "nothing loaded". */
    fun updateActive(
        episodeId: Long?,
        isPlaying: Boolean,
    ) {
        activeState.value = PlaybackSyncActive(episodeId != null && isPlaying, episodeId)
    }

    fun paused() {
        boundaries.tryEmit(PlaybackSyncEvent.Paused)
    }

    fun stopped() {
        // 06 Sync interplay: the boundary is observable only after the matching save, and
        // `active` must already be cleared — a release reading the port at `Stopped` (10's
        // while-playing deferral) has to see that nothing plays any more.
        updateActive(null, isPlaying = false)
        guard.currentChanged(null)
        boundaries.tryEmit(PlaybackSyncEvent.Stopped)
    }

    fun transitioned(episodeId: Long) {
        boundaries.tryEmit(PlaybackSyncEvent.Transition(episodeId))
    }

    /**
     * 06 `onDestroy`: joins the teardown flush. [stopped] has already cleared `active` before its
     * boundary; the repeat covers a teardown with no flush (no loaded item), where no boundary
     * fires.
     */
    fun detachAfter(teardown: Job?) {
        notifyPlayer = null
        scope.launch {
            teardown?.join()
            updateActive(null, isPlaying = false)
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
     * The transaction that installed the mark rolled back: drop exactly its contribution. A
     * committed mark keeps its freeze and the observer that lifts it when it becomes unplayed.
     */
    override suspend fun onRemoteMarkReverted(episodeId: Long) {
        // Keep the watch when an earlier committed mark still owns a freeze. The guard's
        // observer cancels it once the last contribution is gone.
        guard.release(episodeId)
    }

    /**
     * Lifts the freeze when the episode becomes unplayed here again (the user's "Mark unplayed"
     * or a newer remote unplayed/in-progress state). The call arrives inside the applying
     * transaction, before the mark's commit lands, so the watch arms only after the stored
     * markers show an effectively played state once; a lift and a repeated freeze each restart
     * it. The store dereference runs on the application scope, where the database gate blocks
     * until the open finishes.
     */
    private fun watchUnfreeze(episodeId: Long) {
        unfreezeWatches.remove(episodeId)?.cancel()
        val job =
            scope.launch {
                try {
                    var armed = false
                    positions().observeMarks(episodeId).collect { marks ->
                        when {
                            marks?.playedAt != null && marks.startedAt == null -> {
                                armed = true
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
                }
            }
        unfreezeWatches[episodeId] = job
        job.invokeOnCompletion {
            // A newer watch may already have replaced this one; keep its entry.
            unfreezeWatches.remove(episodeId, job)
        }
    }
}
