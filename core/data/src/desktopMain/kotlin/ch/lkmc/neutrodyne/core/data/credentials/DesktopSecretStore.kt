// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.credentials

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.common.Log
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

/**
 * The desktop `SecretStore` (03 Basic auth and CredentialStore; PO-44, 11 Secrets): secret
 * material lives only in the per-user secrets file — DPAPI `secrets.bin` on Windows, the `0600`
 * `secrets.json` on macOS and Linux — while `credential` rows carry just origin and username with
 * `secretCipher`/`iv` null, so `podcast.credentialId`, the unsubscribe cascade and the
 * `db-maintenance` sweep work unchanged. `sync:` entries are file-only and keep their
 * `attributes` (10's `fp` fingerprint).
 *
 * [SecretStoreCore] owns the published in-memory map, the `observeAll` reconcile loop and
 * key-loss recovery; this class owns only the file persistence half. Because the file is the
 * secret's source of truth, a `put` commits it before the metadata row — a row without its file
 * entry is lost material and can never be committed first — and a `credential` row whose file
 * entry is gone resolves as [SecretLostException] (needsCredentials + delete).
 *
 * The Metro bindings expose `SecretStore` and the `AuthInterceptor`'s [CredentialLookup] —
 * the same singleton answers both (03 Start-up: `FeedFetcher` also awaits its load).
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding<SecretStore>())
@ContributesBinding(AppScope::class, binding<CredentialLookup>())
@Inject
internal class DesktopSecretStore(
    db: NeutrodyneDatabase,
    appDirs: AppDirs,
    coordinator: CredentialCommitCoordinator,
    private val clock: Clock,
    private val protection: SecretProtection,
    @ApplicationScope scope: CoroutineScope,
) : SecretStore {
    private val dao: CredentialDao = db.credentialDao()

    private val file = DesktopSecretsFile(appDirs.data.resolve(protection.fileName), protection)

    private val core =
        SecretStoreCore(
            dao = dao,
            podcasts = db.podcastDao(),
            coordinator = coordinator,
            seam = DesktopSeam(),
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
            val stored = StoredSecret(username, secret.copyOf(), attributes.toMap())
            if (canonical.startsWith(SYNC_ORIGIN_PREFIX)) {
                // `sync:` entries live only in the file — there is no `credential` row (03, 10).
                file.put(canonical, stored)
                PersistedSecret(row = null, stored = stored)
            } else {
                persistWithRow(canonical, stored)
            }
        }

    /**
     * File entry first, then the metadata row (11). If the row write fails, the staged entry is
     * rolled back so it cannot outlive its row; an entry that still escapes — a process crash
     * between the writes — is dropped by the first reconcile's orphan sweep.
     */
    private suspend fun persistWithRow(
        canonical: String,
        stored: StoredSecret,
    ): PersistedSecret {
        val prior = file.put(canonical, stored)
        val written =
            CredentialEntity(
                origin = canonical,
                username = stored.username,
                secretCipher = null,
                iv = null,
                createdAt = clock.now(),
            )
        try {
            return PersistedSecret(written.copy(id = dao.putByOrigin(written)), stored)
        } catch (t: Throwable) {
            rollBackFileEntry(canonical, prior)
            throw t
        }
    }

    private suspend fun rollBackFileEntry(
        origin: String,
        prior: StoredSecret?,
    ) {
        try {
            if (prior == null) file.remove(origin) else file.put(origin, prior)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG) { "could not roll back the secrets file after a failed row write (${t::class.simpleName})" }
        }
    }

    override suspend fun remove(origin: String) {
        core.remove(origin) { canonical ->
            // Same file-first order as `put`.
            file.remove(canonical)
            val rows = dao.byOrigin(canonical)
            for (row in rows) dao.delete(row.id)
            rows
        }
    }

    private inner class DesktopSeam : SecretStoreSeam {
        /** The interrupted-put sweep runs once per process, inside the core mutex. */
        private var orphansChecked = false

        override suspend fun resolve(row: CredentialEntity): StoredSecret? {
            val entry =
                file.entry(storedOrigin(row.origin))
                    ?: throw SecretLostException(
                        IllegalStateException(
                            "credential row ${row.id} has no usable secrets-file entry",
                        ),
                    )
            return StoredSecret(row.username, entry.secret, entry.attributes)
        }

        override suspend fun rowlessEntries(): Map<String, StoredSecret> {
            if (!orphansChecked) {
                // Interrupted-put sweep (11): `put` commits the file entry before its row, so a
                // crash or failed row write can leave an entry no row claims. This runs inside
                // the core mutex — an in-flight `put` is impossible here — and the liveness
                // check reads the database, not the emission, so an older snapshot cannot get a
                // newly staged entry deleted. `sync:` entries are file-only, never orphans.
                file.dropOrphans { origin ->
                    origin.startsWith(SYNC_ORIGIN_PREFIX) || dao.byOrigin(origin).isNotEmpty()
                }
                orphansChecked = true
            }
            return file.entries().filterKeys { it.startsWith(SYNC_ORIGIN_PREFIX) }
        }

        override suspend fun rowDropped(origin: String) {
            file.remove(origin)
        }
    }

    private companion object {
        const val TAG = "DesktopSecretStore"
    }
}
