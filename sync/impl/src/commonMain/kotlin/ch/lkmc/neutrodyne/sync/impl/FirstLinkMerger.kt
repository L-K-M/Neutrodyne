// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.sync.api.FirstLinkChoice
import ch.lkmc.neutrodyne.sync.protocol.FieldState
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.ProtocolLimits
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The outcome of a first-link step (10 First-link choices). */
sealed interface FirstLinkResult {
    data class Completed(
        val applied: Int,
    ) : FirstLinkResult

    data class Failed(
        val error: String,
    ) : FirstLinkResult
}

/** The minimum admitted clock (2020-01-01); earlier row timestamps are raised to it. */
const val SYNC_MIN_CLOCK_MS = 1_577_836_800_000L

/**
 * 10 Merge / Use the server's library / Use this device's library everywhere. The server's records
 * are pulled page by page and applied through [SyncApplier]; before the pull, [seedLocalState]
 * stamps this device's rows into `sync_clock` with their own timestamps (counter 0, this node), so
 * the field merge decides by the newer of the two and a local June `playedAt` loses to a server
 * October "unplayed" (MS2 acceptance 3).
 *
 * Implementation note (2026-11-04, MS2): the base branch has no `LibraryMerger` implementation
 * (05's `RestoreMerger` refuses the `LINK_*` policies), so the first-link merge seeds the local
 * rows into `sync_clock` and reuses the engine's field merge instead of 05's `mergeStaged`. The
 * rules are the same timestamp-LWW comparison; the staging-file resume of 10 Merge step 1 is not
 * implemented yet.
 */
class FirstLinkMerger(
    private val db: NeutrodyneDatabase,
    private val client: SyncClient,
    private val applier: SyncApplier,
    private val clock: Clock,
    private val nodeId: NodeId,
) {
    suspend fun merge(choice: FirstLinkChoice): FirstLinkResult {
        val roundId = clock.now()
        return try {
            when (choice) {
                FirstLinkChoice.MERGE -> mergeBoth(roundId)
                FirstLinkChoice.USE_SERVER -> useServer(roundId)
                FirstLinkChoice.USE_DEVICE -> useDeviceEverywhere()
            }
        } catch (failure: Exception) {
            FirstLinkResult.Failed(failure.message ?: "merge failed")
        }
    }

    private suspend fun mergeBoth(roundId: Long): FirstLinkResult {
        seedLocalState()
        val applied = pullAndApply(roundId)
        captureLocalWins()
        completeLink()
        return FirstLinkResult.Completed(applied)
    }

    private suspend fun useServer(roundId: Long): FirstLinkResult {
        val applied = pullAndApply(roundId)
        completeLink()
        return FirstLinkResult.Completed(applied)
    }

    private suspend fun useDeviceEverywhere(): FirstLinkResult {
        // Upload first: capture every local record with a fresh clock, then push.
        db.withWriteTransaction {
            db.syncStateDao().withApplying {
                for (podcast in db.podcastDao().allForExport()) {
                    db.syncOutboxDao().captureAll(SyncCollection.PODCAST.wire, podcast.syncId)
                }
                for (group in db.groupDao().groupsInOrder()) {
                    db.syncOutboxDao().captureAll(SyncCollection.GROUP.wire, group.uuid)
                }
            }
        }
        completeLink()
        return FirstLinkResult.Completed(0)
    }

    private suspend fun pullAndApply(roundId: Long): Int {
        var applied = 0
        var cursor: String? = null
        var pages = 0
        while (pages < MAX_PAGES) {
            pages++
            val page = client.changes(cursor, ProtocolLimits.MAX_RECORDS)
            applied += page.records.size
            applier.applyPage(page.records, roundId)
            cursor = page.cursor
            if (!page.hasMore) break
        }
        db.syncStateDao().get()?.let { db.syncStateDao().upsert(it.copy(cursor = cursor)) }
        return applied
    }

    /**
     * Stamps this device's current rows into `sync_clock` at their own timestamps, so the incoming
     * field merge sees the local values and decides by clock.
     */
    private suspend fun seedLocalState() {
        db.withWriteTransaction {
            db.syncStateDao().withApplying {
                for (podcast in db.podcastDao().allForExport()) {
                    val fields =
                        mapOf(
                            "feedUrl" to fieldString(podcast.feedUrl, podcast.subscribedAt),
                            "subscribed" to fieldBool(true, podcast.subscribedAt),
                            "title" to fieldString(podcast.title, podcast.subscribedAt),
                        )
                    writeSeed(SyncCollection.PODCAST, podcast.syncId, fields)
                }
                for (episode in db.episodeDao().all()) {
                    val podcast = db.podcastDao().byId(episode.podcastId) ?: continue
                    val state = db.episodeStateDao().byEpisode(episode.id)
                    val position = db.positionDao().byEpisode(episode.id)
                    val fields = mutableMapOf<String, FieldState>()
                    if (state != null) {
                        val at = maxOf(state.updatedAt, position?.updatedAt ?: 0L)
                        fields["played"] = fieldBool(state.playedAt != null, at)
                        fields["playedAt"] =
                            FieldState(state.playedAt?.let(::JsonPrimitive) ?: JsonNull, stamp(at))
                        fields["fav"] = fieldBool(state.isFavorite, at)
                        if (state.playCount > 0) fields["playCount"] = fieldLong(state.playCount.toLong(), at)
                        state.lastPlayedAt?.let { fields["lastPlayedAt"] = fieldLong(it, at) }
                    }
                    if (position != null && position.positionMs > 0) {
                        fields["pos"] =
                            FieldState(
                                JsonObject(buildMap { put("ms", JsonPrimitive(position.positionMs)) }),
                                stamp(position.updatedAt),
                            )
                    }
                    if (fields.isNotEmpty()) {
                        writeSeed(SyncCollection.EPISODE, podcast.syncId + episode.identityKey, fields)
                    }
                }
            }
        }
    }

    private suspend fun captureLocalWins() {
        // A local-won field is re-captured with its own timestamp so the server learns it without
        // a fresh local action.
        for (row in db.syncClockDao().all()) {
            val raw = runCatching { SyncJson.decodeFromString<RawSyncRecord>(row.clocks) }.getOrNull() ?: continue
            if (db.syncOutboxDao().rowsFor(row.coll, listOf(row.rid)).isNotEmpty()) continue
            db.withWriteTransaction {
                db.syncStateDao().withApplying {
                    for ((name, field) in raw.state.fields) {
                        val at = field.c?.millis ?: continue
                        db.syncOutboxDao().captureAt(row.coll, row.rid, name, at, field.v.toString())
                    }
                }
            }
        }
    }

    private suspend fun completeLink() {
        db.withWriteTransaction {
            db.syncStateDao().get()?.let {
                db.syncStateDao().upsert(it.copy(enabled = true, linkedAt = clock.now()))
            }
        }
    }

    private suspend fun writeSeed(
        coll: SyncCollection,
        rid: String,
        fields: Map<String, FieldState>,
    ) {
        val raw = RawSyncRecord(RecordState(coll, fields))
        db.syncClockDao().upsert(
            ch.lkmc.neutrodyne.core.database
                .SyncClockEntity(coll.wire, rid, SyncJson.encodeToString(raw)),
        )
    }

    private fun stamp(atMs: Long): Hlc = Hlc.of(atMs.coerceAtLeast(SYNC_MIN_CLOCK_MS), 0, nodeId)

    private fun fieldString(
        value: String,
        atMs: Long,
    ): FieldState = FieldState(JsonPrimitive(value), stamp(atMs))

    private fun fieldLong(
        value: Long,
        atMs: Long,
    ): FieldState = FieldState(JsonPrimitive(value), stamp(atMs))

    private fun fieldBool(
        value: Boolean,
        atMs: Long,
    ): FieldState = FieldState(JsonPrimitive(value), stamp(atMs))

    private companion object {
        const val MAX_PAGES = 10_000
    }
}
