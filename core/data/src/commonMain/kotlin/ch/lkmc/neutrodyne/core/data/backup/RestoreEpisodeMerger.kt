// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.backup

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.database.BackupDao
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.OrderKeys
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.model.MergePolicy
import ch.lkmc.neutrodyne.core.model.MergeSummary
import ch.lkmc.neutrodyne.core.model.MergeValueOrigin
import ch.lkmc.neutrodyne.core.model.MergedField
import ch.lkmc.neutrodyne.core.model.MergedRecord
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.model.RestoreCategory
import ch.lkmc.neutrodyne.feeds.backup.EpisodeLineV1
import ch.lkmc.neutrodyne.feeds.backup.QueueV1
import ch.lkmc.neutrodyne.feeds.backup.SessionV1
import ch.lkmc.neutrodyne.feeds.backup.UpNextRefV1
import ch.lkmc.neutrodyne.feeds.identity.EpisodeKeys
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import dev.zacsweers.metro.Inject
import kotlinx.serialization.json.JsonPrimitive

private const val NO_STATE_TS = 0L

/** 05 finish: `ImportOptions.redownloadIds` keeps at most this many resolved `dl` ids. */
private const val MAX_REDOWNLOAD_IDS = 2_000

/** Every key version this build computes; a ref's own `kv` may be any of them. */
private val REF_KEY_VERSIONS: Set<Int> = (1..EpisodeKeys.VERSION).toSet()

/**
 * 05's `RestoreMerger` episode, Up next and session phases: steps 5–6 of the Restore
 * algorithm — the transactions B that stream `episodes.jsonl` in 1,000-line write
 * transactions (line `p` → podcast, the shared [EpisodeMatcher] ladder per podcast, then
 * [RestoreWrites]; unmatched lines stubbing per the policy's rule) and transaction C (Up
 * next entries and the `play_session` singleton) of the Merge and Replace rules table.
 *
 * The `LINK_*` policies add the First-link column's timestamp-LWW state rules (05): played
 * and favourite are decided by the newer of the record's own field clock (`fc`) and the
 * local row timestamp — a newer remote "unplayed" clears an older local mark — while
 * `playCount`/`lastPlayedAt` still take the maximum and a record without the field leaves
 * the local value alone. Unmatched lines stub only when queued, current, in progress or
 * favourite; every other unmatched line is handed to [onUnmatched] after its chunk commits
 * (10 parks the record). A linked Merge restore ([captureWithBackupTimes]) records each
 * written value with `captureAt` at the backup's timestamps.
 *
 * Podcast and group identities attach through transaction A's settled maps — the
 * [LibraryMergeResult] `mergeLibrary` returned — never a re-run of the match ladders: A
 * matched before its writes, so a key, alias or `podcastGuid` a later staged row
 * committed cannot re-point an already-settled `p`.
 *
 * Caller contract: run after `RestoreMerger.mergeLibrary` in the same restore with that
 * result and the same [policy]/[categories]. A failure inside a chunk aborts only that
 * transaction — every earlier chunk is committed — and a re-run replays idempotently
 * (max/OR state merges, newer-`posAt`-wins positions, `INSERT OR IGNORE` stubs and queue
 * entries).
 */
@Inject
internal class RestoreEpisodeMerger(
    private val db: NeutrodyneDatabase,
    private val episodes: EpisodeMatcher,
    private val writes: RestoreWrites,
    private val orderKeys: OrderKeys,
    private val clock: Clock,
) {
    private val backupDao get() = db.backupDao()
    private val queueDao get() = db.queueDao()
    private val sessionDao get() = db.playSessionDao()
    private val outbox get() = db.syncOutboxDao()

    /**
     * Writes [staged]'s episode lines under [policy] in 1,000-line write transactions,
     * then its `queue.json` under the Up next/session rules (05 transactions B and C);
     * [settled] is transaction A's result — its `p`/group-`uuid` maps are the only
     * identities this phase attaches through. [progress] reports `(done, total)` once per
     * committed chunk. Only the episode counts of the result's [MergeSummary] are filled
     * — the library counts are [settled]'s — and [EpisodeMergeResult.redownloadIds] lists
     * the local episode ids resolved from `dl = true` lines (newest `d` first, capped),
     * the finish step's worker-owned `ImportOptions.redownloadIds` (05).
     */
    suspend fun mergeEpisodes(
        staged: StagedLibrary,
        settled: LibraryMergeResult,
        policy: MergePolicy,
        categories: Set<RestoreCategory>,
        episodeTotal: Int = 0,
        captureWithBackupTimes: Boolean = false,
        suppressCapture: Boolean = false,
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
        onUnmatched: (suspend (podcastSyncId: String, identityKey: String) -> Unit)? = null,
        onApplied: (suspend (MergedRecord) -> Unit)? = null,
    ): EpisodeMergeResult {
        val link = policy == MergePolicy.LINK_MERGE || policy == MergePolicy.LINK_REPLACE
        val history = RestoreCategory.HISTORY in categories
        val upNext = RestoreCategory.UP_NEXT in categories
        if (!history && !upNext) return EpisodeMergeResult(MergeSummary(0, 0, 0, 0, 0, 0, 0, 0), emptyList())
        val now = clock.now()
        val podcastIdByKey = settled.podcastIdByKey
        // `queue.json` is read before the stream: an unmatched line referenced by it stubs
        // even without HISTORY, and transaction C resolves refs through the `(p, k) → id`
        // map the chunk transactions record (05 step 5).
        val queue = if (upNext) staged.queue() else null
        val referenced = referencedKeys(queue)
        val refIds = HashMap<EpisodeRefKey, Long>()
        // `dl` line → resolved episode id, newest `d` kept per id — recorded at the write,
        // never by a later live match (05 After restore's re-download offer).
        val downloads = LinkedHashMap<Long, Long>()
        // The local podcast rows' sync ids, for `onUnmatched` and the linked captures.
        val syncIdByPodcastId = HashMap<Long, String>()

        var episodeLines = 0
        var stubs = 0
        var unmatched = 0
        var skippedLines = 0
        val chunk = ArrayList<EpisodeLineV1>(BackupDao.EPISODE_CHUNK)

        suspend fun flushChunk() {
            if (chunk.isEmpty()) return
            // Lines a link policy declines to stub — handed over only after the commit.
            val unmatchedRecords = ArrayList<Pair<String, String>>()
            db.withWriteTransaction {
                val block: suspend () -> Unit = {
                    for ((podcastId, lines) in linesByPodcast(chunk, podcastIdByKey) { skippedLines++ }) {
                        val index = episodes.index(podcastId, lines.mapTo(HashSet()) { it.kv })
                        for (line in lines) {
                            var episodeId =
                                refIds[refKeyOf(line.p, line.k, line.kv)]
                                    ?: index.match(line.matchHints())
                            if (episodeId == null) {
                                val canStub =
                                    if (link) {
                                        line.canStubLink(referenced)
                                    } else {
                                        line.canStub(history, referenced)
                                    }
                                episodeId =
                                    if (canStub) {
                                        writes.insertStub(podcastId, line.stubFields(), now)
                                    } else {
                                        null
                                    }
                                if (episodeId == null) {
                                    unmatched++
                                    if (link) {
                                        val syncId = syncIdOf(podcastId, syncIdByPodcastId)
                                        if (syncId != null) {
                                            unmatchedRecords += syncId to canonicalKey(line.k, line.kv)
                                        }
                                    }
                                    continue
                                }
                                stubs++
                            }
                            if (history) {
                                when (policy) {
                                    MergePolicy.RESTORE_MERGE -> {
                                        if (line.ts != NO_STATE_TS) {
                                            writes.mergeState(episodeId, line.stateValues())
                                        }
                                        writes.mergePosition(episodeId, line.positionValues(staged.createdAtMs))
                                        if (captureWithBackupTimes) {
                                            captureRestoredState(
                                                syncIdOf(podcastId, syncIdByPodcastId),
                                                episodeId,
                                                line,
                                            )
                                        }
                                    }

                                    MergePolicy.LINK_MERGE -> {
                                        linkMergeEpisode(
                                            podcastId,
                                            episodeId,
                                            line,
                                            syncIdByPodcastId,
                                            onApplied,
                                        )
                                        writes.mergePosition(episodeId, line.positionValues(staged.createdAtMs))
                                    }

                                    else -> {
                                        if (line.ts != NO_STATE_TS) {
                                            writes.replaceState(episodeId, line.stateValues())
                                        }
                                        writes.replacePosition(episodeId, line.positionValues(staged.createdAtMs))
                                    }
                                }
                            }
                            refIds[refKeyOf(line.p, line.k, line.kv)] = episodeId
                            if (line.dl) downloads[episodeId] = maxOf(line.d ?: 0L, downloads[episodeId] ?: 0L)
                        }
                    }
                }
                if (link || captureWithBackupTimes || suppressCapture) {
                    db.syncStateDao().withApplying { block() }
                } else {
                    block()
                }
            }
            for ((syncId, identityKey) in unmatchedRecords) onUnmatched?.invoke(syncId, identityKey)
            progress(episodeLines, episodeTotal)
            chunk.clear()
        }
        staged.forEachEpisodeLine { line ->
            chunk += line
            episodeLines++
            if (chunk.size == BackupDao.EPISODE_CHUNK) flushChunk()
        }
        flushChunk()

        if (upNext) {
            mergeQueueAndSession(
                staged,
                queue,
                policy,
                settled.groupIdByUuid,
                podcastIdByKey,
                refIds,
                now,
                captureWithBackupTimes,
                syncIdByPodcastId,
                suppressCapture,
            )
        }
        return EpisodeMergeResult(
            summary =
                MergeSummary(
                    podcastsAdded = 0,
                    podcastsMatched = 0,
                    groupsAdded = 0,
                    groupsMatched = 0,
                    episodeLines = episodeLines,
                    stubs = stubs,
                    unmatched = unmatched,
                    skippedLines = skippedLines,
                ),
            redownloadIds =
                downloads.entries
                    .sortedByDescending { it.value }
                    .take(MAX_REDOWNLOAD_IDS)
                    .map { it.key },
        )
    }

    // --- Transaction B helpers --------------------------------------------------------------

    /**
     * A line's or ref's `p` → the settled local podcast: transaction A's key/alias map
     * only. An un-normalised spelling still resolves — 02's `feedKey` is
     * `forIdentity(feedUrl)` — but no live lookup: A's committed map is the identity.
     */
    private fun podcastIdOf(
        key: String,
        podcastIdByKey: Map<String, Long>,
    ): Long? = podcastIdByKey[key] ?: UrlNormalizer.forIdentity(key)?.let(podcastIdByKey::get)

    /**
     * A chunk grouped by resolved podcast, stream order preserved. Lines whose `p` is
     * unknown to the settled map are skipped per 05 — [onSkipped] counts them.
     */
    private suspend fun linesByPodcast(
        lines: List<EpisodeLineV1>,
        podcastIdByKey: Map<String, Long>,
        onSkipped: () -> Unit,
    ): LinkedHashMap<Long, MutableList<EpisodeLineV1>> {
        val grouped = LinkedHashMap<Long, MutableList<EpisodeLineV1>>()
        for (line in lines) {
            val podcastId = podcastIdOf(line.p, podcastIdByKey)
            if (podcastId == null) {
                onSkipped()
                continue
            }
            grouped.getOrPut(podcastId) { mutableListOf() } += line
        }
        return grouped
    }

    /**
     * 05's stub rule: an unmatched line stubs when it carries the stub columns' evidence
     * (`u` or `yt`) — every such line with HISTORY, only `queue.json`-referenced ones
     * without it (the queue/session refs need their episode to exist).
     */
    private fun EpisodeLineV1.canStub(
        history: Boolean,
        referenced: Set<EpisodeRefKey>,
    ): Boolean {
        if (u == null && yt == null) return false
        return history || refKeyOf(p, k, kv) in referenced
    }

    /**
     * The First-link column's stub rule (05): only lines that are queued, current, in
     * progress (`pos > 0`, not played) or favourite stub — with their evidence — because
     * the next refresh ingests the podcast anyway; every other unmatched line goes to
     * `onUnmatched` and 10 parks its record.
     */
    private fun EpisodeLineV1.canStubLink(referenced: Set<EpisodeRefKey>): Boolean {
        if (u == null && yt == null) return false
        val inProgress = (pos ?: 0L) > 0L && pl == null
        return inProgress || fav || refKeyOf(p, k, kv) in referenced
    }

    /** The podcast row's `syncId`, cached per merge run. */
    private suspend fun syncIdOf(
        podcastId: Long,
        cache: MutableMap<Long, String>,
    ): String? {
        cache[podcastId]?.let { return it }
        val syncId = db.podcastDao().byId(podcastId)?.syncId ?: return null
        cache[podcastId] = syncId
        return syncId
    }

    /**
     * The First-link column's state rules for one line (05): `played` and `fav` by the newer
     * of the record's own field clock (`fc`) and the local row timestamp — a newer remote
     * "unplayed" clears an older local mark, and a record without the field leaves the local
     * value alone — `playCount`/`lastPlayedAt` by max, `measuredDurationMs` local-first, the
     * device-local tombstone untouched. `startedAt` and the in-progress proxy derive through
     * the shared post rules. Winners are reported through [onApplied] with the episode's
     * logical record id.
     */
    private suspend fun linkMergeEpisode(
        podcastId: Long,
        episodeId: Long,
        line: EpisodeLineV1,
        syncIdByPodcastId: MutableMap<Long, String>,
        onApplied: (suspend (MergedRecord) -> Unit)?,
    ) {
        val local = db.episodeStateDao().byEpisode(episodeId)
        val fc = line.fc.orEmpty()
        if (line.ts == NO_STATE_TS && local == null) return
        var playedAt = local?.playedAt
        var playedWinner: MergeValueOrigin? = null
        var playedWinnerAt = 0L
        val playedClock = fc["played"]
        if (playedClock != null) {
            val localAt = local?.playedAt ?: local?.updatedAt ?: 0L
            if (playedClock >= localAt) {
                playedAt = line.pl
                playedWinner = MergeValueOrigin.STAGED
            } else {
                playedWinner = MergeValueOrigin.LOCAL
            }
            playedWinnerAt = if (playedWinner == MergeValueOrigin.STAGED) playedClock else localAt
        }

        var favourite = local?.isFavorite ?: false
        var favWinner: MergeValueOrigin? = null
        var favWinnerAt = 0L
        val favClock = fc["fav"]
        if (favClock != null) {
            val localAt = local?.updatedAt ?: 0L
            if (favClock >= localAt) {
                favourite = line.fav
                favWinner = MergeValueOrigin.STAGED
            } else {
                favWinner = MergeValueOrigin.LOCAL
            }
            favWinnerAt = if (favWinner == MergeValueOrigin.STAGED) favClock else localAt
        }

        val playCount = maxOf(line.pc, local?.playCount ?: 0)
        val lastPlayedAt = maxOfNullable(line.lp, local?.lastPlayedAt)
        val measuredDurationMs = local?.measuredDurationMs ?: line.md
        val updatedAt = maxOf(local?.updatedAt ?: 0L, line.ts)

        writes.linkState(
            episodeId,
            LinkStateValues(
                playedAt = playedAt,
                playCount = playCount,
                lastPlayedAt = lastPlayedAt,
                isFavorite = favourite,
                downloadDismissedAt = local?.downloadDismissedAt,
                measuredDurationMs = measuredDurationMs,
                startedAt = local?.startedAt,
                updatedAt = updatedAt,
            ),
        )

        val rid = syncIdOf(podcastId, syncIdByPodcastId) ?: return
        val fields = LinkedHashMap<String, MergedField>()
        if (playedWinner != null) {
            fields["played"] = MergedField(JsonPrimitive(playedAt != null), playedWinnerAt, playedWinner)
        }
        if (favWinner != null) {
            fields["fav"] = MergedField(JsonPrimitive(favourite), favWinnerAt, favWinner)
        }
        if (line.pc > (local?.playCount ?: 0)) {
            fields["playCount"] = MergedField(JsonPrimitive(line.pc), line.ts, MergeValueOrigin.STAGED)
        }
        val stagedLastPlayed = line.lp
        val localLastPlayed = local?.lastPlayedAt
        if (stagedLastPlayed != null && (localLastPlayed == null || stagedLastPlayed > localLastPlayed)) {
            fields["lastPlayedAt"] = MergedField(JsonPrimitive(stagedLastPlayed), line.ts, MergeValueOrigin.STAGED)
        }
        onApplied?.invoke(
            MergedRecord(collection = COLL_EPISODE, recordId = rid + canonicalKey(line.k, line.kv), fields = fields),
        )
    }

    /**
     * 05 Restore while linked: the state fields a Merge restore wrote, captured at the
     * backup's own timestamps — the played pair as one literal with `ts`, favourite,
     * `playCount` and `lastPlayedAt` as their values — plus the winning position at
     * `posAt` with the snapshot it wrote (never a later read).
     */
    private suspend fun captureRestoredState(
        podcastSyncId: String?,
        episodeId: Long,
        line: EpisodeLineV1,
    ) {
        if (podcastSyncId == null) return
        val rid = podcastSyncId + canonicalKey(line.k, line.kv)
        if (line.ts != NO_STATE_TS) {
            if (line.pl != null) {
                val literal = """{"played":true,"playedAt":${line.pl}}"""
                outbox.captureAt(COLL_EPISODE, rid, "played", line.ts, literal)
            }
            if (line.fav) outbox.captureAt(COLL_EPISODE, rid, "fav", line.ts, "true")
            if (line.pc > 0) outbox.captureAt(COLL_EPISODE, rid, "playCount", line.ts, line.pc.toString())
            if (line.lp != null) outbox.captureAt(COLL_EPISODE, rid, "lastPlayedAt", line.ts, line.lp.toString())
        }
        val values = line.positionValues(fallbackPosAt = 0L)
        val stagedPos = line.pos
        if (stagedPos != null && stagedPos > 0) {
            val literal = """{"ms":$stagedPos,"dur":${line.dur ?: 0},"src":"${values.positionSource.name}"}"""
            outbox.captureAt(COLL_EPISODE, rid, "pos", line.posAt ?: 0L, literal)
        }
    }

    // --- Transaction C: Up next and session -------------------------------------------------

    /**
     * 05 transaction C (UP_NEXT): Merge sweeps queued episodes that became played and
     * appends the backup's refs not already queued in archive order (`OrderKey.after(last)`);
     * Replace replays the backup's list minus played entries (`ok` verbatim, else
     * `OrderKey.rewrite(n)`), then the play session lands — Merge only while the local
     * `currentEpisodeId` is free, Replace always, an absent or null backup session meaning
     * the local session row clears. The link policies follow the First-link column (Merge
     * order for `LINK_MERGE`, Replace order for `LINK_REPLACE`, the session always
     * idle-only — never while this device plays, R7.5). A linked Merge restore captures
     * each appended entry's presence with a fresh literal tick.
     */
    private suspend fun mergeQueueAndSession(
        staged: StagedLibrary,
        queue: QueueV1?,
        policy: MergePolicy,
        groupIdByUuid: Map<String, Long>,
        podcastIdByKey: Map<String, Long>,
        refIds: Map<EpisodeRefKey, Long>,
        now: Long,
        captureWithBackupTimes: Boolean = false,
        syncIdByPodcastId: MutableMap<Long, String> = HashMap(),
        suppressCapture: Boolean = false,
    ) {
        val link = policy == MergePolicy.LINK_MERGE || policy == MergePolicy.LINK_REPLACE
        db.withWriteTransaction {
            val block: suspend () -> Unit = {
                if (policy == MergePolicy.RESTORE_MERGE || policy == MergePolicy.LINK_MERGE) {
                    backupDao.deletePlayedQueueEntries()
                }
                val played = backupDao.playedEpisodeIds().toHashSet()
                val queued = queueDao.queuedEpisodeIds().toHashSet()
                val indexCache = HashMap<Long, EpisodeMatchIndex>()

                val accepted = ArrayList<Pair<UpNextRefV1, Long>>(queue?.upNext?.size ?: 0)
                val seen = HashSet<Long>()
                for (ref in queue?.upNext.orEmpty()) {
                    val episodeId =
                        resolveRef(ref.p, ref.k, ref.kv, podcastIdByKey, refIds, indexCache) ?: continue
                    if (episodeId in played || episodeId in queued || !seen.add(episodeId)) continue
                    accepted += ref to episodeId
                }
                if (policy == MergePolicy.RESTORE_REPLACE || policy == MergePolicy.LINK_REPLACE) {
                    // The same all-valid-and-strictly-ordered-or-rewrite rule the library and
                    // member lists already share — no interpolation of untrusted `ok`s.
                    val keys = restoreOrderKeys(orderKeys, accepted.map { it.first.ok })
                    for ((entry, key) in accepted.zip(keys)) {
                        writes.insertQueueEntry(entry.second, key, staged.createdAtMs)
                    }
                } else {
                    var lastKey = queueDao.lastOrderKey()
                    for ((_, episodeId) in accepted) {
                        val key = orderKeys.after(lastKey)
                        // An ignored insert consumed no key.
                        if (writes.insertQueueEntry(episodeId, key, staged.createdAtMs)) {
                            lastKey = key
                            if (captureWithBackupTimes) {
                                captureQueuedEntry(episodeId, syncIdByPodcastId)
                            }
                        }
                    }
                }

                if (policy == MergePolicy.RESTORE_REPLACE ||
                    policy == MergePolicy.LINK_REPLACE ||
                    queue?.session != null
                ) {
                    // A Replace archive without a session still owns the row: the empty
                    // SessionV1 clears `currentEpisodeId` with the context the pre-step cut.
                    restoreSession(
                        queue?.session ?: SessionV1(),
                        policy,
                        podcastIdByKey,
                        groupIdByUuid,
                        refIds,
                        indexCache,
                        now,
                    )
                }
            }
            if (link || captureWithBackupTimes || suppressCapture) {
                db.syncStateDao().withApplying { block() }
            } else {
                block()
            }
        }
    }

    /**
     * A queue entry's presence capture (05 Restore while linked): "in = true" with a fresh
     * literal tick on the episode's `upnext` record. The link policies capture nothing —
     * `FirstLinkMerger` uploads the merged library afterwards stamped from row timestamps.
     */
    private suspend fun captureQueuedEntry(
        episodeId: Long,
        syncIdByPodcastId: MutableMap<Long, String>,
    ) {
        val episode = db.episodeDao().byId(episodeId) ?: return
        val podcastSyncId = syncIdOf(episode.podcastId, syncIdByPodcastId) ?: return
        outbox.captureLiteral(COLL_UPNEXT, podcastSyncId + episode.identityKey, "in", "true")
    }

    /**
     * A `(p, k, kv)` ref → an episode id: the `(p, k) → id` map the episode transactions
     * recorded first (it covers stubs and foreign-version keys the ladder's key step
     * skips), then the shared ladder for refs whose episode had no line.
     */
    private suspend fun resolveRef(
        podcastKey: String,
        identityKey: String,
        keyVersion: Int,
        podcastIdByKey: Map<String, Long>,
        refIds: Map<EpisodeRefKey, Long>,
        indexCache: MutableMap<Long, EpisodeMatchIndex>,
    ): Long? {
        refIds[refKeyOf(podcastKey, identityKey, keyVersion)]?.let { return it }
        val podcastId = podcastIdOf(podcastKey, podcastIdByKey) ?: return null
        // A ref carries no enclosure/GUID, so the ladder reduces to its key step; the
        // match version comes from the key's own prefix (02's caller rule). The cached index
        // serves refs of any version, so it prefetches the heads every computable version needs.
        val index =
            indexCache[podcastId]
                ?: episodes.index(podcastId, REF_KEY_VERSIONS).also {
                    indexCache[podcastId] = it
                }
        return index.match(
            EpisodeMatchHints(identityKey = identityKey, kv = EpisodeKeys.versionOf(identityKey)),
        )
    }

    /**
     * 05 Play session: the backup's `current`, context and context columns — refs resolved
     * through the same maps; a GROUP/PODCAST context whose row can't be mapped keeps no
     * reference (02: `contextId` is polymorphic). `generation + 1`; `updatedAt` stamps
     * `now` (a device-local column).
     */
    private suspend fun restoreSession(
        session: SessionV1,
        policy: MergePolicy,
        podcastIdByKey: Map<String, Long>,
        groupIdByUuid: Map<String, Long>,
        refIds: Map<EpisodeRefKey, Long>,
        indexCache: MutableMap<Long, EpisodeMatchIndex>,
        now: Long,
    ) {
        sessionDao.ensureNow(now)
        var contextType = enumNamed<ContextType>(session.contextType)
        var contextId: Long? = null
        when (contextType) {
            ContextType.GROUP -> {
                contextId = session.contextGroupUuid?.let(groupIdByUuid::get)
                if (contextId == null) contextType = null
            }

            ContextType.PODCAST -> {
                contextId = session.contextPodcastKey?.let { podcastIdOf(it, podcastIdByKey) }
                if (contextId == null) contextType = null
            }

            else -> {}
        }
        sessionDao.restoreSession(
            currentEpisodeId =
                session.current?.let {
                    resolveRef(it.p, it.k, it.kv, podcastIdByKey, refIds, indexCache)
                },
            contextType = contextType,
            contextId = contextId,
            contextOrder = enumNamed<FeedOrder>(session.order) ?: FeedOrder.NEWEST_FIRST,
            contextFilterFlags = session.filterFlags,
            contextMediaFilter = enumNamed<MediaFilter>(session.mediaFilter) ?: MediaFilter.ALL,
            contextMinSortDate = session.minSortDate,
            contextAnchorEpisodeId =
                session.anchor?.let {
                    resolveRef(it.p, it.k, it.kv, podcastIdByKey, refIds, indexCache)
                },
            contextAnchorSortDate = session.anchorSortDate,
            // Merge — and the link policies (R7.5: never while this device plays) — land the
            // session only while the local row is idle; Replace owns the row.
            onlyWhenIdle = policy != MergePolicy.RESTORE_REPLACE && policy != MergePolicy.LINK_REPLACE,
            now = now,
        )
    }

    /**
     * The ref `(p, k)` set `queue.json` references — Up next, session current and anchor —
     * `kv`-canonicalised like the ids the episode transactions record (05 step 5).
     */
    private fun referencedKeys(queue: QueueV1?): Set<EpisodeRefKey> {
        queue ?: return emptySet()
        val refs = HashSet<EpisodeRefKey>()

        fun add(
            p: String,
            k: String,
            kv: Int,
        ) {
            refs += refKeyOf(p, k, kv)
        }
        for (ref in queue.upNext) add(ref.p, ref.k, ref.kv)
        queue.session?.current?.let { add(it.p, it.k, it.kv) }
        queue.session?.anchor?.let { add(it.p, it.k, it.kv) }
        return refs
    }

    /** The match hints of one line (`yt` qualifies a stub only — never a match step). */
    private fun EpisodeLineV1.matchHints() =
        EpisodeMatchHints(
            identityKey = k,
            kv = kv,
            guid = g,
            enclosureUrl = u,
            externalMediaId = yt,
        )

    private fun EpisodeLineV1.stateValues() =
        RestoreStateValues(
            playedAt = pl,
            playCount = pc,
            startedAt = st,
            lastPlayedAt = lp,
            isFavorite = fav,
            downloadDismissedAt = dis,
            measuredDurationMs = md,
            ts = ts,
        )

    /**
     * The line's position fields; `posAt` falls back to the archive's `createdAt` (02's
     * "the archive's `createdAt` when absent"), `posSrc` to STREAM on an unknown name.
     */
    private fun EpisodeLineV1.positionValues(fallbackPosAt: Long) =
        RestorePositionValues(
            positionMs = pos ?: 0L,
            durationMs = dur,
            positionSource = enumNamed<PositionSource>(posSrc) ?: PositionSource.STREAM,
            posAt = posAt ?: fallbackPosAt,
        )

    /**
     * 02's stub fields: the foreign-version key stays prefixed so it can never collide
     * ("prefixed by version"), the guid rides only as a match hint — `insertStub` writes
     * it to the stub's `guid` column and provenance stays untouched (D98's rule: a hint
     * never mints `KNOWN_INDEPENDENT`).
     */
    private fun EpisodeLineV1.stubFields() =
        RestoreStubFields(
            identityKey = canonicalKey(k, kv),
            guid = g,
            title = t.orEmpty(),
            pubDate = d,
            enclosureUrl = u,
            enclosureType = ty,
            durationMs = dur,
            externalMediaId = yt,
            link = l,
        )
}

/** Transaction B/C's result: the episode counts plus the finish step's re-download ids. */
internal class EpisodeMergeResult(
    val summary: MergeSummary,
    /**
     * Local episode ids resolved from `dl = true` lines — newest `d` first, ≤ 2,000 —
     * the worker-owned `ImportOptions.redownloadIds` of 05's finish. YouTube rows stay
     * listed; the M6 offer skips them when capabilities say downloads false.
     */
    val redownloadIds: List<Long>,
)

/** `(p, k)` as episode lines record it and `queue.json` refs carry it. */
private data class EpisodeRefKey(
    val p: String,
    val k: String,
)

private fun refKeyOf(
    p: String,
    k: String,
    kv: Int,
): EpisodeRefKey = EpisodeRefKey(p, canonicalKey(k, kv))

/**
 * The stored form of a `k`/`kv` pair: `k` verbatim when its own prefix already carries
 * `kv`, else the version prefix applied — so a foreign-version line's stub key and the
 * ref spellings it may arrive under compare equal (05 Versioning: "prefixed by version,
 * so it cannot collide").
 */
private fun canonicalKey(
    k: String,
    kv: Int,
): String = if (EpisodeKeys.versionOf(k) == kv) k else "$kv$k"

private fun maxOfNullable(
    a: Long?,
    b: Long?,
): Long? =
    when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }

private inline fun <reified E : Enum<E>> enumNamed(name: String?): E? =
    name?.let { wanted -> enumValues<E>().firstOrNull { it.name == wanted } }

/** Sync collection wire names (10) for the explicit captures of a linked restore. */
private const val COLL_EPISODE = "episode"
private const val COLL_UPNEXT = "upnext"
