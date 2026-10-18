// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.artwork

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.database.ArtworkEntity
import ch.lkmc.neutrodyne.core.database.PodcastEntity
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import java.nio.file.Files
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
                        localPath = "u-" + "a".repeat(40) + ".jpg",
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
                val store =
                    DefaultArtworkStore(
                        db,
                        paths,
                        DesktopArtworkContentUris(),
                        this,
                        ArtworkSyncScheduler(
                            object : ArtworkWorkScheduler {
                                override suspend fun enqueue() {}
                            },
                            backgroundScope,
                        ),
                        StandardTestDispatcher(testScheduler),
                    )
                val requested = ArtworkRef(stored.key, "https://img.example/changed.jpg", version = 0)

                repeat(2) {
                    store.pin(requested, PinReason.SUBSCRIPTION, ownerId = 7)
                    advanceUntilIdle()
                    assertEquals(stored, db.artworkDao().byKey(stored.key))
                    store.refreshIndex()
                    assertTrue(store.isPinned(stored.key))
                    assertEquals(
                        root.resolve("artwork/${stored.key}.jpg").toString(),
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
                val store =
                    DefaultArtworkStore(
                        db,
                        paths,
                        DesktopArtworkContentUris(),
                        this,
                        ArtworkSyncScheduler(
                            object : ArtworkWorkScheduler {
                                override suspend fun enqueue() {}
                            },
                            backgroundScope,
                        ),
                        StandardTestDispatcher(testScheduler),
                    )
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

    @Test
    fun `partial batch retains reference count and version follows bytes`() =
        runTest {
            val db = TestDb.inMemory(queryContext = StandardTestDispatcher(testScheduler))
            val root =
                java.nio.file.Files
                    .createTempDirectory("artwork-byte-version")
            try {
                val paths = StoragePaths(AppDirs(root, root, root, root, root, root))
                val store =
                    DefaultArtworkStore(
                        db,
                        paths,
                        DesktopArtworkContentUris(),
                        this,
                        ArtworkSyncScheduler(
                            object : ArtworkWorkScheduler {
                                override suspend fun enqueue() {}
                            },
                            backgroundScope,
                        ),
                        StandardTestDispatcher(testScheduler),
                    )
                val key = "u-" + "c".repeat(40)
                val url = "https://synthetic.test/cover"
                db.artworkDao().upsert(ArtworkEntity(key, pinCount = 7))
                val image = EncodedArtwork("synthetic JPEG bytes".encodeUtf8(), 512, 512, "jpg")
                store.applyBatch(listOf(ArtworkProduction(key, url, image)), 1L)
                assertEquals(1, db.artworkDao().byKey(key)?.version)
                assertEquals(7, db.artworkDao().byKey(key)?.pinCount)
                store.applyBatch(listOf(ArtworkProduction(key, url, image)), 2L)
                assertEquals(1, db.artworkDao().byKey(key)?.version)
                assertEquals(2L, db.artworkDao().byKey(key)?.fetchedAt)
                store.applyBatch(listOf(ArtworkProduction(key, url, error = "HTTP_404")), 3L)
                val failed = db.artworkDao().byKey(key)!!
                assertEquals("HTTP_404:1", failed.lastError)
                assertEquals(url, failed.url)
                assertEquals(1, failed.version)
                assertTrue(store.pinnedFile(key) != null)
                val png = image.copy(bytes = "synthetic PNG bytes".encodeUtf8(), extension = "png")
                store.applyBatch(listOf(ArtworkProduction(key, url, png)), 4L)
                assertEquals(2, db.artworkDao().byKey(key)?.version)
                assertTrue(
                    java.nio.file.Files
                        .exists(root.resolve("artwork/$key.png")),
                )
                assertTrue(
                    !java.nio.file.Files
                        .exists(root.resolve("artwork/$key.jpg")),
                )
                assertTrue(
                    !java.nio.file.Files
                        .exists(root.resolve("artwork/$key.tmp")),
                )
            } finally {
                db.close()
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun `index rejects stored traversal and missing files are not served`() =
        runTest {
            val db = TestDb.inMemory(queryContext = StandardTestDispatcher(testScheduler))
            val root =
                java.nio.file.Files
                    .createTempDirectory("artwork-paths")
            try {
                val store =
                    DefaultArtworkStore(
                        db,
                        StoragePaths(AppDirs(root, root, root, root, root, root)),
                        DesktopArtworkContentUris(),
                        this,
                        ArtworkSyncScheduler(
                            object : ArtworkWorkScheduler {
                                override suspend fun enqueue() {}
                            },
                            backgroundScope,
                        ),
                        StandardTestDispatcher(testScheduler),
                    )
                val key = "u-" + "d".repeat(40)
                db.artworkDao().upsert(ArtworkEntity(key, localPath = "../outside.jpg"))
                store.refreshIndex()
                assertEquals(null, store.pinnedPath(key))
                db.artworkDao().upsert(ArtworkEntity(key, localPath = "$key.jpg"))
                store.refreshIndex()
                assertTrue(store.isPinned(key))
                assertEquals(null, store.pinnedFile(key))
            } finally {
                db.close()
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun `garbage collection uses real references rather than stale pin counts`() =
        runTest {
            val db = TestDb.inMemory(queryContext = StandardTestDispatcher(testScheduler))
            val root =
                java.nio.file.Files
                    .createTempDirectory("artwork-garbage")
            try {
                val store =
                    DefaultArtworkStore(
                        db,
                        StoragePaths(AppDirs(root, root, root, root, root, root)),
                        DesktopArtworkContentUris(),
                        this,
                        ArtworkSyncScheduler(
                            object : ArtworkWorkScheduler {
                                override suspend fun enqueue() {}
                            },
                            backgroundScope,
                        ),
                        StandardTestDispatcher(testScheduler),
                    )
                val wanted = "u-" + "e".repeat(40)
                val garbage = "u-" + "f".repeat(40)
                db.podcastDao().insertPodcast(
                    PodcastEntity(
                        syncId = "12345678-1234-1234-1234-123456789abc",
                        sourceType = SourceType.RSS,
                        feedUrl = "https://synthetic.test/feed",
                        feedKey = "synthetic.test/feed",
                        title = "Synthetic",
                        artworkKey = wanted,
                        status = PodcastStatus.ACTIVE,
                        initialFetch = false,
                        subscribedAt = 1L,
                    ),
                )
                db.artworkDao().upsert(ArtworkEntity(garbage, pinCount = 99))
                val image = EncodedArtwork("synthetic bytes".encodeUtf8(), 512, 512, "jpg")
                store.applyBatch(
                    listOf(ArtworkProduction(wanted, "url", image), ArtworkProduction(garbage, "url", image)),
                    1L,
                )
                assertEquals(1, store.collectGarbage())
                assertEquals(null, db.artworkDao().byKey(garbage))
                assertEquals(1, db.artworkDao().byKey(wanted)?.pinCount)
                assertTrue(
                    !java.nio.file.Files
                        .exists(root.resolve("artwork/$garbage.jpg")),
                )
                assertTrue(store.pinnedFile(wanted) != null)
                assertEquals(0, store.collectGarbage())
            } finally {
                db.close()
                root.toFile().deleteRecursively()
            }
        }
}
