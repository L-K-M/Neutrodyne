// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.database.DownloadEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.datastore.SettingStore
import ch.lkmc.neutrodyne.core.domain.DownloadMover
import ch.lkmc.neutrodyne.core.domain.MoveResult
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.IOException

/** Copying is cancellable; publication and rollback finish before releasing the ownership lock. */
internal class DownloadMoverImpl(
    private val db: NeutrodyneDatabase,
    private val files: DownloadFileSystem,
    private val roots: StorageRoots,
    private val settings: SettingStore,
    private val index: LocalMediaIndexStore,
    private val deletes: DeferredDeletes,
    private val claimMutex: Mutex,
    private val clock: Clock,
    private val quota: DownloadQuota,
    private val uris: LocalUris = PlatformLocalUris(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : DownloadMover {
    private val singleFlight = Mutex()
    private val dao = db.downloadDao()

    override suspend fun run(
        target: String,
        deadline: Long?,
    ): MoveResult =
        withContext(io) {
            singleFlight.withLock {
                if (!requested(target)) return@withLock MoveResult.COMPLETE
                quota.moveStorageLow.value = false
                quota.moveUnavailableSources.value = 0
                if (roots.resolve(target) == null) return@withLock MoveResult.PENDING
                sweepMoves()
                var afterId = 0L
                var pending = false
                // Reconcile may have marked the missing pre-commit source MISSING after a crash.
                for (row in dao.allRows().filter { it.state == DownloadState.MISSING && it.rootId != target }) {
                    if (expired(deadline)) return@withLock MoveResult.DEADLINE
                    if (!requested(target)) return@withLock MoveResult.COMPLETE
                    val oldPath = row.relativePath ?: continue
                    if (roots.resolve(row.rootId) == null || files.stat(row.rootId, oldPath) != null) continue
                    val root = roots.resolve(target) ?: return@withLock MoveResult.PENDING
                    val path = destination(row, root) ?: continue
                    if (!valid(target, path, row.totalBytes ?: continue)) continue
                    if (publish(
                            row,
                            target,
                            path,
                            ".partial/${row.episodeId}.move",
                            method(row.rootId, target),
                            Artifact.ADOPTED,
                        ) ==
                        Outcome.PENDING
                    ) {
                        pending = true
                    }
                }
                val visitedRoots = roots.list().mapTo(mutableSetOf()) { it.rootId }
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (!requested(target)) return@withLock MoveResult.COMPLETE
                    if (expired(deadline)) return@withLock MoveResult.DEADLINE
                    val batch =
                        if (afterId == 0L) {
                            dao.rowsOnOtherRoots(target, BATCH_SIZE)
                        } else {
                            dao.rowsOnOtherRootsAfter(target, afterId, BATCH_SIZE)
                        }
                    if (batch.isEmpty()) break
                    for (row in batch) {
                        if (!requested(target)) return@withLock MoveResult.COMPLETE
                        if (expired(deadline)) return@withLock MoveResult.DEADLINE
                        afterId = row.episodeId
                        visitedRoots += row.rootId
                        try {
                            when (move(row, target, deadline)) {
                                Outcome.MOVED, Outcome.SKIPPED -> {}

                                Outcome.UNAVAILABLE -> {
                                    quota.moveUnavailableSources.value++
                                }

                                Outcome.PENDING -> {
                                    pending = true
                                }

                                Outcome.DEADLINE -> {
                                    return@withLock MoveResult.DEADLINE
                                }

                                Outcome.FULL -> {
                                    quota.moveStorageLow.value = true
                                    return@withLock MoveResult.STORAGE_FULL
                                }
                            }
                        } catch (failure: IOException) {
                            if (roots.classify(failure, target) == StorageFailure.FULL) {
                                quota.moveStorageLow.value = true
                                return@withLock MoveResult.STORAGE_FULL
                            }
                            pending = true
                        }
                    }
                }
                claimMutex.withLock {
                    deletes.drain(db.playSessionDao().get()?.currentEpisodeId)
                    cleanFolders(visitedRoots)
                    if (!pending) {
                        settings.update(DownloadSettingKeys.MOVE_TARGET) { if (it == target) "" else it }
                    }
                }
                if (pending) MoveResult.PENDING else MoveResult.COMPLETE
            }
        }

    private suspend fun requested(target: String) =
        target.isNotEmpty() && settings.get(DownloadSettingKeys.MOVE_TARGET) == target

    private fun expired(deadline: Long?) = deadline != null && clock.elapsedRealtime() >= deadline

    private suspend fun move(
        row: DownloadEntity,
        target: String,
        deadline: Long?,
    ): Outcome {
        if (roots.resolve(row.rootId) == null) return Outcome.UNAVAILABLE
        val targetRoot = roots.resolve(target) ?: return Outcome.PENDING
        val oldPath = row.relativePath ?: return Outcome.PENDING
        val total = row.totalBytes ?: return Outcome.PENDING
        val path = destination(row, targetRoot) ?: return Outcome.PENDING
        val sameStore = files.sameStore(row.rootId, target)
        if (sameStore && db.playSessionDao().get()?.currentEpisodeId == row.episodeId) return Outcome.PENDING
        val original = files.stat(row.rootId, oldPath)
        // A same-store rename may have survived process death before the row commit.
        val adopted = original == null && valid(target, path, total)
        if (!adopted && (original == null || original.directory || original.bytes != total)) return Outcome.PENDING
        val temporary = ".partial/${row.episodeId}.move"
        if (!sameStore && !adopted) {
            val required = total.coerceAtMost(Long.MAX_VALUE - SPACE_MARGIN_BYTES) + SPACE_MARGIN_BYTES
            when (roots.ensureSpace(target, required)) {
                StorageFailure.FULL, StorageFailure.TOO_LARGE -> return Outcome.FULL
                StorageFailure.UNAVAILABLE, StorageFailure.IO -> return Outcome.PENDING
                null -> Unit
            }
            if (!copy(row.rootId, oldPath, target, temporary, total, deadline)) return Outcome.DEADLINE
        }
        if (expired(deadline)) return Outcome.DEADLINE
        return publish(
            row,
            target,
            path,
            temporary,
            if (sameStore) Method.RENAME else Method.COPY,
            if (adopted) Artifact.ADOPTED else Artifact.NEW,
        )
    }

    private suspend fun destination(
        row: DownloadEntity,
        root: ResolvedRoot,
    ): String? {
        val source = db.episodeDao().downloadSources(listOf(row.episodeId)).singleOrNull() ?: return null
        return DownloadPaths.finalPath(
            files,
            root,
            source.podcastId,
            source.podcastTitle,
            row.episodeId,
            source.title,
            localDate(source.sortDate),
            row.relativePath?.substringAfterLast('.') ?: return null,
            pathPlatform(),
        )
    }

    private suspend fun valid(
        root: String,
        path: String,
        bytes: Long,
    ): Boolean {
        val info = files.stat(root, path)
        return info != null && !info.directory && info.bytes == bytes
    }

    private suspend fun copy(
        sourceRoot: String,
        sourcePath: String,
        target: String,
        temporary: String,
        total: Long,
        deadline: Long?,
    ): Boolean {
        val source = files.openPart(sourceRoot, sourcePath)
        val output = files.openPart(target, temporary)
        try {
            output.truncate(0)
            val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
            var offset = 0L
            while (offset < total) {
                currentCoroutineContext().ensureActive()
                if (expired(deadline)) return false
                val count = source.read(offset, buffer, minOf(buffer.size.toLong(), total - offset).toInt())
                if (count <= 0) throw IOException("Download source changed during move")
                output.append(buffer, count)
                offset += count
            }
            output.sync()
            if (source.length() != total ||
                output.length() != total
            ) {
                throw IOException("Moved download length mismatch")
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    output.close()
                } finally {
                    source.close()
                }
            }
        }
        if (!valid(target, temporary, total)) throw IOException("Moved download length mismatch")
        return true
    }

    private suspend fun publish(
        row: DownloadEntity,
        target: String,
        path: String,
        temporary: String,
        method: Method,
        artifact: Artifact,
    ): Outcome =
        claimMutex.withLock {
            withContext(NonCancellable) {
                val current = dao.byEpisode(row.episodeId)
                if (!requested(target) || current?.state != row.state ||
                    current.rootId != row.rootId || current.relativePath != row.relativePath ||
                    current.totalBytes != row.totalBytes
                ) {
                    if (method == Method.COPY && artifact == Artifact.NEW) files.delete(target, temporary)
                    return@withContext retryOutcome(current, row, target)
                }
                if (method == Method.RENAME && artifact == Artifact.NEW &&
                    db.playSessionDao().get()?.currentEpisodeId == row.episodeId
                ) {
                    return@withContext Outcome.PENDING
                }
                deletes.journal(listOf(row))
                if (method == Method.RENAME && artifact == Artifact.NEW &&
                    db.playSessionDao().get()?.currentEpisodeId == row.episodeId
                ) {
                    return@withContext Outcome.PENDING
                }
                files.createDirectories(target, path.substringBeforeLast('/'))
                var renamed = artifact == Artifact.ADOPTED
                try {
                    if (artifact == Artifact.NEW) {
                        if (method == Method.RENAME) {
                            files.moveBetweenRoots(row.rootId, checkNotNull(row.relativePath), target, path)
                        } else {
                            files.atomicMove(target, temporary, path)
                        }
                        renamed = true
                    }
                    val root = roots.resolve(target) ?: throw IOException("Move target unavailable")
                    val uri = uris.fileUri("${root.absolutePath}/$path")
                    val committed =
                        db.withWriteTransaction {
                            val latest = dao.byEpisode(row.episodeId)
                            if (latest?.state != row.state || latest.rootId != row.rootId ||
                                latest.relativePath != row.relativePath
                            ) {
                                false
                            } else {
                                dao.updateUnclaimed(
                                    latest.copy(
                                        state = DownloadState.COMPLETED,
                                        lastError = null,
                                        rootId = target,
                                        relativePath = path,
                                        finalUri = uri,
                                    ),
                                    row.state,
                                ) ==
                                    1
                            }
                        }
                    if (!committed) {
                        rollback(row, target, path, method)
                        return@withContext retryOutcome(dao.byEpisode(row.episodeId), row, target)
                    }
                    index.put(row.episodeId, uri)
                    deletes.drain(db.playSessionDao().get()?.currentEpisodeId)
                    Outcome.MOVED
                } catch (failure: Exception) {
                    // Never undo a committed row, including a failure delivering its index update.
                    if (renamed &&
                        dao.byEpisode(row.episodeId)?.rootId != target
                    ) {
                        rollback(row, target, path, method)
                    }
                    throw failure
                }
            }
        }

    private fun retryOutcome(
        current: DownloadEntity?,
        snapshot: DownloadEntity,
        target: String,
    ): Outcome = if (current?.state == snapshot.state && current.rootId != target) Outcome.PENDING else Outcome.SKIPPED

    private suspend fun rollback(
        row: DownloadEntity,
        target: String,
        path: String,
        method: Method,
    ) {
        if (method == Method.RENAME) {
            files.moveBetweenRoots(target, path, row.rootId, checkNotNull(row.relativePath))
        } else {
            files.delete(target, path)
        }
    }

    private suspend fun sweepMoves() {
        for (root in roots.list().map { it.rootId } + roots.current()) {
            if (roots.resolve(root) == null) continue
            for (file in files.list(root, ".partial")) {
                if (!file.directory && MOVE_FILE.matches(file.path)) files.delete(root, file.path)
            }
        }
    }

    private suspend fun cleanFolders(visitedRoots: Set<String>) {
        val used =
            dao
                .allRows()
                .mapNotNull { row ->
                    row.relativePath?.let { row.rootId to it.substringBeforeLast('/') }
                }.toSet()
        for (root in visitedRoots) {
            if (roots.resolve(root) == null) continue
            for (folder in files.list(root, "")) {
                if (!folder.directory || !PODCAST_FOLDER.matches(folder.path) || (root to folder.path) in used) continue
                if (files.list(root, folder.path).isEmpty()) files.delete(root, folder.path)
            }
        }
    }

    private suspend fun method(
        source: String,
        target: String,
    ) = if (files.sameStore(source, target)) Method.RENAME else Method.COPY

    private enum class Method { COPY, RENAME }

    private enum class Artifact { NEW, ADOPTED }

    private enum class Outcome { MOVED, SKIPPED, UNAVAILABLE, PENDING, DEADLINE, FULL }

    private companion object {
        const val BATCH_SIZE = 20
        const val SPACE_MARGIN_BYTES = 200L * 1024 * 1024
        val MOVE_FILE = Regex("\\.partial/[0-9]+\\.move")
        val PODCAST_FOLDER = Regex(".+ \\[p[0-9]+\\]")
    }
}
