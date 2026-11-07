// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.sync.api.FirstLinkChoice
import ch.lkmc.neutrodyne.sync.api.LinkPhase
import ch.lkmc.neutrodyne.sync.api.SyncRoundReason
import ch.lkmc.neutrodyne.sync.protocol.FieldState
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.ProtocolLimits
import ch.lkmc.neutrodyne.sync.protocol.RecordId
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import ch.lkmc.neutrodyne.sync.protocol.dto.MatchHints
import ch.lkmc.neutrodyne.sync.protocol.dto.RecordDto
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

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

/** `Hlc`'s counter bits; a raw wall-clock tick is `millis << 16`. */
private const val HLC_COUNTER_BITS = 16

/** The pull's result: how much was applied and where the server snapshot ends. */
private class PullResult(
    val applied: Int,
    val cursor: String?,
    val maxClockPacked: Long,
    val podcastIds: Set<String>,
    val groupUuids: Set<String>,
    val upNextRefs: List<UpNextRef>,
)

/** One live server Up-next record and its match hints, for the replace step's keep set. */
private class UpNextRef(
    val id: RecordId.Episode,
    val hints: MatchHints?,
)

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
 * implemented yet and a crash mid-merge restarts the pull. "Use the server's library" replaces
 * local-only podcasts and groups, Up next and the session through the shared removal paths rather
 * than 05's Replace; "Use this device's library everywhere" uploads, pushes and prunes with the
 * sequence-bounded reset as designed.
 */
class FirstLinkMerger(
    private val db: NeutrodyneDatabase,
    private val client: SyncClient,
    private val applier: SyncApplier,
    private val engine: SyncEngine,
    private val clock: Clock,
) {
    suspend fun merge(
        choice: FirstLinkChoice,
        onProgress: (LinkPhase, Int, Int) -> Unit = { _, _, _ -> },
    ): FirstLinkResult {
        val roundId = clock.now()
        return try {
            when (choice) {
                FirstLinkChoice.MERGE -> mergeBoth(roundId, onProgress)
                FirstLinkChoice.USE_SERVER -> useServer(roundId, onProgress)
                FirstLinkChoice.USE_DEVICE -> useDeviceEverywhere(roundId, onProgress)
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            FirstLinkResult.Failed(failure.message ?: "merge failed")
        }
    }

    /**
     * Full resync (10 Cursor semantics and resync): pull every page from the beginning, apply it
     * against `sync_clock` and the outbox, then re-assert every known field with its original
     * literal value and complete clock (counter and origin node included). A newer pending local
     * intent stays untouched.
     */
    suspend fun resync(): FirstLinkResult {
        val roundId = clock.now()
        return try {
            val applied = pullAndApply(roundId, { _, _, _ -> })
            reassert()
            FirstLinkResult.Completed(applied)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            FirstLinkResult.Failed(failure.message ?: "resync failed")
        }
    }

    private suspend fun reassert() {
        for (row in db.syncClockDao().all()) {
            val raw = runCatching { SyncJson.decodeFromString<RawSyncRecord>(row.clocks) }.getOrNull() ?: continue
            if (db.syncOutboxDao().rowsFor(row.coll, listOf(row.rid)).isNotEmpty()) continue
            db.withWriteTransaction {
                db.syncStateDao().withApplying {
                    for ((name, field) in raw.state.fields) {
                        val clock = field.c ?: continue
                        db
                            .syncOutboxDao()
                            .captureStored(
                                row.coll,
                                row.rid,
                                name,
                                clock.packed,
                                clock.nodeId.value,
                                field.v.toString(),
                            )
                    }
                    raw.state.growSets.forEach { (name, values) ->
                        val clock = raw.state.maxClock ?: return@forEach
                        db
                            .syncOutboxDao()
                            .captureStored(
                                row.coll,
                                row.rid,
                                name,
                                clock.packed,
                                clock.nodeId.value,
                                values.sorted().joinToString(prefix = "[", postfix = "]") { "\"$it\"" },
                            )
                    }
                }
            }
        }
    }

    private suspend fun mergeBoth(
        roundId: Long,
        onProgress: (LinkPhase, Int, Int) -> Unit,
    ): FirstLinkResult {
        seedLocalState()
        onProgress(LinkPhase.DOWNLOADING, 0, 1)
        val pull = pull(roundId, apply = true, onProgress)
        // Step 4: adopt the pulled cursor and clocks before any bootstrap write, so capture is
        // already admitted while the local winners are re-stamped with their own times.
        adoptPulledState(pull.cursor, pull.maxClockPacked)
        captureLocalWins()
        pushOutbox(onProgress)
        completeLink()
        return FirstLinkResult.Completed(pull.applied)
    }

    private suspend fun useServer(
        roundId: Long,
        onProgress: (LinkPhase, Int, Int) -> Unit,
    ): FirstLinkResult {
        val pull = pull(roundId, apply = true, onProgress)
        replaceWithServerLibrary(pull)
        adoptPulledState(pull.cursor, pull.maxClockPacked)
        completeLink()
        return FirstLinkResult.Completed(pull.applied)
    }

    private suspend fun useDeviceEverywhere(
        roundId: Long,
        onProgress: (LinkPhase, Int, Int) -> Unit,
    ): FirstLinkResult {
        // Upload first: stage the server snapshot (never applied), adopt its cursor and clocks,
        // then capture this device's whole library.
        val pull = pull(roundId, apply = false, onProgress)
        adoptPulledState(pull.cursor, pull.maxClockPacked)
        captureLocalLibrary()
        pushOutbox(onProgress)
        // Prune last: the server drops everything this upload did not re-assert, so no device can
        // observe a half-replaced library.
        client.reset(DELETE_CONFIRMATION, RESET_MODE_LIBRARY, pull.cursor)
        completeLink()
        return FirstLinkResult.Completed(0)
    }

    /** Applies every page and reports the live ids the account still has. */
    private suspend fun pull(
        roundId: Long,
        apply: Boolean,
        onProgress: (LinkPhase, Int, Int) -> Unit,
    ): PullResult {
        var applied = 0
        var cursor: String? = null
        var maxClock = 0L
        val podcasts = mutableSetOf<String>()
        val groups = mutableSetOf<String>()
        val upNext = mutableListOf<UpNextRef>()
        var pages = 0
        while (pages < MAX_PAGES) {
            pages++
            val page = client.changes(cursor, ProtocolLimits.MAX_RECORDS)
            for (dto in page.records) {
                maxClock = maxOf(maxClock, maxClockOf(dto))
                val coll = runCatching { SyncCollection.fromWire(dto.coll) }.getOrNull() ?: continue
                when (coll) {
                    SyncCollection.PODCAST -> {
                        if ((dto.fields["subscribed"]?.v as? JsonPrimitive)?.booleanOrNull != false) {
                            (dto.id as? JsonPrimitive)?.content?.let(podcasts::add)
                        }
                    }

                    SyncCollection.GROUP -> {
                        if ((dto.fields["deleted"]?.v as? JsonPrimitive)?.booleanOrNull != true) {
                            (dto.id as? JsonPrimitive)?.content?.let(groups::add)
                        }
                    }

                    SyncCollection.UPNEXT -> {
                        if ((dto.fields["in"]?.v as? JsonPrimitive)?.booleanOrNull != false) {
                            (runCatching { RecordId.fromWire(coll, dto.id) }.getOrNull() as? RecordId.Episode)
                                ?.let { upNext += UpNextRef(it, dto.match) }
                        }
                    }

                    else -> {
                        continue
                    }
                }
            }
            if (apply) {
                applied += page.records.size
                applier.applyPage(page.records, roundId)
            }
            cursor = page.cursor
            onProgress(LinkPhase.DOWNLOADING, applied, applied.coerceAtLeast(1))
            if (!page.hasMore) break
        }
        return PullResult(applied, cursor, maxClock, podcasts, groups, upNext)
    }

    /** Unsubscribes what the server lacks and replaces Up next and the session (10 Replace). */
    private suspend fun replaceWithServerLibrary(pull: PullResult) {
        val now = clock.now()
        db.withWriteTransaction {
            db.syncStateDao().withApplying {
                val removedPodcasts =
                    db
                        .podcastDao()
                        .allForExport()
                        .filter { it.syncId !in pull.podcastIds }
                        .map { it.syncId }
                val removedGroups =
                    db
                        .groupDao()
                        .groupsInOrder()
                        .filter { it.uuid !in pull.groupUuids }
                        .map { it.uuid }
                MassChangeGuard.apply(
                    db,
                    HeldBatch(
                        podcasts = removedPodcasts.map { HeldRemoval(it, "") },
                        groups = removedGroups.map { HeldRemoval(it, "") },
                    ),
                    now,
                )
                // Up next is replaced by the server's set: the pull applied its rows, so only
                // local-only entries are pruned. A whole-table clear would erase what the
                // adopted cursor then never re-pulls.
                val keepRids = pull.upNextRefs.mapTo(HashSet()) { it.id.rid }
                val keepEpisodes = mutableSetOf<Long>()
                val matcher = EpisodeMatcher(db.backupDao())
                for (ref in pull.upNextRefs) {
                    val podcast = db.podcastDao().bySyncId(ref.id.podcast.value) ?: continue
                    matcher.match(podcast.id, ref.id.key.value, ref.hints)?.let(keepEpisodes::add)
                }
                val staleEpisodes =
                    db.queueDao().entries().mapNotNull { entry ->
                        if (entry.episodeId in keepEpisodes) return@mapNotNull null
                        val episode = db.episodeDao().byId(entry.episodeId) ?: return@mapNotNull entry.episodeId
                        val podcast = db.podcastDao().byId(episode.podcastId) ?: return@mapNotNull entry.episodeId
                        if (podcast.syncId + episode.identityKey in keepRids) null else entry.episodeId
                    }
                db.queueDao().removeEpisodes(staleEpisodes)
                db.playSessionDao().clearContext(now)
            }
        }
    }

    /** Captures every local record of the library with a fresh clock (10 step 3). */
    private suspend fun captureLocalLibrary() {
        db.withWriteTransaction {
            db.syncStateDao().withApplying {
                for (podcast in db.podcastDao().allForExport()) {
                    db.syncOutboxDao().captureAll(SyncCollection.PODCAST.wire, podcast.syncId)
                }
                for (group in db.groupDao().groupsInOrder()) {
                    db.syncOutboxDao().captureAll(SyncCollection.GROUP.wire, group.uuid)
                    for (member in db.groupDao().membersOf(group.id)) {
                        val podcast = db.podcastDao().byId(member.podcastId) ?: continue
                        db.syncOutboxDao().captureAll(SyncCollection.MEMBER.wire, group.uuid + podcast.syncId)
                    }
                }
                for (entry in db.queueDao().entries()) {
                    val episode = db.episodeDao().byId(entry.episodeId) ?: continue
                    val podcast = db.podcastDao().byId(episode.podcastId) ?: continue
                    db.syncOutboxDao().captureAll(SyncCollection.UPNEXT.wire, podcast.syncId + episode.identityKey)
                }
                db.syncOutboxDao().captureAll(SyncCollection.SESSION.wire, "current")
            }
        }
    }

    /**
     * Stamps this device's current rows into `sync_clock` at their own timestamps, so the incoming
     * field merge sees the local values and decides by clock.
     */
    private suspend fun seedLocalState() {
        val nodeId = nodeId()
        db.withWriteTransaction {
            db.syncStateDao().withApplying {
                for (podcast in db.podcastDao().allForExport()) {
                    val fields =
                        mapOf(
                            "feedUrl" to fieldString(podcast.feedUrl, podcast.subscribedAt, nodeId),
                            "subscribed" to fieldBool(true, podcast.subscribedAt, nodeId),
                            "title" to fieldString(podcast.title, podcast.subscribedAt, nodeId),
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
                        fields["played"] = fieldBool(state.playedAt != null, at, nodeId)
                        fields["playedAt"] =
                            FieldState(state.playedAt?.let(::JsonPrimitive) ?: JsonNull, stamp(at, nodeId))
                        fields["fav"] = fieldBool(state.isFavorite, at, nodeId)
                        if (state.playCount > 0) fields["playCount"] = fieldLong(state.playCount.toLong(), at, nodeId)
                        state.lastPlayedAt?.let { fields["lastPlayedAt"] = fieldLong(it, at, nodeId) }
                    }
                    if (position != null && position.positionMs > 0) {
                        fields["pos"] =
                            FieldState(
                                JsonObject(buildMap { put("ms", JsonPrimitive(position.positionMs)) }),
                                stamp(position.updatedAt, nodeId),
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
        // a fresh local action. Capture runs after adoptPulledState enabled the outbox.
        for (row in db.syncClockDao().all()) {
            val raw = runCatching { SyncJson.decodeFromString<RawSyncRecord>(row.clocks) }.getOrNull() ?: continue
            if (db.syncOutboxDao().rowsFor(row.coll, listOf(row.rid)).isNotEmpty()) continue
            db.withWriteTransaction {
                db.syncStateDao().withApplying {
                    val played = raw.state.fields["played"]
                    val playedAt = raw.state.fields["playedAt"]
                    for ((name, field) in raw.state.fields) {
                        val at = field.c?.millis ?: continue
                        if (name == "playedAt") continue
                        if (name == "played" && played != null) {
                            // The wire pair travels together with one clock (10 played literals).
                            val literal =
                                JsonObject(
                                    buildMap {
                                        put("played", played.v)
                                        put("playedAt", playedAt?.v ?: JsonNull)
                                    },
                                ).toString()
                            db.syncOutboxDao().captureAt(row.coll, row.rid, "played", at, literal)
                            continue
                        }
                        db.syncOutboxDao().captureAt(row.coll, row.rid, name, at, field.v.toString())
                    }
                }
            }
        }
    }

    /** Pushes until the outbox drains; the server's field merge keeps whichever side is newer. */
    private suspend fun pushOutbox(onProgress: (LinkPhase, Int, Int) -> Unit) {
        val total = db.syncOutboxDao().count()
        if (total == 0) return
        var rounds = 0
        while (db.syncOutboxDao().count() > 0 && rounds < MAX_PUSH_ROUNDS) {
            rounds++
            engine.round(SyncRoundReason.FIRST_LINK)
            onProgress(LinkPhase.UPLOADING, rounds, total.coerceAtLeast(1))
        }
    }

    /** Writes the negotiated cursor and raises the clock past everything seen and now. */
    private suspend fun adoptPulledState(
        cursor: String?,
        maxClockPacked: Long,
    ) {
        db.withWriteTransaction {
            db.syncStateDao().get()?.let { state ->
                db.syncStateDao().upsert(
                    state.copy(
                        enabled = true,
                        cursor = cursor,
                        hlc = maxOf(state.hlc, maxClockPacked, clock.now() shl HLC_COUNTER_BITS),
                    ),
                )
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

    private suspend fun nodeId(): NodeId =
        NodeId(
            checkNotNull(db.syncStateDao().get()?.nodeId) {
                "the link wrote no node ID before the first-link merge"
            },
        )

    private suspend fun pullAndApply(
        roundId: Long,
        onProgress: (LinkPhase, Int, Int) -> Unit,
    ): Int = pull(roundId, apply = true, onProgress).applied

    private fun maxClockOf(record: RecordDto): Long =
        record.fields.values
            .mapNotNull { it.c }
            .plus(record.matchClocks.values)
            .maxOfOrNull { runCatching { Hlc.parse(it).packed }.getOrDefault(0L) } ?: 0L

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

    private fun stamp(
        atMs: Long,
        nodeId: NodeId,
    ): Hlc = Hlc.of(atMs.coerceAtLeast(SYNC_MIN_CLOCK_MS), 0, nodeId)

    private fun fieldString(
        value: String,
        atMs: Long,
        nodeId: NodeId,
    ): FieldState = FieldState(JsonPrimitive(value), stamp(atMs, nodeId))

    private fun fieldLong(
        value: Long,
        atMs: Long,
        nodeId: NodeId,
    ): FieldState = FieldState(JsonPrimitive(value), stamp(atMs, nodeId))

    private fun fieldBool(
        value: Boolean,
        atMs: Long,
        nodeId: NodeId,
    ): FieldState = FieldState(JsonPrimitive(value), stamp(atMs, nodeId))

    private companion object {
        const val MAX_PAGES = 10_000
        const val MAX_PUSH_ROUNDS = 100
        const val DELETE_CONFIRMATION = "DELETE"
        const val RESET_MODE_LIBRARY = "library"
    }
}
