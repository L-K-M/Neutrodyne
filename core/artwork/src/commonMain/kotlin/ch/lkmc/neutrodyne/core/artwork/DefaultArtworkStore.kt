// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.artwork

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.database.ArtworkEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * [ArtworkStore] over the `artwork` table and `<filesDir>/artwork` (08). The index is a
 * `MutableStateFlow` key → absolute file `Path`, loaded from the DAO on [refreshIndex]; until
 * loaded it reads empty, and Coil falls back to the URL.
 *
 * M1a slice: the index and lookup members are live; `pin`/`unpin`/`collectGarbage` record only
 * what is deterministic without the M4 pipeline — `pin` upserts the descriptor row so M4's first
 * `syncCandidates` pass picks it up, `unpin` and `collectGarbage` are no-ops until the runner and
 * 02's garbage query exist (deviation 2026-10-07, 08 ArtworkStore).
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class DefaultArtworkStore(
    private val db: NeutrodyneDatabase,
    private val paths: StoragePaths,
    private val uris: ArtworkContentUris,
    @param:ApplicationScope private val scope: CoroutineScope,
) : ArtworkStore {
    /** Key → absolute file path; `null` until [refreshIndex] ran (lookups then return null). */
    private val index = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Path>?>(null)

    private val root: Path get() = paths.filesDir.toPath() / ARTWORK_DIR

    override fun pinnedFile(key: String): Path? = index.value?.get(key)?.takeIf { FileSystem.SYSTEM.exists(it) }

    override fun pin(
        ref: ArtworkRef,
        reason: PinReason,
        ownerId: Long,
    ) {
        // No fetch pipeline before M4; recording the descriptor row is all that is deterministic.
        scope.launch { db.artworkDao().upsert(ArtworkEntity(key = ref.key, url = ref.url)) }
    }

    override fun unpin(
        key: String,
        reason: PinReason,
        ownerId: Long,
    ) {
        // GC passes start with the M4 runner; unreferenced rows are collected there.
    }

    override fun contentUri(
        key: String,
        version: Int,
    ): String = uris.of(key, version, pinnedPath(key))

    override fun isPinned(key: String): Boolean = index.value?.containsKey(key) == true

    override fun pinnedPath(key: String): Path? = index.value?.get(key)

    override suspend fun collectGarbage(): Int = 0

    /** Rebuilds the in-memory index from `artwork` rows; the M4 runner calls this after batches. */
    public suspend fun refreshIndex() {
        val rows = db.artworkDao().pinnedRows()
        index.value =
            rows.associate { it.key to (root / it.localPath.toPath()) }
    }

    private companion object {
        const val ARTWORK_DIR = "artwork"
    }
}
