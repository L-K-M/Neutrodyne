// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.importing.ImportOptionsJson
import ch.lkmc.neutrodyne.core.data.importing.ImportSessionCleaner
import ch.lkmc.neutrodyne.core.data.importing.PayloadStore
import ch.lkmc.neutrodyne.core.domain.ImportError
import ch.lkmc.neutrodyne.core.model.ImportOptions
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.network.OkHttpNeutrodyneHttpClients
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `ImportSessionCleaner` (05 §9, 02 `db-maintenance` step 3) against a real Room database and
 * a real `PayloadStore`: expired `DONE`/`CANCELLED`/abandoned `PREVIEW` sessions lose payload
 * then row, active sessions are never swept, and a failed file delete keeps the row so the
 * next pass retries the file first. The restore-request races run as real concurrent writers:
 * a request committed before the sweep's ownership claim keeps row AND payload, a sweep that
 * wins leaves the requester `SessionGone` — never a live row pointing at a deleted payload.
 */
class ImportSessionCleanerTest {
    private val clock = TestClock()
    private val driver = CommitCancellingDriver()
    private val db = TestDb.inMemory(driver = driver, clock = clock)
    private val fileSystem = HookedDeleteFileSystem(FileSystem.SYSTEM)

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
                fileSystem = fileSystem,
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

    // --- The restore-request race (05 §9's abandoned-PREVIEW rule) --------------------------------------

    @Test
    fun aRestoreRequestCommittedDuringThePayloadDeleteNeverStrandsARow() =
        runTest {
            // A restore intent landing while the payload delete runs must not leave a live
            // row pointing at a deleted file — either the request wins outright (row and
            // payload both kept) or the sweep wins (both gone). The hook writes it
            // synchronously on the same connection, so no nested transaction is awaited.
            val sid =
                db.seedImportSession(
                    state = ImportState.PREVIEW,
                    createdAt = 0,
                    payloadPath = "import/a.bin",
                )
            writePayload("a.bin")
            fileSystem.onDelete = {
                driver.exec(
                    "UPDATE import_session SET optionsJson = " +
                        "'${ImportOptionsJson.encode(ImportOptions(restoreRequestedAt = clock.now()))}'" +
                        " WHERE id = $sid",
                )
            }

            cleaner.cleanExpired()

            val session = db.importDao().sessionById(sid)
            if (session != null) {
                assertTrue(
                    payloadFile("a.bin").exists(),
                    "a live row must keep its payload",
                )
            }
        }

    @Test
    fun aCommittedRestoreRequestKeepsRowAndPayload() =
        runTest {
            val sid =
                db.seedImportSession(
                    state = ImportState.PREVIEW,
                    createdAt = 0,
                    payloadPath = "import/a.bin",
                )
            writePayload("a.bin")

            // The requester's own write transaction is genuinely open and holding the writer
            // lock while the sweep runs — the committed request wins and keeps both.
            val inRequest = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val requester =
                async(Dispatchers.Default) {
                    db.withWriteTransaction {
                        checkNotNull(db.importDao().sessionById(sid))
                        inRequest.complete(Unit)
                        release.await()
                        db
                            .importDao()
                            .setOptionsJson(
                                sid,
                                ImportOptionsJson.encode(
                                    ImportOptions(restoreRequestedAt = clock.now()),
                                ),
                            )
                    }
                }
            inRequest.await()
            val pass = async(Dispatchers.Default) { cleaner.cleanExpired() }
            release.complete(Unit)
            requester.await()

            assertEquals(0, pass.await(), "the requested row is not the sweep's")
            val session = assertNotNull(db.importDao().sessionById(sid))
            assertNotNull(
                ImportOptionsJson.decode(requireNotNull(session.optionsJson)).restoreRequestedAt,
                "the committed request stands",
            )
            assertTrue(payloadFile("a.bin").exists(), "the payload stays with its row")
        }

    @Test
    fun aSweepWinningTheRestoreRaceDeletesBothAndFailsTheRequester() =
        runTest {
            val sid =
                db.seedImportSession(
                    state = ImportState.PREVIEW,
                    createdAt = 0,
                    payloadPath = "import/a.bin",
                )
            writePayload("a.bin")

            // The sweep holds its write transaction across the payload delete, so the
            // delete hook is where its ownership claim is held: the requester only starts
            // there, and its own transaction can then commit only after the sweep's — it
            // must find the row gone. The hook waits on the requester STARTING, never on
            // its transaction (which cannot run while the sweep holds the writer lock).
            val claimHeld = CompletableDeferred<Unit>()
            val requesterStarted = CountDownLatch(1)
            fileSystem.onDelete = {
                claimHeld.complete(Unit)
                requesterStarted.await()
            }
            val requester =
                async(Dispatchers.Default) {
                    claimHeld.await()
                    requesterStarted.countDown()
                    db.requestImportRestore(sid, clock.now())
                }

            assertEquals(1, cleaner.cleanExpired())
            val outcome = requester.await()

            assertEquals(
                ImportError.SessionGone,
                assertIs<Outcome.Failure<ImportError>>(outcome).error,
            )
            assertNull(db.importDao().sessionById(sid))
            assertTrue(!payloadFile("a.bin").exists())
        }

    @Test
    fun aCorruptOptionsRowIsSkippedAndLaterCandidatesStillSweep() =
        runTest {
            val wedged =
                db.seedImportSession(
                    state = ImportState.PREVIEW,
                    createdAt = 0,
                    payloadPath = "import/w.bin",
                )
            db.importDao().setOptionsJson(wedged, "{corrupt")
            writePayload("w.bin")
            val gone =
                db.seedImportSession(
                    state = ImportState.DONE,
                    createdAt = 0,
                    finishedAt = 0,
                    payloadPath = "import/g.bin",
                )
            writePayload("g.bin")

            assertEquals(1, cleaner.cleanExpired(), "the corrupt row never aborts the pass")

            assertNotNull(db.importDao().sessionById(wedged), "an unreadable row is left alone")
            assertTrue(payloadFile("w.bin").exists(), "its payload stays with it")
            assertNull(db.importDao().sessionById(gone), "later candidates still sweep")
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
