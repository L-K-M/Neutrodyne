// SPDX-License-Identifier: Unlicense
@file:Suppress("ktlint:standard:max-line-length")

package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.EpisodeProgress
import ch.lkmc.neutrodyne.sync.protocol.EpisodeStateRules
import ch.lkmc.neutrodyne.sync.protocol.RecordId
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection

internal class GcJob(
    private val store: SqliteSyncStore,
    private val config: ServerConfig,
    private val inputs: ServerInputs,
    private val metrics: ServerMetrics,
) {
    suspend fun run(): Long {
        val now = inputs.wallMs()
        var count = 0L
        // Cursor selection, record deletion and min_cursor advancement share the writer transaction.
        store.maintenance {
            transaction(this) {
                query("SELECT id,seq FROM account") { it.getLong(1) to it.getLong(2) }.forEach { (id, seq) ->
                    val active =
                        query(
                            "SELECT last_cursor FROM device WHERE account_id=? AND revoked_at IS NULL AND last_seen_at>=?",
                            id,
                            now - ACTIVE_DAYS * DAY_MS,
                        ) {
                            it.getLong(1).let { cursor -> if (it.wasNull()) 0L else cursor }
                        }
                    val bound = active.minOrNull() ?: seq
                    val rows = query("SELECT * FROM record WHERE account_id=? ORDER BY seq", id) { it.storedRecord() }
                    val byKey = rows.associateBy { it.key }
                    val candidates = mutableSetOf<RecordKey>()

                    fun expiredDeath(
                        row: StoredRecord?,
                        days: Int,
                    ): Boolean = row?.deadAtMs?.let { it <= now - days * DAY_MS } == true
                    rows.forEach { row ->
                        if (row.seq > bound) return@forEach
                        if (row.key.collection != SyncCollection.EPISODE &&
                            expiredDeath(row, config.retentionTombstoneDays)
                        ) {
                            candidates += row.key
                        }
                        if (row.key.collection == SyncCollection.EPISODE) {
                            val parent =
                                byKey[
                                    RecordKey(
                                        SyncCollection.PODCAST,
                                        RecordId.Podcast((row.key.id as RecordId.Episode).podcast),
                                    ),
                                ]
                            val effective = EpisodeStateRules.derive(row.raw.fields)
                            val queue = byKey[RecordKey(SyncCollection.UPNEXT, row.key.id)]
                            val empty =
                                effective.state == EpisodeProgress.UNPLAYED && !effective.favourite &&
                                    queue?.live() != true
                            if (expiredDeath(parent, config.retentionUnsubscribedDays) ||
                                expiredDeath(row, config.retentionTombstoneDays) ||
                                (empty && row.updatedAtMs <= now - config.retentionEmptyEpisodeDays * DAY_MS)
                            ) {
                                candidates +=
                                    row.key
                            }
                        }
                    }
                    // A newer child blocks its parent's purge; otherwise it could be orphaned without a cursor signal.
                    rows
                        .filter {
                            it.key in candidates &&
                                it.key.collection in setOf(SyncCollection.PODCAST, SyncCollection.GROUP)
                        }.forEach { parent ->
                            val children =
                                rows.filter { row ->
                                    val member = row.key.id as? RecordId.Member
                                    member != null &&
                                        (
                                            member.group.value == parent.key.id.rid ||
                                                member.podcast.value == parent.key.id.rid
                                        )
                                }
                            if (children.any { it.seq > bound }) {
                                candidates -= parent.key
                            } else {
                                candidates +=
                                    children.map { it.key }
                            }
                        }
                    val purged = rows.filter { it.key in candidates }
                    purged.forEach { row ->
                        update(
                            "DELETE FROM record WHERE account_id=? AND coll=? AND rid=?",
                            id,
                            row.key.collection.wire,
                            row.key.id.rid,
                        )
                    }
                    purged.maxOfOrNull { it.seq }?.let {
                        update(
                            "UPDATE account SET min_cursor=MAX(min_cursor,?) WHERE id=?",
                            it,
                            id,
                        )
                    }
                    count += purged.size
                }
                update("DELETE FROM link_request WHERE expires_at<=?", now - HOUR_MS)
                update(
                    "UPDATE link_request SET approver_token_hash=NULL WHERE approver_token_hash IN (SELECT hash FROM token WHERE expires_at<=? OR (kind='device' AND revoked_at<=?))",
                    now,
                    now - 30 * DAY_MS,
                )
                update(
                    "DELETE FROM token WHERE (kind IN ('invite','setup','web_session') AND expires_at<=?) OR (kind='device' AND revoked_at<=?)",
                    now,
                    now - 30 * DAY_MS,
                )
                update("DELETE FROM audit WHERE at<=?", now - 365 * DAY_MS)
            }
            sql("PRAGMA optimize")
            val free = query("PRAGMA freelist_count") { it.getLong(1) }.single()
            val total = query("PRAGMA page_count") { it.getLong(1) }.single()
            if (free * 4 > total) sql("PRAGMA incremental_vacuum")
        }
        metrics.gcPurged(count)
        return count
    }

    private companion object {
        const val ACTIVE_DAYS = 180
        const val HOUR_MS = 3_600_000L
    }
}
