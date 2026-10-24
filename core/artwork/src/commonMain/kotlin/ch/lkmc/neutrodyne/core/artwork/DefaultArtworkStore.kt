// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.artwork

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.common.art.Monogram
import ch.lkmc.neutrodyne.core.database.ArtworkEntity
import ch.lkmc.neutrodyne.core.database.ArtworkSyncResult
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ExposeImplBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer

/**
 * One process owns pinned files. The lock spans file publication and its row/index update,
 * including provider fallbacks and GC; image fetching/encoding happens outside it.
 * Metro exposes this implementation to the shell graphs so all consumers share that lock/index;
 * construction and file mutation remain internal to the module.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@ExposeImplBinding
public class DefaultArtworkStore
    @Inject
    internal constructor(
        private val db: NeutrodyneDatabase,
        paths: StoragePaths,
        private val uris: ArtworkContentUris,
        @param:ApplicationScope private val scope: CoroutineScope,
        private val scheduler: ArtworkSyncScheduler,
        @param:Dispatcher(NeutrodyneDispatchers.IO) private val io: CoroutineDispatcher,
    ) : ArtworkStore {
        private val index = MutableStateFlow<Map<String, Path>>(emptyMap())
        private val files = Mutex()
        private val root = paths.filesDir.toPath() / "artwork"
        private val fs = FileSystem.SYSTEM

        override fun pinnedFile(key: String): Path? = pinnedPath(key)?.takeIf { fs.exists(it) }

        override fun pin(
            ref: ArtworkRef,
            reason: PinReason,
            ownerId: Long,
        ) {
            require(ArtworkKeys.VALID.matches(ref.key)) { "Invalid artwork key" }
            scope.launch(io) {
                db.artworkDao().insertIfAbsent(ArtworkEntity(key = ref.key, url = ref.url))
                scheduler.request()
            }
        }

        override fun unpin(
            key: String,
            reason: PinReason,
            ownerId: Long,
        ) {
            scope.launch(io) { collectGarbage() }
        }

        override fun contentUri(
            key: String,
            version: Int,
        ): String = uris.of(key, version, pinnedPath(key))

        override fun isPinned(key: String): Boolean = index.value.containsKey(key)

        override fun pinnedPath(key: String): Path? = index.value[key]

        override suspend fun collectGarbage(): Int =
            files.withLock {
                // Hold a writer transaction so a new subscription cannot gain a reference between
                // the garbage query and deletion. Files always precede their row deletion.
                val count =
                    db.withWriteTransaction {
                        val garbage = db.artworkDao().garbage()
                        for (row in garbage) {
                            if (ArtworkKeys.VALID.matches(row.key)) deleteVariants(row.key, keep = null)
                            db.artworkDao().delete(row.key)
                        }
                        db.artworkDao().recountPins()
                        garbage.size
                    }
                refreshIndexLocked()
                count
            }

        internal suspend fun refreshIndex() = files.withLock { refreshIndexLocked() }

        private suspend fun refreshIndexLocked() {
            index.value =
                db
                    .artworkDao()
                    .pinnedRows()
                    .mapNotNull { row ->
                        // Stored relative paths are never trusted as arbitrary filesystem paths.
                        safePath(row.key, row.localPath)?.let { row.key to it }
                    }.toMap()
        }

        /** Re-read metadata under the lock: a provider may have installed a fallback during fetch. */
        internal suspend fun applyBatch(
            produced: List<ArtworkProduction>,
            now: Long,
        ) = files.withLock {
            val rows =
                produced.map { result ->
                    val old = db.artworkDao().byKey(result.key) ?: ArtworkEntity(result.key)
                    val image = result.image
                    if (image == null) {
                        val attempt = (old.lastError?.substringAfterLast(':')?.toIntOrNull() ?: 0) + 1
                        return@map old.copy(fetchedAt = now, lastError = "${result.error}:$attempt").syncResult()
                    }
                    val filename = "${result.key}.${image.extension}"
                    val target = root / filename
                    val previous = old.localPath?.let { safePath(old.key, it) }
                    val unchanged =
                        previous != null && fs.exists(previous) &&
                            fs.read(previous) { readByteString().sha256() } == image.bytes.sha256()
                    if (!unchanged || previous != target) write(result.key, filename, image)
                    // A successful fetch removes the interim fallback even if an old file vanished.
                    deleteVariants(result.key, keep = filename)
                    old
                        .copy(
                            url = result.descriptor,
                            localPath = filename,
                            width = image.width,
                            height = image.height,
                            seedArgb = result.monogramColor,
                            avgArgb = result.monogramColor,
                            version = old.version + if (unchanged) 0 else 1,
                            fetchedAt = now,
                            lastError = null,
                        ).syncResult()
                }
            db.artworkDao().applyBatch(rows)
            refreshIndexLocked()
        }

        /** Opens the descriptor while still holding the lock, so GC/replacement cannot unlink it first. */
        internal suspend fun <T> openLocal(
            key: String,
            render: (String) -> EncodedArtwork,
            open: (Path) -> T,
        ): T? =
            files.withLock {
                // The index can be empty on the first provider call of a cold process.
                refreshIndexLocked()
                pinnedFile(key)?.let { return@withLock open(it) }
                val source = db.artworkDao().fallbackFor(key) ?: return@withLock null
                val spec = Monogram.spec(source.title)
                val encoded = render(source.title)
                val filename = if (key.startsWith("m-")) "$key.png" else "$key.fallback.png"
                val old = db.artworkDao().byKey(key) ?: ArtworkEntity(key)
                write(key, filename, encoded)
                deleteVariants(key, keep = filename)
                db.artworkDao().applyBatch(
                    listOf(
                        old
                            .copy(
                                url = monogramDescriptor(spec),
                                localPath = filename,
                                width = encoded.width,
                                height = encoded.height,
                                version = old.version + 1,
                                // Retry timestamps/errors describe the URL attempt, never the fallback raster.
                            ).syncResult(),
                    ),
                )
                refreshIndexLocked()
                open(root / filename)
            }

        private fun write(
            key: String,
            filename: String,
            image: EncodedArtwork,
        ) {
            require(ArtworkKeys.VALID.matches(key))
            require(image.extension == "jpg" || image.extension == "png")
            fs.createDirectories(root)
            val temporary = root / "$key.tmp"
            try {
                fs.openReadWrite(temporary).use { handle ->
                    handle.resize(0)
                    handle.sink().buffer().use { it.write(image.bytes) }
                    handle.flush()
                }
                fs.atomicMove(temporary, root / filename)
            } finally {
                fs.delete(temporary, mustExist = false)
            }
        }

        private fun deleteVariants(
            key: String,
            keep: String?,
        ) {
            for (suffix in listOf("jpg", "png", "fallback.png", "tmp")) {
                val name = "$key.$suffix"
                if (name != keep) fs.delete(root / name, mustExist = false)
            }
        }

        private fun safePath(
            key: String,
            filename: String,
        ): Path? {
            if (!ArtworkKeys.VALID.matches(key)) return null
            if (filename !in listOf("$key.jpg", "$key.png", "$key.fallback.png")) return null
            return root / filename
        }
    }

internal fun ArtworkEntity.syncResult(): ArtworkSyncResult =
    ArtworkSyncResult(
        key,
        url,
        localPath,
        width,
        height,
        seedArgb,
        avgArgb,
        version,
        fetchedAt,
        lastError,
    )
