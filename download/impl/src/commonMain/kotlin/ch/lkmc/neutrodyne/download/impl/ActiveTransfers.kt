// SPDX-License-Identifier: Unlicense
@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.database.DownloadEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.concurrent.atomics.AtomicReference

/** Registration happens before claimMutex is released, including before a child can suspend. */
internal class ActiveTransfers {
    private val entries = MutableStateFlow<Map<Long, Entry>>(emptyMap())
    val snapshot: List<Entry> get() = entries.value.values.toList()

    fun contains(episodeId: Long): Boolean = episodeId in entries.value

    fun register(row: DownloadEntity): Entry {
        val entry = Entry(row)
        entries.update {
            check(row.episodeId !in it)
            it + (row.episodeId to entry)
        }
        return entry
    }

    fun remove(entry: Entry) {
        entries.update { if (it[entry.row.episodeId] === entry) it - entry.row.episodeId else it }
        entry.finished.complete(Unit)
    }

    /** May be called with claimMutex held. Cleanup never takes that mutex. First intent wins. */
    suspend fun stop(
        episodeId: Long,
        intent: StopIntent,
    ) {
        val entry = entries.value[episodeId] ?: return
        entry.stop(intent)
        entry.finished.await()
    }

    internal class Entry(
        val row: DownloadEntity,
    ) {
        private val job = AtomicReference<Job?>(null)
        private val stop = AtomicReference<StopIntent?>(null)
        val finished = CompletableDeferred<Unit>()
        val intent: StopIntent? get() = stop.load()

        fun attach(child: Job) {
            job.store(child)
            if (intent != null) child.cancel()
        }

        fun stop(intent: StopIntent) {
            stop.compareAndSet(null, intent)
            job.load()?.cancel()
        }
    }
}
