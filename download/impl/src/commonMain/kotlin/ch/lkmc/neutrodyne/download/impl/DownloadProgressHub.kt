// SPDX-License-Identifier: Unlicense
@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.download.api.DownloadProgressSource
import ch.lkmc.neutrodyne.download.api.LiveProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicReference
import kotlin.math.exp

/** Byte callbacks only touch memory. A single idle-suspending ticker bounds all publications. */
internal class DownloadProgressHub(
    scope: CoroutineScope,
    private val clock: Clock,
) : DownloadProgressSource {
    private val entries = MutableStateFlow<Map<Long, AtomicReference<LiveProgress>>>(emptyMap())
    private val published = MutableStateFlow<Map<Long, LiveProgress>>(emptyMap())
    val snapshots = published.asStateFlow()

    init {
        scope.launch {
            val previous = mutableMapOf<Long, Sample>()
            while (true) {
                entries.first { it.isNotEmpty() }
                do {
                    delay(TICK_MS)
                    val now = clock.elapsedRealtime()
                    val snapshot =
                        entries.value.mapValues { (id, entry) ->
                            val value = entry.load()
                            val old = previous[id]
                            val elapsed = old?.let { now - it.at }
                            val speed =
                                if (old != null && elapsed != null && elapsed > 0 &&
                                    value.downloadedBytes >= old.bytes
                                ) {
                                    val measured = (value.downloadedBytes - old.bytes) * 1000.0 / elapsed
                                    val weight = 1 - exp(-elapsed.toDouble() / SPEED_WINDOW_MS)
                                    old.speed?.let { it + weight * (measured - it) } ?: measured
                                } else {
                                    null
                                }
                            previous[id] = Sample(value.downloadedBytes, now, speed)
                            value.copy(bytesPerSecond = speed?.toLong())
                        }
                    previous.keys.retainAll(snapshot.keys)
                    published.value = snapshot
                } while (entries.value.isNotEmpty() || published.value.isNotEmpty())
            }
        }
    }

    fun begin(
        episodeId: Long,
        downloaded: Long,
        total: Long?,
    ) {
        entries.update {
            it +
                (episodeId to AtomicReference(LiveProgress(DownloadState.RESOLVING, downloaded, total, null)))
        }
    }

    fun update(
        episodeId: Long,
        state: DownloadState,
        downloaded: Long,
        total: Long?,
    ) {
        entries.value[episodeId]?.store(LiveProgress(state, downloaded, total, null))
    }

    /** Called only after the final commit, even on cancellation. Removal obeys the ticker rate. */
    fun end(episodeId: Long) {
        entries.update { it - episodeId }
    }

    override fun observe(ids: Set<Long>): Flow<Map<Long, LiveProgress>> =
        snapshots.map { values -> values.filterKeys { it in ids } }.distinctUntilChanged()

    private data class Sample(
        val bytes: Long,
        val at: Long,
        val speed: Double?,
    )

    private companion object {
        const val TICK_MS = 250L
        const val SPEED_WINDOW_MS = 3000.0
    }
}
