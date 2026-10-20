// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import ch.lkmc.neutrodyne.core.domain.PlaybackMediaLookup
import ch.lkmc.neutrodyne.core.domain.PositionStore
import ch.lkmc.neutrodyne.core.model.MEASURED_DURATION_TOLERANCE_MS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** First usable duration per current occurrence; metadata invalidations cannot trigger another write. */
internal class MeasuredDuration(
    private val player: Player,
    private val lookup: PlaybackMediaLookup,
    private val positions: PositionStore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val reportFailure: (Exception) -> Unit = { android.util.Log.w("MeasuredDuration", "Duration save failed") },
) : Player.Listener {
    private var currentId: Long? = null
    private var epoch = 0L
    private var dispatched = false
    private var writing: Job? = null

    fun attach() {
        player.addListener(this)
        inspect()
    }

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
    ) = inspect()

    override fun onPlaybackStateChanged(playbackState: Int) = inspect()

    override fun onTimelineChanged(
        timeline: Timeline,
        reason: Int,
    ) = inspect()

    private fun inspect() {
        val id = player.episodeIdOrNull()
        if (id != currentId) {
            epoch++
            currentId = id
            dispatched = false
            writing?.cancel()
        }
        if (id == null || dispatched || player.playbackState != Player.STATE_READY) return
        val duration = player.knownDuration() ?: return
        val occurrence = epoch
        dispatched = true
        writing =
            scope.launch {
                try {
                    val row = withContext(io) { lookup.get(id) } ?: return@launch
                    if (epoch != occurrence || player.episodeIdOrNull() != id) return@launch
                    val stored = row.measuredDurationMs
                    if (stored == null || abs(stored - duration) > MEASURED_DURATION_TOLERANCE_MS) {
                        withContext(io) { positions.setMeasuredDuration(id, duration) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    reportFailure(failure)
                }
            }
    }

    fun detach() {
        epoch++
        writing?.cancel()
        player.removeListener(this)
    }
}
