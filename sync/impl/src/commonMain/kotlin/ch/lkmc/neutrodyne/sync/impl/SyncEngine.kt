// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import ch.lkmc.neutrodyne.core.domain.PlaybackSyncPort
import ch.lkmc.neutrodyne.core.domain.SecretStore
import ch.lkmc.neutrodyne.core.domain.SettingsSyncPort
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import ch.lkmc.neutrodyne.sync.api.SyncReport
import ch.lkmc.neutrodyne.sync.api.SyncRoundReason
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.ProtocolException
import ch.lkmc.neutrodyne.sync.protocol.ProtocolLimits
import ch.lkmc.neutrodyne.sync.protocol.RecordId
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeDto
import ch.lkmc.neutrodyne.sync.protocol.dto.RecordDto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The host of a server URL, for the token origin (`sync:<host>`). */
fun hostOf(url: String): String = url.removePrefix("https://").removePrefix("http://").substringBefore('/')

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
) {
    private val reader = OutboxReader(db, clock)
    private val builder = ChangeBuilder(db, secrets, sharePasswords)

    /**
     * Rounds serialize: an in-process round, a `SyncWorker` round and the controller's "Sync now"
     * may race; interleaved pushes would confuse the outbox ACK arithmetic.
     */
    private val roundMutex = Mutex()

    suspend fun round(reason: SyncRoundReason): SyncReport = roundMutex.withLock { roundLocked(reason) }

    private suspend fun roundLocked(reason: SyncRoundReason): SyncReport {
        val started = clock.now()
        val serverUrl = address.serverUrl()
        val token = serverUrl?.let { tokens.load(hostOf(it)) }
        if (serverUrl == null || token == null) {
            return SyncReport(0, 0, 0, 0, 0, 0, clock.now() - started)
        }
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
            val rows = reader.next()
            val changes = builder.build(rows)
            if (changes.isEmpty() && (rounds > 1 || reason in noPullReasons)) break
            val state = db.syncStateDao().get()
            val response =
                try {
                    client.sync(state?.cursor, changes)
                } catch (failure: SyncTransportException) {
                    // 10 Errors: a rejected token revokes the link on every caller, not just
                    // the controller's "Sync now", so worker and scheduler rounds stop too.
                    if (failure.status == STATUS_REVOKED) markRevoked()
                    throw failure
                }
            pushed += changes.size
            db.withWriteTransaction { handleResults(changes, response.results) }
            applied += response.records.size
            val page = applier.applyPage(response.records, roundId)
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
                val next = client.changes(cursor, ProtocolLimits.MAX_RECORDS)
                applied += next.records.size
                pulled += next.records.size
                applier.applyPage(next.records, roundId)
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
        db.syncStateDao().get()?.let { db.syncStateDao().upsert(it.copy(lastSyncAt = clock.now())) }
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
        }
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
        val state = db.syncStateDao().get() ?: return
        db.syncStateDao().upsert(state.copy(cursor = cursor))
    }

    private suspend fun localPodcastCount(): Int = db.podcastDao().allForExport().size

    private suspend fun markRevoked() {
        val state = db.syncStateDao().get() ?: return
        db.syncStateDao().upsert(state.copy(lastError = SYNC_ERROR_REVOKED))
    }

    private companion object {
        const val STATUS_REVOKED = 401
    }
}
