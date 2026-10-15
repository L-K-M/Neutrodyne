// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.credentials

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.Origin
import ch.lkmc.neutrodyne.core.data.seedPodcast
import ch.lkmc.neutrodyne.core.database.CredentialEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.StoredSecret
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `DesktopSecretStore` (03 Basic auth and CredentialStore; 11 Secrets, PO-44): the secrets file
 * is the secret's home, the `credential` row its metadata shadow. These tests run on the
 * identity protection (the macOS/Linux shape); the Windows leg is the DPAPI round-trip at the
 * bottom, skipped everywhere else, including the Linux CI host.
 */
class DesktopSecretStoreTest {
    /** A fresh temp [AppDirs] + in-memory database; [close] owns their lifetimes. */
    private class Fixture(
        val db: NeutrodyneDatabase,
        val dirs: AppDirs,
        val root: Path,
    ) {
        fun close() {
            db.close()
            root.toFile().deleteRecursively()
        }
    }

    private fun newFixture(): Fixture {
        val root = Files.createTempDirectory("nd-secrets-test")
        val dirs =
            AppDirs(
                data = root.resolve("data"),
                config = root.resolve("config"),
                cache = root.resolve("cache"),
                state = root.resolve("state"),
                logs = root.resolve("state").resolve("logs"),
                downloadsDefault = root.resolve("data").resolve("Downloads"),
            )
        dirs.ensureCreated()
        return Fixture(db = TestDb.inMemory(), dirs = dirs, root = root)
    }

    private fun newStore(
        f: Fixture,
        scope: CoroutineScope,
        protection: SecretProtection = IdentitySecretProtection,
    ) = DesktopSecretStore(
        db = f.db,
        appDirs = f.dirs,
        coordinator = CredentialCommitCoordinatorImpl(),
        clock = TestClock(),
        protection = protection,
        scope = scope,
    )

    private fun Fixture.secretsPath(protection: SecretProtection): Path = dirs.data.resolve(protection.fileName)

    /** A fresh reader over the file on disk — a store caches its own load. */
    private suspend fun Fixture.diskEntries(
        protection: SecretProtection = IdentitySecretProtection,
    ): Map<String, StoredSecret> = DesktopSecretsFile(secretsPath(protection), protection).entries()

    private suspend fun awaitLoaded(store: DesktopSecretStore) =
        withContext(Dispatchers.Default) {
            withTimeout(REAL_TIMEOUT_MS) { store.awaitLoaded() }
        }

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

    private fun rawSecretsFile(
        f: Fixture,
        protection: SecretProtection = IdentitySecretProtection,
    ): String = Files.readString(f.secretsPath(protection))

    private fun assertNoTempFiles(f: Fixture) {
        Files.list(f.dirs.data).use { stream ->
            assertTrue(
                stream.noneMatch { it.fileName.toString().endsWith(".tmp") },
                "temp files left in ${f.dirs.data}",
            )
        }
    }

    // --- file + row shape ----------------------------------------------------------------------------

    @Test
    fun `put stores the secret in the file and a metadata row with null cipher columns`() =
        runTest {
            val f = newFixture()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val store = newStore(f, scope)
                val id = store.put("https://a.test/feed.xml", "u", "p".toCharArray())!!
                awaitLoaded(store)

                val row =
                    f.db
                        .credentialDao()
                        .byIds(listOf(id))
                        .single()
                assertEquals("https://a.test", row.origin)
                assertEquals("u", row.username)
                assertNull(row.secretCipher)
                assertNull(row.iv)

                val fileEntry = f.diskEntries()["https://a.test"]!!
                assertEquals("u", fileEntry.username)
                assertEquals("p", fileEntry.secret.concatToString())
                assertEquals(basicAuth("u", "p"), store.basicAuthorization(Origin("https", "a.test", 443)))
            } finally {
                scope.cancel()
                f.close()
            }
        }

    @Test
    fun `a new store instance reads the file back`() =
        runTest {
            val f = newFixture()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                newStore(f, scope).put("https://a.test", "u", "p".toCharArray())
                newStore(f, scope).put("sync:sync.test", "", "tok".toCharArray(), mapOf("fp" to "abc"))

                val second = newStore(f, secondScope)
                awaitLoaded(second)
                assertEquals("p", second.get("https://a.test")?.secret?.concatToString())
                assertEquals(basicAuth("u", "p"), second.basicAuthorization(Origin("https", "a.test", 443)))
                val sync = second.get("sync:sync.test")
                assertEquals("tok", sync?.secret?.concatToString())
                assertEquals(mapOf("fp" to "abc"), sync?.attributes)
            } finally {
                scope.cancel()
                secondScope.cancel()
                f.close()
            }
        }

    @Test
    fun `sync entries stay file-only and never answer basicAuthorization`() =
        runTest {
            val f = newFixture()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val store = newStore(f, scope)
                assertNull(store.put("sync:sync.test", "", "tok".toCharArray(), mapOf("fp" to "abc")))
                awaitLoaded(store)

                assertTrue(
                    f.db
                        .credentialDao()
                        .byOrigin("sync:sync.test")
                        .isEmpty(),
                )
                assertNull(store.basicAuthorization(Origin("https", "sync.test", 443)))
                val raw = rawSecretsFile(f)
                assertTrue("\"sync:sync.test\"" in raw)
                assertTrue("\"fp\":\"abc\"" in raw)
            } finally {
                scope.cancel()
                f.close()
            }
        }

    @Test
    fun `podcastindex keeps a metadata row like a feed`() =
        runTest {
            val f = newFixture()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val store = newStore(f, scope)
                val id = store.put(PODCAST_INDEX_ORIGIN, "key", "apisecret".toCharArray())!!
                awaitLoaded(store)

                val row =
                    f.db
                        .credentialDao()
                        .byIds(listOf(id))
                        .single()
                assertNull(row.secretCipher)
                assertNull(row.iv)
                assertEquals("key" to "apisecret", store.podcastIndexKey())
                assertNull(store.basicAuthorization(Origin("https", "podcastindex", 443)))
            } finally {
                scope.cancel()
                f.close()
            }
        }

    @Test
    fun `remove deletes the file entry and the row`() =
        runTest {
            val f = newFixture()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val store = newStore(f, scope)
                store.put("https://a.test", "u", "p".toCharArray())
                store.remove("https://a.test")

                assertNull(store.get("https://a.test"))
                assertNull(store.basicAuthorization(Origin("https", "a.test", 443)))
                assertTrue(
                    f.db
                        .credentialDao()
                        .byOrigin("https://a.test")
                        .isEmpty(),
                )
                assertNull(f.diskEntries()["https://a.test"])
            } finally {
                scope.cancel()
                f.close()
            }
        }

    @Test
    fun `forPodcast resolves through the credentialId link`() =
        runTest {
            val f = newFixture()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val store = newStore(f, scope)
                val credentialId = store.put("https://a.test", "u", "p".toCharArray())!!
                val podcastId = seedPodcast(f.db, "https://a.test/feed")
                f.db.podcastDao().setCredentialId(podcastId, credentialId)

                val credentials = store.forPodcast(podcastId)
                assertEquals("u", credentials?.username)
                assertEquals("p", credentials?.password)
            } finally {
                scope.cancel()
                f.close()
            }
        }

    // --- write mechanics -----------------------------------------------------------------------------

    @Test
    fun `a failed protect fails the put and leaves the file intact`() =
        runTest {
            val flaky =
                object : SecretProtection {
                    override val fileName: String = "secrets.json"

                    @Volatile var failWrites = false

                    override fun protect(plain: ByteArray): ByteArray =
                        if (failWrites) throw IOException("simulated storage failure") else plain

                    override fun unprotect(protected: ByteArray): ByteArray = protected
                }
            val f = newFixture()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val store = newStore(f, scope, flaky)
                store.put("https://a.test", "u", "p".toCharArray())
                val before = Files.readAllBytes(f.secretsPath(flaky))

                flaky.failWrites = true
                assertFailsWith<IOException> { store.put("https://b.test", "u", "p2".toCharArray()) }

                // The target was never touched: same bytes, still the single first entry.
                assertTrue(Files.readAllBytes(f.secretsPath(flaky)).contentEquals(before))
                assertEquals(setOf("https://a.test"), f.diskEntries(flaky).keys)
                assertNoTempFiles(f)
            } finally {
                scope.cancel()
                f.close()
            }
        }

    @Test
    fun `a denied atomic move surfaces a storage failure and leaves the target untouched`() =
        runTest {
            val f = newFixture()
            try {
                val path = f.secretsPath(IdentitySecretProtection)
                val file = DesktopSecretsFile(path, IdentitySecretProtection)
                file.put("https://a.test", StoredSecret("u", "p".toCharArray(), emptyMap()))
                val before = Files.readAllBytes(path)

                val denied =
                    DesktopSecretsFile(
                        path,
                        IdentitySecretProtection,
                        move = { source, target ->
                            throw AtomicMoveNotSupportedException(
                                source.toString(),
                                target.toString(),
                                "test-denied",
                            )
                        },
                    )
                assertFailsWith<SecretStorageException> {
                    denied.put("https://b.test", StoredSecret("u", "p".toCharArray(), emptyMap()))
                }

                assertTrue(Files.readAllBytes(path).contentEquals(before))
                assertEquals(setOf("https://a.test"), f.diskEntries().keys)
                assertNoTempFiles(f)
            } finally {
                f.close()
            }
        }

    @Test
    fun `the file is 0600 in a 0700 directory`() =
        runTest {
            val f = newFixture()
            try {
                assumeTrue(
                    "POSIX permissions need a POSIX file store",
                    Files.getFileStore(f.dirs.data).supportsFileAttributeView(PosixFileAttributeView::class.java),
                )
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    newStore(f, scope).put("https://a.test", "u", "p".toCharArray())
                } finally {
                    scope.cancel()
                }

                assertEquals(
                    PosixFilePermissions.fromString("rwx------"),
                    Files.getPosixFilePermissions(f.dirs.data),
                )
                assertEquals(
                    PosixFilePermissions.fromString("rw-------"),
                    Files.getPosixFilePermissions(f.secretsPath(IdentitySecretProtection)),
                )
            } finally {
                f.close()
            }
        }

    // --- observation, loss and the interrupted-put sweep ----------------------------------------------

    @Test
    fun `a cascade-deleted row drops the file entry`() =
        runTest {
            val f = newFixture()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val store = newStore(f, scope)
                val id = store.put("https://a.test", "u", "p".toCharArray())!!
                awaitLoaded(store)

                // The unsubscribe cascade and the maintenance sweep delete rows directly (02).
                f.db.credentialDao().delete(id)
                awaitReal { store.get("https://a.test") == null }
                awaitReal { f.diskEntries()["https://a.test"] == null }
                assertNull(store.basicAuthorization(Origin("https", "a.test", 443)))
            } finally {
                scope.cancel()
                f.close()
            }
        }

    @Test
    fun `an unreadable file marks podcasts needsCredentials, deletes rows and is kept aside`() =
        runTest {
            val f = newFixture()
            val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val writer = newStore(f, writerScope)
                val id = writer.put("https://a.test", "u", "p".toCharArray())!!
                val podcastId = seedPodcast(f.db, "https://a.test/feed")
                f.db.podcastDao().setCredentialId(podcastId, id)

                // Key loss (03): DPAPI cannot unwrap a foreign-machine blob, or the JSON is
                // unreadable — both land here as corrupt bytes. The writer's own collection is
                // stopped first so only the fresh store drives the recovery below.
                writerScope.cancel()
                Files.write(f.secretsPath(IdentitySecretProtection), "not secrets".encodeToByteArray())

                val reader = newStore(f, readerScope)
                awaitLoaded(reader) // startup must survive the loss
                awaitReal {
                    f.db
                        .podcastDao()
                        .byId(podcastId)
                        ?.needsCredentials == true &&
                        f.db
                            .credentialDao()
                            .byIds(listOf(id))
                            .isEmpty()
                }

                assertNull(reader.get("https://a.test"))
                assertNull(reader.basicAuthorization(Origin("https", "a.test", 443)))
                val podcast = f.db.podcastDao().byId(podcastId)!!
                assertTrue(podcast.needsCredentials)
                assertNull(podcast.credentialId)

                // The unreadable bytes are set aside for manual recovery, never overwritten.
                val aside = f.secretsPath(IdentitySecretProtection).resolveSibling("secrets.json.unreadable")
                awaitReal { Files.exists(aside) }
                assertEquals("not secrets", Files.readAllBytes(aside).decodeToString())
            } finally {
                writerScope.cancel()
                readerScope.cancel()
                f.close()
            }
        }

    @Test
    fun `a row without a file entry resolves as lost`() =
        runTest {
            val f = newFixture()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                // Row committed, file entry absent — the other half of the interrupted-put gap,
                // or a file reset that outlived its rows.
                val id =
                    f.db.credentialDao().insert(
                        CredentialEntity(
                            origin = "https://lost.test",
                            username = "u",
                            secretCipher = null,
                            iv = null,
                            createdAt = 1,
                        ),
                    )
                val podcastId = seedPodcast(f.db, "https://lost.test/feed")
                f.db.podcastDao().setCredentialId(podcastId, id)
                newStore(f, scope)

                awaitReal {
                    f.db
                        .podcastDao()
                        .byId(podcastId)
                        ?.needsCredentials == true &&
                        f.db
                            .credentialDao()
                            .byIds(listOf(id))
                            .isEmpty()
                }
            } finally {
                scope.cancel()
                f.close()
            }
        }

    @Test
    fun `the first reconcile sweeps row-less file entries but keeps sync ones`() =
        runTest {
            val f = newFixture()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                // Crash-mid-put residue, staged before any store exists: the file entry
                // committed, the row never did.
                val staged = DesktopSecretsFile(f.secretsPath(IdentitySecretProtection), IdentitySecretProtection)
                staged.put("https://orphan.test", StoredSecret("u", "p".toCharArray(), emptyMap()))
                staged.put("sync:sync.test", StoredSecret("", "tok".toCharArray(), mapOf("fp" to "abc")))

                val store = newStore(f, scope)
                awaitLoaded(store)
                awaitReal {
                    f.diskEntries().let {
                        it["https://orphan.test"] == null && it["sync:sync.test"] != null
                    }
                }

                assertNull(store.get("https://orphan.test"))
                assertEquals("tok", store.get("sync:sync.test")?.secret?.concatToString())
            } finally {
                scope.cancel()
                f.close()
            }
        }

    // --- Windows DPAPI --------------------------------------------------------------------------------

    @Test
    fun `dpapi protection round-trips the secrets file`() =
        runTest {
            // Windows-only: on the Linux CI host and every macOS/Linux machine this is skipped.
            assumeTrue(
                "DPAPI exists only on Windows",
                AppDirs.DesktopOs.current() == AppDirs.DesktopOs.WINDOWS,
            )
            val protection = DpapiSecretProtection()
            val f = newFixture()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val store = newStore(f, scope, protection)
                store.put("https://a.test", "u", "p".toCharArray())
                awaitLoaded(store)

                assertEquals("secrets.bin", protection.fileName)
                // The blob is DPAPI ciphertext, not readable JSON.
                assertFalse("\"secret\"" in String(Files.readAllBytes(f.secretsPath(protection))))

                val second = newStore(f, secondScope, protection)
                awaitLoaded(second)
                assertEquals("p", second.get("https://a.test")?.secret?.concatToString())
            } finally {
                scope.cancel()
                secondScope.cancel()
                f.close()
            }
        }

    private companion object {
        const val REAL_TIMEOUT_MS = 15_000L
        const val POLL_MS = 10L
    }
}
