// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import ch.lkmc.neutrodyne.youtube.api.AudioPref
import ch.lkmc.neutrodyne.youtube.api.YouTubeStreamResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/** Identity is the installed occurrence, retained across metadata and reorder emissions. */
internal class PreResolveTarget(
    val episodeId: Long,
    val videoId: String,
)

internal data class PreResolveWindow(
    val next: PreResolveTarget?,
    val installed: Set<PreResolveTarget>,
)

internal data class PlaybackTick(
    val episodeId: Long,
    val occurrence: Long,
    val positionMs: Long,
    val durationMs: Long?,
)

/** Service/player-thread confined. Only W5 owns flights, URL caching, deadlines and health. */
internal class PreResolver(
    private val streams: YouTubeStreamResolver,
    private val applicationScope: CoroutineScope,
) {
    private var active = true
    private var current: Pair<Long, Long>? = null
    private val attempted = mutableSetOf<PreResolveTarget>()

    fun onTick(
        tick: PlaybackTick,
        window: PreResolveWindow?,
        pref: AudioPref,
    ) {
        if (!active) return
        val occurrence = tick.episodeId to tick.occurrence
        if (current != occurrence) {
            current = occurrence
            attempted.clear()
        }
        if (window == null) return
        attempted.retainAll(window.installed)
        val duration = tick.durationMs?.takeIf { it > 0 } ?: return
        if (tick.positionMs < 0 || tick.positionMs > duration || duration - tick.positionMs > 60_000) return
        val next = window.next ?: return
        if (!attempted.add(next)) return
        // Begin now, before another player callback can detach/replace the captured window.
        // Once begun, the app-scope waiter may finish warming W5's cache after service teardown.
        applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                streams.resolveAudio(next.videoId, pref)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                android.util.Log.w("PreResolver", "Pre-resolve failed")
            }
        }
    }

    fun detach() {
        active = false
        attempted.clear()
    }
}
