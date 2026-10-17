// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.data.importing.ImportSessionCleaner
import ch.lkmc.neutrodyne.core.data.importing.PayloadStore
import ch.lkmc.neutrodyne.core.model.ImportOptions
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.network.OkHttpNeutrodyneHttpClients
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `ImportSessionCleaner` (05 §9, 02 `db-maintenance` step 3) against a real Room database and
 * a real `PayloadStore`: expired `DONE`/`CANCELLED`/abandoned `PREVIEW` sessions lose payload
 * then row, active sessions are never swept, and a failed file delete keeps the row so the
 * next pass retries the file first.
 */
class ImportSessionCleanerTest {
    private val clock = TestClock()
    private val db = TestDb.inMemory(clock = clock)

    private lateinit var root: File
    private lateinit var store: PayloadStore
    private lateinit var cleaner: ImportSessionCleaner

    @BeforeTest
    fun setUp() {
        root =
            kotlin.io.path
                .createTempDirectory("nd-import-clean")
                .toFile()
        store =
            PayloadStore(
                storagePaths = storagePathsFor(root),
                fileSystem = FileSystem.SYSTEM,
                payloads = FakePayloadSource(),
                httpClients = OkHttpNeutrodyneHttpClients(newNetworkClients(), testUserAgent()),
                netErrors = testClassifier,
                io = Dispatchers.Default,
            )
        cleaner = ImportSessionCleaner(db, store, clock, Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    @Test
    fun expiredSessionsLosePayloadThenRow() =
        runTest {
            val expired =
                db.seedImportSession(
                    state = ImportState.DONE,
                    createdAt = 0,
                    finishedAt = clock.now() - EIGHT_DAYS,
                    payloadPath = payloadOf("done.bin"),
                    items = listOf(importItem(1, "https://a.example.com/f")),
                )
            writePayload("done.bin")
            val cancelled =
                db.seedImportSession(
                    state = ImportState.CANCELLED,
                    createdAt = 0,
                    finishedAt = clock.now() - EIGHT_DAYS,
                    payloadPath = "import/cancelled.bin",
                )
            writePayload("cancelled.bin")

            assertEquals(2, cleaner.cleanExpired())

            assertNull(db.importDao().sessionById(expired))
            assertNull(db.importDao().sessionById(cancelled))
            assertTrue(db.importDao().itemsOf(expired).isEmpty(), "items cascade with the row")
            assertTrue(!payloadFile("done.bin").exists())
            assertTrue(!payloadFile("cancelled.bin").exists())
        }

    @Test
    fun activeAndYoungSessionsAreNeverSwept() =
        runTest {
            val committed =
                db.seedImportSession(state = ImportState.COMMITTED, createdAt = 0, payloadPath = "import/c.bin")
            val fetching =
                db.seedImportSession(state = ImportState.FETCHING, createdAt = 0, payloadPath = "import/f.bin")
            val fresh =
                db.seedImportSession(state = ImportState.DONE, createdAt = 0, finishedAt = clock.now() - SIX_DAYS)
            writePayload("c.bin")
            writePayload("f.bin")

            assertEquals(0, cleaner.cleanExpired())

            assertNotNull(db.importDao().sessionById(committed))
            assertNotNull(db.importDao().sessionById(fetching))
            assertNotNull(db.importDao().sessionById(fresh))
            assertTrue(payloadFile("c.bin").exists())
            assertTrue(payloadFile("f.bin").exists())
        }

    @Test
    fun abandonedPreviewsSweepUnlessARestoreWasRequested() =
        runTest {
            val abandoned =
                db.seedImportSession(state = ImportState.PREVIEW, createdAt = 0, payloadPath = "import/a.bin")
            writePayload("a.bin")
            val requested =
                db.seedImportSession(
                    state = ImportState.PREVIEW,
                    createdAt = 0,
                    options = ImportOptions(restoreRequestedAt = clock.now() - EIGHT_DAYS),
                )

            assertEquals(1, cleaner.cleanExpired())

            assertNull(db.importDao().sessionById(abandoned))
            assertTrue(!payloadFile("a.bin").exists())
            assertNotNull(db.importDao().sessionById(requested), "a requested restore is its host's")
        }

    @Test
    fun aFailedPayloadDeleteKeepsTheRowForTheNextPass() =
        runTest {
            // A non-empty directory at the payload path makes FileSystem.delete throw —
            // the row must stay so the next sweep retries the file, not orphans it.
            val sid =
                db.seedImportSession(
                    state = ImportState.DONE,
                    createdAt = 0,
                    finishedAt = clock.now() - EIGHT_DAYS,
                    payloadPath = "import/stuck.bin",
                )
            payloadFile("stuck.bin").mkdirs()
            File(payloadFile("stuck.bin"), "contents").writeText("x")

            assertEquals(0, cleaner.cleanExpired())
            assertNotNull(db.importDao().sessionById(sid), "the row survives a failed delete")

            payloadFile("stuck.bin").deleteRecursively()
            assertEquals(1, cleaner.cleanExpired())
            assertNull(db.importDao().sessionById(sid))
        }

    @Test
    fun aMissingPayloadCountsAsDeleted() =
        runTest {
            val sid =
                db.seedImportSession(
                    state = ImportState.DONE,
                    createdAt = 0,
                    finishedAt = clock.now() - EIGHT_DAYS,
                    payloadPath = "import/gone.bin",
                )

            assertEquals(1, cleaner.cleanExpired())
            assertNull(db.importDao().sessionById(sid))
        }

    @Test
    fun expiredWithoutPayloadStillDeletes() =
        runTest {
            val sid =
                db.seedImportSession(
                    state = ImportState.DONE,
                    createdAt = 0,
                    finishedAt = clock.now() - EIGHT_DAYS,
                )

            assertEquals(1, cleaner.cleanExpired())
            assertNull(db.importDao().sessionById(sid))
        }

    // --- Helpers --------------------------------------------------------------------------------------

    private fun payloadFile(name: String): File = File(root, "cache/import/$name")

    private fun writePayload(name: String) {
        payloadFile(name).apply {
            parentFile?.mkdirs()
            writeText("payload")
        }
    }

    private fun payloadOf(name: String): String = "import/$name"

    private companion object {
        const val EIGHT_DAYS = 8L * 86_400_000
        const val SIX_DAYS = 6L * 86_400_000
    }
}
