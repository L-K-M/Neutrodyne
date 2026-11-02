// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.importing

import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.artwork.ArtworkKeys
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.Redactor
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.data.credentials.removeUnreferenced
import ch.lkmc.neutrodyne.core.database.ImportItemEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastEntity
import ch.lkmc.neutrodyne.core.database.PodcastGroupEntity
import ch.lkmc.neutrodyne.core.database.PodcastGroupMemberEntity
import ch.lkmc.neutrodyne.core.database.PodcastSettingsEntity
import ch.lkmc.neutrodyne.core.database.PodcastUrlAliasEntity
import ch.lkmc.neutrodyne.core.database.ScopeOverrides
import ch.lkmc.neutrodyne.core.domain.CredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import ch.lkmc.neutrodyne.core.domain.ImportError
import ch.lkmc.neutrodyne.core.domain.OrderKeys
import ch.lkmc.neutrodyne.core.domain.RefreshController
import ch.lkmc.neutrodyne.core.domain.SecretStore
import ch.lkmc.neutrodyne.core.model.AliasReason
import ch.lkmc.neutrodyne.core.model.GroupIcons
import ch.lkmc.neutrodyne.core.model.GroupPalette
import ch.lkmc.neutrodyne.core.model.ImportFormat
import ch.lkmc.neutrodyne.core.model.ImportItemKind
import ch.lkmc.neutrodyne.core.model.ImportItemStatus
import ch.lkmc.neutrodyne.core.model.ImportOptions
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.feeds.discovery.AddInputNormalizer
import ch.lkmc.neutrodyne.feeds.discovery.NormalizedInput
import ch.lkmc.neutrodyne.feeds.groups.GroupNames
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The commit half of `ImportRepository.confirm` (05 §6, 02 Import commit): turns a `PREVIEW`
 * session's selected rows into `PENDING_FIRST_FETCH` podcasts, groups and memberships in chunks
 * of at most [CHUNK] items per write transaction, then marks the session `COMMITTED` once and
 * asks for its fetch work.
 *
 * Ordering and durability rules this owns:
 * - The session row and its `optionsJson` are re-read inside the commit's first transaction —
 *   preview edits a stale screen left behind still apply, and worker-owned fields
 *   (`committedAt`, `attemptMarkerAt`) are merged into the row read at commit time, never an
 *   earlier snapshot (A1's `withUserChanges` keeps user edits away from them meanwhile).
 * - The gate is `PREVIEW` plus no restore intent (`NEUTRODYNE_BACKUP` sessions and a set
 *   `restoreRequestedAt` belong to the restore path, not this committer). A session already past
 *   `PREVIEW` makes confirm an idempotent no-op — the commit's writes all hold up on replay.
 * - Each chunk re-checks session ownership inside its own transaction: a PREVIEW
 *   cancel/delete or the expiry sweep between chunks would otherwise keep the loop writing
 *   podcasts, groups and memberships no session tracks. The aborting chunk rolls back,
 *   committed chunks stand and the put cleanup still runs.
 * - Credential `put`s run outside any Room transaction but inside
 *   [CredentialCommitCoordinator.withCredentialCommit], which stays held across every commit
 *   chunk (03's ordering): a thrown chunk rolls back only its own transaction, so puts whose
 *   referencing podcast rows already committed keep their credentials — the cleanup removes a
 *   put only while no `podcast.credentialId` references it.
 * - feedKey/alias conflicts are re-checked inside each chunk transaction (classify raced ahead):
 *   a hit "continues as already subscribed" (memberships still attach, the row carries the
 *   existing podcast id); a `syncId` collision instead gets a fresh UUIDv4 and a retried insert.
 * - `originalUrl` is rewritten through [Redactor.url] for every row the commit touches — after
 *   the `put` pass — so no password survives in `import_item`.
 * - First fetch stays `INITIAL`: pending rows only; `notifyNewEpisodes` writes the
 *   `podcast_settings` override without producing notifications, and `treatExistingAsPlayed`
 *   is the fetch/result pass's business (05 §7), not commit's.
 *
 * Not contributed to the graph yet: bindings arrive with M3's platform rows (05's own note in
 * [ImportSessions]); [ImportWorkScheduler]'s platform implementations land with C2/C3.
 */
@Inject
@OptIn(ExperimentalUuidApi::class)
internal class ImportCommitter(
    private val db: NeutrodyneDatabase,
    private val secrets: SecretStore,
    private val coordinator: CredentialCommitCoordinator,
    private val workScheduler: ImportWorkScheduler,
    private val refresh: RefreshController,
    private val channels: GroupChannelSync,
    private val orderKeys: OrderKeys,
    private val clock: Clock,
    private val newSyncId: () -> String = { Uuid.random().toString() },
) {
    suspend fun confirm(sessionId: Long): Outcome<Unit, ImportError> =
        try {
            when (coordinator.withCredentialCommit { commitLocked(sessionId) }) {
                Commit.GONE -> {
                    Outcome.Failure(ImportError.SessionGone)
                }

                Commit.REPLAYED -> {
                    Outcome.Success(Unit)
                }

                Commit.READY_TO_FINISH -> {
                    // Keep cancellation between chunks; the final marker and its effects
                    // must finish together, outside the credential coordinator.
                    withContext(NonCancellable) { finishCommit(sessionId) }
                    Outcome.Success(Unit)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: SessionLostException) {
            Outcome.Failure(ImportError.SessionGone)
        } catch (_: Exception) {
            Outcome.Failure(ImportError.Storage)
        }

    /** Called non-cancellably so Room cannot commit the marker and skip scheduling its host. */
    private suspend fun finishCommit(sessionId: Long) {
        val marked =
            db.withWriteTransaction {
                val latest = db.importDao().sessionById(sessionId) ?: return@withWriteTransaction false
                if (latest.state != ImportState.PREVIEW) return@withWriteTransaction false

                // Merge into the latest row: a stale preview must not replace worker markers.
                val latestOptions = latest.optionsJson?.let(ImportOptionsJson::decode) ?: ImportOptions()
                val now = clock.now()
                db.importDao().markCommitted(
                    sessionId,
                    ImportOptionsJson.encode(latestOptions.copy(committedAt = now, attemptMarkerAt = now)),
                ) > 0
            }
        if (!marked) return

        suspendRunCatching { workScheduler.schedule(sessionId) }
            .onFailure { e -> Log.w(TAG, e) { "import fetch scheduling failed for session $sessionId" } }
        suspendRunCatching { refresh.reschedulePeriodic() }
            .onFailure { e -> Log.w(TAG, e) { "periodic reschedule failed after import commit" } }
        suspendRunCatching { channels.sync() }
            .onFailure { e -> Log.w(TAG, e) { "group channel sync failed after import commit" } }
    }

    /**
     * The credential-guarded chunks. The final marker and scheduling run after this releases
     * the coordinator; the other results mean there is nothing left to commit.
     */
    private suspend fun commitLocked(sessionId: Long): Commit =
        when (val plan = db.withReadTransaction { planLocked(sessionId) }) {
            Plan.Replay -> Commit.REPLAYED
            Plan.NotOurs, Plan.Gone -> Commit.GONE
            is Plan.Ready -> commitReady(sessionId, plan)
        }

    /**
     * The gate + work-list read — the session row, its options and the items in one consistent
     * snapshot: [Plan.Gone] for a missing session, [Plan.Replay] when confirm already ran (or a
     * cancel beat it), [Plan.NotOurs] for sessions the restore path owns, else [Plan.Ready].
     */
    private suspend fun planLocked(sessionId: Long): Plan {
        val session = db.importDao().sessionById(sessionId) ?: return Plan.Gone
        if (session.state != ImportState.PREVIEW) return Plan.Replay
        val options = session.optionsJson?.let(ImportOptionsJson::decode) ?: ImportOptions()
        if (session.sourceFormat == ImportFormat.NEUTRODYNE_BACKUP || options.restoreRequestedAt != null) {
            return Plan.NotOurs
        }
        val newItems = db.importDao().selectedItems(sessionId)
        val memberOnly =
            db.importDao().itemsByStatus(sessionId, listOf(ImportItemStatus.ALREADY_SUBSCRIBED))
        return Plan.Ready(
            options = options,
            work =
                (newItems.mapNotNull(::newWork) + memberOnly.mapNotNull(::memberWork))
                    .sortedBy { it.ordinal },
        )
    }

    private suspend fun commitReady(
        sessionId: Long,
        plan: Plan.Ready,
    ): Commit {
        // 05 §6 step 1: outside any Room transaction, inside the coordinator — selected items
        // whose originalUrl carried userinfo get their credentials stored against the ORIGINAL
        // request's origin (the host they were sent to; 03's same rule in subscribe).
        // AddInputNormalizer re-runs classify's unwrapping — feed:/pcast://podcast://itpc:// and
        // the subscribe pages — so its clean URL and extracted credentials decide the put;
        // splitUserInfo/origin on the raw wrapper URL never parse (a feed:/itpc: scheme is not
        // the bare http(s) they accept) and would silently drop the credentials.
        val puts = HashMap<Int, CredentialPut>()
        try {
            for (work in plan.work) {
                if (work !is Work.New) continue
                val normalized =
                    AddInputNormalizer.normalize(work.item.originalUrl) as? NormalizedInput.Url
                        ?: continue
                val credentials = normalized.credentials ?: continue
                val origin = UrlNormalizer.origin(normalized.url) ?: continue
                puts[work.ordinal] =
                    CredentialPut(
                        origin = origin,
                        rowId =
                            secrets.put(
                                origin,
                                credentials.username,
                                credentials.password.toCharArray(),
                            ),
                    )
            }

            val ctx = ChunkContext(resolveGroups(plan.options, plan.work), puts)
            // ≤ CHUNK items per write transaction (02); the empty plan still runs one
            // transaction so a deselect-everything confirm creates its proposed groups.
            for (chunk in plan.work.chunked(CHUNK).ifEmpty { listOf(emptyList()) }) {
                db.withWriteTransaction {
                    writeChunk(sessionId, plan.options, ctx, chunk)
                }
            }
            return Commit.READY_TO_FINISH
        } catch (t: Throwable) {
            // The put loop is inside this try: a later put throwing (or cancellation landing
            // mid-loop) must not strand the earlier ones — each collected put whose
            // referencing insert never landed goes back, unless a committed chunk (or
            // another podcast on a shared origin) references the row (03's rule). A chunk's
            // own rollback already protected the rows it wrote.
            withContext(NonCancellable) {
                for (put in puts.values) {
                    secrets.removeUnreferenced(db.credentialDao(), put.origin, put.rowId)
                }
            }
            throw t
        }
    }

    // --- Chunk transaction (02's "each chunk of ≤ 500 items is one write transaction") -----------

    private suspend fun writeChunk(
        sessionId: Long,
        options: ImportOptions,
        ctx: ChunkContext,
        work: List<Work>,
    ) {
        // A PREVIEW cancel/delete (or the expired-session sweep) between chunks cascades the
        // item rows this chunk would still write; [planLocked]'s snapshot cannot see it.
        // Re-check ownership inside the chunk's own transaction — writers serialize, so a
        // delete visible here already committed — rather than materialize podcasts, groups
        // and memberships no session tracks.
        if (db.importDao().sessionById(sessionId)?.state != ImportState.PREVIEW) {
            throw SessionLostException()
        }
        val now = clock.now()
        ctx.ensureGroups(now)
        val targetGroupId = ctx.targetGroupId(options)

        for (entry in work) {
            val item = entry.item
            when (entry) {
                is Work.New -> {
                    var podcastId = existingOwner(entry.feedKey)
                    val inserted = podcastId == null
                    if (podcastId == null) {
                        podcastId =
                            insertPending(
                                item,
                                entry.feedKey,
                                ctx.puts,
                                customTitle = options.itemCustomTitles[item.ordinal],
                                now,
                            )
                    }
                    if (inserted) {
                        if (options.notifyNewEpisodes) {
                            db.scopeSettingsDao().upsertPodcast(
                                PodcastSettingsEntity(
                                    podcastId = podcastId,
                                    o = ScopeOverrides(notifyNewEpisodes = true),
                                ),
                            )
                        }
                        db.importDao().markQueued(sessionId, item.ordinal, podcastId)
                    } else {
                        db.importDao().markAlreadySubscribed(sessionId, item.ordinal, podcastId)
                    }
                    recordAlias(item, entry.feedKey, podcastId, now)
                    attachMemberships(item, podcastId, ctx, targetGroupId, now)
                }

                is Work.MemberOnly -> {
                    val podcastId =
                        item.podcastId?.let { db.podcastDao().byId(it)?.id }
                            ?: entry.feedKey?.let { existingOwner(it) }
                            ?: continue
                    recordAlias(item, entry.feedKey, podcastId, now)
                    attachMemberships(item, podcastId, ctx, targetGroupId, now)
                }
            }
            // After the put pass (05 §6 step 3): no password survives in committed rows.
            db
                .importDao()
                .setItemOriginalUrl(sessionId, item.ordinal, Redactor.url(item.originalUrl))
        }

        db.groupDao().touchUpdatedAt(ctx.stampedGroupIds, now)
    }

    /** feedKey or alias owner inside the chunk transaction — classify's in-tx re-check (02). */
    private suspend fun existingOwner(feedKey: String): Long? =
        db.podcastDao().byFeedKey(feedKey)?.id ?: db.podcastDao().aliasOwner(feedKey)

    /**
     * 02's `INSERT OR IGNORE` loop: an ignored insert means a unique index raced — the feedKey
     * (or an alias row) wins reuse-as-subscribed; finding no owner means the `syncId` itself
     * collided, so only the UUID is regenerated. [SYNC_ID_RETRIES] bounds the retry before the
     * impossible is reported as a bug.
     */
    private suspend fun insertPending(
        item: ImportItemEntity,
        feedKey: String,
        puts: Map<Int, CredentialPut>,
        customTitle: String?,
        now: Long,
    ): Long {
        repeat(SYNC_ID_RETRIES) {
            val rowId =
                db.podcastDao().insertPodcastOrIgnore(pendingRow(item, feedKey, puts, customTitle, now))
            if (rowId != -1L) return rowId
            existingOwner(feedKey)?.let { return it }
        }
        error("podcast insert ignored $SYNC_ID_RETRIES× without a feedKey/alias owner")
    }

    private fun pendingRow(
        item: ImportItemEntity,
        feedKey: String,
        puts: Map<Int, CredentialPut>,
        customTitle: String?,
        now: Long,
    ): PodcastEntity {
        val feedUrl = checkNotNull(item.normalizedUrl) { "selected item without normalizedUrl" }
        return PodcastEntity(
            syncId = newSyncId(),
            sourceType = SourceType.RSS,
            feedUrl = feedUrl,
            feedKey = feedKey,
            title = item.title ?: feedUrl,
            customTitle = customTitle,
            artworkKey = ArtworkKeys.monogram(feedKey),
            status = PodcastStatus.PENDING_FIRST_FETCH,
            initialFetch = true,
            subscribedAt = now,
            nextRefreshAt = now,
            credentialId = puts[item.ordinal]?.rowId,
            guidCoverageSince = now,
        )
    }

    /**
     * The original identity becomes an `IMPORT` alias when it differs from the feed's own key
     * (02 step 3) — never when another podcast's feedKey already claims that identity.
     */
    private suspend fun recordAlias(
        item: ImportItemEntity,
        feedKey: String?,
        podcastId: Long,
        now: Long,
    ) {
        // A subscribe-page URI can contain encoded credentials; its wrapper is not a feed identity.
        val original = AddInputNormalizer.normalize(item.originalUrl) as? NormalizedInput.Url ?: return
        val originalKey = UrlNormalizer.forIdentity(original.url) ?: return
        if (originalKey == feedKey) return
        if (db.podcastDao().byFeedKey(originalKey) != null) return
        db.podcastDao().insertAliasOrIgnore(
            PodcastUrlAliasEntity(
                url = originalKey,
                podcastId = podcastId,
                reason = AliasReason.IMPORT,
                addedAt = now,
            ),
        )
    }

    /**
     * The item's resolved groups plus the session target, in file order — `INSERT OR IGNORE`
     * makes replays and concurrent subscribers idempotent. Each insert lands at the tail of its
     * group ([OrderKeys.after] the group's last member key); groups that gained a member are
     * stamped `updatedAt` (02's membership-write rule).
     */
    private suspend fun attachMemberships(
        item: ImportItemEntity,
        podcastId: Long,
        ctx: ChunkContext,
        targetGroupId: Long?,
        now: Long,
    ) {
        val groupIds = LinkedHashSet<Long>()
        ctx.groups.itemKeys[item.ordinal]?.mapNotNullTo(groupIds) { ctx.groups.ids[it] }
        targetGroupId?.let(groupIds::add)
        for (groupId in groupIds) {
            val key = orderKeys.after(ctx.memberTail(groupId))
            val inserted =
                db
                    .groupDao()
                    .insertMember(
                        PodcastGroupMemberEntity(
                            groupId = groupId,
                            podcastId = podcastId,
                            orderKey = key,
                            addedAt = now,
                        ),
                    )
            if (inserted != -1L) {
                ctx.memberKeys[groupId] = key
                ctx.stampedGroupIds += groupId
            }
        }
    }

    // --- Group resolution (05 §4 + §6 step 3's "create missing groups") --------------------------

    /**
     * Turns the stored options into creation specs keyed by resolved `nameKey`, in proposal
     * order (then encounter order for a group name no proposal covers). Renames are looked up by
     * the proposal's original nameKey, validated again here — `updateOptions` guards, but the
     * stored row is the contract. Exclusions are tested on the original nameKey too.
     */
    private fun resolveGroups(
        options: ImportOptions,
        work: List<Work>,
    ): ResolvedGroups {
        val groups = LinkedHashMap<String, ResolvedGroup>()
        val proposals = options.sourceGroups.associateBy { it.nameKey }

        fun resolve(
            originalKey: String,
            fallbackName: String,
            colorArgb: Int?,
            iconKey: String?,
        ): ResolvedGroup? {
            if (originalKey in options.excludedGroupKeys) return null
            val renamed = options.renamedGroups[originalKey]?.let { GroupNames.truncate(it)?.name }
            val valid = GroupNames.truncate(renamed ?: fallbackName) ?: return null
            return ResolvedGroup(
                name = valid.name,
                nameKey = valid.nameKey,
                colorArgb = colorArgb?.let(GroupPalette::opaque),
                iconKey = iconKey?.takeIf(GroupIcons::isValid),
            )
        }

        if (options.importGroups) {
            for (source in options.sourceGroups) {
                resolve(source.nameKey, source.name, source.colorArgb, source.iconKey)?.let {
                    groups.putIfAbsent(it.nameKey, it)
                }
            }
        }

        var targetKey: String? = null
        if (options.targetGroupId == null) {
            GroupNames.truncate(options.targetGroupName ?: "")?.let { valid ->
                targetKey = valid.nameKey
                groups.putIfAbsent(valid.nameKey, ResolvedGroup(valid.name, valid.nameKey, null, null))
            }
        }

        val itemKeys = HashMap<Int, List<String>>()
        for (entry in work) {
            val keys = LinkedHashSet<String>()
            for (raw in json.decodeFromString<List<String>>(entry.item.groupNamesJson)) {
                if (!options.importGroups) break
                val valid = GroupNames.validate(raw) as? GroupNames.Validation.Valid ?: continue
                val proposal = proposals[valid.nameKey]
                resolve(valid.nameKey, proposal?.name ?: valid.name, proposal?.colorArgb, proposal?.iconKey)
                    ?.let {
                        groups.putIfAbsent(it.nameKey, it)
                        keys += it.nameKey
                    }
            }
            itemKeys[entry.item.ordinal] = keys.toList()
        }
        return ResolvedGroups(
            specs = groups,
            targetId = options.targetGroupId,
            targetKey = targetKey,
            itemKeys = itemKeys,
        )
    }

    /**
     * Per-commit mutable state carried across chunks: resolved groups (specs in creation order,
     * ids once ensured), the session's credential puts by item ordinal, member-order tails per
     * group and the groups this commit stamped.
     */
    private inner class ChunkContext(
        val groups: ResolvedGroups,
        val puts: MutableMap<Int, CredentialPut> = mutableMapOf(),
    ) {
        val stampedGroupIds = LinkedHashSet<Long>()
        val memberKeys = HashMap<Long, String?>()
        private var lastGroupKey: String? = null
        private var lastGroupKeyRead = false

        /**
         * `INSERT OR IGNORE` every resolved group not yet ensured — a `nameKey` hit reuses the
         * existing group (its look stays untouched); created groups append after the list's tail
         * in proposal order with a fresh UUIDv4, source metadata (opaque-forced) or the next
         * palette colour (05 §6 step 3).
         */
        suspend fun ensureGroups(now: Long) {
            for (spec in groups.specs.values) {
                if (groups.ids.containsKey(spec.nameKey)) continue
                db.groupDao().groupByNameKey(spec.nameKey)?.let {
                    groups.ids[spec.nameKey] = it.id
                    continue
                }
                val orderKey = orderKeys.after(lastGroupOrderKey())
                val rowId =
                    db.groupDao().insertGroupOrIgnore(
                        PodcastGroupEntity(
                            uuid = Uuid.random().toString(),
                            name = spec.name,
                            nameKey = spec.nameKey,
                            orderKey = orderKey,
                            colorArgb = spec.colorArgb ?: GroupPalette.nextColor(db.groupDao().groupColors()),
                            iconKey = spec.iconKey,
                            lastViewedAt = now,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                groups.ids[spec.nameKey] =
                    if (rowId != -1L) {
                        lastGroupKey = orderKey
                        rowId
                    } else {
                        // Ignored means the unique nameKey raced — reuse its owner; nothing
                        // found means the fresh `uuid` itself collided, a bug (02).
                        db.groupDao().groupByNameKey(spec.nameKey)?.id
                            ?: error("group insert ignored with no nameKey owner")
                    }
            }
        }

        private suspend fun lastGroupOrderKey(): String? {
            if (!lastGroupKeyRead) {
                lastGroupKey = db.groupDao().lastGroupOrderKey()
                lastGroupKeyRead = true
            }
            return lastGroupKey
        }

        /** The session's target: an existing group id or the resolved target group (created). */
        suspend fun targetGroupId(options: ImportOptions): Long? =
            options.targetGroupId?.let { db.groupDao().groupById(it)?.id }
                ?: groups.targetKey?.let { groups.ids[it] }

        /**
         * A group's member tail — seeded from the table on first touch so a group the library
         * already holds appends after its last member rather than restarting its key range.
         */
        suspend fun memberTail(groupId: Long): String? {
            if (groupId !in memberKeys) {
                memberKeys[groupId] = db.groupDao().lastMemberOrderKey(groupId)
            }
            return memberKeys[groupId]
        }
    }

    // --- Plan / item shapes -----------------------------------------------------------------------

    private sealed interface Plan {
        /** No session row — `SessionGone`. */
        data object Gone : Plan

        /** The session is past `PREVIEW` — a replayed confirm does nothing. */
        data object Replay : Plan

        /** Backup format or restore intent: the restore path owns it, this committer refuses. */
        data object NotOurs : Plan

        data class Ready(
            val options: ImportOptions,
            val work: List<Work>,
        ) : Plan
    }

    private sealed interface Work {
        val item: ImportItemEntity
        val ordinal: Int
            get() = item.ordinal

        /** A selected new RSS item — subscribes unless an owner is found in-transaction. */
        data class New(
            override val item: ImportItemEntity,
            val feedKey: String,
        ) : Work

        /** An `ALREADY_SUBSCRIBED` row — memberships only; the podcast itself is never touched. */
        data class MemberOnly(
            override val item: ImportItemEntity,
            val feedKey: String?,
        ) : Work
    }

    private fun newWork(item: ImportItemEntity): Work.New? {
        if (item.kind != ImportItemKind.RSS) return null
        val feedKey = item.normalizedUrl?.let(UrlNormalizer::forIdentity) ?: return null
        return Work.New(item, feedKey)
    }

    private fun memberWork(item: ImportItemEntity): Work.MemberOnly? {
        // YouTube rows are gated until M8: they are never committed into subscriptions or
        // re-marked — only a feedKey-carrying RSS row can resolve memberships.
        if (item.kind != ImportItemKind.RSS) return null
        return Work.MemberOnly(item, item.normalizedUrl?.let(UrlNormalizer::forIdentity))
    }

    /** One stored credential keyed to its item: origin + the `credential` row [SecretStore.put] made. */
    private data class CredentialPut(
        val origin: String,
        val rowId: Long?,
    )

    private data class ResolvedGroup(
        val name: String,
        val nameKey: String,
        val colorArgb: Int?,
        val iconKey: String?,
    )

    private data class ResolvedGroups(
        /** resolvedNameKey → creation spec, in creation order. */
        val specs: LinkedHashMap<String, ResolvedGroup>,
        /** An existing group the session targets ("Put everything into group…" by id). */
        val targetId: Long?,
        /** The resolved nameKey of a named target group (created like any proposal). */
        val targetKey: String?,
        /** Item ordinal → the resolvedNameKeys its file groups landed on, in file order. */
        val itemKeys: Map<Int, List<String>>,
        /** resolvedNameKey → ensured `podcast_group.id`, filled by the chunk transactions. */
        val ids: MutableMap<String, Long> = mutableMapOf(),
    )

    /**
     * The session left `PREVIEW` while chunks were still writing (a PREVIEW cancel/delete or
     * the expiry sweep). The throwing chunk rolls back; earlier committed chunks stand.
     */
    private class SessionLostException : Exception()

    /**
     * [commitLocked]'s verdict: [GONE] has no committable session (missing, backup-format or
     * restore-intent — the typed `SessionGone` covers "no import commit exists"); [REPLAYED] is
     * the idempotent no-op of a session already past `PREVIEW` (or one a racing cancel stole);
     * [COMMITTED] means this call's own transition write landed and post-commit effects run.
     */
    private enum class Commit { GONE, REPLAYED, READY_TO_FINISH }

    private companion object {
        const val TAG = "ImportCommitter"

        /** 02 Import commit: at most 500 items inside one write transaction. */
        const val CHUNK = 500

        /** Bounds the `syncId` collision retry — a collision is a 1-in-2^122 event. */
        const val SYNC_ID_RETRIES = 3

        val json = Json
    }
}
