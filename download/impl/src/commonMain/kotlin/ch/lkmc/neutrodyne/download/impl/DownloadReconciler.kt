// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.database.DownloadEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.model.DownloadError
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.WaitReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.IOException

internal class DownloadReconciler(
    private val db: NeutrodyneDatabase,
    private val files: DownloadFileSystem,
    private val roots: StorageRoots,
    private val uris: LocalUris,
    private val index: LocalMediaIndexStore,
    private val deletes: DeferredDeletes,
    private val claimMutex: Mutex,
    private val liveTokens: () -> Set<String>,
    private val clock: Clock,
    private val beforeRunnerReset: suspend (Set<String>) -> Unit = {},
    private val youtubeRules: YouTubeEngineRules? = null,
) {
    private val dao = db.downloadDao()
    private var reset = false
    private var loaded = false
    val orphanBytes = MutableStateFlow(0L)

    /** Only startup replaces the whole snapshot: later passes must not erase concurrent puts. */
    suspend fun initialLoad() =
        claimMutex.withLock {
            if (loaded) return@withLock
            index.replace(dao.completedIndex().associate { it.episodeId to it.finalUri })
            deletes.drain(null)
            loaded = true
        }

    suspend fun resetOrphanedRunners() =
        claimMutex.withLock {
            if (reset) return@withLock
            // Claims share this mutex. A newly registered token cannot claim until this reset ends;
            // owners unregister only after their final commits, so the snapshot cannot reset a live row.
            val live = liveTokens()
            // Android W6 applies its MANUAL rule here before the generic reset, under claimMutex.
            beforeRunnerReset(live)
            dao.resetOrphanedRunners(live, DownloadStopReasons.PROCESS_DEATH)
            reset = true
        }

    suspend fun reconcile() {
        resetOrphanedRunners()
        reconcileFiles()
    }

    /** Daily steps 3–5: check files and roots, drain the journal and sweep without resetting runners. */
    suspend fun reconcileFiles() =
        claimMutex.withLock {
            youtubeRules?.syncLocked()
            for (batch in dao.allRows().chunked(RECONCILE_BATCH)) {
                for (row in batch) if (row.state in FINAL_STATES) checkCompleted(row)
            }
            deletes.drain(db.playSessionDao().get()?.currentEpisodeId)
            sweepAndReport()
            roots.prune(dao.rootsInUse().toSet() + deletes.entries().map { it.rootId })
        }

    /** Cleanup can sweep without resetting runners or re-statting every completed file. */
    suspend fun sweepFiles() =
        claimMutex.withLock {
            deletes.drain(db.playSessionDao().get()?.currentEpisodeId)
            sweepAndReport()
        }

    private suspend fun sweepAndReport() {
        sweepParts()
        orphanBytes.value = orphanFiles().sumOf { it.second.bytes }
    }

    suspend fun reportFileMissing(episodeId: Long) =
        claimMutex.withLock {
            val row = dao.byEpisode(episodeId) ?: return@withLock
            if (row.state in FINAL_STATES) checkCompleted(row)
        }

    private suspend fun checkCompleted(row: DownloadEntity) {
        val root = roots.resolve(row.rootId)
        val info =
            try {
                if (root == null) null else row.relativePath?.let { files.stat(row.rootId, it) }
            } catch (_: IOException) {
                null
            }
        val intact = info != null && !info.directory && row.totalBytes != null && info.bytes == row.totalBytes
        val updated =
            if (intact) {
                row.copy(
                    state = DownloadState.COMPLETED,
                    waitReason = WaitReason.NONE,
                    lastError = null,
                    finalUri = uris.fileUri("${checkNotNull(root).absolutePath}/${row.relativePath}"),
                )
            } else {
                index.remove(row.episodeId)
                row.copy(
                    state = DownloadState.MISSING,
                    lastError = if (root == null) DownloadError.STORAGE_UNAVAILABLE else null,
                )
            }
        dao.updateUnclaimed(updated, row.state)
        if (intact) index.put(row.episodeId, checkNotNull(updated.finalUri))
    }

    private suspend fun rootIds(): Set<String> =
        (dao.rootsInUse() + roots.list().map { it.rootId } + roots.current()).toSet()

    private suspend fun sweepParts() {
        val rows = dao.allRows()
        for (root in rootIds()) {
            if (roots.resolve(root) == null) continue
            for (part in files.list(root, ".partial")) {
                if (part.directory || !part.path.endsWith(".part")) continue
                val owners =
                    rows.filter {
                        it.rootId == root && (it.tempPath ?: DownloadPaths.part(it.episodeId)) == part.path
                    }
                // Transfer IO runs outside claimMutex. Never sweep an owned active path, even
                // if a restored/malformed row has no token or another row shares the path.
                if (owners.any { it.runnerToken != null || it.state in ACTIVE_STATES }) continue
                val stale = clock.now() - part.modifiedAt >= STALE_PART_MS
                if (owners.isNotEmpty() && !stale) continue
                files.delete(root, part.path)
                for (owner in owners) {
                    if (owner.state !in RESTARTABLE_STATES) continue
                    dao.updateUnclaimed(
                        owner.copy(downloadedBytes = 0, etag = null, lastModified = null),
                        owner.state,
                    )
                }
            }
        }
    }

    /** Only app-shaped final files count. Arbitrary user files and directories are never swept. */
    suspend fun orphanFiles(): List<Pair<String, DownloadFileInfo>> {
        val rows = dao.allRows()
        val active = rows.filter { it.runnerToken != null }.map { it.episodeId }.toSet()
        val owned =
            rows
                .flatMap { row ->
                    listOfNotNull(row.relativePath, row.tempPath).map {
                        row.rootId to
                            it
                    }
                }.toSet()
        val journal = deletes.entries().map { it.rootId to it.path }.toSet()
        return buildList {
            for (root in rootIds()) {
                if (roots.resolve(root) == null) continue
                for (folder in files.list(root, "")) {
                    if (!folder.directory || !PODCAST_FOLDER.matches(folder.path)) continue
                    for (file in files.list(root, folder.path)) {
                        if (file.directory) continue
                        val match = FINAL_FILE.matchEntire(file.path) ?: continue
                        val episodeId = match.groupValues[1].toLongOrNull() ?: continue
                        // A first download can have renamed its final file before its completion commit.
                        if (episodeId in active) continue
                        if ((root to file.path) !in owned && (root to file.path) !in journal) add(root to file)
                    }
                }
            }
        }
    }

    private companion object {
        const val RECONCILE_BATCH = 200
        const val STALE_PART_MS = 14L * 24 * 60 * 60 * 1000
        val FINAL_STATES = setOf(DownloadState.COMPLETED, DownloadState.MISSING)
        val ACTIVE_STATES = setOf(DownloadState.RESOLVING, DownloadState.DOWNLOADING, DownloadState.VERIFYING)
        val RESTARTABLE_STATES = setOf(DownloadState.PAUSED, DownloadState.QUEUED, DownloadState.FAILED)
        val PODCAST_FOLDER = Regex(".* \\[p[0-9]+\\]")
        val FINAL_FILE = Regex(".* \\[e([0-9]+)\\]\\.[a-z0-9]+")
    }
}
