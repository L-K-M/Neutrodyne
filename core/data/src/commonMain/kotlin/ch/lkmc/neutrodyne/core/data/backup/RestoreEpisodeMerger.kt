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
import ch.lkmc.neutrodyne.feeds.backup.LibraryV1
import ch.lkmc.neutrodyne.feeds.backup.QueueV1
import ch.lkmc.neutrodyne.feeds.backup.SessionV1
import ch.lkmc.neutrodyne.feeds.backup.UpNextRefV1
import ch.lkmc.neutrodyne.feeds.groups.GroupNames
import ch.lkmc.neutrodyne.feeds.identity.EpisodeKeys
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import dev.zacsweers.metro.Inject

private const val NO_STATE_TS = 0L
private const val DEFAULT_GROUP_NAME = "Group"

/**
 * 05's `RestoreMerger` episode, Up next and session phases: steps 5–6 of the Restore
 * algorithm — the transactions B that stream `episodes.jsonl` in 1,000-line write
 * transactions (line `p` → podcast, the shared [EpisodeMatcher] ladder per podcast, then
 * [RestoreWrites]; unmatched lines stubbing per the `queue.json`-referenced rule) and
 * transaction C (Up next entries and the `play_session` singleton) of the Merge and
 * Replace rules table.
 *
 * The maps transaction A produced are re-derived rather than shared with `RestoreMerger`:
 * [podcasts] replays the same match ladder over the staged podcasts — committed rows
 * re-resolve identically — and staged group `uuid`s resolve through `uuid`-then-`nameKey`,
 * the same evidence A used, so a session context maps to the row a Merge insert, a
 * Replace adoption or a folded match landed on.
 *
 * Caller contract: run after `RestoreMerger.mergeLibrary` in the same restore with the
 * same [policy]/[categories]. A failure inside a chunk aborts only that transaction —
 * every earlier chunk is committed — and a re-run replays idempotently (max/OR state
 * merges, newer-`posAt`-wins positions, `INSERT OR IGNORE` stubs and queue entries).
 */
@Inject
internal class RestoreEpisodeMerger(
    private val db: NeutrodyneDatabase,
    private val podcasts: PodcastMatcher,
    private val episodes: EpisodeMatcher,
    private val writes: RestoreWrites,
    private val orderKeys: OrderKeys,
    private val clock: Clock,
) {
    private val backupDao get() = db.backupDao()
    private val podcastDao get() = db.podcastDao()
    private val groupDao get() = db.groupDao()
    private val queueDao get() = db.queueDao()
    private val sessionDao get() = db.playSessionDao()

    /**
     * Writes [staged]'s episode lines under [policy] in 1,000-line write transactions,
     * then its `queue.json` under the Up next/session rules (05 transactions B and C).
     * [progress] reports `(done, total)` once per committed chunk. Only the episode counts
     * of the returned [MergeSummary] are filled — the library counts are `mergeLibrary`'s.
     */
    suspend fun mergeEpisodes(
        staged: StagedLibrary,
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
        val library = staged.library()
        val podcastIdByKey = stagedPodcastIds(library)
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
            mergeQueueAndSession(staged, queue, policy, library, podcastIdByKey, refIds, now)
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
     * Transaction A's `p` → podcastId map, re-derived by the same ladder over committed
     * rows: an inserted staged podcast now answers its own `feedKey` step, a matched one
     * re-matches by the same evidence.
     */
    private suspend fun stagedPodcastIds(library: LibraryV1): Map<String, Long> {
        val map = HashMap<String, Long>()
        for (podcast in library.podcasts.distinctBy { it.key }) {
            val localId = podcasts.match(podcast.key, podcast.aliases, podcast.podcastGuid) ?: continue
            map[podcast.key] = localId
            for (alias in podcast.aliases) map.putIfAbsent(alias, localId)
        }
        return map
    }

    /**
     * A line's or ref's `p` → a local podcast: the staged key/alias map first, then the
     * live `feedKey`/alias lookups, then the canonicalised key — a backup spelling a URL
     * un-normalised still finds its feed (02's `feedKey` is `forIdentity(feedUrl)`).
     */
    private suspend fun podcastIdOf(
        key: String,
        podcastIdByKey: Map<String, Long>,
    ): Long? {
        podcastIdByKey[key]?.let { return it }
        podcastDao.byFeedKey(key)?.id?.let { return it }
        podcastDao.aliasOwner(key)?.let { return it }
        val canonical = UrlNormalizer.forIdentity(key) ?: return null
        return podcastIdByKey[canonical] ?: podcastDao.byFeedKey(canonical)?.id
    }

    /**
     * A chunk grouped by resolved podcast, stream order preserved. Lines whose `p` is
     * unknown (no staged key, live `feedKey` or alias) are skipped per 05 — [onSkipped]
     * counts them.
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
     * `currentEpisodeId` is free.
     */
    private suspend fun mergeQueueAndSession(
        staged: StagedLibrary,
        queue: QueueV1?,
        policy: MergePolicy,
        library: LibraryV1,
        podcastIdByKey: Map<String, Long>,
        refIds: Map<EpisodeRefKey, Long>,
        now: Long,
    ) {
        val groupIdByUuid =
            if (queue?.session?.contextGroupUuid != null) stagedGroupUuids(library) else emptyMap()
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
                val keys = fillOrderKeys(accepted.map { it.first.ok })
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

            queue?.session?.let {
                restoreSession(it, policy, podcastIdByKey, groupIdByUuid, refIds, indexCache, now)
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
        // match version comes from the key's own prefix (02's caller rule).
        val index =
            indexCache[podcastId]
                ?: episodes.index(podcastId, setOf(EpisodeKeys.VERSION)).also {
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
                contextId =
                    session.contextGroupUuid?.let {
                        groupIdByUuid[it] ?: backupDao.matchGroupByUuid(it)
                    }
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
     * Transaction A's staged `uuid` → groupId map: the staged `uuid` hits its adopted or
     * matched row (Replace), else the group's normalised `nameKey` — covering Merge's
     * fresh-uuid inserts and folded staged groups alike (the fold shares one `nameKey`,
     * so a folded `uuid` maps to its canonical's row).
     */
    private suspend fun stagedGroupUuids(library: LibraryV1): Map<String, Long> {
        val map = HashMap<String, Long>()
        for (group in library.groups) {
            val valid = GroupNames.truncate(group.name) ?: GroupNames.truncate(DEFAULT_GROUP_NAME)!!
            map[group.uuid] =
                backupDao.matchGroupByUuid(group.uuid)
                    ?: groupDao.groupByNameKey(valid.nameKey)?.id
                    ?: continue
        }
        return map
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

    /**
     * OrderKeys for a Replace-written list (05 "the backup's keys, else `OrderKey.rewrite(n)`
     * in rank order"): present keys kept, missing keys generated between their keyed
     * neighbours; a file carrying no keys at all gets the evenly spaced rewrite.
     */
    private fun fillOrderKeys(wanted: List<String?>): List<String> {
        if (wanted.isEmpty()) return emptyList()
        if (wanted.all { it == null }) return orderKeys.rewrite(wanted.size)
        val filled = arrayOfNulls<String>(wanted.size)
        var previous: String? = null
        for (index in wanted.indices) {
            val key = wanted[index]
            if (key != null) {
                filled[index] = key
                previous = key
                continue
            }
            val next = wanted.subList(index + 1, wanted.size).firstOrNull { it != null }
            filled[index] =
                if (previous != null && next != null && previous < next) {
                    orderKeys.between(previous, next)
                } else {
                    orderKeys.after(previous)
                }
            previous = filled[index]
        }
        @Suppress("UNCHECKED_CAST")
        return filled.asList() as List<String>
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
