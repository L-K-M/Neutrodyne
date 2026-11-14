// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncCaptureKind
import ch.lkmc.neutrodyne.core.database.SyncClockEntity
import ch.lkmc.neutrodyne.core.database.SyncOutboxEntity
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.SettingWriteOrigin
import ch.lkmc.neutrodyne.core.domain.SettingsSyncPort
import ch.lkmc.neutrodyne.core.domain.SettingsWriteCoordinator
import ch.lkmc.neutrodyne.core.domain.SyncedSettingSnapshot
import ch.lkmc.neutrodyne.core.model.settings.AllSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okio.IOException

/**
 * 10 Settings capture: a local write's admission is decided before DataStore. While linked the
 * recorder remains installed, reading the persisted sharing gate live. ON commits a literal
 * outbox intent and returns an account/link/complete-clock receipt; OFF returns uncaptured
 * provenance without requiring Room. The repository commits that opaque receipt with the value
 * in one DataStore edit. A failed edit changes neither; a lost Room callback cannot lose origin.
 *
 * Recovery and push snapshots take the same per-key [SettingsWriteCoordinator] lock as local
 * set/reset and linked restore. Durable origin is checked before value equality or baseline
 * heuristics: an equal older intent cannot claim an OFF write, and an ON write keeps its clock
 * across an OFF edge or a crash before notification. Each intent journals its predecessor's
 * receipt and value hash in the same Room transaction: an older UNC value cannot cancel a newer
 * intent whose DataStore write failed, and another equal-value OFF write has a new receipt.
 * Direct store edits clear the receipt, so
 * clocked-baseline drift remains evidence for genuinely unrecorded edits. Known account values
 * win over explicit uncaptured writes when sharing is enabled; absent keys upload as at link.
 *
 * A pending remote effect at least as new as an intent drains before recovery can mask it.
 * Replay and first-link bootstrap retain their original-clock rules. [prepareForPush] admits
 * only materialized literals, and acknowledgements merge immutable sent values, never a later
 * store projection. Neither Room transactions nor account metadata cross the DataStore IO.
 */
class SettingsCapture(
    private val db: NeutrodyneDatabase,
    private val settings: SettingsSyncPort,
    private val writeLocks: SettingsWriteCoordinator,
    private val settingsSyncEnabled: suspend () -> Boolean,
) {
    /**
     * The lifecycle entry point (start-up, link, the switch's both edges, unlink, revocation):
     * installs live-gated callbacks while linked, and clears them while unlinked. The gates are the persisted
     * values read at this call — never a cache.
     */
    suspend fun reconcile() {
        if (!linked()) {
            clear()
        } else if (settingsSyncEnabled()) {
            install()
        } else {
            off()
        }
    }

    /** Both callbacks remain installed while linked; admission reads the persisted gate live. */
    fun off() {
        installRecorder()
        installMaterializedListener()
    }

    /** Starts recording and recovers committed intents; called while linked with the switch on. */
    suspend fun install() {
        installRecorder()
        installMaterializedListener()
        recoverCommittedIntents()
        reconcileStoredKeys()
    }

    private fun installRecorder() {
        settings.registerCommittedRecorder { key, json, before ->
            db.withWriteTransaction {
                val state = linkedState() ?: throw IOException("restore link is unavailable")
                val intent =
                    db.syncOutboxDao().get(SyncCollection.SETTING.wire, key.name, "v")
                        ?: throw IOException("restore setting intent is missing")
                if (intent.value != json) throw IOException("restore setting intent changed")
                rememberIntentBoundary(key, intent, before, SettingIntentKind.RESTORE)
                val clock = Hlc(intent.hlc, NodeId(intent.nodeId)).toString()
                SettingWriteOrigin.Captured(
                    SyncJson.encodeToString(
                        CapturedSettingReceipt(clock, state.nodeId, state.accountId, state.serverUrl),
                    ),
                )
            }
        }
        settings.registerRecorder { key, json ->
            // OFF settings remain usable even if Room is unavailable. Their uncaptured origin
            // commits with the value in DataStore, so it survives a failed Room notification.
            if (!settingsSyncEnabled()) {
                SettingWriteOrigin.Uncaptured
            } else {
                val before = settings.currentSnapshot(key) ?: throw IOException("setting admission is unreadable")
                db.withWriteTransaction {
                    val state = linkedState() ?: return@withWriteTransaction SettingWriteOrigin.Uncaptured
                    if (state.applying) return@withWriteTransaction SettingWriteOrigin.Uncaptured
                    db.syncOutboxDao().captureIntent(SyncCollection.SETTING.wire, key.name, "v", json)
                    val intent = checkNotNull(db.syncOutboxDao().get(SyncCollection.SETTING.wire, key.name, "v"))
                    val clock = Hlc(intent.hlc, NodeId(intent.nodeId)).toString()
                    rememberIntentBoundary(key, intent, before)
                    SettingWriteOrigin.Captured(
                        SyncJson.encodeToString(
                            CapturedSettingReceipt(clock, state.nodeId, state.accountId, state.serverUrl),
                        ),
                    )
                }
            }
        }
    }

    private fun installMaterializedListener() {
        settings.registerMaterialized { key, json ->
            if (linked()) recordMaterialized(key, json)
        }
    }

    /** Stops recording and baselining after unlink or revocation. */
    fun clear() {
        settings.registerRecorder(null)
        settings.registerMaterialized(null)
        settings.registerCommittedRecorder(null)
    }

    /** A linked, unrevoked account — the only state in which capture bookkeeping may live. */
    private suspend fun linked(): Boolean = linkedState() != null

    private suspend fun linkedState(): SyncStateEntity? =
        db.syncStateDao().get()?.takeIf {
            it.enabled && it.linkedAt != null && it.lastError != SYNC_ERROR_REVOKED
        }

    /**
     * The origin was committed with this value, before this notification. A gate flip or an
     * equal older intent cannot relabel an uncaptured write or erase a captured write's clock.
     */
    private suspend fun recordMaterialized(
        key: SettingKey<*>,
        json: String,
    ) {
        val snapshot = settings.currentSnapshot(key) ?: return
        if (!matchesLiteral(key, snapshot.json, json) || snapshot.origin == null) return
        db.withWriteTransaction {
            val state = linkedState() ?: return@withWriteTransaction
            val row = db.syncClockDao().get(SyncCollection.SETTING.wire, key.name)
            val raw = row?.clocks?.let(::decode)
            if (row != null && raw == null) return@withWriteTransaction
            val stored = raw ?: RawSyncRecord(RecordState(SyncCollection.SETTING))
            val intent = db.syncOutboxDao().get(SyncCollection.SETTING.wire, key.name, "v")
            val clock = captureClock(snapshot.origin, state, intent, stored.settingIntent)
            val next =
                if (clock == null) {
                    SettingBaseline(settingBaselineHash(json))
                } else {
                    stored.settingHash.advancedTo(json, clock.toString())
                }
            db.syncClockDao().upsert(
                SyncClockEntity(SyncCollection.SETTING.wire, key.name, encode(stored.copy(settingHash = next))),
            )
        }
    }

    private fun captureClock(
        origin: SettingWriteOrigin?,
        state: SyncStateEntity,
        intent: SyncOutboxEntity? = null,
        boundary: SettingIntent? = null,
    ): Hlc? {
        val token = (origin as? SettingWriteOrigin.Captured)?.token ?: return null
        val receipt =
            runCatching { SyncJson.decodeFromString<CapturedSettingReceipt>(token) }.getOrNull()
                ?: return null
        if (receipt.nodeId != state.nodeId || receipt.accountId != state.accountId ||
            receipt.serverUrl != state.serverUrl
        ) {
            return null
        }
        val physical = parseHlc(receipt.clock)?.takeIf { it.nodeId.value == state.nodeId } ?: return null
        if (intent?.captureKind == SyncCaptureKind.LOCAL && intent.nodeId == state.nodeId &&
            boundary?.clock == Hlc(intent.hlc, NodeId(intent.nodeId)).toString() &&
            boundary.receiptClock == physical.toString()
        ) {
            return Hlc(intent.hlc, NodeId(intent.nodeId))
        }
        return physical
    }

    /** Step 2 of [install]: replay or retire each committed `v` intent under its key's lock. */
    private suspend fun recoverCommittedIntents() {
        val rows =
            db.syncOutboxDao().rows().filter {
                it.coll == SyncCollection.SETTING.wire && it.field == "v"
            }
        for (row in rows) {
            val key = AllSettingKeys.list.firstOrNull { it.name == row.rid && it.synced } ?: continue
            writeLocks.withLock(key) { recoverIntent(key) }
        }
    }

    private suspend fun recoverIntent(
        key: SettingKey<*>,
        scope: RecoveryScope = RecoveryScope.LINKED,
    ) {
        // Re-read under the lock: a local write may have coalesced or acked the row meanwhile.
        val row = db.syncOutboxDao().get(SyncCollection.SETTING.wire, key.name, "v") ?: return
        val intentClock = Hlc(row.hlc, NodeId(row.nodeId))
        val raw =
            db
                .syncClockDao()
                .get(SyncCollection.SETTING.wire, key.name)
                ?.clocks
                ?.let(::decode)
        val pendingClock = raw?.pendingSetting?.clock?.let(::parseHlc)
        // A pending remote effect at least as new as the intent wins; the drain applies it next.
        val deferred =
            row.captureKind != SyncCaptureKind.REPLAY &&
                pendingClock != null &&
                pendingClock >= intentClock
        if (deferred) return
        val snapshot = settings.currentSnapshot(key) ?: return
        val current = snapshot.json
        val state = db.syncStateDao().get() ?: return
        val receiptClock = captureClock(snapshot.origin, state, row, raw?.settingIntent)
        val hasOrigin = scope == RecoveryScope.LINKED && snapshot.origin != null
        val uncaptured = hasOrigin && receiptClock == null
        val matchesIntent = row.value?.let { matchesLiteral(key, current, it) } == true
        val boundary = raw?.settingIntent?.takeIf { it.clock == intentClock.toString() }
        val predecessorUnchanged =
            boundary != null && boundary.priorReceipt == snapshot.receipt &&
                boundary.priorHash == settingBaselineHash(current)
        val baseline = raw?.settingHash
        if (row.captureKind != SyncCaptureKind.REPLAY) {
            val baselineClock = baseline?.clock?.let(::parseHlc)
            val capturedHere = hasOrigin && receiptClock == intentClock && matchesIntent
            val offWrite =
                !predecessorUnchanged &&
                    (uncaptured || (!capturedHere && baseline != null && baseline.clock == null))
            val historicalWrite = boundary?.kind == SettingIntentKind.RESTORE && (capturedHere || predecessorUnchanged)
            val superseded = baselineClock != null && baselineClock > intentClock && !historicalWrite
            val externalDrift =
                !predecessorUnchanged && baseline != null &&
                    !matchesIntent && !matchesBaseline(key, current, baseline)
            if (offWrite || superseded || externalDrift) {
                // Off-writes have explicit clockless provenance. A clocked baseline instead
                // remains evidence for hash reconciliation of an unrecorded external edit.
                retire(key, row, current)
                return
            }
        }
        if (matchesIntent) {
            // A captured write may have landed before its post-write bookkeeping did. Equal
            // intent/baseline clocks are normal here; the still-unacknowledged row must survive.
            row.value?.let { advanceBaseline(key, it, intentClock.toString()) }
            return
        }
        // A replay or an intent with no evidence of a newer local write keeps its own clock.
        materialize(key, row, intentClock, boundary?.kind ?: SettingIntentKind.LOCAL, snapshot)
    }

    /** The intent never landed in DataStore: apply it, then baseline it under its own clock. */
    private suspend fun materialize(
        key: SettingKey<*>,
        row: SyncOutboxEntity,
        intentClock: Hlc,
        kind: SettingIntentKind,
        before: SyncedSettingSnapshot,
    ) {
        val literal = row.value ?: return
        val applied =
            if (kind == SettingIntentKind.RESTORE) {
                val origin = settings.recordCommitted(key, literal, before)
                settings.applyRecorded(key, literal, origin)
            } else {
                settings.applyRemote(key, literal)
            }
        if (!applied) return
        advanceBaseline(key, literal, intentClock.toString())
    }

    /** Canonicalizes an immutable sent literal without reading mutable settings storage. */
    internal fun canonicalLiteral(
        name: String,
        json: String,
    ): String? {
        val key = AllSettingKeys.list.firstOrNull { it.name == name && it.synced } ?: return null
        return settings.canonicalJson(key, json)
    }

    /** Caller owns the profile writer; this records no accepted value and performs no store IO. */
    internal suspend fun rejectOversizeSetting(row: SyncOutboxEntity) {
        if (row.coll != SyncCollection.SETTING.wire || row.field != "v") return
        val literal =
            row.value?.let { canonicalLiteral(row.rid, it) }
                ?: throw IOException("rejected setting literal is unreadable")
        val entry = db.syncClockDao().get(row.coll, row.rid)
        val raw = entry?.clocks?.let(::decode)
        if (entry != null && raw == null) throw IOException("setting metadata is unreadable")
        val stored = raw ?: RawSyncRecord(RecordState(SyncCollection.SETTING))
        db.syncClockDao().upsert(
            SyncClockEntity(
                row.coll,
                row.rid,
                encode(
                    stored.copy(
                        rejectedSetting =
                            SettingBaseline(
                                settingBaselineHash(literal),
                                Hlc(row.hlc, NodeId(row.nodeId)).toString(),
                            ),
                    ),
                ),
            ),
        )
    }

    /** Baselines [json] at [clock] when that is newer — merged into the fresh row, one transaction. */
    private suspend fun advanceBaseline(
        key: SettingKey<*>,
        json: String,
        clock: String,
    ) {
        db.withWriteTransaction {
            val row = db.syncClockDao().get(SyncCollection.SETTING.wire, key.name)
            val raw = row?.clocks?.let(::decode)
            if (row != null && raw == null) return@withWriteTransaction
            val stored = raw ?: RawSyncRecord(RecordState(SyncCollection.SETTING))
            // Recovery may have read its intent just before clock repair committed.
            // Resolve the immutable receipt again under this writer, against the fresh row.
            val intent = db.syncOutboxDao().get(SyncCollection.SETTING.wire, key.name, "v")
            val boundary = stored.settingIntent
            val currentClock = intent?.let { Hlc(it.hlc, NodeId(it.nodeId)).toString() }
            val effectiveClock =
                if (intent?.captureKind == SyncCaptureKind.LOCAL && boundary?.clock == currentClock &&
                    boundary?.receiptClock == clock
                ) {
                    checkNotNull(currentClock)
                } else {
                    clock
                }
            val next =
                stored.settingHash.advancedTo(
                    json,
                    effectiveClock,
                    settings.canonicalJson(key, json) ?: return@withWriteTransaction,
                )
            if (next === stored.settingHash) return@withWriteTransaction
            db.syncClockDao().upsert(
                SyncClockEntity(
                    SyncCollection.SETTING.wire,
                    key.name,
                    encode(stored.copy(settingHash = next)),
                ),
            )
        }
    }

    /**
     * Deletes the superseded intent under its own complete clock (so a newer coalesced intent
     * survives) and baselines the value that actually won locally — clocklessly when the winner
     * was never captured.
     */
    private suspend fun retire(
        key: SettingKey<*>,
        row: SyncOutboxEntity,
        current: String?,
    ) {
        db.withWriteTransaction {
            db.syncOutboxDao().deleteIfUnchanged(
                SyncCollection.SETTING.wire,
                key.name,
                "v",
                row.hlc,
                row.nodeId,
            )
            // Nothing stored survives the retirement itself; a wiped value baselines nothing.
            if (current == null) return@withWriteTransaction
            val clockRow = db.syncClockDao().get(SyncCollection.SETTING.wire, key.name)
            val raw = clockRow?.clocks?.let(::decode) ?: return@withWriteTransaction
            // Do not turn a clocked baseline into off-write provenance merely by retiring an
            // unrelated failed intent. The unchanged hash lets reconciliation detect real drift.
            if (raw.settingHash?.clock != null) return@withWriteTransaction
            db.syncClockDao().upsert(
                clockRow.copy(
                    clocks = encode(raw.copy(settingHash = SettingBaseline(settingBaselineHash(current)))),
                ),
            )
        }
    }

    /**
     * Freeze only materialized setting intents for this push. A local write can commit after
     * the round's initial recovery, so re-read and recover each selected key under the same
     * mutex as set/reset. The returned literals stay immutable if a later write coalesces them.
     * Failed reads/writes keep their rows queued rather than acknowledging an unapplied value.
     */
    internal suspend fun prepareForPush(
        rows: List<SyncOutboxEntity>,
        firstLink: Boolean,
    ): List<SyncOutboxEntity> {
        val prepared = mutableListOf<SyncOutboxEntity>()
        for (row in rows) {
            if (row.coll != SyncCollection.SETTING.wire || row.field != "v") {
                prepared += row
                continue
            }
            val key = AllSettingKeys.list.firstOrNull { it.synced && it.name == row.rid } ?: continue
            writeLocks.withLock(key) {
                val state = db.syncStateDao().get() ?: return@withLock
                if (!state.enabled || (!firstLink && state.linkedAt == null) ||
                    state.lastError == SYNC_ERROR_REVOKED || !settingsSyncEnabled()
                ) {
                    return@withLock
                }
                val scope = if (firstLink && state.linkedAt == null) RecoveryScope.BOOTSTRAP else RecoveryScope.LINKED
                if (scope == RecoveryScope.BOOTSTRAP) {
                    val fresh = db.syncOutboxDao().get(row.coll, row.rid, row.field) ?: return@withLock
                    val snapshot = settings.currentSnapshot(key) ?: return@withLock
                    if (fresh.value?.let { matchesLiteral(key, snapshot.json, it) } == true) {
                        db.withWriteTransaction {
                            val raw =
                                db
                                    .syncClockDao()
                                    .get(row.coll, row.rid)
                                    ?.clocks
                                    ?.let(::decode)
                            val clock = Hlc(fresh.hlc, NodeId(fresh.nodeId)).toString()
                            if (raw?.settingIntent?.clock != clock) rememberIntentBoundary(key, fresh, snapshot)
                        }
                    }
                }
                recoverIntent(key, scope)
                val fresh = db.syncOutboxDao().get(row.coll, row.rid, row.field) ?: return@withLock
                val current = settings.currentJson(key) ?: return@withLock
                val literal = fresh.value ?: return@withLock
                if (matchesLiteral(key, current, literal)) prepared += fresh
            }
        }
        return prepared
    }

    /**
     * After recovery, capture genuinely unrecorded changes to known clocked baselines. A
     * pending effect or intent always takes precedence. An absent/clockless baseline cannot
     * prove a newer synced edit: initialize from the accepted value, preserving server-wins
     * when enabling after an off-switch edit. Account-absent keys upload their current value.
     */
    private suspend fun reconcileStoredKeys() {
        for (key in AllSettingKeys.list) {
            if (!key.synced) continue
            writeLocks.withLock(key) {
                if (!linked() || !settingsSyncEnabled()) return@withLock
                if (db.syncOutboxDao().get(SyncCollection.SETTING.wire, key.name, "v") != null) return@withLock
                val raw =
                    db
                        .syncClockDao()
                        .get(SyncCollection.SETTING.wire, key.name)
                        ?.clocks
                        ?.let(::decode)
                if (raw?.pendingSetting != null) return@withLock
                val snapshot = settings.currentSnapshot(key) ?: return@withLock
                val current = snapshot.json
                val state = db.syncStateDao().get() ?: return@withLock
                val rejected = raw?.rejectedSetting
                val rejectedReceipt =
                    raw?.settingIntent?.takeIf { it.clock == rejected?.clock }?.receiptClock ?: rejected?.clock
                if (rejected != null && rejectedReceipt != null &&
                    captureClock(snapshot.origin, state)?.toString() == rejectedReceipt &&
                    matchesBaseline(key, current, rejected)
                ) {
                    // A 413 already retired this exact materialized local receipt. An explicit
                    // new edit has its own outbox row/receipt; off-switch writes have no clock.
                    return@withLock
                }
                val uncaptured = snapshot.origin != null && captureClock(snapshot.origin, state) == null
                val accepted = raw?.state?.fields?.get("v")
                val baseline = raw?.settingHash
                val acceptedClock = accepted?.c
                if (accepted != null && acceptedClock != null) {
                    val literal = accepted.v.toString()
                    if (matchesLiteral(key, current, literal)) {
                        advanceBaseline(key, literal, acceptedClock.toString())
                        return@withLock
                    }
                    if (uncaptured || baseline?.clock == null) {
                        if (settings.applyRemote(key, literal)) {
                            advanceBaseline(key, literal, acceptedClock.toString())
                        }
                        return@withLock
                    }
                    if (matchesBaseline(key, current, baseline)) return@withLock
                }
                db.withWriteTransaction {
                    db.syncOutboxDao().captureIntent(SyncCollection.SETTING.wire, key.name, "v", current)
                    val intent = checkNotNull(db.syncOutboxDao().get(SyncCollection.SETTING.wire, key.name, "v"))
                    rememberIntentBoundary(key, intent, snapshot)
                }
            }
        }
    }

    /** Caller owns the key lock and writer; no DataStore IO occurs inside this transaction. */
    private suspend fun rememberIntentBoundary(
        key: SettingKey<*>,
        intent: SyncOutboxEntity,
        before: SyncedSettingSnapshot,
        kind: SettingIntentKind = SettingIntentKind.LOCAL,
    ) {
        val row = db.syncClockDao().get(SyncCollection.SETTING.wire, key.name)
        val stored = row?.clocks?.let(::decode)
        if (row != null && stored == null) throw IOException("setting metadata is unreadable")
        val raw = stored ?: RawSyncRecord(RecordState(SyncCollection.SETTING))
        val boundary =
            SettingIntent(
                Hlc(intent.hlc, NodeId(intent.nodeId)).toString(),
                before.receipt,
                settingBaselineHash(before.json),
                kind,
            )
        db.syncClockDao().upsert(
            SyncClockEntity(
                SyncCollection.SETTING.wire,
                key.name,
                encode(raw.copy(settingIntent = boundary)),
            ),
        )
    }

    private fun matchesLiteral(
        key: SettingKey<*>,
        current: String,
        literal: String,
    ): Boolean {
        val canonical = settings.canonicalJson(key, literal) ?: return false
        val effective = if (canonical == "null") settings.encode(key, key.default) ?: return false else canonical
        return settingBaselineHash(current) == settingBaselineHash(effective)
    }

    private fun matchesBaseline(
        key: SettingKey<*>,
        current: String,
        baseline: SettingBaseline,
    ): Boolean =
        settingBaselineHash(current) == baseline.hash ||
            (baseline.hash == settingBaselineHash("null") && matchesLiteral(key, current, "null"))

    private enum class RecoveryScope { LINKED, BOOTSTRAP }

    @Serializable
    private data class CapturedSettingReceipt(
        val clock: String,
        val nodeId: String?,
        val accountId: String? = null,
        val serverUrl: String? = null,
    )

    private fun parseHlc(clock: String): Hlc? = runCatching { Hlc.parse(clock) }.getOrNull()

    private fun decode(json: String): RawSyncRecord? =
        runCatching { SyncJson.decodeFromString<RawSyncRecord>(json) }.getOrNull()

    private fun encode(raw: RawSyncRecord): String = SyncJson.encodeToString(raw)
}
