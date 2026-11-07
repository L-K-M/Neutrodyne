// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncHeldEntity
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The guard's thresholds (R7.7, PO-37, PO-47). */
const val MASS_PODCAST_ABSOLUTE = 10
const val MASS_PODCAST_PERCENT = 20
const val MASS_PODCAST_MINIMUM = 3
const val MASS_GROUP_ABSOLUTE = 3

/** The batch state: staged by a round, held by the guard, or deferred while playing. */
enum class HeldState { STAGED, HELD, DEFERRED }

@Serializable
data class HeldRemoval(
    val rid: String,
    val clock: String,
)

@Serializable
data class HeldBatch(
    val podcasts: List<HeldRemoval> = emptyList(),
    val groups: List<HeldRemoval> = emptyList(),
)

/**
 * 10 Mass-change guard: a pull that would unsubscribe more than 10 podcasts, or more than 20 % of
 * the locally subscribed podcasts and at least 3, or delete more than 3 groups is held. The
 * applier stages removals in a provisional `sync_held` row of the round; [evaluate] applies or
 * holds the batch. A removal of the podcast loaded in a playing player is never applied while it
 * plays (R7.5): it is staged as "deferred while playing" and [releaseDeferred] applies it when
 * the player stops or the item changes, re-checking that its clock still wins.
 */
object MassChangeGuard {
    /** Stages one podcast removal in the round's provisional row. */
    suspend fun stagePodcast(
        db: NeutrodyneDatabase,
        roundId: Long,
        podcastRid: String,
        clock: String?,
    ) {
        val removal = HeldRemoval(podcastRid, clock ?: "")
        append(db, roundId) { batch ->
            if (batch.podcasts.any { it.rid == podcastRid }) {
                batch
            } else {
                HeldBatch(batch.podcasts + removal, batch.groups)
            }
        }
    }

    /** Stages one group removal in the round's provisional row. */
    suspend fun stageGroup(
        db: NeutrodyneDatabase,
        roundId: Long,
        groupUuid: String,
        clock: String?,
    ) {
        val removal = HeldRemoval(groupUuid, clock ?: "")
        append(db, roundId) { batch ->
            if (batch.groups.any { it.rid == groupUuid }) {
                batch
            } else {
                HeldBatch(batch.podcasts, batch.groups + removal)
            }
        }
    }

    /**
     * Stages a removal of the playing podcast outside the round's batch (10 rule 4): its own
     * DEFERRED row, reused when a later round re-delivers the same removal.
     */
    suspend fun stageDeferred(
        db: NeutrodyneDatabase,
        roundId: Long,
        podcastRid: String,
        clock: String?,
    ) {
        val removal = HeldRemoval(podcastRid, clock ?: "")
        val existing = db.syncHeldDao().all().firstOrNull { row -> holdsRid(row, podcastRid, HeldState.DEFERRED) }
        if (existing != null) return
        db
            .syncHeldDao()
            .insert(
                SyncHeldEntity(
                    batch = encode(HeldBatch(podcasts = listOf(removal)), HeldState.DEFERRED),
                    summary = "{}",
                    heldAt = roundId,
                ),
            )
    }

    /** The thresholds' verdict for a batch, given the local subscribed-podcast denominator. */
    fun isMassChange(
        podcastCount: Int,
        groupCount: Int,
        localPodcasts: Int,
    ): Boolean {
        val percent = localPodcasts > 0 && podcastCount * 100 > MASS_PODCAST_PERCENT * localPodcasts
        val absolute = podcastCount > MASS_PODCAST_ABSOLUTE
        val smallLibrary = podcastCount >= MASS_PODCAST_MINIMUM && percent
        return absolute || smallLibrary || groupCount > MASS_GROUP_ABSOLUTE
    }

    /**
     * Evaluates the round's provisional batch. Below the thresholds the staged removals are
     * applied (re-checking that their clocks still win) and the row deleted; above, a HELD row
     * is written (the prompt) and the provisional row deleted. Returns the held batch, or `null`
     * when nothing was held. DEFERRED rows never join the count: they wait for playback.
     */
    suspend fun evaluate(
        db: NeutrodyneDatabase,
        roundId: Long,
        localPodcasts: Int,
        now: Long,
    ): HeldBatch? {
        val provisional =
            db.syncHeldDao().forRound(roundId).firstOrNull { stateOf(it.batch) != HeldState.DEFERRED } ?: return null
        val batch = decode(provisional.batch)
        if (batch.podcasts.isEmpty() && batch.groups.isEmpty()) {
            db.syncHeldDao().delete(provisional.id)
            return null
        }
        if (!isMassChange(batch.podcasts.size, batch.groups.size, localPodcasts)) {
            applyBatch(db, stillWinning(db, batch), now)
            db.syncHeldDao().delete(provisional.id)
            return null
        }
        db
            .syncHeldDao()
            .insert(
                SyncHeldEntity(
                    batch = encode(batch, HeldState.HELD),
                    summary = summary(db, batch),
                    heldAt = roundId,
                ),
            )
        db.syncHeldDao().delete(provisional.id)
        return batch
    }

    /**
     * Applies the deferred rows whose podcast is no longer the playing one (10 rule 4):
     * each removal whose clock still wins runs through the shared apply; one that a newer
     * value overturned is dropped. A still-playing podcast's row stays deferred.
     */
    suspend fun releaseDeferred(
        db: NeutrodyneDatabase,
        playingPodcastRid: String?,
        now: Long,
    ) {
        for (row in db.syncHeldDao().all()) {
            if (stateOf(row.batch) != HeldState.DEFERRED) continue
            val batch = decode(row.batch)
            val due =
                HeldBatch(
                    podcasts =
                        batch.podcasts.filter {
                            it.rid != playingPodcastRid && removalStillWins(db, "podcast", "subscribed", it)
                        },
                    groups = emptyList(),
                )
            db.syncHeldDao().delete(row.id)
            if (due.podcasts.isEmpty()) continue
            applyBatch(db, due, now)
        }
    }

    /** A removal applies only while its record clock still wins against `sync_clock`. */
    private suspend fun stillWinning(
        db: NeutrodyneDatabase,
        batch: HeldBatch,
    ): HeldBatch =
        HeldBatch(
            podcasts = batch.podcasts.filter { removalStillWins(db, "podcast", "subscribed", it) },
            groups = batch.groups.filter { removalStillWins(db, "group", "deleted", it) },
        )

    private suspend fun removalStillWins(
        db: NeutrodyneDatabase,
        coll: String,
        field: String,
        removal: HeldRemoval,
    ): Boolean {
        val raw =
            db.syncClockDao().get(coll, removal.rid)?.let {
                runCatching { SyncJson.decodeFromString<RawSyncRecord>(it.clocks) }.getOrNull()
            } ?: return true
        val stored = raw.state.fields[field]?.c ?: return true
        val staged = runCatching { Hlc.parse(removal.clock) }.getOrNull() ?: return true
        return stored <= staged
    }

    /** The removal application the below-threshold branch and deferred releases share. */
    private suspend fun applyBatch(
        db: NeutrodyneDatabase,
        batch: HeldBatch,
        now: Long,
    ) {
        db.withWriteTransaction {
            db.syncStateDao().withApplying { apply(db, batch, now) }
        }
    }

    /** Applies a held batch's removals through the repository paths (the caller's transaction). */
    suspend fun apply(
        db: NeutrodyneDatabase,
        batch: HeldBatch,
        now: Long,
    ) {
        for (removal in batch.podcasts) {
            val podcast = db.podcastDao().bySyncId(removal.rid) ?: continue
            db.podcastDao().deleteCascade(podcast.id, now = now)
        }
        for (removal in batch.groups) {
            val group = db.groupDao().groupByUuid(removal.rid) ?: continue
            db.groupDao().deleteGroup(group.id)
        }
    }

    /** Re-captures the local state of a held batch ("Keep mine") with fresh clocks. */
    suspend fun keepMine(
        db: NeutrodyneDatabase,
        batch: HeldBatch,
        clock: ch.lkmc.neutrodyne.core.common.Clock,
    ) {
        for (removal in batch.podcasts) {
            val podcast = db.podcastDao().bySyncId(removal.rid) ?: continue
            db.syncOutboxDao().captureAll("podcast", podcast.syncId)
        }
        for (removal in batch.groups) {
            db.syncOutboxDao().captureAll("group", removal.rid)
        }
    }

    private suspend fun append(
        db: NeutrodyneDatabase,
        roundId: Long,
        transform: (HeldBatch) -> HeldBatch,
    ) {
        val existing =
            db.syncHeldDao().forRound(roundId).firstOrNull { stateOf(it.batch) != HeldState.DEFERRED }
        val batch = transform(existing?.let { decode(it.batch) } ?: HeldBatch())
        val entity =
            existing?.copy(batch = encode(batch, HeldState.STAGED))
                ?: SyncHeldEntity(batch = encode(batch, HeldState.STAGED), summary = "{}", heldAt = roundId)
        db.syncHeldDao().upsert(entity)
    }

    /** Whether a row in [state] already carries a removal of [rid]. */
    private fun holdsRid(
        row: SyncHeldEntity,
        rid: String,
        state: HeldState,
    ): Boolean = stateOf(row.batch) == state && decode(row.batch).let { it.podcasts.any { r -> r.rid == rid } }

    /**
     * Whether a `sync_held` row is a HELD batch, the only state the prompt reviews (10 step 3);
     * STAGED is provisional and DEFERRED waits for playback. Malformed JSON is not held.
     */
    internal fun isHeld(json: String): Boolean = runCatching { stateOf(json) == HeldState.HELD }.getOrDefault(false)

    /** The batch state a row's JSON carries; legacy rows without the field read as STAGED. */
    private fun stateOf(json: String): HeldState {
        val obj = SyncJson.parseToJsonElement(json).jsonObject
        val name = (obj["state"] as? JsonPrimitive)?.contentOrNull ?: return HeldState.STAGED
        return HeldState.entries.firstOrNull { it.name == name } ?: HeldState.STAGED
    }

    private suspend fun summary(
        db: NeutrodyneDatabase,
        batch: HeldBatch,
    ): String {
        val titles =
            batch.podcasts.mapNotNull { db.podcastDao().bySyncId(it.rid)?.let { p -> p.customTitle ?: p.title } }
        val names = batch.groups.mapNotNull { db.groupDao().groupByUuid(it.rid)?.name }
        return encodeSummary(titles, names)
    }

    private fun encodeSummary(
        titles: List<String>,
        names: List<String>,
    ): String =
        buildJsonObject {
            put("podcasts", JsonArray(titles.map(::JsonPrimitive)))
            put("groups", JsonArray(names.map(::JsonPrimitive)))
        }.toString()

    private fun encode(
        batch: HeldBatch,
        state: HeldState,
    ): String =
        buildJsonObject {
            put("state", JsonPrimitive(state.name))
            put("podcasts", encodeRemovals(batch.podcasts))
            put("groups", encodeRemovals(batch.groups))
        }.toString()

    private fun encodeRemovals(removals: List<HeldRemoval>): JsonArray =
        JsonArray(
            removals.map { removal ->
                buildJsonObject {
                    put("rid", JsonPrimitive(removal.rid))
                    put("clock", JsonPrimitive(removal.clock))
                }
            },
        )

    private fun decode(json: String): HeldBatch {
        val obj = SyncJson.parseToJsonElement(json).jsonObject
        return HeldBatch(
            podcasts = decodeRemovals(obj["podcasts"]),
            groups = decodeRemovals(obj["groups"]),
        )
    }

    private fun decodeRemovals(element: kotlinx.serialization.json.JsonElement?): List<HeldRemoval> =
        element
            ?.jsonArray
            ?.map { removal ->
                HeldRemoval(
                    removal.jsonObject["rid"]!!.jsonPrimitive.content,
                    removal.jsonObject["clock"]!!.jsonPrimitive.contentOrNull ?: "",
                )
            } ?: emptyList()
}
