// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncClockEntity
import ch.lkmc.neutrodyne.core.domain.CredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.domain.SecretStore
import ch.lkmc.neutrodyne.core.domain.SettingsSyncPort
import ch.lkmc.neutrodyne.core.domain.SettingsWriteCoordinator
import ch.lkmc.neutrodyne.core.model.settings.AllSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.sync.protocol.FieldState
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.RecordId
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeDto
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeResultDto
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okio.FileSystem
import okio.Path

/**
 * 10 Durable apply effects: drains the recoverable part of a committed page (settings, protected
 * credentials) after the commit and at each start. A winning setting stores `pendingSetting`;
 * [drainSettings] applies it through [SettingsSyncPort] and clears the marker only after the
 * DataStore write succeeds, so a crash between the stores repeats the idempotent write.
 *
 * Every key drains under [SettingsWriteCoordinator], the same per-key mutex a local `set`/`reset`
 * and a linked restore take. Under that lock the marker and the newest local `v` outbox intent are
 * re-read: a strictly newer local intent wins and the stale remote value is skipped, while an equal
 * clock is an identical intent and still applies (a storage-failure retry). The marker is cleared
 * from a fresh row inside a Room write transaction, and only when it still matches the applied
 * value and clock, so a newer page's effect or other raw metadata is never rolled back. A skipped
 * marker is cleared by the same conditional ack: the upload ack deletes the outbox intent before
 * the server's echo page can replace the marker, so leaving it would let a later drain re-apply
 * the superseded remote value. No Room transaction spans the DataStore write.
 *
 * A drain runs only while the persisted "Sync playback settings" switch is on
 * ([settingsSyncEnabled], the live `SyncServerAddress.syncPlaybackSettings()` read — never
 * `SyncFlags`' late cache): while it is off the markers stay pending (10 Pull and apply step 3),
 * and turning it on drains them. A drain failure propagates; the markers it did not clear stay
 * for the next drain.
 *
 * 10 Passwords: [drainPasswords] installs each `pendingAuth` marker's protected snapshot as the
 * podcast's credential, all under [CredentialCommitCoordinator] — never inside a Room
 * transaction and never holding one across a `SecretStore` await or write. Under the lock the
 * marker, the winning reference and the newest local `auth` intent are re-read: a strictly
 * newer winner (local or remote) discards the stale marker and its snapshot; an equal or
 * unfinished marker installs idempotently. A missing podcast or a missing snapshot leaves the
 * marker pending (a missing snapshot also flags `needsCredentials`); the `credentialId` write
 * runs under capture suppression so a synced credential never echoes as a local intent. After
 * both stores commit the marker clears and the accepted snapshot stays the reference — the
 * immutable `sync:pending:` copy accepted at the marker's complete clock, never a pointer to
 * the mutable credential origin.
 *
 * [acknowledge] applies one push's results: acknowledged outbox rows delete, each
 * acknowledged local `auth` binds its protected reference, and each acknowledged setting
 * merges its sent `v` into raw state, all atomically inside the coordinator and one Room
 * write. The bound snapshot is the exact `{u,p}` the acknowledged change carried, staged
 * under the acknowledged complete clock before the write — a later resync replay reasserts
 * those bytes, not whatever the live credential holds then. The settings merge is pure: it
 * hashes the immutable sent literal through [SettingsSyncPort.canonicalJson], never a
 * DataStore read, so the Room writer performs no settings-store IO.
 */
class SyncApplyEffects(
    private val db: NeutrodyneDatabase,
    private val settings: SettingsSyncPort,
    private val writeLocks: SettingsWriteCoordinator,
    private val settingsSyncEnabled: suspend () -> Boolean,
    private val secrets: SecretStore,
    private val credentials: CredentialCommitCoordinator,
    /** The first-link staging file's parent — a live file retains every staged snapshot. */
    private val cacheDir: Path,
) {
    private val fileSystem = FileSystem.SYSTEM

    /** Applies every pending setting whose marker survived; leaves the rest for a later drain. */
    suspend fun drainSettings() {
        // The switch gate is the persisted value read live: markers stay pending while it is
        // off, so enabling it later applies them (the server's values win, as at the first link).
        if (!settingsSyncEnabled()) return
        for (row in db.syncClockDao().all()) {
            if (row.coll != SyncCollection.SETTING.wire) continue
            val key = AllSettingKeys.list.firstOrNull { it.name == row.rid } ?: continue
            drainSetting(key)
        }
    }

    /**
     * 10 Durable apply effects / Settings capture: the at-each-start drain, before any push or
     * hash reconciliation. Only while linked — an unlink cleared the sync tables, so a leftover
     * marker of the old account must never re-apply. Local only (Room and DataStore): no network
     * call, nothing that blocks start-up beyond the markers' own writes.
     *
     * [capture] reconciles first: its committed-intent recovery must precede the marker drain,
     * because the drain clears a superseded marker after applying — an older local intent
     * materialized only afterwards would then overwrite the newer remote value it had lost to.
     */
    suspend fun drainAtStart(capture: SettingsCapture) {
        val state = db.syncStateDao().get() ?: return
        if (!state.enabled || state.linkedAt == null) return
        capture.reconcile()
        drainSettings()
        drainPasswords()
    }

    // --- 10 Passwords: the protected credential drain ----------------------------------------------

    /**
     * Drains every podcast `pendingAuth` marker: orphaned staging keys leave first (a live
     * first-link staging file retains them all — it is itself a consumer), then each marker
     * installs under the credential-commit coordinator.
     */
    suspend fun drainPasswords() {
        secrets.awaitLoaded()
        sweepOrphanedAuthStaging()
        for (row in db.syncClockDao().all()) {
            if (row.coll != SyncCollection.PODCAST.wire) continue
            val raw = decode(row.clocks) ?: continue
            val pending = raw.pendingAuth ?: continue
            drainAuth(row.rid, pending)
        }
    }

    /**
     * `sync:pending:` entries nothing references are orphans: losing writes, failed pages and
     * forgotten accounts. Every committed `authReference`/`pendingAuth` key is kept.
     */
    private suspend fun sweepOrphanedAuthStaging() {
        // A live first-link staging file is itself a consumer of the pending snapshots.
        if (stagingFileAlive()) return
        credentials.withCredentialCommit {
            // The reference set is read inside the hold: a page mid-commit keeps its staged
            // keys unreachable until it publishes them, so enumerating first and locking
            // second would delete exactly the snapshots that page is about to reference.
            val referenced = referencedAuthKeys(db)
            for (origin in secrets.originsWithPrefix(PENDING_AUTH_PREFIX)) {
                if (origin !in referenced) suspendRunCatching { secrets.remove(origin) }
            }
        }
    }

    private suspend fun stagingFileAlive(): Boolean {
        val deviceId = db.syncStateDao().get()?.deviceId ?: return false
        return fileSystem.exists(cacheDir / "sync-link-$deviceId.jsonl")
    }

    private suspend fun drainAuth(
        rid: String,
        marker: PendingAuth,
    ) {
        credentials.withCredentialCommit {
            val row = db.syncClockDao().get(SyncCollection.PODCAST.wire, rid) ?: return@withCredentialCommit
            val raw = decode(row.clocks) ?: return@withCredentialCommit
            // A newer page moved the marker on; this pass owns only the marker it read.
            if (raw.pendingAuth != marker) return@withCredentialCommit
            val ref = raw.authReference
            val mergedOrigin = jsonContent(raw.state.fields["credentialOrigin"]?.v)
            if (marker.credentialOrigin != mergedOrigin) {
                // The winning origin moved past the origin this marker proved (a newer bare
                // origin or a newer proven pair): installing its snapshot would bind the
                // credential bytes to an origin they did not write with. The marker clears
                // like any moved-on winner; the feed is flagged when nothing satisfies the
                // winning origin — never setCredentialId, never a silent clear.
                flagMissingCredential(rid, mergedOrigin)
                discardAuth(rid, marker)
                return@withCredentialCommit
            }
            val markerClock = runCatching { Hlc.parse(marker.clock) }.getOrNull()
            val newerLocal =
                db.syncOutboxDao().get(SyncCollection.PODCAST.wire, rid, "auth")?.let { intent ->
                    val local = outboxClock(intent)
                    markerClock != null && local != null && local > markerClock
                } == true
            if (ref == null || ref.clock != marker.clock || newerLocal) {
                // The winner moved past this marker (a newer local or remote write): discard it
                // and its snapshot — the newer effect owns the credential now.
                discardAuth(rid, marker)
                return@withCredentialCommit
            }
            // A missing podcast loses nothing: the marker outlives the row's arrival.
            val podcast = db.podcastDao().bySyncId(rid) ?: return@withCredentialCommit
            val snapshot = secrets.get(marker.reference)
            if (snapshot == null) {
                // The protected copy is gone (key loss, cleared store): the feed stays flagged
                // and the marker pending for the next drain, never silently dropped.
                db.withWriteTransaction {
                    db.syncStateDao().withApplying {
                        if (db.podcastDao().byId(podcast.id) != null) {
                            db.podcastDao().setNeedsCredentials(podcast.id, true)
                        }
                    }
                }
                return@withCredentialCommit
            }
            val credentialId =
                secrets.put(marker.credentialOrigin, snapshot.username, snapshot.secret)
                    ?: return@withCredentialCommit
            val committed =
                try {
                    db.withWriteTransaction<Boolean> {
                        db.syncStateDao().withApplying<Boolean> {
                            if (db.podcastDao().byId(podcast.id) != null) {
                                db.podcastDao().setCredentialId(podcast.id, credentialId)
                                db.podcastDao().clearNeedsCredentials(podcast.id)
                                true
                            } else {
                                false
                            }
                        }
                    }
                } catch (failure: Throwable) {
                    dropUnreferencedCredential(marker.credentialOrigin, credentialId)
                    throw failure
                }
            if (!committed) {
                // The podcast vanished between the checks and the commit — the idempotent put
                // is unreferenced again and the marker stays for a later arrival.
                dropUnreferencedCredential(marker.credentialOrigin, credentialId)
                return@withCredentialCommit
            }
            ackAuth(rid, marker)
            // The staged snapshot stays while the reference owns it; a candidate only drops
            // when nothing durable points at it any more.
            dropUnreferencedStagedLocked(db, secrets, setOf(marker.reference))
        }
    }

    /**
     * Both stores committed: only the marker clears — the reference keeps pointing at the
     * immutable accepted snapshot, so a replay reasserts the exact bytes accepted at the
     * marker's complete clock instead of a later live-credential value. Re-read and write
     * share one Room transaction so a newer page's marker is never cleared.
     */
    private suspend fun ackAuth(
        rid: String,
        marker: PendingAuth,
    ) {
        db.withWriteTransaction {
            val fresh = db.syncClockDao().get(SyncCollection.PODCAST.wire, rid) ?: return@withWriteTransaction
            val raw = decode(fresh.clocks) ?: return@withWriteTransaction
            if (raw.pendingAuth != marker) return@withWriteTransaction
            db.syncClockDao().upsert(
                fresh.copy(clocks = encode(raw.copy(pendingAuth = null))),
            )
        }
    }

    /**
     * The feed is flagged `needsCredentials` unless its installed credential already
     * satisfies [mergedOrigin] — the projection the pending marker suppressed while the
     * moved-from origin still looked installable.
     */
    private suspend fun flagMissingCredential(
        rid: String,
        mergedOrigin: String?,
    ) {
        val podcast = db.podcastDao().bySyncId(rid) ?: return
        val satisfied =
            podcast.credentialId
                ?.let {
                    db
                        .credentialDao()
                        .byIds(listOf(it))
                        .firstOrNull()
                        ?.origin
                } == mergedOrigin &&
                mergedOrigin != null
        if (satisfied) return
        db.withWriteTransaction {
            db.syncStateDao().withApplying {
                if (db.podcastDao().byId(podcast.id) != null) {
                    db.podcastDao().setNeedsCredentials(podcast.id, true)
                }
            }
        }
    }

    /**
     * The marker's winner is gone: the marker clears and the staged snapshot leaves once
     * nothing durable references it — a strictly newer write owns the credential now.
     */
    private suspend fun discardAuth(
        rid: String,
        marker: PendingAuth,
    ) {
        db.withWriteTransaction {
            val fresh = db.syncClockDao().get(SyncCollection.PODCAST.wire, rid) ?: return@withWriteTransaction
            val raw = decode(fresh.clocks) ?: return@withWriteTransaction
            if (raw.pendingAuth != marker) return@withWriteTransaction
            db.syncClockDao().upsert(fresh.copy(clocks = encode(raw.copy(pendingAuth = null))))
        }
        dropUnreferencedStagedLocked(db, secrets, setOf(marker.reference))
    }

    /**
     * The `put` committed but the `credentialId` write did not: the unreferenced row is
     * reclaimed like `PodcastRepository.setCredentials` does — the marker stays pending and the
     * idempotent next drain re-puts it.
     */
    private suspend fun dropUnreferencedCredential(
        origin: String,
        credentialId: Long,
    ) {
        if (db.credentialDao().referenceCount(credentialId) == 0) {
            suspendRunCatching { secrets.remove(origin) }
        }
    }

    /**
     * 10 Passwords / Push ack: the round's acknowledgement effects commit as one unit under
     * the credential coordinator. For every accepted change the acknowledged outbox rows
     * delete exactly as before; for every accepted podcast `auth` the raw reference binds the
     * immutable snapshot of the exact `{u,p}` that was sent, keyed by account, rid and the
     * acknowledged complete clock. The snapshots stage before the Room write — never inside
     * it — and a delayed old ack cannot replace newer state: the clock comparison keeps the
     * newer reference and `deleteAcked` keeps the newer intent.
     */
    suspend fun acknowledge(
        changes: List<ChangeDto>,
        results: List<ChangeResultDto>,
    ) {
        if (changes.isEmpty() || results.isEmpty()) return
        credentials.withCredentialCommit {
            val state = db.syncStateDao().get()
            val account = state?.accountId ?: "local"
            val deviceId = state?.deviceId
            changes.forEachIndexed { index, change ->
                if (change.coll != SyncCollection.PODCAST.wire) return@forEachIndexed
                val result = results.getOrNull(index) ?: return@forEachIndexed
                if (result.status !in setOf("applied", "stale", "merged")) return@forEachIndexed
                val value = change.fields["auth"] ?: return@forEachIndexed
                val (user, pass) = authPair(value.v) ?: return@forEachIndexed
                val clock = value.c?.let { runCatching { Hlc.parse(it) }.getOrNull() } ?: return@forEachIndexed
                val rid =
                    runCatching {
                        RecordId.fromWire(SyncCollection.PODCAST, change.id)
                    }.getOrNull()
                        ?.rid ?: return@forEachIndexed
                val key = pendingAuthKey(account, rid, clock.toString())
                // The snapshot is immutable by clock: an existing key at the same complete
                // clock already holds the accepted bytes and is not rewritten.
                if (secrets.get(key) == null) secrets.put(key, user, pass)
            }
            db.withWriteTransaction {
                changes.forEachIndexed { index, change ->
                    val result = results.getOrNull(index) ?: return@forEachIndexed
                    if (result.status !in setOf("applied", "stale", "merged")) return@forEachIndexed
                    val coll = change.coll
                    val rid = RecordId.fromWire(SyncCollection.fromWire(coll), change.id).rid
                    if (change.op == "rekey") {
                        db.syncOutboxDao().deleteField(coll, rid, "~rekey")
                        return@forEachIndexed
                    }
                    for ((field, value) in change.fields) {
                        val clock = value.c ?: continue
                        val parsed = runCatching { Hlc.parse(clock) }.getOrNull() ?: continue
                        db.syncOutboxDao().deleteAcked(coll, rid, field, parsed.packed, parsed.nodeId.value)
                    }
                    if (coll == SyncCollection.PODCAST.wire && change.fields.containsKey("auth")) {
                        bindAcknowledgedAuth(account, change, rid)
                    }
                    // A `*` row is acknowledged by every field it expanded to.
                    change.fields.values
                        .mapNotNull { it.c }
                        .mapNotNull { runCatching { Hlc.parse(it) }.getOrNull() }
                        .minOrNull()
                        ?.let { db.syncOutboxDao().deleteAcked(coll, rid, "*", it.packed, it.nodeId.value) }
                    if (coll == SyncCollection.SETTING.wire) mergeAckedSetting(rid, change)
                    if (coll == SyncCollection.SESSION.wire) mergeAckedSession(rid, change, deviceId)
                }
            }
        }
    }

    /**
     * 10 Outbox and coalescing / Settings capture: the acknowledgement merges the exact sent `v`
     * into `sync_clock` raw state by the normal field rules — the cursor alone must not strand
     * the accepted value, and the merged `v` keeps a restart from re-capturing the key — and
     * advances the materialization baseline to the immutable sent value and its complete clock
     * in the same transaction, never re-reading a possibly newer DataStore value. The pending
     * marker and the other raw metadata stay untouched, so a newer page's effect or a newer
     * local intent is never rolled back by an older ack.
     *
     * Runs inside [acknowledge]'s writer, so the outbox deletes and this raw merge commit or
     * roll back together; [SettingsSyncPort.canonicalJson] is the pure typed spelling of the
     * sent literal — no settings lock or store IO belongs in this transaction.
     */
    private suspend fun mergeAckedSetting(
        rid: String,
        change: ChangeDto,
    ) {
        val sent = change.fields["v"] ?: return
        val value = sent.v ?: return
        val clock = sent.c?.let { runCatching { Hlc.parse(it) }.getOrNull() } ?: return
        val key = AllSettingKeys.list.firstOrNull { it.name == rid && it.synced } ?: return
        val stored = db.syncClockDao().get(SyncCollection.SETTING.wire, rid)?.let { decode(it.clocks) }
        val incoming =
            RecordState(
                SyncCollection.SETTING,
                fields = mapOf("v" to FieldState(value, clock)),
            )
        val merged = SyncRecordMerger.merge(stored?.state, null, incoming)
        // A historical restore may have materialized below a value already known in raw state.
        // Its rejected ACK must journal that winner even if the response re-delivers equal raw
        // clocks; otherwise the applier's stale fast path leaves the restore looking unrecorded.
        val winner = merged.record.fields["v"]
        val winnerClock = winner?.c
        val pending = stored?.pendingSetting
        val pendingClock = pending?.clock?.let { runCatching { Hlc.parse(it) }.getOrNull() }
        val materializeWinner =
            if (winner != null && winnerClock != null && winnerClock > clock &&
                (pendingClock == null || pendingClock < winnerClock)
            ) {
                PendingSetting(winner.v.toString(), winnerClock.toString())
            } else {
                pending
            }
        db.syncClockDao().upsert(
            SyncClockEntity(
                SyncCollection.SETTING.wire,
                rid,
                encode(
                    RawSyncRecord(
                        state = merged.record,
                        by = stored?.by,
                        authReference = stored?.authReference,
                        redirect = stored?.redirect,
                        alias = stored?.alias,
                        // prepareForPush baselines a selected literal before sending it. A clockless
                        // baseline seen now therefore belongs to a later off-switch write, and this
                        // delayed acknowledgement must not relabel it as the older sent value.
                        settingHash =
                            stored?.settingHash?.takeIf { it.clock == null }
                                ?: stored?.settingHash.advancedTo(
                                    value.toString(),
                                    clock.toString(),
                                    settings.canonicalJson(key, value.toString()) ?: return,
                                ),
                        pendingSetting = materializeWinner,
                        pendingAuth = stored?.pendingAuth,
                        settingIntent = stored?.settingIntent,
                    ),
                ),
            ),
        )
    }

    /**
     * 10 Now playing and handoff: an acknowledged session merges its sent literals into
     * `sync_clock` raw state under the normal field rules, in the same transaction that deletes
     * the outbox rows. Without it the gap between the deletes and the server's echo would leave
     * only an older remote winner on record, and the adopter's played-since fence — which reads
     * raw plus pending — could offer back a session this device already superseded. When the
     * acknowledged fields win, this device becomes the recorded writer (`by`); a newer stored
     * session keeps its writer untouched.
     */
    private suspend fun mergeAckedSession(
        rid: String,
        change: ChangeDto,
        deviceId: String?,
    ) {
        val sent =
            change.fields.mapNotNull { (field, value) ->
                val v = value.v ?: return@mapNotNull null
                val c = value.c?.let { runCatching { Hlc.parse(it) }.getOrNull() } ?: return@mapNotNull null
                field to FieldState(v, c)
            }
        if (sent.isEmpty()) return
        val stored = db.syncClockDao().get(SyncCollection.SESSION.wire, rid)?.let { decode(it.clocks) }
        val incoming = RecordState(SyncCollection.SESSION, fields = sent.toMap())
        val merged = SyncRecordMerger.merge(stored?.state, null, incoming)
        val oursWon =
            sent.any { (field, state) ->
                merged.record.fields[field]?.let { it.v == state.v && it.c == state.c } == true
            }
        db.syncClockDao().upsert(
            SyncClockEntity(
                SyncCollection.SESSION.wire,
                rid,
                encode(
                    RawSyncRecord(
                        state = merged.record,
                        by = if (oursWon) deviceId ?: stored?.by else stored?.by,
                        authReference = stored?.authReference,
                        redirect = stored?.redirect,
                        alias = stored?.alias,
                        settingHash = stored?.settingHash,
                        pendingSetting = stored?.pendingSetting,
                        pendingAuth = stored?.pendingAuth,
                        settingIntent = stored?.settingIntent,
                    ),
                ),
            ),
        )
    }

    /**
     * Binds the acknowledged auth's staged snapshot as the raw reference — the protected copy
     * a resync replay hydrates — unless the stored reference is already at least as new.
     * Only the proven sent pair binds: the `credentialOrigin` companion the change itself
     * carried at the auth's exact complete clock. An ack whose auth rode unpaired binds
     * nothing — the independently merged or live origin is never a fallback for what the
     * snapshot wrote with.
     */
    private suspend fun bindAcknowledgedAuth(
        account: String,
        change: ChangeDto,
        rid: String,
    ) {
        val clock = change.fields.getValue("auth").c ?: return
        runCatching { Hlc.parse(clock) }.getOrNull() ?: return
        val origin =
            change.fields["credentialOrigin"]
                ?.takeIf { it.c == clock }
                ?.v
                ?.let(::jsonContent) ?: return
        val row = db.syncClockDao().get(SyncCollection.PODCAST.wire, rid) ?: return
        val raw = decode(row.clocks) ?: return
        val existing = raw.authReference
        val existingClock = existing?.clock?.let { runCatching { Hlc.parse(it) }.getOrNull() }
        if (existingClock != null && existingClock >= Hlc.parse(clock)) return
        db.syncClockDao().upsert(
            row.copy(
                clocks =
                    encode(
                        raw.copy(
                            authReference =
                                AuthReference(
                                    pendingAuthKey(account, rid, clock),
                                    origin,
                                    clock,
                                ),
                        ),
                    ),
            ),
        )
    }

    private fun jsonContent(value: kotlinx.serialization.json.JsonElement?): String? =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    // --- Settings drain (10 Durable apply effects) --------------------------------------------------

    private suspend fun drainSetting(key: SettingKey<*>) {
        writeLocks.withLock(key) {
            // A previous key or local writer may have held this drain while sync was disabled.
            // Admit each key under its write lock; later keys stay pending once the switch is off.
            if (!settingsSyncEnabled()) return@withLock
            val row = db.syncClockDao().get(SyncCollection.SETTING.wire, key.name) ?: return@withLock
            val raw = decode(row.clocks) ?: return@withLock
            val pending = raw.pendingSetting ?: return@withLock
            if (hasNewerLocalIntent(key, pending)) {
                ack(key, pending, applied = false)
                return@withLock
            }
            if (!settings.applyRemote(key, pending.value)) return@withLock
            ack(key, pending, applied = true)
        }
    }

    /**
     * True when the newest local `v` intent's complete clock is strictly newer than the pending
     * remote clock. A marker or intent without a parseable clock cannot prove a newer local write,
     * so it applies as before.
     */
    private suspend fun hasNewerLocalIntent(
        key: SettingKey<*>,
        pending: PendingSetting,
    ): Boolean {
        val pendingClock = runCatching { Hlc.parse(pending.clock) }.getOrNull() ?: return false
        val local = db.syncOutboxDao().get(SyncCollection.SETTING.wire, key.name, "v") ?: return false
        val localClock = runCatching { Hlc(local.hlc, NodeId(local.nodeId)) }.getOrNull() ?: return false
        return localClock > pendingClock
    }

    /**
     * Clears the marker only when the fresh row still carries exactly the given value and clock.
     * The re-read and the write share one Room transaction, so a concurrent metadata update or a
     * newer page's marker is neither overwritten nor cleared. Used both after a successful apply
     * and when a strictly newer local intent supersedes the marker.
     *
     * Only an [applied] marker advances the materialization baseline to the applied value and
     * clock — atomically with the clear. A superseded marker never materialized, so the baseline
     * stays with the local intent that beat it.
     */
    private suspend fun ack(
        key: SettingKey<*>,
        pending: PendingSetting,
        applied: Boolean,
    ) {
        db.withWriteTransaction {
            val fresh =
                db.syncClockDao().get(SyncCollection.SETTING.wire, key.name)
                    ?: return@withWriteTransaction
            val freshRaw = decode(fresh.clocks) ?: return@withWriteTransaction
            val marker = freshRaw.pendingSetting ?: return@withWriteTransaction
            if (marker.value != pending.value || marker.clock != pending.clock) {
                return@withWriteTransaction
            }
            val next =
                if (applied) {
                    freshRaw.settingHash.advancedTo(
                        marker.value,
                        marker.clock,
                        settings.canonicalJson(key, marker.value) ?: return@withWriteTransaction,
                    )
                } else {
                    freshRaw.settingHash
                }
            db.syncClockDao().upsert(
                fresh.copy(clocks = encode(freshRaw.copy(pendingSetting = null, settingHash = next))),
            )
        }
    }

    private fun decode(json: String): RawSyncRecord? = decodeRawClocks(json)

    private fun encode(raw: RawSyncRecord): String = SyncJson.encodeToString(raw)
}
