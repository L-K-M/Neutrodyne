// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.playback.api.MarkedPlayedElsewhere
import ch.lkmc.neutrodyne.playback.api.MarkedPlayedElsewhereSource
import ch.lkmc.neutrodyne.playback.api.PlaybackStateSource
import ch.lkmc.neutrodyne.sync.api.SyncNotice
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ExposeImplBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * Retains the replay-0 [SyncNotice.MarkedPlayedElsewhere] for the loaded item so the player's
 * "Marked played on {device}" line (08 Sync notices) can appear after the notice fired — the
 * acknowledged snackbar alone leaves no trace.
 *
 * [SyncNoticeBus.preEmit] stamps each posted mark with the [ch.lkmc.neutrodyne.playback.api.NowPlaying.loadId]
 * that is current **at post time** — a collector resuming a dispatcher pass later could not tell
 * whether the notice predated or followed the last load transition. The line is then a pure
 * function of the latest committed item and the stamped mark: a switch, unload or reload of the
 * item — including the same episode — yields a different loadId and drops it, while pause/play
 * keeps the occurrence and preserves it. A notice posted before the item loaded, for another
 * item, or for a previous occurrence never matches.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@ExposeImplBinding
@Inject
class MarkedPlayedElsewhereTracker(
    private val state: PlaybackStateSource,
    private val bus: SyncNoticeBus,
    @param:ApplicationScope private val scope: CoroutineScope,
) : MarkedPlayedElsewhereSource {
    private val line = MutableStateFlow<MarkedPlayedElsewhere?>(null)
    override val markedPlayedElsewhere: StateFlow<MarkedPlayedElsewhere?> = line.asStateFlow()

    /** The latest posted mark with its post-time load stamp; durable input to the combine. */
    private val posted = MutableStateFlow<MarkedPlayedElsewhere?>(null)

    private val started = MutableStateFlow(false)

    /**
     * Arms the post-time stamper before returning: the assignment is synchronous, so every
     * notice posted after [start] — long before the band-100 open that all producers sit
     * behind — is captured even while the replay-0 SharedFlow still has no collector.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        val stamper: (SyncNotice) -> Unit = { notice ->
            (notice as? SyncNotice.MarkedPlayedElsewhere)?.let { n ->
                val now = state.currentNowPlaying
                // Only a notice for the item actually current at post time lands; anything else
                // leaves the retained mark for the current item untouched. `currentNowPlaying`,
                // not the merged StateFlow, so a live commit not yet pumped is still seen.
                if (now?.episodeId == n.episodeId) {
                    posted.value = MarkedPlayedElsewhere(n.episodeId, n.deviceName, now.loadId)
                }
            }
        }
        bus.preEmit.value = stamper
        scope.coroutineContext.job.invokeOnCompletion {
            // Release only our own observer — a restarted tracker installs its replacement.
            bus.preEmit.compareAndSet(stamper, null)
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            state.nowPlaying
                .combine(posted) { now, mark ->
                    mark?.takeIf { it.episodeId == now?.episodeId && it.loadId == now?.loadId }
                }.collect { line.value = it }
        }
    }
}

/**
 * Band-90 arming, ahead of the band-100 database open: every path that can post
 * [SyncNotice.MarkedPlayedElsewhere] sits behind the open, so subscribing first loses nothing.
 * Resolving the tracker only builds db-free state — [PlaybackStateSource] keeps repositories
 * behind providers and the opener gate — so this early construction cannot suspend on the open
 * it precedes.
 */
@ContributesIntoSet(AppScope::class)
@Inject
class MarkedPlayedElsewhereInitializer(
    private val tracker: MarkedPlayedElsewhereTracker,
) : AppInitializer {
    override val order: Int = 90

    override suspend fun run() = tracker.start()
}
