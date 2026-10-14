// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.credentials

import ch.lkmc.neutrodyne.core.common.Origin
import ch.lkmc.neutrodyne.core.data.seedPodcast
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
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
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `KeystoreCredentialStore` under Robolectric (03 Basic auth and CredentialStore): the
 * AndroidKeyStore cannot run on the host, so [SoftwareCipherProvider] answers the `CipherProvider`
 * seam with a plain JCE AES-256-GCM key — the store's encrypt/decrypt/AAD/key-loss paths all run
 * for real, only key custody is faked.
 */
@RunWith(RobolectricTestRunner::class)
class CredentialStoreTest {
    /** Software AES-GCM over a fixed 256-bit key — same transformation and tag size as the Keystore. */
    private class SoftwareCipherProvider : CipherProvider {
        private val key: SecretKey = SecretKeySpec(ByteArray(KEY_BYTES), "AES")

        override fun forEncryption(): Cipher =
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }

        override fun forDecryption(iv: ByteArray): Cipher =
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            }
    }

    private lateinit var db: NeutrodyneDatabase
    private lateinit var storeScope: CoroutineScope
    private lateinit var store: KeystoreCredentialStore

    @Before
    fun setUp() {
        db = TestDb.inMemory(RuntimeEnvironment.getApplication())
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        store = newStore(storeScope)
    }

    @After
    fun tearDown() {
        storeScope.cancel()
        db.close()
    }

    private fun newStore(scope: CoroutineScope) =
        KeystoreCredentialStore(
            db = db,
            ciphers = SoftwareCipherProvider(),
            clock = TestClock(),
            coordinator = CredentialCommitCoordinatorImpl(),
            scope = scope,
        )

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

    @Test
    fun `store loads and round-trips through the database`() =
        runTest {
            val id = store.put("https://a.test/feed.xml", "u", "p".toCharArray())!!
            // `withTimeout` inside runTest ticks virtual time; the barrier resolves on a real
            // dispatcher, so the timeout must run there too.
            withContext(Dispatchers.Default) { withTimeout(REAL_TIMEOUT_MS) { store.awaitLoaded() } }

            // The row carries ciphertext, not the secret — and a 12-byte GCM IV.
            val row = db.credentialDao().byIds(listOf(id)).single()
            assertNotNull(row.secretCipher)
            assertNotEquals("p", row.secretCipher!!.decodeToString())
            assertEquals(GCM_IV_BYTES, row.iv!!.size)

            assertEquals(basicAuth("u", "p"), store.basicAuthorization(Origin("https", "a.test", 443)))

            // A second store instance over the same DB decrypts through the observer path:
            // `awaitLoaded` returns once the first emission — which carries the row — is reconciled.
            val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val second = newStore(secondScope)
                withContext(Dispatchers.Default) { withTimeout(REAL_TIMEOUT_MS) { second.awaitLoaded() } }
                assertEquals("p", second.get("https://a.test")?.secret?.concatToString())
                assertEquals(basicAuth("u", "p"), second.basicAuthorization(Origin("https", "a.test", 443)))
            } finally {
                secondScope.cancel()
            }
        }

    @Test
    fun `put replaces the secret and keeps the row id`() =
        runTest {
            val firstId = store.put("https://a.test", "u", "p1".toCharArray())!!
            val secondId = store.put("https://a.test", "u", "p2".toCharArray())

            assertEquals(firstId, secondId)
            assertEquals(1, db.credentialDao().byOrigin("https://a.test").size)
            assertEquals("p2", store.get("https://a.test")?.secret?.concatToString())
        }

    @Test
    fun `tampered ciphertext is marked lost and deleted`() =
        runTest {
            val id = store.put("https://a.test", "u", "p".toCharArray())!!
            val podcastId = seedPodcast(db, "https://a.test/feed.xml")
            db.podcastDao().setCredentialId(podcastId, id)

            // Flip a ciphertext bit: the next emission fails the GCM tag check.
            val row = db.credentialDao().byIds(listOf(id)).single()
            val tampered = row.secretCipher!!.copyOf()
            tampered[0] = (tampered[0] + 1).toByte()
            db.credentialDao().putByOrigin(row.copy(secretCipher = tampered))

            awaitReal {
                db.credentialDao().byIds(listOf(id)).isEmpty() &&
                    db.podcastDao().byId(podcastId)?.needsCredentials == true
            }
            val podcast = db.podcastDao().byId(podcastId)!!
            assertTrue(podcast.needsCredentials)
            assertNull(podcast.credentialId)
            // The post-delete emission drops the entry from memory too.
            awaitReal { store.get("https://a.test") == null }
        }

    @Test
    fun `a row whose AAD no longer matches is rejected`() =
        runTest {
            val id = store.put("https://a.test", "u", "p".toCharArray())!!

            // Renaming the username changes the AAD ("origin\nusername") under the same ciphertext.
            val row = db.credentialDao().byIds(listOf(id)).single()
            db.credentialDao().putByOrigin(row.copy(username = "other"))

            awaitReal { db.credentialDao().byIds(listOf(id)).isEmpty() }
            awaitReal { store.get("https://a.test") == null }
        }

    @Test
    fun `remove deletes the row and clears the entry`() =
        runTest {
            store.put("https://a.test", "u", "p".toCharArray())
            store.remove("https://a.test")

            assertNull(store.get("https://a.test"))
            assertNull(store.basicAuthorization(Origin("https", "a.test", 443)))
            assertTrue(db.credentialDao().byOrigin("https://a.test").isEmpty())
        }

    @Test
    fun `sync and podcastindex entries never answer basicAuthorization`() =
        runTest {
            store.put("sync:sync.test", "", "token".toCharArray())
            store.put("podcastindex", "key", "apisecret".toCharArray())

            assertNull(store.basicAuthorization(Origin("https", "sync.test", 443)))
            assertEquals("key" to "apisecret", store.podcastIndexKey())
        }

    @Test
    fun `forPodcast resolves through the credentialId link`() =
        runTest {
            val credentialId = store.put("https://a.test", "u", "p".toCharArray())!!
            val podcastId = seedPodcast(db, "https://a.test/feed.xml")
            db.podcastDao().setCredentialId(podcastId, credentialId)

            val credentials = store.forPodcast(podcastId)
            assertEquals("u", credentials?.username)
            assertEquals("p", credentials?.password)
            assertNull(store.forPodcast(podcastId + 1))
        }

    private companion object {
        const val KEY_BYTES = 32
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val GCM_IV_BYTES = 12
        const val REAL_TIMEOUT_MS = 15_000L
        const val POLL_MS = 10L
    }
}
