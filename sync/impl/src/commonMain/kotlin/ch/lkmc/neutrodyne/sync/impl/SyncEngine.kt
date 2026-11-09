// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import ch.lkmc.neutrodyne.core.domain.LocalNetworkGate
import ch.lkmc.neutrodyne.core.domain.PlaybackSyncPort
import ch.lkmc.neutrodyne.core.domain.SecretStore
import ch.lkmc.neutrodyne.core.domain.SettingsSyncPort
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import ch.lkmc.neutrodyne.sync.api.SyncReport
import ch.lkmc.neutrodyne.sync.api.SyncRoundReason
import ch.lkmc.neutrodyne.sync.protocol.FieldState
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.ProtocolException
import ch.lkmc.neutrodyne.sync.protocol.ProtocolLimits
import ch.lkmc.neutrodyne.sync.protocol.RecordId
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeDto
import ch.lkmc.neutrodyne.sync.protocol.dto.RecordDto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The host of a server URL, for the token origin (`sync:<host>`). */
fun hostOf(url: String): String = url.removePrefix("https://").removePrefix("http://").substringBefore('/')

/**
 * The bare host of a normalised server URL, for the local-network gate's classification: userinfo
 * and an explicit `:port` are dropped, brackets around an IPv6 literal removed, and a multi-colon
 * remainder is treated as the literal itself. `null` for an empty or malformed authority, so the
 * link flow fails it explicitly instead of classifying garbage as a public address.
 */
internal fun bareHostOf(url: String): String? {
    val authority =
        url
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .substringAfterLast('@')
    val host =
        if (authority.startsWith("[")) {
            val end = authority.indexOf(']')
            if (end < 0) return null
            val rest = authority.substring(end + 1)
            if (rest.isNotEmpty() &&
                !(rest.startsWith(':') && rest.drop(1).isNotEmpty() && rest.drop(1).all(Char::isDigit))
            ) {
                return null
            }
            authority.substring(1, end)
        } else if (authority.count { it == ':' } == 1) {
            val port = authority.substringAfterLast(':')
            if (port.isEmpty() || port.any { !it.isDigit() }) return null
            authority.substringBeforeLast(':')
        } else {
            authority
        }
    return host.ifEmpty { null }
}

/** The maximum number of push rounds in one `round()` (10 Sync round). */
private const val MAX_ROUNDS = 50

/**
 * 10 Sync round: push the outbox, apply the returned page, drain `hasMore`, then evaluate the
 * mass-change guard. The engine owns no scheduling; `SyncScheduler` (Android) and
 * `DesktopSyncLane` call [round].
 */
class SyncEngine(
    private val db: NeutrodyneDatabase,
    private val client: SyncClient,
    private val applier: SyncApplier,
    private val clock: Clock,
    private val secrets: SecretStore,
    private val sharePasswords: () -> Boolean,
    private val address: ch.lkmc.neutrodyne.core.domain.SyncServerAddress,
    private val tokens: SyncTokenStore,
    private val activity: SyncActivityState,
    private val gate: LocalNetworkGate,
    private val effects: SyncApplyEffects,
    private val capture: SettingsCapture,
) {
    private val reader = OutboxReader(db, clock)
    private val builder = ChangeBuilder(db, secrets, sharePasswords)
    private val clockStore = SyncClockStore(db)

    /**
     * Rounds serialize: an in-process round, a `SyncWorker` round and the controller's "Sync now"
     * may race; interleaved pushes would confuse the outbox ACK arithmetic.
     */
    private val roundMutex = Mutex()

    /** Marks the shared [SyncActivity] flag for the round's whole duration, including failures. */
    suspend fun round(reason: SyncRoundReason): SyncReport {
        activity.begin()
        try {
            return roundMutex.withLock { roundLocked(reason) }
        } finally {
            activity.end()
        }
    }

    private suspend fun roundLocked(reason: SyncRoundReason): SyncReport {
        // The gate owns the SYNC bypass: apply its live verdict before the round's first request,
        // so a permission granted before the process started works after a restart and a
        // revocation blocks the round (10 Local-network gate 3-4). No prompt is shown here.
        gate.onPermissionResult(gate.hasPermission())
        val started = clock.now()
        val serverUrl = address.serverUrl()
        val token = serverUrl?.let { tokens.load(hostOf(it)) }
        if (serverUrl == null || token == null) {
            return SyncReport(0, 0, 0, 0, 0, 0, clock.now() - started)
        }
        // A revoked link stays dormant until a re-link clears the marker: no periodic round and
        // no "Sync now" reaches the server again (10 Errors: 401 → Revoked stops rounds).
        if (db.syncStateDao().get()?.lastError == SYNC_ERROR_REVOKED) {
            return SyncReport(0, 0, 0, 0, 0, 0, clock.now() - started)
        }
        // 10 Durable apply effects: a marker an earlier committed page left behind retries before
        // this round's first request — the drain is local (Room and DataStore), so it recovers
        // while offline too, and the push below can never echo over an unapplied server value.
        capture.reconcile()
        effects.drainSettings()
        var pushed = 0
        var applied = 0
        var stale = 0
        var rejected = 0
        var pulled = 0
        var held = 0
        val roundId = clock.now()

        // Reasons that never pull on their own: a round with nothing to push is a no-op.
        val noPullReasons = setOf(SyncRoundReason.OUTBOX, SyncRoundReason.PLAYBACK)
        var rounds = 0
        while (rounds < MAX_ROUNDS) {
            rounds++
            val rows = capture.prepareForPush(reader.next(), reason == SyncRoundReason.FIRST_LINK)
            val changes = builder.build(rows)
            if (changes.isEmpty() && (rounds > 1 || reason in noPullReasons)) break
            val state = db.syncStateDao().get()
            val response = markingRevocation { client.sync(state?.cursor, changes) }
            pushed += changes.size
            db.withWriteTransaction { handleResults(changes, response.results) }
            applied += response.records.size
            val page = applier.applyPage(response.records, roundId)
            // 10 Pull and apply step 5: the committed page's settings effects drain right after
            // the commit, under the same per-key lock a local set takes.
            effects.drainSettings()
            stale += page.stale
            held += page.held
            pulled += response.records.size
            raiseHlc(response.records)
            storeCursor(response.cursor)
            var cursor = response.cursor
            var hasMore = response.hasMore
            var guard = 0
            while (hasMore && guard < MAX_ROUNDS) {
                guard++
                val next = markingRevocation { client.changes(cursor, ProtocolLimits.MAX_RECORDS) }
                applied += next.records.size
                pulled += next.records.size
                applier.applyPage(next.records, roundId)
                // Each later page's effects drain with its own commit, not only at the round's end.
                effects.drainSettings()
                raiseHlc(next.records)
                storeCursor(next.cursor)
                cursor = next.cursor
                hasMore = next.hasMore
            }
            if (db.syncOutboxDao().count() == 0) break
        }

        val batch = MassChangeGuard.evaluate(db, roundId, localPodcastCount())
        if (batch != null) {
            held += batch.podcasts.size + batch.groups.size
        } else {
            // Below the thresholds the guard deleted its provisional row; the removals were staged
            // in `sync_held` only when held, so nothing else to apply here.
        }
        db.syncStateDao().recordSyncAt(clock.now())
        return SyncReport(pushed, applied, stale, rejected, pulled, held, clock.now() - started)
    }

    private suspend fun handleResults(
        changes: List<ChangeDto>,
        results: List<ch.lkmc.neutrodyne.sync.protocol.dto.ChangeResultDto>,
    ) {
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
            // A `*` row is acknowledged by every field it expanded to.
            change.fields.values
                .mapNotNull { it.c }
                .mapNotNull { runCatching { Hlc.parse(it) }.getOrNull() }
                .minOrNull()
                ?.let { db.syncOutboxDao().deleteAcked(coll, rid, "*", it.packed, it.nodeId.value) }
            if (coll == SyncCollection.SETTING.wire) mergeAckedSetting(rid, change)
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
     */
    private suspend fun mergeAckedSetting(
        rid: String,
        change: ChangeDto,
    ) {
        val sent = change.fields["v"] ?: return
        val value = sent.v ?: return
        val clock = sent.c?.let { runCatching { Hlc.parse(it) }.getOrNull() } ?: return
        val stored = clockStore.get(SyncCollection.SETTING.wire, rid)
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
        clockStore.put(
            SyncCollection.SETTING.wire,
            rid,
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
                            capture.canonicalLiteral(rid, value.toString()) ?: return,
                        ),
                pendingSetting = materializeWinner,
                pendingAuth = stored?.pendingAuth,
                settingIntent = stored?.settingIntent,
            ),
        )
    }

    private suspend fun raiseHlc(records: List<RecordDto>) {
        val max =
            records.maxOfOrNull { record ->
                record.fields.values
                    .mapNotNull { it.c }
                    .maxOfOrNull { runCatching { Hlc.parse(it) }.getOrNull()?.packed ?: 0L } ?: 0L
            } ?: 0L
        if (max > 0) db.syncStateDao().raiseHlc(max)
    }

    private suspend fun storeCursor(cursor: String) {
        db.syncStateDao().storeCursor(cursor)
    }

    private suspend fun localPodcastCount(): Int = db.podcastDao().allForExport().size

    /**
     * 10 Errors: a rejected token revokes the link on every caller — worker and lane rounds too,
     * not just the controller's "Sync now" — so the marker is written next to the outbox.
     */
    private suspend fun <T> markingRevocation(block: suspend () -> T): T =
        try {
            block()
        } catch (failure: SyncTransportException) {
            if (failure.status == STATUS_REVOKED) markRevoked()
            throw failure
        }

    private suspend fun markRevoked() {
        db.syncStateDao().markRevoked(SYNC_ERROR_REVOKED)
    }

    private companion object {
        const val STATUS_REVOKED = 401
    }
}
