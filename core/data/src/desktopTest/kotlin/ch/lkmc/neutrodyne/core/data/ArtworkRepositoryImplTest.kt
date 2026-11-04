// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data

import androidx.room3.useWriterConnection
import app.cash.turbine.test
import ch.lkmc.neutrodyne.core.data.repo.ArtworkRepositoryImpl
import ch.lkmc.neutrodyne.core.database.ArtworkEntity
import ch.lkmc.neutrodyne.core.model.ArtworkColors
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArtworkRepositoryImplTest {
    private val db = TestDb.inMemory()
    private val repo = ArtworkRepositoryImpl(db)
    private val primary = "u-" + "1".repeat(40)
    private val podcast = "u-" + "2".repeat(40)
    private val replacement = "u-" + "3".repeat(40)
    private val seed = 0xffcc4433.toInt()
    private val avg = 0xff776655.toInt()

    private fun row(
        key: String,
        version: Int = 1,
        seedArgb: Int? = seed,
        avgArgb: Int? = avg,
    ) = ArtworkEntity(key, localPath = "$key.jpg", version = version, seedArgb = seedArgb, avgArgb = avgArgb)

    private fun ArtworkEntity.colors() = ArtworkColors(key, version, seedArgb, avgArgb)

    @AfterTest fun close() = db.close()

    @Test fun absenceUnprocessedAndNoLocalPathDoNotExposeColours() =
        runTest {
            repo.observeColors(primary).test {
                assertNull(awaitItem())
                db.artworkDao().upsert(row(primary, avgArgb = null))
                db.artworkDao().upsert(row(primary).copy(localPath = null))
                db.artworkDao().upsert(row(primary))
                assertEquals(row(primary).colors(), awaitItem())
                db.artworkDao().delete(primary)
                assertNull(awaitItem())
            }
        }

    @Test fun observesColourOnlyUpdatesAndRetainsColoursAfterRefreshFailure() =
        runTest {
            db.artworkDao().upsert(row(primary, avgArgb = null))
            repo.observeColors(primary).test {
                assertNull(awaitItem())
                db.artworkDao().upsert(row(primary))
                assertEquals(row(primary).colors(), awaitItem())
                db.artworkDao().upsert(row(primary).copy(lastError = "HTTP_500:1"))
                db.artworkDao().upsert(row(primary, version = 2))
                assertEquals(row(primary, version = 2).colors(), awaitItem())
            }
        }

    @Test fun completedMonochromeAndTransparentNeverUsePodcastSeed() =
        runTest {
            db.podcastDao().insertPodcast(podcastEntity(1).copy(artworkKey = podcast))
            db.artworkDao().upsert(row(podcast))
            db.artworkDao().upsert(row(primary, seedArgb = null))
            repo.observeColors(primary, 1).filterNotNull().test {
                assertEquals(row(primary, seedArgb = null).colors(), awaitItem())
                db.artworkDao().upsert(row(primary, seedArgb = null, avgArgb = 0))
                assertEquals(row(primary, seedArgb = null, avgArgb = 0).colors(), awaitItem())
                db.artworkDao().upsert(row(podcast, version = 2))
                expectNoEvents()
            }
        }

    @Test fun fallbackObservesPodcastKeyChangesAndDeletion() =
        runTest {
            db.podcastDao().insertPodcast(podcastEntity(1).copy(artworkKey = podcast))
            db.artworkDao().upsert(row(podcast))
            db.artworkDao().upsert(row(replacement))
            repo.observeColors(primary, 1).test {
                assertNull(awaitItem())
                assertEquals(row(podcast).colors(), awaitItem())
                db.useWriterConnection { connection ->
                    connection.usePrepared("UPDATE podcast SET artworkKey = ? WHERE id = 1") { statement ->
                        statement.bindText(1, replacement)
                        statement.step()
                    }
                }
                assertNull(awaitItem())
                assertEquals(row(replacement).colors(), awaitItem())
                db.artworkDao().upsert(row(podcast, version = 9))
                db.artworkDao().delete(replacement)
                assertNull(awaitItem())
                db.podcastDao().deleteCascade(1, 100L)
                expectNoEvents()
            }
        }

    @Test fun primaryArrivalCancelsFallbackAndDisappearanceResumesIt() =
        runTest {
            db.podcastDao().insertPodcast(podcastEntity(1).copy(artworkKey = podcast))
            db.artworkDao().upsert(row(podcast))
            repo.observeColors(primary, 1).test {
                assertNull(awaitItem())
                assertEquals(row(podcast).colors(), awaitItem())
                db.artworkDao().upsert(row(primary))
                assertEquals(row(primary).colors(), awaitItem())
                db.artworkDao().upsert(row(podcast, version = 2))
                db.artworkDao().delete(primary)
                assertNull(awaitItem())
                assertEquals(row(podcast, version = 2).colors(), awaitItem())
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun switchingCurrentItemClearsPreviousTintAndCancelsObsoleteEpisode() =
        runTest {
            val current = MutableStateFlow(primary)
            db.artworkDao().upsert(row(primary))
            current.flatMapLatest { repo.observeColors(it) }.test {
                assertNull(awaitItem())
                assertEquals(row(primary).colors(), awaitItem())
                current.value = replacement
                assertNull(awaitItem())
                db.artworkDao().upsert(row(primary, version = 99))
                db.artworkDao().upsert(row(replacement, seedArgb = null))
                assertEquals(row(replacement, seedArgb = null).colors(), awaitItem())
            }
        }

    @Test fun persistedColoursAreObservableAfterProductionDatabaseReopen() =
        runTest {
            val root = Files.createTempDirectory("artwork-repository")
            try {
                val original = TestDb.file(root)
                try {
                    original.artworkDao().upsert(row(primary))
                } finally {
                    original.close()
                }
                val reopened = TestDb.file(root)
                try {
                    ArtworkRepositoryImpl(reopened).observeColors(primary).filterNotNull().test {
                        assertEquals(row(primary).colors(), awaitItem())
                    }
                } finally {
                    reopened.close()
                }
            } finally {
                root.toFile().deleteRecursively()
            }
        }
}
