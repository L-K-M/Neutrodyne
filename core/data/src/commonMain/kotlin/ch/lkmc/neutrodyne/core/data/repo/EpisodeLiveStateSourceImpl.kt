// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.repo

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.database.DownloadLiveRow
import ch.lkmc.neutrodyne.core.database.EpisodePositionEntity
import ch.lkmc.neutrodyne.core.database.EpisodeStateLiveRow
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.EpisodeLiveStateSource
import ch.lkmc.neutrodyne.core.model.RowLive
import ch.lkmc.neutrodyne.playback.api.NowPlaying
import ch.lkmc.neutrodyne.playback.api.PlaybackStateSource
import ch.lkmc.neutrodyne.playback.api.PositionSnapshot
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * `EpisodeLiveStateSource` (08 Live row state) over the three persisted `IN (:ids)` flows of
 * 02 (`episode_position`, `download` transitions and `episode_state.measuredDurationMs`), chunked
 * at 200 ids per query and merged by 08's merge rules with the optional playback
 * (`PlaybackStateSource`, present from M4) producer. The download-progress
 * (`DownloadProgressSource`, M6) producer joins this same combine as a Metro optional binding when
 * it lands. Visible-id sets are distinct, the first passes immediately and later sets debounce by
 * 100 ms (scrolling); the merged map is distinct and conflated on `@Dispatcher(Default)`; merging
 * runs off the main thread.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class EpisodeLiveStateSourceImpl(
    private val db: NeutrodyneDatabase,
    @Dispatcher(NeutrodyneDispatchers.Default) private val defaultDispatcher: CoroutineDispatcher,
    private val playback: PlaybackStateSource? = null,
) : EpisodeLiveStateSource {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observe(visibleIds: Flow<Set<Long>>): Flow<Map<Long, RowLive>> =
        visibleIds
            .distinctUntilChanged()
            .debounceAfterFirst(DEBOUNCE)
            .flatMapLatest { ids ->
                if (ids.isEmpty()) return@flatMapLatest flowOf(emptyMap())
                val chunks = ids.chunked(MAX_IN_IDS)
                val persisted =
                    combine(
                        chunks.map { db.positionDao().observeFor(it) }.combineAll(),
                        chunks.map { db.downloadDao().observeLiveFor(it) }.combineAll(),
                        chunks.map { db.episodeStateDao().observeFor(it) }.combineAll(),
                        ::Triple,
                    )
                combine(
                    persisted,
                    playback?.nowPlaying ?: flowOf(null),
                    // A nullable first value lets the merge emit before the first tick arrives;
                    // ticks of a previous item are dropped by the merge, not filtered upstream.
                    playback
                        ?.positionTicks
                        ?.map<PositionSnapshot, PositionSnapshot?> { it }
                        ?.onStart { emit(null) }
                        ?: flowOf(null),
                ) { (positions, downloads, states), now, tick ->
                    merge(ids, positions, downloads, states, now, tick)
                }
            }.distinctUntilChanged()
            .flowOn(defaultDispatcher)
            .conflate()

    private companion object {
        /** 08: the first id set is immediate; later sets debounce by 100 ms (scrolling). */
        val DEBOUNCE = 100.milliseconds

        /** 02 Live row state: `IN (:ids)` queries take at most 200 ids per call. */
        const val MAX_IN_IDS = 200
    }
}

/**
 * 08's merge rules (the download-progress producer arrives in M6): an id gets an entry only if a
 * live field carries data or it is now playing; an all-empty `RowLive` means "use the paged row".
 * `playedAt`/`startedAt`/`isFavorite` deliberately never become `RowLive` fields.
 *
 * On the now-playing row the player wins position and duration — a matching [tick] first, else the
 * `NowPlaying` snapshot, since a tick can still belong to the previous item right after a
 * transition. A tick for any other id is noncurrent and ignored, and non-current rows always read
 * `episode_position`.
 */
private fun merge(
    ids: Set<Long>,
    positions: List<EpisodePositionEntity>,
    downloads: List<DownloadLiveRow>,
    states: List<EpisodeStateLiveRow>,
    now: NowPlaying?,
    tick: PositionSnapshot?,
): Map<Long, RowLive> {
    val positionBy = positions.associateBy { it.episodeId }
    val downloadBy = downloads.associateBy { it.episodeId }
    val stateBy = states.associateBy { it.episodeId }
    val live = HashMap<Long, RowLive>()
    for (id in ids) {
        val isNow = now?.episodeId == id
        val position = positionBy[id]
        val download = downloadBy[id]
        val state = stateBy[id]
        if (!isNow && position == null && download == null && state == null) continue
        val player = if (tick?.episodeId == id) tick else now?.position
        val row =
            RowLive(
                positionMs = if (isNow) player?.positionMs else position?.positionMs,
                durationMs =
                    if (isNow) {
                        player?.durationMs
                    } else {
                        position?.durationMs ?: state?.measuredDurationMs
                    },
                downloadState = download?.state,
                downloadedBytes = download?.downloadedBytes,
                totalBytes = download?.let { it.totalBytes ?: it.estimatedBytes },
                waitReason = download?.waitReason,
                nextAttemptAt = download?.nextAttemptAt,
                lastError = download?.lastError,
                isNowPlaying = isNow,
                isPlaying = isNow && now?.isPlaying == true,
            )
        if (row != RowLive()) live[id] = row
    }
    return live
}

/** `combine` over one table's per-chunk `IN` flows, flattened back to a single row list. */
private fun <T> List<Flow<List<T>>>.combineAll(): Flow<List<T>> =
    combine(this) { chunkResults -> chunkResults.flatMap { it } }

/**
 * Emits the upstream's first value immediately and debounces later values by [timeout]
 * (08: a list's first frame is not delayed; rapid id changes while scrolling coalesce).
 * `channelFlow` awaits the pending debounce child, so the latest value is never lost on completion.
 */
private fun <T> Flow<T>.debounceAfterFirst(timeout: Duration): Flow<T> =
    channelFlow {
        var pending: Job? = null
        var first = true
        this@debounceAfterFirst.collect { value ->
            if (first) {
                first = false
                send(value)
            } else {
                pending?.cancel()
                pending =
                    launch {
                        delay(timeout)
                        send(value)
                    }
            }
        }
    }
