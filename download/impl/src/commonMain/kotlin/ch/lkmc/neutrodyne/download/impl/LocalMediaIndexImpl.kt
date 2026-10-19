// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.common.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Construction and lookup never touch storage, including before band 130 has loaded the DB. */
@SingleIn(AppScope::class)
@Inject
internal class LocalMediaIndexImpl : LocalMediaIndexStore {
    private val snapshot = MutableStateFlow<Map<Long, String>>(emptyMap())
    private val writes = Mutex()
    private val events = MutableSharedFlow<Set<Long>>(extraBufferCapacity = 64)
    override val changes = events.asSharedFlow()

    override fun localUriOrNull(episodeId: Long): String? = snapshot.value[episodeId]

    override suspend fun replace(snapshot: Map<Long, String>) =
        writes.withLock {
            val old = this.snapshot.value
            this.snapshot.value = snapshot.toMap()
            val changed = (old.keys + snapshot.keys).filterTo(mutableSetOf()) { old[it] != snapshot[it] }
            if (changed.isNotEmpty()) events.emit(changed)
        }

    override suspend fun put(
        episodeId: Long,
        uri: String,
    ) = writes.withLock {
        if (snapshot.value[episodeId] == uri) return@withLock
        snapshot.value = snapshot.value + (episodeId to uri)
        events.emit(setOf(episodeId))
    }

    override suspend fun remove(episodeId: Long) =
        writes.withLock {
            if (episodeId !in snapshot.value) return@withLock
            snapshot.value = snapshot.value - episodeId
            events.emit(setOf(episodeId))
        }
}
