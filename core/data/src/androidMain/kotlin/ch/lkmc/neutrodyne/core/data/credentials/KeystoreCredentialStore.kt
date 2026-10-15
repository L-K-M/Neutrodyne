// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.credentials

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Origin
import ch.lkmc.neutrodyne.core.database.CredentialDao
import ch.lkmc.neutrodyne.core.database.CredentialEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.CredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.domain.SecretStore
import ch.lkmc.neutrodyne.core.domain.StoredSecret
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CoroutineScope
import java.security.GeneralSecurityException

/**
 * The Android `SecretStore` (03 Basic auth and CredentialStore): `credential` rows carry an
 * AES-256-GCM ciphertext + IV produced by [CipherProvider]; [SecretStoreCore] owns the published
 * in-memory map, the `observeAll` reconcile loop and key-loss recovery, this class owns only
 * the crypto/persistence half.
 *
 * Key loss — a Keystore reset, an invalidated key, or tampered ciphertext — surfaces as
 * [SecretLostException] on the next emission; the core then marks referencing podcasts
 * `needsCredentials` and deletes the row in one transaction.
 *
 * The Metro binding exposes `SecretStore` only. `CredentialLookup.None` stays the
 * `CredentialLookup` provider until the switchover wiring lands (M1b PR4).
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding<SecretStore>())
@Inject
internal class KeystoreCredentialStore(
    db: NeutrodyneDatabase,
    private val ciphers: CipherProvider,
    private val clock: Clock,
    coordinator: CredentialCommitCoordinator,
    @ApplicationScope scope: CoroutineScope,
) : SecretStore {
    private val dao: CredentialDao = db.credentialDao()

    private val core =
        SecretStoreCore(
            dao = dao,
            podcasts = db.podcastDao(),
            coordinator = coordinator,
            seam = AndroidSeam(),
            scope = scope,
        )

    override suspend fun awaitLoaded() = core.awaitLoaded()

    override fun basicAuthorization(origin: Origin): String? = core.basicAuthorization(origin)

    override suspend fun get(origin: String): StoredSecret? = core.get(origin)

    override suspend fun forPodcast(podcastId: Long): BasicCredentials? = core.forPodcast(podcastId)

    override suspend fun podcastIndexKey(): Pair<String, String>? = core.podcastIndexKey()

    override suspend fun put(
        origin: String,
        username: String,
        secret: CharArray,
        attributes: Map<String, String>,
    ): Long? =
        core.put(origin) { canonical ->
            val cipher = ciphers.forEncryption()
            cipher.updateAAD(aad(canonical, username))
            val ciphertext = cipher.doFinal(secret.concatToString().encodeToByteArray())
            val written =
                CredentialEntity(
                    origin = canonical,
                    username = username,
                    secretCipher = ciphertext,
                    iv = cipher.iv,
                    createdAt = clock.now(),
                )
            val id = dao.putByOrigin(written)
            // `attributes` never reach Android storage (03); the in-memory entry keeps only
            // what a rebuilt row would carry.
            PersistedSecret(
                row = written.copy(id = id),
                stored = StoredSecret(username, secret.copyOf(), emptyMap()),
            )
        }

    override suspend fun remove(origin: String) {
        core.remove(origin) { canonical ->
            // `secretCipher` and `iv` both die with the row (02).
            val rows = dao.byOrigin(canonical)
            for (row in rows) dao.delete(row.id)
            rows
        }
    }

    /** `credential` row → plaintext via AES-256-GCM; anything unrecoverable is a `SecretLostException`. */
    private inner class AndroidSeam : SecretStoreSeam {
        override suspend fun resolve(row: CredentialEntity): StoredSecret? {
            val ciphertext = row.secretCipher
            val iv = row.iv
            if (ciphertext == null || iv == null) {
                throw SecretLostException(
                    IllegalStateException("credential row ${row.id} carries no ciphertext"),
                )
            }
            try {
                val cipher = ciphers.forDecryption(iv)
                cipher.updateAAD(aad(row.origin, row.username))
                return StoredSecret(
                    row.username,
                    cipher.doFinal(ciphertext).decodeToString().toCharArray(),
                    emptyMap(),
                )
            } catch (e: GeneralSecurityException) {
                throw SecretLostException(e)
            }
        }
    }

    private companion object {
        /** AAD binds a ciphertext to its origin + username, so a moved row never decrypts (03). */
        fun aad(
            origin: String,
            username: String,
        ): ByteArray = "$origin\n$username".encodeToByteArray()
    }
}
