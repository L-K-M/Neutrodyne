// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.CredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.domain.SecretStore
import ch.lkmc.neutrodyne.core.domain.SettingsSyncPort
import ch.lkmc.neutrodyne.core.model.settings.AllSettingKeys
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * 10 Durable apply effects: drains the recoverable part of a committed page (settings, protected
 * credentials) after the commit and at each start, before pushing. A winning setting stores
 * `pendingSetting`; [drainSettings] applies it through [SettingsSyncPort] and clears the marker
 * only after the DataStore write succeeds, so a crash between the stores repeats the idempotent
 * write. A winning credential stores `pendingAuth`; [drainPasswords] installs the staged secret
 * through [CredentialCommitCoordinator] — the same put→reference coordination as local edits, so
 * the credential sweep and the unsubscribe cascade cannot race it — and sets `credentialId` with
 * capture suspended, so a recovered effect never echoes as a fresh local edit.
 */
class SyncApplyEffects(
    private val db: NeutrodyneDatabase,
    private val settings: SettingsSyncPort,
    private val secrets: SecretStore,
    private val coordinator: CredentialCommitCoordinator,
) {
    /** The full drain after a committed page and at each start, sweep included. */
    suspend fun drain() {
        drainSettings()
        drainPasswords()
        sweepOrphanStaging(
            db
                .syncStateDao()
                .get()
                ?.accountId
                .orEmpty(),
        )
    }

    /** Applies every pending setting whose marker survived; leaves the rest for a later drain. */
    suspend fun drainSettings() {
        for (row in db.syncClockDao().all()) {
            if (row.coll != SyncCollection.SETTING.wire) continue
            val raw = decode(row.clocks) ?: continue
            val pending = raw.pendingSetting ?: continue
            val key = AllSettingKeys.list.firstOrNull { it.name == row.rid } ?: continue
            if (!settings.applyRemote(key, pending.value)) continue
            db.syncClockDao().upsert(row.copy(clocks = encode(raw.copy(pendingSetting = null))))
        }
    }

    /**
     * Installs every journaled credential. A podcast that is not present yet keeps its effect
     * for the drain after the podcast arrives (the post-page drain or the next start); a staging
     * entry that no longer decrypts is unrecoverable — the effect is dropped and the feed flagged
     * `needsCredentials` instead of retrying forever.
     */
    suspend fun drainPasswords() {
        // A locked or not-yet-loaded store would answer every lookup with null; wait for the
        // first decrypted emission so a merely slow store is never mistaken for key loss.
        secrets.awaitLoaded()
        for (row in db.syncClockDao().all()) {
            if (row.coll != SyncCollection.PODCAST.wire) continue
            val raw = decode(row.clocks) ?: continue
            val pending = raw.pendingAuth ?: continue
            try {
                installPassword(row.rid, pending)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                // Leave the marker; the next drain retries after the transient cause is gone.
                Log.w(TAG) { "pending credential install failed (${failure::class.simpleName})" }
            }
        }
    }

    private suspend fun installPassword(
        rid: String,
        pending: PendingAuth,
    ) {
        val podcast = db.podcastDao().bySyncId(rid) ?: return
        val staged = secrets.get(pending.reference)
        if (staged == null) {
            abandonPassword(rid, pending, podcast.id)
            return
        }
        coordinator.withCredentialCommit {
            // Re-check under the coordinator: a newer page may have replaced the effect, or the
            // record's origin may have been re-derived, since the marker was read.
            val current = rawOf(rid) ?: return@withCredentialCommit
            val effect = current.pendingAuth ?: return@withCredentialCommit
            if (effect != pending) return@withCredentialCommit
            val origin = current.stringValueOf("credentialOrigin") ?: effect.credentialOrigin
            val rowId = secrets.put(origin, staged.username, staged.secret.copyOf())
            db.withWriteTransaction {
                db.syncStateDao().withApplying {
                    // The podcast may have been unsubscribed mid-drain; the effect then waits
                    // for nothing and is cleared below without a reference.
                    if (db.podcastDao().byId(podcast.id) != null) {
                        db.podcastDao().setCredentialId(podcast.id, rowId)
                        db.podcastDao().clearNeedsCredentials(podcast.id)
                    }
                }
            }
            clearPendingAuth(rid, pending)
        }
    }

    /** Key loss or a lost staging entry: the credential can never install from this device. */
    private suspend fun abandonPassword(
        rid: String,
        pending: PendingAuth,
        podcastId: Long,
    ) {
        clearPendingAuth(rid, pending)
        db.withWriteTransaction {
            db.syncStateDao().withApplying {
                if (db.podcastDao().byId(podcastId) != null) db.podcastDao().markNeedsCredentials(podcastId)
            }
        }
    }

    /** Deletes the marker only if it still names [done], so a newer page's effect survives. */
    private suspend fun clearPendingAuth(
        rid: String,
        done: PendingAuth,
    ) {
        val raw = rawOf(rid) ?: return
        if (raw.pendingAuth == done) {
            db.syncClockDao().upsert(
                ch.lkmc.neutrodyne.core.database.SyncClockEntity(
                    SyncCollection.PODCAST.wire,
                    rid,
                    encode(raw.copy(pendingAuth = null)),
                ),
            )
        }
    }

    /**
     * 10 Durable apply effects: protected snapshots stay outside credential sweeps while raw
     * state or pending work references them; everything else under the account's prefix is an
     * orphan (a failed page, a superseded reference) and leaves the store.
     */
    suspend fun sweepOrphanStaging(account: String) {
        if (account.isBlank()) return
        val referenced = HashSet<String>()
        for (row in db.syncClockDao().all()) {
            val raw = decode(row.clocks) ?: continue
            raw.authReference?.let { referenced += it.storeKey }
            raw.pendingAuth?.let { referenced += it.reference }
        }
        for (origin in secrets.originsWithPrefix("$PENDING_ORIGIN_PREFIX$account:")) {
            if (origin !in referenced) secrets.remove(origin)
        }
    }

    private suspend fun rawOf(rid: String): RawSyncRecord? =
        db.syncClockDao().get(SyncCollection.PODCAST.wire, rid)?.let { decode(it.clocks) }

    private fun decode(json: String): RawSyncRecord? =
        runCatching { SyncJson.decodeFromString<RawSyncRecord>(json) }.getOrNull()

    private fun encode(raw: RawSyncRecord): String = SyncJson.encodeToString(raw)

    private companion object {
        const val TAG = "SyncApplyEffects"
    }
}

/** The record's own `credentialOrigin` field, when it carries a string. */
private fun RawSyncRecord.stringValueOf(name: String): String? =
    (state.fields[name]?.v as? kotlinx.serialization.json.JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
