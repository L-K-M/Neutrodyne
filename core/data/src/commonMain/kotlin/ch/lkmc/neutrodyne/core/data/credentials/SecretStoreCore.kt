// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.credentials

import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.Origin
import ch.lkmc.neutrodyne.core.database.CredentialDao
import ch.lkmc.neutrodyne.core.database.CredentialEntity
import ch.lkmc.neutrodyne.core.database.PodcastDao
import ch.lkmc.neutrodyne.core.domain.CredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.domain.StoredSecret
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.encoding.Base64

/**
 * A `credential` row's secret material cannot be recovered — the platform key is gone or the
 * ciphertext is corrupt (03 Basic auth: key loss). The row is dead weight: [SecretStoreCore]
 * drops the entry, marks every referencing podcast `needsCredentials` and deletes the row.
 */
internal class SecretLostException(
    cause: Throwable,
) : Exception(cause)

/** What a platform `SecretStore` persists on `put`: the written row plus the entry to serve. */
internal class PersistedSecret(
    /** The `credential` row as written, id filled in; null when the origin has no row (desktop `sync:`). */
    val row: CredentialEntity?,
    /** The in-memory entry — the platform decides whether [StoredSecret.attributes] survive (they do not on Android). */
    val stored: StoredSecret,
)

/**
 * The platform half of a `SecretStore` (03 Basic auth and CredentialStore): how a `credential`
 * row becomes usable secret material. `KeystoreCredentialStore` decrypts `secretCipher`/`iv`;
 * the desktop resolves rows against its `secrets.json`/`secrets.bin` file and keeps `sync:`
 * entries file-only.
 */
internal interface SecretStoreSeam {
    /**
     * The stored secret of [row]; `null` when the row carries no usable material (not an error —
     * the row stays out of the map), or throw [SecretLostException] when the material is
     * unrecoverable (key loss — the row is then dropped and deleted).
     */
    suspend fun resolve(row: CredentialEntity): StoredSecret?

    /**
     * The secrets the platform keeps outside `credential` rows — the desktop's file-only
     * `sync:` entries, with their `attributes`. Consulted on every rebuild; empty on Android.
     */
    suspend fun rowlessEntries(): Map<String, StoredSecret> = emptyMap()

    /**
     * A row-backed stored origin left the row set (cascade, sweep): drop whatever the platform
     * keeps outside rows — on the desktop, the `secrets.json` entry. No-op on Android, where the
     * row carries everything. Key-loss deletions do not call this — their outside-row state is
     * the platform's problem. Runs under the core's lock, so it must not call back into the core.
     */
    suspend fun rowDropped(origin: String) {}
}

/**
 * The platform-neutral half of 03's `SecretStore` (Basic auth and CredentialStore): the
 * in-memory map `AuthInterceptor` reads, the `observeAll`-driven rebuild loop and key-loss
 * recovery. `KeystoreCredentialStore` (androidMain) and `DesktopSecretStore` (desktopMain)
 * delegate to it and supply only the persistence/crypto work through [SecretStoreSeam] and the
 * `persist`/`delete` lambdas of [put]/[remove].
 *
 * Concurrency contract:
 *
 * - `basicAuthorization` is a non-blocking read of a published immutable map — the
 *   `AuthInterceptor` must never suspend.
 * - [awaitLoaded] completes once the first emission has been reconciled — or when collection
 *   fails, because a failed load must not strand `FeedFetcher` forever.
 * - [put]/[remove] run their platform persistence inside [mutex] and update the map directly:
 *   the caller's change is visible to `basicAuthorization` before the next Room emission lands.
 * - The reconcile compares emitted rows by id + content against the entry's expected row, so a
 *   stale emission cannot resurrect a replaced or removed secret, and a `put` that committed
 *   after the emission's snapshot is re-checked live instead of being dropped.
 */
internal class SecretStoreCore(
    private val dao: CredentialDao,
    private val podcasts: PodcastDao,
    private val coordinator: CredentialCommitCoordinator,
    private val seam: SecretStoreSeam,
    scope: CoroutineScope,
    rows: Flow<List<CredentialEntity>> = dao.observeAll(),
) {
    /**
     * One published entry. [expectedRow] is the row content the entry was built from (observer)
     * or written with ([put]); [confirmed] marks whether `observeAll` has delivered that row —
     * an unconfirmed entry survives emissions whose snapshot predates its write.
     */
    private class Entry(
        /** The `credential` row id; null for entries the platform keeps outside rows (desktop `sync:`). */
        val rowId: Long?,
        val stored: StoredSecret,
        val expectedRow: CredentialEntity?,
        val confirmed: Boolean,
    )

    /** The map `basicAuthorization` reads: parsed http(s) origins only — never `sync:`/`podcastindex`. */
    private class Published(
        val entries: Map<String, Entry>,
        val authorization: Map<Origin, String>,
    )

    private val mutex = Mutex()
    private var entries: Map<String, Entry> = emptyMap()

    /** Rows deleted by [remove]: id → content, so a stale emission cannot resurrect them. */
    private val tombstones = HashMap<Long, CredentialEntity>()

    /**
     * The row content behind every live entry, keyed by row id — the cheap "unchanged" check
     * that keeps the reconcile from re-decrypting rows it has already seen. Published for the
     * resolve pass to read without holding [mutex].
     */
    @Volatile
    private var seenRows: Map<Long, CredentialEntity> = emptyMap()

    @Volatile
    private var published = Published(emptyMap(), emptyMap())

    /** The first-load barrier behind [awaitLoaded]. */
    private val loaded = CompletableDeferred<Unit>()

    init {
        scope.launch {
            try {
                rows.collect { emission ->
                    try {
                        reconcile(emission)
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        Log.e(TAG, t) { "credential row reconcile failed" }
                    }
                    // The first emission — even a failed reconcile — ends the load wait.
                    loaded.complete(Unit)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.e(TAG, t) { "credential row observation ended" }
            } finally {
                // A flow that dies before its first emission must not strand awaitLoaded callers.
                loaded.complete(Unit)
            }
        }
    }

    /** Suspends until the first emission is reconciled into memory (03). */
    suspend fun awaitLoaded() {
        loaded.await()
    }

    /** `"Basic base64(user:pass)"` for [origin], or null — a non-blocking map read. */
    fun basicAuthorization(origin: Origin): String? = published.authorization[origin]

    /** The stored entry for [origin], canonicalised the way [put] stores it. */
    suspend fun get(origin: String): StoredSecret? = published.entries[storedOrigin(origin)]?.stored

    /** The feed credential of [podcastId] — resolved through `podcast.credentialId`, not the URL. */
    suspend fun forPodcast(podcastId: Long): BasicCredentials? {
        val credentialId = podcasts.byId(podcastId)?.credentialId ?: return null
        val stored =
            published.entries.values
                .firstOrNull { it.rowId == credentialId }
                ?.stored ?: return null
        return BasicCredentials(stored.username, stored.secret.concatToString())
    }

    /** The user's Podcast Index key pair (origin `podcastindex`), or null. */
    suspend fun podcastIndexKey(): Pair<String, String>? =
        published.entries[PODCAST_INDEX_ORIGIN]
            ?.stored
            ?.let { it.username to it.secret.concatToString() }

    /**
     * `SecretStore.put`: [persist] encrypts and writes the row (Android) or the file entry and
     * metadata row (desktop), then the map is updated under the same lock — the new secret
     * answers `basicAuthorization` immediately, ahead of the Room emission.
     */
    suspend fun put(
        origin: String,
        persist: suspend (canonicalOrigin: String) -> PersistedSecret,
    ): Long? =
        mutex.withLock {
            val canonical = storedOrigin(origin)
            val result = persist(canonical)
            entries =
                entries +
                (
                    canonical to
                        Entry(
                            rowId = result.row?.id,
                            stored = result.stored,
                            expectedRow = result.row,
                            confirmed = false,
                        )
                )
            publish()
            result.row?.id
        }

    /**
     * `SecretStore.remove`: [delete] drops the persisted state (the origin's rows on Android) and
     * returns what it deleted so a late-arriving emission of those rows stays dead.
     */
    suspend fun remove(
        origin: String,
        delete: suspend (canonicalOrigin: String) -> List<CredentialEntity>,
    ) {
        mutex.withLock {
            val canonical = storedOrigin(origin)
            for (row in delete(canonical)) tombstones[row.id] = row
            // Tombstone the entry's row too: it is what a stale emission would carry if [delete]
            // missed it.
            entries[canonical]?.expectedRow?.let { tombstones[it.id] = it }
            entries = entries - canonical
            publish()
        }
    }

    /**
     * Rebuilds the published map from one `observeAll` emission. Rows that no longer exist leave
     * memory on the same commit (the cascade/sweep path of 02); rows that fail to resolve with
     * [SecretLostException] are marked and deleted afterwards (03 key loss).
     */
    private suspend fun reconcile(rows: List<CredentialEntity>) {
        // Resolve changed rows before touching the map — decryption can be slow. Rows whose
        // stored content is already behind an entry are never re-decrypted.
        val seen = seenRows
        val resolved = HashMap<Long, StoredSecret>()
        val lost = ArrayList<CredentialEntity>()
        for (row in rows) {
            if (seen[row.id] == row) continue
            try {
                resolved[row.id] = seam.resolve(row) ?: continue
            } catch (lostFailure: SecretLostException) {
                lost += row
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.w(TAG, t) { "credential row ${row.id} could not be read" }
            }
        }

        val emittedIds = rows.mapTo(HashSet()) { it.id }
        val droppedOrigins = ArrayList<String>()
        mutex.withLock {
            val next = LinkedHashMap<String, Entry>(entries.size + 4)
            // Rowless entries are re-read from the seam on every rebuild (the desktop's file-only
            // `sync:` secrets); row-backed ones are reconciled against the emission.
            for ((key, entry) in entries) if (entry.rowId != null) next[key] = entry
            for ((origin, stored) in seam.rowlessEntries()) {
                next[origin] = Entry(rowId = null, stored = stored, expectedRow = null, confirmed = true)
            }

            for (row in rows) {
                if (tombstones[row.id] == row) continue // a snapshot predating our own delete
                val key = storedOrigin(row.origin)
                val existing = next[key]
                when {
                    existing == null -> {
                        resolved[row.id]?.let { next[key] = Entry(row.id, it, row, confirmed = true) }
                    }

                    !existing.confirmed -> {
                        // A put/remove staged this entry; only the exact row it wrote confirms it —
                        // anything else is an emission predating the write.
                        if (existing.rowId == row.id && existing.expectedRow == row) {
                            next[key] = Entry(row.id, existing.stored, row, confirmed = true)
                        }
                    }

                    else -> {
                        // Confirmed entries take the emission as authoritative: same content is a
                        // no-op, new content replaces, unresolvable content drops the entry.
                        val stored = resolved[row.id]
                        when {
                            existing.expectedRow == row -> {}

                            stored != null -> {
                                next[key] = Entry(row.id, stored, row, confirmed = true)
                            }

                            else -> {
                                next.remove(key)
                            }
                        }
                    }
                }
            }

            // Rows absent from the emission: confirmed entries were deleted out from under us
            // (unsubscribe cascade, merge, sweep). Pending puts may postdate the snapshot —
            // check the row still exists before trusting either interpretation.
            for ((key, entry) in next.entries.toList()) {
                val rowId = entry.rowId ?: continue
                if (rowId in emittedIds) continue
                val alive =
                    if (entry.confirmed) {
                        false
                    } else {
                        dao.byIds(listOf(rowId)).isNotEmpty()
                    }
                if (!alive) {
                    next.remove(key)
                    droppedOrigins += key
                }
            }

            // An emission lacking a tombstoned row proves the view moved past our delete; the
            // tombstone has done its work (and the id may later be reused).
            tombstones.entries.removeAll { it.key !in emittedIds }
            entries = next
            publish()

            // Under the lock: a `put` for the same origin waits, so its freshly persisted
            // platform state is never the one dropped here.
            for (origin in droppedOrigins) seam.rowDropped(origin)
        }

        if (lost.isNotEmpty()) {
            // One transaction marks every referencing podcast before the SET_NULL delete runs.
            coordinator.withCredentialCommit { dao.dropLostRows(lost) }
        }
    }

    private fun publish() {
        val authorization = HashMap<Origin, String>(entries.size)
        for ((stored, entry) in entries) {
            val origin = storedOriginToOrigin(stored) ?: continue
            val secret = entry.stored.secret.concatToString()
            authorization[origin] =
                BASIC_PREFIX + Base64.encode("${entry.stored.username}:$secret".encodeToByteArray())
        }
        published = Published(entries, authorization)
        seenRows = entries.values.mapNotNull { it.expectedRow }.associateBy { it.id }
    }

    private companion object {
        const val TAG = "SecretStore"
        const val BASIC_PREFIX = "Basic "
    }
}
