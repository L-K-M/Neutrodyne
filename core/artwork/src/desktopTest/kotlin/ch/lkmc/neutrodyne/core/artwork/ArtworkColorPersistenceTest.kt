// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.artwork

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.common.art.Monogram
import ch.lkmc.neutrodyne.core.database.ArtworkEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.model.art.MonogramMode
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Room and the real store over generated local bytes; no image codec or network is involved. */
class ArtworkColorPersistenceTest {
    private class Fixture(
        val scope: TestScope,
    ) : AutoCloseable {
        val root = Files.createTempDirectory("artwork-colours")
        var db: NeutrodyneDatabase = TestDb.file(root.resolve("db"))
        val key = "u-" + "a".repeat(40)
        val url = "https://synthetic.test/art.jpg"
        val first = EncodedArtwork("first encoded pixels".encodeUtf8(), 512, 512, "jpg")
        val colors = ExtractedArtworkColors(0xffaa4422.toInt(), 0xff998877.toInt())
        val paths = StoragePaths(AppDirs(root, root, root, root, root, root))
        val scheduler =
            ArtworkSyncScheduler(
                object : ArtworkWorkScheduler {
                    override suspend fun enqueue() {}
                },
                scope.backgroundScope,
            )
        var store = makeStore()

        fun makeStore() =
            DefaultArtworkStore(
                db,
                paths,
                DesktopArtworkContentUris(),
                scope,
                scheduler,
                StandardTestDispatcher(scope.testScheduler),
            )

        suspend fun seed() {
            db.podcastDao().insertPodcast(podcastEntity(1).copy(artworkKey = key, artworkUrl = url))
            store.applyBatch(listOf(ArtworkProduction(key, url, first)), 1L)
        }

        suspend fun row() = assertNotNull(db.artworkDao().byKey(key))

        suspend fun snapshot() = assertNotNull(store.localSnapshot(row()))

        override fun close() {
            db.close()
            root.toFile().deleteRecursively()
        }
    }

    private suspend fun TestScope.withFixture(block: suspend Fixture.() -> Unit) {
        Fixture(this).use { it.block() }
    }

    @Test fun identicalBytesFillColoursAndFailureRetainsThem() =
        runTest {
            withFixture {
                seed()
                val old = row()
                store.applyBatch(listOf(ArtworkProduction(key, url, first, colours = colors)), 2L)
                assertEquals(
                    old.copy(seedArgb = colors.seedArgb, avgArgb = colors.avgArgb, fetchedAt = 2L),
                    row(),
                )
                store.applyBatch(listOf(ArtworkProduction(key, url, error = "HTTP_503")), 3L)
                val failed = row()
                assertEquals(colors.seedArgb, failed.seedArgb)
                assertEquals(colors.avgArgb, failed.avgArgb)
                assertEquals(old.version, failed.version)
                assertEquals("HTTP_503:1", failed.lastError)
                assertEquals(first.bytes, snapshot().bytes)
                store.applyBatch(
                    listOf(ArtworkProduction(key, url, first, colours = ExtractedArtworkColors(null, 0))),
                    4L,
                )
                assertNull(row().seedArgb)
                assertEquals(0, row().avgArgb)
                assertNull(row().lastError)
            }
        }

    @Test fun colourOnlyBackfillPreservesEveryOtherFieldAcrossReopen() =
        runTest {
            withFixture {
                seed()
                db.artworkDao().upsert(row().copy(pinCount = 7, lastError = "HTTP_503:2", fetchedAt = 12L))
                val old = row()
                store.applyBatch(listOf(ArtworkProduction(key, url, colours = colors, backfill = snapshot())), 99L)
                val expected = old.copy(seedArgb = colors.seedArgb, avgArgb = colors.avgArgb)
                assertEquals(expected, row())
                db.close()
                db = TestDb.file(root.resolve("db"))
                store = makeStore()
                store.refreshIndex()
                assertEquals(expected, row())
                assertEquals(first.bytes, snapshot().bytes)
            }
        }

    @Test fun backfillCannotOverwriteReplacementProviderFallbackOrDeletedRows() =
        runTest {
            withFixture {
                seed()
                val stale = ArtworkProduction(key, url, colours = colors, backfill = snapshot())
                val replacement = first.copy(bytes = "replacement encoded pixels".encodeUtf8())
                store.applyBatch(
                    listOf(ArtworkProduction(key, url, replacement, colours = ExtractedArtworkColors(null, 0))),
                    2L,
                )
                val replaced = row()
                store.applyBatch(listOf(stale), 3L)
                assertEquals(replaced, row())

                // A provider replaces a vanished local image while extraction is in flight.
                db.artworkDao().upsert(row().copy(seedArgb = null, avgArgb = null))
                val beforeProvider = ArtworkProduction(key, url, colours = colors, backfill = snapshot())
                Files.delete(root.resolve("artwork/$key.jpg"))
                store.openLocal(key, { first.copy(extension = "png") }, { it })
                val fallback = row()
                assertEquals("$key.fallback.png", fallback.localPath)
                assertEquals(Monogram.spec("Example podcast").colors(MonogramMode.RASTER).first, fallback.avgArgb)
                store.applyBatch(listOf(beforeProvider), 4L)
                assertEquals(fallback, row())

                db.artworkDao().delete(key)
                store.applyBatch(listOf(stale), 5L)
                assertNull(db.artworkDao().byKey(key))
            }
        }

    @Test fun outOfBandFileReplacementInvalidatesSnapshotEvenWhenRowIsUnchanged() =
        runTest {
            withFixture {
                seed()
                val old = row()
                val stale = ArtworkProduction(key, url, colours = colors, backfill = snapshot())
                Files.write(root.resolve("artwork/$key.jpg"), byteArrayOf(1, 2, 3))
                store.applyBatch(listOf(stale), 2L)
                assertEquals(old, row())
                Files.delete(root.resolve("artwork/$key.jpg"))
                store.applyBatch(listOf(stale), 3L)
                assertEquals(old, row())
            }
        }

    @Test fun changedBytesWithoutExtractionClearOldColoursAndMosaicsNeverKeepASeed() =
        runTest {
            withFixture {
                seed()
                store.applyBatch(listOf(ArtworkProduction(key, url, first, colours = colors)), 2L)
                store.applyBatch(listOf(ArtworkProduction(key, url, first.copy(bytes = "new pixels".encodeUtf8()))), 3L)
                assertNull(row().avgArgb)
                assertNull(row().seedArgb)
                assertEquals(2, row().version)
                val mosaic = "g-12345678-1234-1234-1234-123456789abc"
                store.applyBatch(listOf(ArtworkProduction(mosaic, "nd:mosaic:test", first, colours = colors)), 4L)
                assertNull(db.artworkDao().byKey(mosaic)?.seedArgb)
                assertEquals(colors.avgArgb, db.artworkDao().byKey(mosaic)?.avgArgb)
            }
        }
}
