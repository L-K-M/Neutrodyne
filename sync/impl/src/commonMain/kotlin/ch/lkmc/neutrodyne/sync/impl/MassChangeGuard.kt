// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncHeldEntity
import ch.lkmc.neutrodyne.core.domain.PlaybackSyncActive
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
    /** The guard's verdict for one provisional batch. */
    sealed interface Verdict {
        /**
         * Below the thresholds: the caller applies [batch]'s still-winning removals and commits
         * row [rowId]'s delete in the same transaction — the provisional row stays until the
         * outcome commits, so a rolled-back or dead round re-evaluates the same batch.
         */
        data class Approved(
            val rowId: Long,
            val batch: HeldBatch,
            /**
             * `true` when the row was already DEFERRED: its removals hold while the item
             * stays *loaded* (10 step 4's stop-or-item-change boundary), not merely while it
             * plays — a pause-scheduled round must not release them. `false` for a fresh
             * batch, which defers only while `isPlaying` reports.
             */
            val alreadyDeferred: Boolean = false,
        ) : Verdict

        /** Above the thresholds: the provisional row became the held prompt row of [batch]. */
        data class Held(
            val batch: HeldBatch,
        ) : Verdict
    }

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
     * Evaluates every provisional batch: this round's plus STAGED or DEFERRED leftovers of
     * dead or rolled-back rounds (10 Mass-change guard 2). Below the thresholds the verdict is
     * [Verdict.Approved] and the row stays until the caller commits the apply with it; above,
     * the HELD row (the prompt) replaces the provisional row inside the caller's transaction.
     * DEFERRED rows were already approved and skip the threshold check.
     */
    suspend fun evaluate(
        db: NeutrodyneDatabase,
        roundId: Long,
        localPodcasts: Int,
    ): List<Verdict> {
        val verdicts = mutableListOf<Verdict>()
        for (row in db.syncHeldDao().all()) {
            val state = stateOf(row.batch) ?: continue
            if (state == HeldState.HELD) continue
            val batch = runCatching { decode(row.batch) }.getOrNull() ?: continue
            if (batch.podcasts.isEmpty() && batch.groups.isEmpty()) {
                db.syncHeldDao().delete(row.id)
                continue
            }
            if (state != HeldState.DEFERRED &&
                isMassChange(batch.podcasts.size, batch.groups.size, localPodcasts)
            ) {
                db
                    .syncHeldDao()
                    .insert(
                        SyncHeldEntity(
                            batch = encode(batch, HeldState.HELD),
                            summary = summary(db, batch),
                            heldAt = roundId,
                        ),
                    )
                db.syncHeldDao().delete(row.id)
                verdicts += Verdict.Held(batch)
            } else {
                verdicts += Verdict.Approved(row.id, batch, alreadyDeferred = state == HeldState.DEFERRED)
            }
        }
        return verdicts
    }

    /**
     * Applies a batch's removals through the repository paths (the caller's transaction) whose
     * staged clock still wins locally (10 Mass-change guard 2-3): a newer remote revival or a
     * newer local capture cancels the staged removal. Podcast removals [defer] holds back (the
     * loaded, playing item's, 10 step 4) are returned, not applied.
     */
    suspend fun apply(
        db: NeutrodyneDatabase,
        batch: HeldBatch,
        now: Long,
        defer: suspend (podcastSyncId: String) -> Boolean = { false },
    ): List<HeldRemoval> {
        val clockStore = SyncClockStore(db)
        val deferred = mutableListOf<HeldRemoval>()
        for (removal in batch.podcasts) {
            if (defer(removal.rid)) {
                deferred += removal
                continue
            }
            if (!removalWins(db, clockStore, SyncCollection.PODCAST, removal.rid, "subscribed", false, removal.clock)) {
                continue
            }
            val podcast = db.podcastDao().bySyncId(removal.rid) ?: continue
            db.podcastDao().deleteCascade(podcast.id, now = now)
        }
        for (removal in batch.groups) {
            if (!removalWins(db, clockStore, SyncCollection.GROUP, removal.rid, "deleted", true, removal.clock)) {
                continue
            }
            val group = db.groupDao().groupByUuid(removal.rid) ?: continue
            // 05 Delete and undo step 2: the repository's delete pair — a GROUP play context
            // clears inside the same transaction, and the current item keeps playing.
            db.groupDao().clearGroupContext(group.id, now)
            db.groupDao().deleteGroup(group.id)
        }
        return deferred
    }

    /**
     * Keeps the unapplied remainder of an approved batch deferred while the local player runs
     * (10 Mass-change guard 4): the row takes the DEFERRED state and a later round's evaluation
     * applies it once the podcast is no longer playing.
     */
    suspend fun defer(
        db: NeutrodyneDatabase,
        rowId: Long,
        removals: List<HeldRemoval>,
    ) {
        val row = db.syncHeldDao().byId(rowId) ?: return
        val batch = HeldBatch(podcasts = removals)
        db
            .syncHeldDao()
            .upsert(
                row.copy(
                    batch = encode(batch, HeldState.DEFERRED),
                    summary = summary(db, batch),
                ),
            )
    }

    /**
     * 10 Mass-change guard step 4's playing predicate: the removal of the podcast that owns
     * the loaded episode never applies while the player reports `isPlaying`. Fresh approvals
     * and `resolveHeld` use this — a removal that arrives while the player is merely paused
     * still applies.
     */
    suspend fun playingRemovalDeferred(
        db: NeutrodyneDatabase,
        active: PlaybackSyncActive,
        podcastSyncId: String,
    ): Boolean = active.isPlaying && loadedRemovalDeferred(db, active, podcastSyncId)

    /**
     * 10 step 4's loaded predicate: the removal of the podcast that owns the loaded episode
     * holds while the item stays loaded — `isPlaying` does not matter. An already-DEFERRED
     * removal waits for a stop or an item change, so a pause-scheduled round or an app
     * restart's first snapshot must not release it.
     */
    suspend fun loadedRemovalDeferred(
        db: NeutrodyneDatabase,
        active: PlaybackSyncActive,
        podcastSyncId: String,
    ): Boolean {
        val episodeId = active.episodeId ?: return false
        val episode = db.episodeDao().byId(episodeId) ?: return false
        return db.podcastDao().byId(episode.podcastId)?.syncId == podcastSyncId
    }

    /**
     * Settles every DEFERRED row's staged removals in the caller's transaction (10
     * Mass-change guard step 4): each still-winning removal applies, the loaded item's
     * removal holds (the caller's predicate decides playing vs loaded), and a remainder
     * stays DEFERRED for the next boundary. HELD and STAGED rows are never touched.
     */
    suspend fun settleDeferred(
        db: NeutrodyneDatabase,
        now: Long,
        defer: suspend (podcastSyncId: String) -> Boolean,
    ) {
        for (row in db.syncHeldDao().all()) {
            if (stateOf(row.batch) != HeldState.DEFERRED) continue
            val batch = runCatching { decode(row.batch) }.getOrNull() ?: continue
            val remainder = apply(db, batch, now, defer)
            if (remainder.isEmpty()) {
                db.syncHeldDao().delete(row.id)
            } else {
                defer(db, row.id, remainder)
            }
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

    /**
     * Whether a staged removal's clock still wins locally: the tombstone field's current
     * winner — the stored `sync_clock` state, the staged removal itself or a newer pending
     * local capture — must still carry the removed value. A `*` capture asserts the live
     * value at its clock; a newer one overturns the removal, an older one already lost.
     */
    private suspend fun removalWins(
        db: NeutrodyneDatabase,
        clockStore: SyncClockStore,
        coll: SyncCollection,
        rid: String,
        field: String,
        removed: Boolean,
        stagedClock: String,
    ): Boolean {
        val stored =
            clockStore
                .get(coll.wire, rid)
                ?.state
                ?.fields
                ?.get(field)
        var winner = stored?.let { (it.v as? JsonPrimitive)?.booleanOrNull }
        var winnerClock = stored?.c
        val staged = runCatching { Hlc.parse(stagedClock) }.getOrNull()
        if (staged != null && (winnerClock == null || staged > winnerClock)) {
            winner = removed
            winnerClock = staged
        }
        for (row in db.syncOutboxDao().rowsFor(coll.wire, listOf(rid))) {
            val clock = outboxClock(row) ?: continue
            val value =
                when (row.field) {
                    "*" -> {
                        !removed
                    }

                    field -> {
                        (
                            row.value?.let { runCatching { SyncJson.parseToJsonElement(it) }.getOrNull() }
                                as? JsonPrimitive
                        )?.booleanOrNull ?: continue
                    }

                    else -> {
                        continue
                    }
                }
            if (winnerClock == null || clock > winnerClock) {
                winner = value
                winnerClock = clock
            }
        }
        // The staged removal is itself a candidate, so an unproven winner keeps the row.
        return winner == removed
    }

    /** The row's encoded [HeldState], or `null` for a malformed batch payload. */
    fun stateOf(json: String): HeldState? =
        runCatching {
            HeldState.valueOf(
                (SyncJson.parseToJsonElement(json).jsonObject["state"] as? JsonPrimitive)?.content
                    ?: return null,
            )
        }.getOrNull()

    private suspend fun append(
        db: NeutrodyneDatabase,
        roundId: Long,
        transform: (HeldBatch) -> HeldBatch,
    ) {
        // A same-millisecond round can share `heldAt` with a DEFERRED or HELD row; only a
        // STAGED row is this round's provisional batch.
        val existing = db.syncHeldDao().forRound(roundId).firstOrNull { stateOf(it.batch) == HeldState.STAGED }
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
