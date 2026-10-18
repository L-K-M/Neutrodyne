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
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.model.RestoreCategory
import ch.lkmc.neutrodyne.feeds.backup.EpisodeLineV1
import ch.lkmc.neutrodyne.feeds.backup.QueueV1
import ch.lkmc.neutrodyne.feeds.backup.SessionV1
import ch.lkmc.neutrodyne.feeds.backup.UpNextRefV1
import ch.lkmc.neutrodyne.feeds.identity.EpisodeKeys
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import dev.zacsweers.metro.Inject

private const val NO_STATE_TS = 0L

/** Every key version this build computes; a ref's own `kv` may be any of them. */
private val REF_KEY_VERSIONS: Set<Int> = (1..EpisodeKeys.VERSION).toSet()

/**
 * 05's `RestoreMerger` episode, Up next and session phases: steps 5–6 of the Restore
 * algorithm — the transactions B that stream `episodes.jsonl` in 1,000-line write
 * transactions (line `p` → podcast, the shared [EpisodeMatcher] ladder per podcast, then
 * [RestoreWrites]; unmatched lines stubbing per the `queue.json`-referenced rule) and
 * transaction C (Up next entries and the `play_session` singleton) of the Merge and
 * Replace rules table.
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

    /**
     * Writes [staged]'s episode lines under [policy] in 1,000-line write transactions,
     * then its `queue.json` under the Up next/session rules (05 transactions B and C);
     * [settled] is transaction A's result — its `p`/group-`uuid` maps are the only
     * identities this phase attaches through. [progress] reports `(done, total)` once per
     * committed chunk. Only the episode counts of the returned [MergeSummary] are filled
     * — the library counts are [settled]'s.
     */
    suspend fun mergeEpisodes(
        staged: StagedLibrary,
        settled: LibraryMergeResult,
        policy: MergePolicy,
        categories: Set<RestoreCategory>,
        episodeTotal: Int = 0,
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): MergeSummary {
        require(policy == MergePolicy.RESTORE_MERGE || policy == MergePolicy.RESTORE_REPLACE) {
            "mergeEpisodes supports RESTORE_MERGE and RESTORE_REPLACE; $policy lands with MS2"
        }
        val history = RestoreCategory.HISTORY in categories
        val upNext = RestoreCategory.UP_NEXT in categories
        if (!history && !upNext) return MergeSummary(0, 0, 0, 0, 0, 0, 0, 0)
        val now = clock.now()
        val podcastIdByKey = settled.podcastIdByKey
        // `queue.json` is read before the stream: an unmatched line referenced by it stubs
        // even without HISTORY, and transaction C resolves refs through the `(p, k) → id`
        // map the chunk transactions record (05 step 5).
        val queue = if (upNext) staged.queue() else null
        val referenced = referencedKeys(queue)
        val refIds = HashMap<EpisodeRefKey, Long>()

        var episodeLines = 0
        var stubs = 0
        var unmatched = 0
        var skippedLines = 0
        val chunk = ArrayList<EpisodeLineV1>(BackupDao.EPISODE_CHUNK)

        suspend fun flushChunk() {
            if (chunk.isEmpty()) return
            db.withWriteTransaction {
                for ((podcastId, lines) in linesByPodcast(chunk, podcastIdByKey) { skippedLines++ }) {
                    val index = episodes.index(podcastId, lines.mapTo(HashSet()) { it.kv })
                    for (line in lines) {
                        var episodeId =
                            refIds[refKeyOf(line.p, line.k, line.kv)]
                                ?: index.match(line.matchHints())
                        if (episodeId == null) {
                            episodeId =
                                if (line.canStub(history, referenced)) {
                                    writes.insertStub(podcastId, line.stubFields(), now)
                                } else {
                                    null
                                }
                            if (episodeId == null) {
                                unmatched++
                                continue
                            }
                            stubs++
                        }
                        if (history) {
                            if (line.ts != NO_STATE_TS) {
                                if (policy == MergePolicy.RESTORE_MERGE) {
                                    writes.mergeState(episodeId, line.stateValues())
                                } else {
                                    writes.replaceState(episodeId, line.stateValues())
                                }
                            }
                            // Position merges independently of `ts` (05): the zero-position
                            // and post-rule cases live inside the write primitives.
                            if (policy == MergePolicy.RESTORE_MERGE) {
                                writes.mergePosition(episodeId, line.positionValues(staged.createdAtMs))
                            } else {
                                writes.replacePosition(episodeId, line.positionValues(staged.createdAtMs))
                            }
                        }
                        refIds[refKeyOf(line.p, line.k, line.kv)] = episodeId
                    }
                }
            }
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
            )
        }
        return MergeSummary(
            podcastsAdded = 0,
            podcastsMatched = 0,
            groupsAdded = 0,
            groupsMatched = 0,
            episodeLines = episodeLines,
            stubs = stubs,
            unmatched = unmatched,
            skippedLines = skippedLines,
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

    // --- Transaction C: Up next and session -------------------------------------------------

    /**
     * 05 transaction C (UP_NEXT): Merge sweeps queued episodes that became played and
     * appends the backup's refs not already queued in archive order (`OrderKey.after(last)`);
     * Replace replays the backup's list minus played entries (`ok` verbatim, else
     * `OrderKey.rewrite(n)`), then the play session lands — Merge only while the local
     * `currentEpisodeId` is free, Replace always, an absent or null backup session meaning
     * the local session row clears.
     */
    private suspend fun mergeQueueAndSession(
        staged: StagedLibrary,
        queue: QueueV1?,
        policy: MergePolicy,
        groupIdByUuid: Map<String, Long>,
        podcastIdByKey: Map<String, Long>,
        refIds: Map<EpisodeRefKey, Long>,
        now: Long,
    ) {
        db.withWriteTransaction {
            if (policy == MergePolicy.RESTORE_MERGE) backupDao.deletePlayedQueueEntries()
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
            if (policy == MergePolicy.RESTORE_REPLACE) {
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
                    if (writes.insertQueueEntry(episodeId, key, staged.createdAtMs)) lastKey = key
                }
            }

            if (policy == MergePolicy.RESTORE_REPLACE || queue?.session != null) {
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
            onlyWhenIdle = policy == MergePolicy.RESTORE_MERGE,
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

private inline fun <reified E : Enum<E>> enumNamed(name: String?): E? =
    name?.let { wanted -> enumValues<E>().firstOrNull { it.name == wanted } }
