// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncHeldEntity
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
 * holds the batch.
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
     * Evaluates the round's provisional batch. Below the thresholds the batch is applied by the
     * caller and the row deleted; above, a HELD row is written (the prompt) and the provisional
     * row deleted. Returns the held batch, or `null` when nothing was held.
     */
    suspend fun evaluate(
        db: NeutrodyneDatabase,
        roundId: Long,
        localPodcasts: Int,
    ): HeldBatch? {
        val provisional = db.syncHeldDao().forRound(roundId).firstOrNull() ?: return null
        val batch = decode(provisional.batch)
        if (batch.podcasts.isEmpty() && batch.groups.isEmpty()) {
            db.syncHeldDao().delete(provisional.id)
            return null
        }
        return if (isMassChange(batch.podcasts.size, batch.groups.size, localPodcasts)) {
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
            batch
        } else {
            db.syncHeldDao().delete(provisional.id)
            null
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

    /**
     * The batch of a HELD `sync_held` row, or `null` for STAGED/DEFERRED rows and malformed
     * payloads: only HELD rows are user prompts, so the prompt observer and `resolveHeld` gate
     * on the row's state before anything reads or applies its removals.
     */
    fun heldBatch(json: String): HeldBatch? =
        runCatching {
            val obj = SyncJson.parseToJsonElement(json).jsonObject
            if ((obj["state"] as? JsonPrimitive)?.contentOrNull != HeldState.HELD.name) {
                return null
            }
            HeldBatch(decodeRemovals(obj["podcasts"]), decodeRemovals(obj["groups"]))
        }.getOrNull()

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
        val existing = db.syncHeldDao().forRound(roundId).firstOrNull()
        val batch = transform(existing?.let { decode(it.batch) } ?: HeldBatch())
        val entity =
            existing?.copy(batch = encode(batch, HeldState.STAGED))
                ?: SyncHeldEntity(batch = encode(batch, HeldState.STAGED), summary = "{}", heldAt = roundId)
        db.syncHeldDao().upsert(entity)
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
