// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.artwork

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.database.ArtworkEntity
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultArtworkStoreTest {
    @Test
    fun `repeated pin preserves every field of stored artwork`() =
        runTest {
            val db = TestDb.inMemory(queryContext = StandardTestDispatcher(testScheduler))
            try {
                val stored =
                    ArtworkEntity(
                        key = "u-" + "a".repeat(40),
                        url = "https://img.example/stored.jpg",
                        localPath = "stored.jpg",
                        width = 640,
                        height = 480,
                        seedArgb = 0xff3366aa.toInt(),
                        avgArgb = 0xff778899.toInt(),
                        version = 3,
                        fetchedAt = 1_791_072_000_000L,
                        pinCount = 2,
                        lastError = "Previous refresh failed",
                    )
                db.artworkDao().upsert(stored)
                val root = Path.of("build/tmp/artwork-store").toAbsolutePath()
                val paths = StoragePaths(AppDirs(root, root, root, root, root, root))
                val store = DefaultArtworkStore(db, paths, DesktopArtworkContentUris(), this)
                val requested = ArtworkRef(stored.key, "https://img.example/changed.jpg", version = 0)

                repeat(2) {
                    store.pin(requested, PinReason.SUBSCRIPTION, ownerId = 7)
                    advanceUntilIdle()
                    assertEquals(stored, db.artworkDao().byKey(stored.key))
                    store.refreshIndex()
                    assertTrue(store.isPinned(stored.key))
                    assertEquals(
                        root.resolve("artwork/stored.jpg").toString(),
                        store.pinnedPath(stored.key).toString(),
                    )
                }
            } finally {
                db.close()
            }
        }

    @Test
    fun `pin inserts a missing row only once`() =
        runTest {
            val db = TestDb.inMemory(queryContext = StandardTestDispatcher(testScheduler))
            try {
                val root = Path.of("build/tmp/artwork-store").toAbsolutePath()
                val paths = StoragePaths(AppDirs(root, root, root, root, root, root))
                val store = DefaultArtworkStore(db, paths, DesktopArtworkContentUris(), this)
                val ref = ArtworkRef("u-" + "b".repeat(40), "https://img.example/first.jpg", version = 0)
                store.pin(ref, PinReason.SUBSCRIPTION, ownerId = 7)
                advanceUntilIdle()
                val inserted = ArtworkEntity(key = ref.key, url = ref.url)
                assertEquals(inserted, db.artworkDao().byKey(ref.key))

                store.pin(ref.copy(url = "https://img.example/second.jpg"), PinReason.DOWNLOAD, ownerId = 8)
                advanceUntilIdle()
                assertEquals(inserted, db.artworkDao().byKey(ref.key))
            } finally {
                db.close()
            }
        }
}
