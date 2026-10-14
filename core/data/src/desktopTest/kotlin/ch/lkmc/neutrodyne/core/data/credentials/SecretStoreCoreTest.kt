// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.credentials

import ch.lkmc.neutrodyne.core.common.Origin
import ch.lkmc.neutrodyne.core.data.seedPodcast
import ch.lkmc.neutrodyne.core.database.CredentialDao
import ch.lkmc.neutrodyne.core.database.CredentialEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.StoredSecret
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.testing.FakeCredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shared `SecretStore` core (03 Basic auth and CredentialStore), driven by scripted row
 * emissions instead of Room's `observeAll` so stale snapshots can be replayed deterministically.
 * The seam "decrypts" by treating `secretCipher` as plaintext bytes — crypto lives in
 * `KeystoreCredentialStore` and its `androidHostTest` Robolectric suite.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SecretStoreCoreTest {
    /**
     * A row whose `secretCipher` carries the plaintext (no crypto — the core's reconcile logic is
     * what is under test). [lostIds] fail with `SecretLostException` (key loss); [brokenIds] fail
     * with a plain error (a transient platform failure — retried, never deleted).
     */
    private class FakeSeam : SecretStoreSeam {
        val lostIds = mutableSetOf<Long>()
        val brokenIds = mutableSetOf<Long>()

        // Written on the collect thread, read by `awaitReal` on a real dispatcher — volatile/atomic.
        @Volatile var resolveCalls = 0

        /** Reconcile passes that reached the apply phase — the "a previous emission finished" probe. */
        @Volatile var reconcilePasses = 0

        @Volatile var rowless = emptyMap<String, StoredSecret>()
        val droppedOrigins = java.util.concurrent.CopyOnWriteArrayList<String>()

        override suspend fun resolve(row: CredentialEntity): StoredSecret? {
            resolveCalls++
            if (row.id in lostIds) throw SecretLostException(IllegalStateException("lost key"))
            if (row.id in brokenIds) throw IllegalStateException("transient")
            return row.secretCipher
                ?.decodeToString()
                ?.let { StoredSecret(row.username, it.toCharArray(), emptyMap()) }
        }

        override suspend fun rowlessEntries(): Map<String, StoredSecret> {
            reconcilePasses++
            return rowless
        }

        override suspend fun rowDropped(origin: String) {
            droppedOrigins += origin
        }
    }

    private fun TestScope.newCore(
        db: NeutrodyneDatabase,
        seam: FakeSeam,
        rows: MutableSharedFlow<List<CredentialEntity>>,
    ) = SecretStoreCore(
        dao = db.credentialDao(),
        podcasts = db.podcastDao(),
        coordinator = FakeCredentialCommitCoordinator(),
        seam = seam,
        scope = backgroundScope,
        rows = rows,
    )

    private fun newFlow() = MutableSharedFlow<List<CredentialEntity>>(extraBufferCapacity = FLOW_BUFFER)

    private suspend fun currentRows(db: NeutrodyneDatabase): List<CredentialEntity> =
        db.credentialDao().observeAll().first()

    /** The platform `put`: "encrypt" by storing the plaintext, keep the row id on replace. */
    private suspend fun persistPlain(
        dao: CredentialDao,
        origin: String,
        username: String,
        secret: String,
    ): PersistedSecret {
        val written =
            CredentialEntity(
                origin = origin,
                username = username,
                secretCipher = secret.encodeToByteArray(),
                iv = byteArrayOf(FAKE_IV_BYTE),
                createdAt = CREATED_AT,
            )
        val id = dao.putByOrigin(written)
        return PersistedSecret(written.copy(id = id), StoredSecret(username, secret.toCharArray(), emptyMap()))
    }

    private suspend fun SecretStoreCore.putPlain(
        db: NeutrodyneDatabase,
        origin: String,
        username: String,
        secret: String,
    ): Long? = put(origin) { persistPlain(db.credentialDao(), it, username, secret) }

    private suspend fun SecretStoreCore.removeRows(
        db: NeutrodyneDatabase,
        origin: String,
    ) = remove(origin) { canonical ->
        val rows = db.credentialDao().byOrigin(canonical)
        for (row in rows) db.credentialDao().delete(row.id)
        rows
    }

    /**
     * Waits (real time — Room work inside a reconcile runs on real dispatchers even under
     * `runTest`) until [check] holds.
     */
    private suspend fun awaitReal(check: suspend () -> Boolean) =
        withContext(Dispatchers.Default) {
            withTimeout(REAL_TIMEOUT_MS) {
                while (!check()) delay(POLL_MS)
            }
        }

    private fun basicAuth(
        username: String,
        secret: String,
    ) = "Basic " + Base64.encode("$username:$secret".encodeToByteArray())

    // --- awaitLoaded -------------------------------------------------------------------------------

    @Test
    fun `load barrier waits for the first emission`() =
        runTest {
            val db = TestDb.inMemory()
            val flow = newFlow()
            val core = newCore(db, FakeSeam(), flow)
            val loaded =
                async {
                    core.awaitLoaded()
                    true
                }
            runCurrent()
            assertFalse(loaded.isCompleted)
            flow.tryEmit(currentRows(db))
            assertTrue(loaded.await())
            db.close()
        }

    @Test
    fun `failed load still releases the barrier`() =
        runTest {
            val db = TestDb.inMemory()
            val core =
                SecretStoreCore(
                    dao = db.credentialDao(),
                    podcasts = db.podcastDao(),
                    coordinator = FakeCredentialCommitCoordinator(),
                    seam = FakeSeam(),
                    scope = backgroundScope,
                    rows = flow { throw IllegalStateException("collection failed") },
                )
            // Must return — a dead load path must not strand callers waiting on credentials.
            withTimeout(REAL_TIMEOUT_MS) { core.awaitLoaded() }
            db.close()
        }

    // --- origin canonicalisation + basicAuthorization ----------------------------------------------

    @Test
    fun `default and explicit ports match the request origin`() =
        runTest {
            val db = TestDb.inMemory()
            val flow = newFlow()
            val core = newCore(db, FakeSeam(), flow)
            core.putPlain(db, "https://a.test", "u", "p")
            core.putPlain(db, "http://b.test:8080", "u2", "p2")

            assertEquals(basicAuth("u", "p"), core.basicAuthorization(Origin("https", "a.test", 443)))
            assertEquals(basicAuth("u2", "p2"), core.basicAuthorization(Origin("http", "b.test", 8080)))
            assertNull(core.basicAuthorization(Origin("https", "a.test", 8443)))
            assertNull(core.basicAuthorization(Origin("http", "a.test", 80)))
            assertNull(core.basicAuthorization(Origin("http", "b.test", 80)))
            db.close()
        }

    @Test
    fun `put canonicalises whole URLs to the stored origin form`() =
        runTest {
            val db = TestDb.inMemory()
            val flow = newFlow()
            val core = newCore(db, FakeSeam(), flow)
            val id = core.putPlain(db, "HTTPS://Feeds.Example.COM:443/show/feed?x=1", "u", "p")

            assertEquals(
                "https://feeds.example.com",
                db
                    .credentialDao()
                    .byIds(listOf(id!!))
                    .single()
                    .origin,
            )
            assertEquals(basicAuth("u", "p"), core.basicAuthorization(Origin("https", "feeds.example.com", 443)))
            assertEquals("p", core.get("https://feeds.example.com")?.secret?.concatToString())
            db.close()
        }

    @Test
    fun `sync and podcastindex namespaces never answer basicAuthorization`() =
        runTest {
            val db = TestDb.inMemory()
            val seam = FakeSeam()
            val flow = newFlow()
            val core = newCore(db, seam, flow)
            core.putPlain(db, "sync:sync.test", "", "token")
            core.putPlain(db, PODCAST_INDEX_ORIGIN, "key", "apisecret")

            // After the real row emission lands the namespaces are still not HTTP origins.
            flow.tryEmit(currentRows(db))
            awaitReal { seam.reconcilePasses >= 1 }

            assertNull(core.basicAuthorization(Origin("https", "sync.test", 443)))
            assertNull(core.basicAuthorization(Origin("https", "podcastindex", 443)))
            assertEquals("token", core.get("sync:sync.test")?.secret?.concatToString())
            assertEquals("key" to "apisecret", core.podcastIndexKey())
            db.close()
        }

    // --- put / remove ------------------------------------------------------------------------------

    @Test
    fun `put replaces the secret and keeps the row id`() =
        runTest {
            val db = TestDb.inMemory()
            val flow = newFlow()
            val core = newCore(db, FakeSeam(), flow)
            val firstId = core.putPlain(db, "https://a.test", "u", "p1")
            val secondId = core.putPlain(db, "https://a.test", "u", "p2")

            assertEquals(firstId, secondId)
            assertEquals(1, db.credentialDao().byOrigin("https://a.test").size)
            assertEquals("p2", core.get("https://a.test")?.secret?.concatToString())
            assertEquals(basicAuth("u", "p2"), core.basicAuthorization(Origin("https", "a.test", 443)))
            db.close()
        }

    @Test
    fun `remove drops the entry and the row`() =
        runTest {
            val db = TestDb.inMemory()
            val flow = newFlow()
            val core = newCore(db, FakeSeam(), flow)
            core.putPlain(db, "https://a.test", "u", "p")
            core.removeRows(db, "https://a.test")

            assertNull(core.get("https://a.test"))
            assertNull(core.basicAuthorization(Origin("https", "a.test", 443)))
            assertTrue(db.credentialDao().byOrigin("https://a.test").isEmpty())
            db.close()
        }

    // --- observer reconcile ------------------------------------------------------------------------

    @Test
    fun `emitted row enters the map and decrypts only once per content`() =
        runTest {
            val db = TestDb.inMemory()
            val seam = FakeSeam()
            val flow = newFlow()
            val core = newCore(db, seam, flow)
            val written =
                CredentialEntity(
                    origin = "https://a.test",
                    username = "u",
                    secretCipher = "p".encodeToByteArray(),
                    iv = byteArrayOf(FAKE_IV_BYTE),
                    createdAt = CREATED_AT,
                )
            val id = db.credentialDao().insert(written)
            val rows = currentRows(db)

            flow.tryEmit(rows)
            flow.tryEmit(rows)
            awaitReal { seam.reconcilePasses >= 2 }

            assertEquals("p", core.get("https://a.test")?.secret?.concatToString())
            assertEquals(1, seam.resolveCalls)

            // A row whose content changed (same id) resolves again.
            flow.tryEmit(listOf(rows.single().copy(createdAt = CREATED_AT + 1)))
            awaitReal { seam.reconcilePasses >= 3 }
            assertEquals(2, seam.resolveCalls)
            db.close()
        }

    @Test
    fun `cascade-deleted row leaves the map on the same commit`() =
        runTest {
            val db = TestDb.inMemory()
            val seam = FakeSeam()
            val flow = newFlow()
            val core = newCore(db, seam, flow)
            val id = core.putPlain(db, "https://a.test", "u", "p")!!
            flow.tryEmit(currentRows(db))
            awaitReal { seam.reconcilePasses >= 1 }

            // The unsubscribe cascade (or sweep) removes the row; the next emission no longer
            // carries it, and the entry must leave memory with that commit.
            db.credentialDao().delete(id)
            flow.tryEmit(emptyList())
            awaitReal { seam.reconcilePasses >= 2 && core.get("https://a.test") == null }

            assertNull(core.get("https://a.test"))
            assertNull(core.basicAuthorization(Origin("https", "a.test", 443)))
            assertEquals(listOf("https://a.test"), seam.droppedOrigins)
            db.close()
        }

    @Test
    fun `stale emission cannot resurrect a removed secret`() =
        runTest {
            val db = TestDb.inMemory()
            val seam = FakeSeam()
            val flow = newFlow()
            val core = newCore(db, seam, flow)
            core.putPlain(db, "https://a.test", "u", "p")
            val staleRows = currentRows(db)
            flow.tryEmit(staleRows)
            awaitReal { seam.reconcilePasses >= 1 }

            core.removeRows(db, "https://a.test")
            // The database snapshot predating the delete arrives late: the tombstone keeps it dead.
            flow.tryEmit(staleRows)
            awaitReal { seam.reconcilePasses >= 2 }

            assertNull(core.get("https://a.test"))
            assertNull(core.basicAuthorization(Origin("https", "a.test", 443)))
            db.close()
        }

    @Test
    fun `stale emission cannot resurrect a replaced secret`() =
        runTest {
            val db = TestDb.inMemory()
            val seam = FakeSeam()
            val flow = newFlow()
            val core = newCore(db, seam, flow)
            core.putPlain(db, "https://a.test", "u", "p1")
            val staleRows = currentRows(db)
            flow.tryEmit(staleRows)
            awaitReal { seam.reconcilePasses >= 1 }

            core.putPlain(db, "https://a.test", "u", "p2")
            // A snapshot taken between the two puts arrives after the second: the pending entry
            // stays — the emission is not the row the second put wrote.
            flow.tryEmit(staleRows)
            awaitReal { seam.reconcilePasses >= 2 }

            assertEquals("p2", core.get("https://a.test")?.secret?.concatToString())
            assertEquals(basicAuth("u", "p2"), core.basicAuthorization(Origin("https", "a.test", 443)))
            db.close()
        }

    @Test
    fun `a put that postdates the emitted snapshot survives it`() =
        runTest {
            val db = TestDb.inMemory()
            val seam = FakeSeam()
            val flow = newFlow()
            val core = newCore(db, seam, flow)
            val staleRows = currentRows(db) // empty — taken before the put
            core.putPlain(db, "https://a.test", "u", "p")

            // Room emitted a snapshot taken before put's write committed: the pending entry is
            // re-checked live instead of dropped.
            flow.tryEmit(staleRows)
            awaitReal { seam.reconcilePasses >= 1 }
            flow.tryEmit(emptyList()) // flush: reconcile N+1 starting means N finished
            awaitReal { seam.reconcilePasses >= 2 }

            assertEquals("p", core.get("https://a.test")?.secret?.concatToString())
            assertEquals(basicAuth("u", "p"), core.basicAuthorization(Origin("https", "a.test", 443)))
            db.close()
        }

    // --- key loss -----------------------------------------------------------------------------------

    @Test
    fun `key loss marks referencing podcasts and deletes the row`() =
        runTest {
            val db = TestDb.inMemory()
            val seam = FakeSeam()
            val flow = newFlow()
            val core = newCore(db, seam, flow)
            val id =
                db.credentialDao().insert(
                    CredentialEntity(
                        origin = "https://a.test",
                        username = "u",
                        secretCipher = "p".encodeToByteArray(),
                        iv = byteArrayOf(FAKE_IV_BYTE),
                        createdAt = CREATED_AT,
                    ),
                )
            val podcastId = seedPodcast(db, "https://a.test/feed")
            db.podcastDao().setCredentialId(podcastId, id)
            seam.lostIds += id

            flow.tryEmit(currentRows(db))
            awaitReal {
                db.credentialDao().byIds(listOf(id)).isEmpty() &&
                    db.podcastDao().byId(podcastId)?.needsCredentials == true
            }

            val podcast = db.podcastDao().byId(podcastId)!!
            assertTrue(podcast.needsCredentials)
            // ON DELETE SET NULL released the reference after the mark ran.
            assertNull(podcast.credentialId)
            assertNull(core.get("https://a.test"))
            db.close()
        }

    @Test
    fun `a stale lost row does not delete its live replacement`() =
        runTest {
            val db = TestDb.inMemory()
            val seam = FakeSeam()
            val flow = newFlow()
            val core = newCore(db, seam, flow)
            val id = core.putPlain(db, "https://a.test", "u", "p1")!!
            val staleRows = currentRows(db)
            val podcastId = seedPodcast(db, "https://a.test/feed")
            db.podcastDao().setCredentialId(podcastId, id)

            // The put replaced the row (new ciphertext for the same id); an emission of the old
            // content then fails to decrypt — the live row must survive.
            core.putPlain(db, "https://a.test", "u", "p2")
            seam.lostIds += id
            flow.tryEmit(staleRows)
            awaitReal { seam.reconcilePasses >= 1 }
            flow.tryEmit(emptyList())
            awaitReal { seam.reconcilePasses >= 2 }

            val live = db.credentialDao().byIds(listOf(id))
            assertEquals(1, live.size)
            assertEquals("p2", live.single().secretCipher?.decodeToString())
            assertFalse(db.podcastDao().byId(podcastId)!!.needsCredentials)
            assertEquals("p2", core.get("https://a.test")?.secret?.concatToString())
            db.close()
        }

    @Test
    fun `transient resolve failure keeps the row for the next emission`() =
        runTest {
            val db = TestDb.inMemory()
            val seam = FakeSeam()
            val flow = newFlow()
            val core = newCore(db, seam, flow)
            val id =
                db.credentialDao().insert(
                    CredentialEntity(
                        origin = "https://a.test",
                        username = "u",
                        secretCipher = "p".encodeToByteArray(),
                        iv = byteArrayOf(FAKE_IV_BYTE),
                        createdAt = CREATED_AT,
                    ),
                )
            seam.brokenIds += id

            flow.tryEmit(currentRows(db))
            awaitReal { seam.reconcilePasses >= 1 }
            assertNull(core.get("https://a.test"))

            // The failure did not delete the row — a later emission resolves it.
            seam.brokenIds.clear()
            flow.tryEmit(currentRows(db))
            awaitReal { seam.reconcilePasses >= 2 }
            assertEquals("p", core.get("https://a.test")?.secret?.concatToString())
            assertEquals(1, db.credentialDao().byIds(listOf(id)).size)
            db.close()
        }

    // --- forPodcast --------------------------------------------------------------------------------

    @Test
    fun `forPodcast resolves through the credentialId link`() =
        runTest {
            val db = TestDb.inMemory()
            val flow = newFlow()
            val core = newCore(db, FakeSeam(), flow)
            val credentialId = core.putPlain(db, "https://a.test", "u", "p")!!
            val podcastId = seedPodcast(db, "https://a.test/feed")
            db.podcastDao().setCredentialId(podcastId, credentialId)

            assertEquals(BasicCredentials("u", "p"), core.forPodcast(podcastId))
            assertNull(core.forPodcast(podcastId + 1))
            db.close()
        }

    @Test
    fun `rowless seam entries join the map without rows`() =
        runTest {
            val db = TestDb.inMemory()
            val seam = FakeSeam()
            val flow = newFlow()
            val core = newCore(db, seam, flow)
            // The desktop's file-only `sync:` shape: an entry with no `credential` row.
            seam.rowless =
                mapOf(
                    "sync:desktop.test" to StoredSecret("", "filetoken".toCharArray(), mapOf("k" to "v")),
                )

            flow.tryEmit(currentRows(db))
            awaitReal { seam.reconcilePasses >= 1 }

            assertEquals("filetoken", core.get("sync:desktop.test")?.secret?.concatToString())
            assertEquals(mapOf("k" to "v"), core.get("sync:desktop.test")?.attributes)
            assertNull(core.basicAuthorization(Origin("https", "desktop.test", 443)))
            db.close()
        }

    private companion object {
        const val FAKE_IV_BYTE: Byte = 1
        const val CREATED_AT = 1_791_000_000_000L
        const val FLOW_BUFFER = 32
        const val REAL_TIMEOUT_MS = 10_000L
        const val POLL_MS = 10L
    }
}
