// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.database.DownloadEntity
import ch.lkmc.neutrodyne.core.datastore.SettingStore
import ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys
import kotlinx.collections.immutable.toPersistentSet
import okio.IOException

/** Callers serialize ownership checks and file deletion with claimMutex, outside DB transactions. */
internal class DeferredDeletes(
    private val settings: SettingStore,
    private val files: DownloadFileSystem,
    private val roots: StorageRoots,
    private val rows: suspend () -> List<DownloadEntity>,
) {
    data class Entry(
        val episodeId: Long,
        val rootId: String,
        val path: String,
    ) {
        fun encoded(): String = "$episodeId|$rootId|$path"
    }

    suspend fun journal(rows: List<DownloadEntity>) {
        val entries =
            rows.flatMap { row ->
                listOfNotNull(row.tempPath ?: DownloadPaths.part(row.episodeId), row.relativePath)
                    .map { Entry(row.episodeId, row.rootId, it).encoded() }
            }
        settings.update(DownloadSettingKeys.DEFERRED_DELETES) { (it + entries).toPersistentSet() }
    }

    suspend fun entries(): Set<Entry> =
        settings.get(DownloadSettingKeys.DEFERRED_DELETES).mapTo(mutableSetOf()) { value ->
            val fields = value.split('|', limit = 3)
            require(fields.size == 3 && fields[0].toLongOrNull() != null) { "Invalid download deletion journal entry" }
            Entry(fields[0].toLong(), fields[1], fields[2])
        }

    suspend fun completed(row: DownloadEntity) {
        val path = row.relativePath ?: return
        val owned = Entry(row.episodeId, row.rootId, path).encoded()
        settings.update(DownloadSettingKeys.DEFERRED_DELETES) { (it - owned).toPersistentSet() }
    }

    suspend fun drain(currentEpisodeId: Long?) {
        val live = rows()
        val owned =
            live
                .flatMap { row ->
                    listOfNotNull(row.tempPath ?: DownloadPaths.part(row.episodeId), row.relativePath)
                        .map { row.rootId to it }
                }.toSet()
        val done = mutableSetOf<String>()
        for (entry in entries()) {
            if ((entry.rootId to entry.path) in owned) {
                // Covers a crash before row removal and a later download reusing the name.
                done += entry.encoded()
                continue
            }
            // An active replacement may already have renamed this path without committing it.
            // Claims cannot start during this drain; existing owners retain the journal until finish.
            val replacing =
                live.any {
                    it.episodeId == entry.episodeId && it.runnerToken != null
                }
            if (replacing) continue
            if (entry.episodeId == currentEpisodeId || roots.resolve(entry.rootId) == null) continue
            try {
                files.delete(entry.rootId, entry.path)
                val parent = entry.path.substringBeforeLast('/', "")
                if (PODCAST_FOLDER.matches(parent) && files.list(entry.rootId, parent).isEmpty()) {
                    files.delete(entry.rootId, parent)
                }
                done += entry.encoded()
            } catch (_: IOException) {
                // Keep the durable intent across unmounts, sharing violations and process death.
            }
        }
        if (done.isNotEmpty()) settings.update(DownloadSettingKeys.DEFERRED_DELETES) { (it - done).toPersistentSet() }
    }

    private companion object {
        val PODCAST_FOLDER = Regex(".+ \\[p[0-9]+\\]")
    }
}
