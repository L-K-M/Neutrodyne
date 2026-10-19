// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.backup

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.artwork.ArtworkKeys
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.data.repo.GroupMerger
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastEntity
import ch.lkmc.neutrodyne.core.database.PodcastGroupEntity
import ch.lkmc.neutrodyne.core.database.PodcastGroupMemberEntity
import ch.lkmc.neutrodyne.core.database.PodcastGroupSettingsEntity
import ch.lkmc.neutrodyne.core.database.PodcastSettingsEntity
import ch.lkmc.neutrodyne.core.database.PodcastUrlAliasEntity
import ch.lkmc.neutrodyne.core.database.ScopeOverrides
import ch.lkmc.neutrodyne.core.domain.CredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import ch.lkmc.neutrodyne.core.domain.OrderKeys
import ch.lkmc.neutrodyne.core.domain.SecretStore
import ch.lkmc.neutrodyne.core.domain.UnsubscribeUseCase
import ch.lkmc.neutrodyne.core.model.AliasReason
import ch.lkmc.neutrodyne.core.model.DeleteAfter
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.GroupKind
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.model.MemberSource
import ch.lkmc.neutrodyne.core.model.MergePolicy
import ch.lkmc.neutrodyne.core.model.MergeSummary
import ch.lkmc.neutrodyne.core.model.NetworkPolicy
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.RestoreCategory
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.feeds.backup.CredentialV1
import ch.lkmc.neutrodyne.feeds.backup.GroupV1
import ch.lkmc.neutrodyne.feeds.backup.LibraryV1
import ch.lkmc.neutrodyne.feeds.backup.MemberV1
import ch.lkmc.neutrodyne.feeds.backup.OverridesV1
import ch.lkmc.neutrodyne.feeds.backup.PodcastV1
import ch.lkmc.neutrodyne.feeds.groups.GroupNames
import dev.zacsweers.metro.Inject
import kotlin.uuid.Uuid

/**
 * 05's `RestoreMerger`, library phase: steps 2–4 of the Restore algorithm — the Replace
 * pre-step (local-only podcasts unsubscribed through `UnsubscribeUseCase`, local-only groups
 * deleted, matched podcasts' state/position rows and the Up next/session context cleared
 * per [RestoreCategory]), the credential step and transaction A (groups, podcasts, aliases,
 * scope settings, memberships). Episode lines, `queue.json` and `settings.json` are the
 * later phases; the `LINK_*` policies are MS2's first-link reuse of this routine and are
 * refused until then.
 *
 * The credential step holds `CredentialCommitCoordinator` from the `SecretStore.put`s until
 * transaction A commits, so 02's maintenance sweep cannot delete a just-stored credential in
 * the gap before `podcast.credentialId` references it (03's commit ordering). Secret writes
 * stay outside the Room transaction; [GroupMerger] row merges run after it commits.
 */
@Inject
internal class RestoreMerger(
    private val db: NeutrodyneDatabase,
    private val podcasts: PodcastMatcher,
    private val writes: RestoreWrites,
    private val groupMerger: GroupMerger,
    private val unsubscribe: UnsubscribeUseCase,
    private val secrets: SecretStore,
    private val coordinator: CredentialCommitCoordinator,
    private val channelSync: GroupChannelSync,
    private val orderKeys: OrderKeys,
    private val clock: Clock,
) {
    private val backupDao get() = db.backupDao()
    private val podcastDao get() = db.podcastDao()
    private val groupDao get() = db.groupDao()
    private val scopeDao get() = db.scopeSettingsDao()

    /**
     * Writes [staged]'s library rows under [policy]'s Merge and Replace rules; [progress]
     * reports `(done, total)` once per staged podcast and canonical group. `LINK_*` policies
     * are not supported in M3. Returns the [LibraryMergeResult] the episode phase must
     * attach through — its `p`/group-`uuid` maps are the settled identities: the matches
     * ran before any write, so live re-derivation cannot reproduce them once a later
     * staged row commits.
     */
    suspend fun mergeLibrary(
        staged: StagedLibrary,
        policy: MergePolicy,
        categories: Set<RestoreCategory>,
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): LibraryMergeResult {
        require(policy == MergePolicy.RESTORE_MERGE || policy == MergePolicy.RESTORE_REPLACE) {
            "mergeLibrary supports RESTORE_MERGE and RESTORE_REPLACE; $policy lands with MS2"
        }
        val library = staged.library()
        val now = clock.now()

        // Matching runs before any write: the Replace pre-step needs the matched sets to
        // decide what is local-only, and the matches feed transaction A unchanged.
        val stagedGroups = foldStagedGroups(library.groups)
        val stagedPodcasts = library.podcasts.distinctBy { it.key }
        val podcastMatches = matchStagedPodcasts(stagedPodcasts)
        val matchedGroupIds = matchStagedGroups(stagedGroups)

        // Replace's order keys are settled before the destructive pre-step: every staged key
        // is validated and the rewrite fallback chosen here, so a malformed `orderKey`/`ok`
        // can neither persist nor abort the restore after the unsubscribes have run.
        val orderKeyPlan =
            if (policy == MergePolicy.RESTORE_REPLACE) {
                planReplaceOrderKeys(stagedGroups)
            } else {
                ReplaceOrderKeys(emptyMap(), emptyMap())
            }

        val deletedGroupUuids =
            if (policy == MergePolicy.RESTORE_REPLACE) {
                replacePreStep(podcastMatches.values.filterNotNull().toSet(), matchedGroupIds, categories, now)
            } else {
                emptyList()
            }

        val credentialIds = HashMap<String, Long>()
        val podcastIdByKey = HashMap<String, Long>()
        val insertedPodcastIds = HashSet<Long>()
        val uuidChanges = ArrayList<UuidChange>()
        var podcastsAdded = 0
        var podcastsMatched = 0
        var groupsAdded = 0
        var groupsMatched = 0
        var done = 0
        // Folded groups write no row of their own — their staged content went to the claimer.
        val total = stagedPodcasts.size + stagedGroups.count { it.foldedInto == null }

        coordinator.withCredentialCommit {
            restoreCredentials(library.credentials, policy, credentialIds)
            db.withWriteTransaction {
                // Replace frees the nameKeys of every surviving local group first, so a pair
                // of groups swapping names cannot hit the unique index mid-rewrite, then
                // clears the join table — surviving memberships are rebuilt exactly (05).
                if (policy == MergePolicy.RESTORE_REPLACE) {
                    val parked =
                        stagedGroups.flatMap { group ->
                            listOfNotNull(group.localId) + group.absorbedLocalIds
                        }
                    backupDao.parkGroupNameKeys(parked)
                    backupDao.clearGroupMembers()
                }

                // Merge appends new groups after the local ones in backup order (05).
                var lastGroupKey: String? = groupDao.lastGroupOrderKey()
                for (group in stagedGroups) {
                    if (group.foldedInto != null) continue
                    val localId =
                        group.localId
                            ?: run {
                                val orderKey =
                                    if (policy == MergePolicy.RESTORE_REPLACE) {
                                        orderKeyPlan.groups.getValue(group)
                                    } else {
                                        orderKeys.after(lastGroupKey).also { lastGroupKey = it }
                                    }
                                groupsAdded++
                                insertGroup(group, orderKey, staged.createdAtMs, now, policy)
                            }
                    group.resolvedId = localId
                    if (group.localId != null) {
                        groupsMatched++
                        if (policy == MergePolicy.RESTORE_REPLACE) {
                            replaceGroup(
                                group,
                                localId,
                                orderKeyPlan.groups.getValue(group),
                                now,
                                uuidChanges,
                            )
                        }
                    }
                    writeGroupSettings(localId, group.primary.settings, policy)
                    progress(++done, total)
                }

                for (podcast in stagedPodcasts) {
                    var localId = podcastMatches[podcast]
                    // The staged list was deduped by key; a same-key row appearing since the
                    // match pass (or a second staged copy) still resolves to the live row.
                    if (localId == null) localId = podcastDao.byFeedKey(podcast.key)?.id
                    if (localId == null) {
                        localId = insertPodcast(podcast, now, credentialIds)
                        podcastsAdded++
                        insertedPodcastIds += localId
                    } else {
                        podcastsMatched++
                        applyMatchedPodcast(localId, podcast, policy)
                    }
                    podcastIdByKey[podcast.key] = localId
                    for (alias in podcast.aliases) podcastIdByKey.putIfAbsent(alias, localId)
                    recordAmbiguousGuids(localId, podcast)
                    restoreAliases(localId, podcast.aliases, staged.createdAtMs)
                    writePodcastSettings(localId, podcast.settings, policy)
                    progress(++done, total)
                }

                writeMembers(stagedGroups, policy, staged.createdAtMs, podcastIdByKey, now, orderKeyPlan.members)
            }
        }

        // Post-commit: folded groups' local rows merge into their survivors (one committed
        // transaction and channel sweep each), then the channel-only effects of Replace.
        for (group in stagedGroups) {
            if (group.foldedInto != null) continue
            val survivor = group.resolvedId ?: continue
            for (loser in group.absorbedLocalIds) {
                if (loser == survivor) continue
                groupMerger.merge(loserId = loser, survivorId = survivor)
            }
        }
        for (change in uuidChanges) {
            suspendRunCatching { channelSync.onUuidChanged(change.from, change.to) }
                .onFailure { Log.w(TAG, it) { "Group channel carry-over failed" } }
        }
        for (uuid in deletedGroupUuids) {
            suspendRunCatching { channelSync.onDeleted(uuid) }
                .onFailure { Log.w(TAG, it) { "Group channel delete failed" } }
        }

        // Transaction A's settled staged-`uuid` → survivor map: canonical, folded and
        // absorbed staged uuids all land on their root's resolved row (Merge's fresh
        // inserts included — the staged uuid never exists locally there).
        val groupIdByUuid = LinkedHashMap<String, Long>()
        for (group in stagedGroups) {
            val survivor =
                checkNotNull(group.root().resolvedId) {
                    "staged group '${group.primary.uuid}' left transaction A unresolved"
                }
            groupIdByUuid[group.primary.uuid] = survivor
            for (absorbed in group.absorbed) groupIdByUuid[absorbed.uuid] = survivor
        }

        return LibraryMergeResult(
            summary =
                MergeSummary(
                    podcastsAdded = podcastsAdded,
                    podcastsMatched = podcastsMatched,
                    groupsAdded = groupsAdded,
                    groupsMatched = groupsMatched,
                    episodeLines = 0,
                    stubs = 0,
                    unmatched = 0,
                    skippedLines = 0,
                ),
            podcastIdByKey = podcastIdByKey.toMap(),
            groupIdByUuid = groupIdByUuid,
            insertedPodcastIds = insertedPodcastIds,
        )
    }

    /**
     * 05 step 5's Replace dry run: the local row ids the backup's library would claim —
     * matched by the same fold and match ladders [mergeLibrary] runs before its writes, so
     * the preview's "not in the backup" counts agree with a real Replace. Read-only on the
     * database (the fold state lives on per-call `StagedGroup`s); the caller holds the read
     * snapshot and counts the local rows *not* in the result. [library] arrives already
     * validated by `BackupStager`.
     */
    suspend fun matchedLocalIds(library: LibraryV1): MatchedLocalIds {
        val stagedGroups = foldStagedGroups(library.groups)
        val stagedPodcasts = library.podcasts.distinctBy { it.key }
        return MatchedLocalIds(
            podcastIds = matchStagedPodcasts(stagedPodcasts).values.filterNotNullTo(HashSet()),
            groupIds = matchStagedGroups(stagedGroups),
        )
    }

    /**
     * 05 step 2 (Replace): local podcasts no backup line matched are unsubscribed through the
     * use case (cascade, credential sweep and reschedule, D24); local groups no staged group
     * matched are deleted without undo. HISTORY clears the matched podcasts' state and
     * position rows — one transaction per podcast — so the backup's rows can land verbatim;
     * UP_NEXT clears `queue_entry` and the session context.
     */
    private suspend fun replacePreStep(
        matchedPodcastIds: Set<Long>,
        matchedGroupIds: Set<Long>,
        categories: Set<RestoreCategory>,
        now: Long,
    ): List<String> {
        val localOnly = podcastDao.allForExport().map { it.id }.filter { it !in matchedPodcastIds }
        if (localOnly.isNotEmpty()) unsubscribe(localOnly)

        val deletedUuids = mutableListOf<String>()
        db.withWriteTransaction {
            for (group in groupDao.groupsInOrder()) {
                if (group.id in matchedGroupIds) continue
                groupDao.clearGroupContext(group.id, now)
                groupDao.deleteGroup(group.id)
                deletedUuids += group.uuid
            }
        }

        if (RestoreCategory.HISTORY in categories) {
            for (podcastId in matchedPodcastIds) {
                db.withWriteTransaction {
                    backupDao.deleteStateOfPodcast(podcastId)
                    backupDao.deletePositionsOfPodcast(podcastId)
                }
            }
        }

        if (RestoreCategory.UP_NEXT in categories) {
            db.withWriteTransaction {
                db.queueDao().clear()
                db.playSessionDao().clearContext(now)
            }
        }
        return deletedUuids
    }

    /**
     * 05 step 3: `SecretStore.put` per backup credential under the coordinator — Merge only
     * for origins without a local credential, Replace per origin. The coordinator stays held
     * through transaction A so the stored row survives until a podcast references it.
     */
    private suspend fun restoreCredentials(
        credentials: List<CredentialV1>,
        policy: MergePolicy,
        restored: MutableMap<String, Long>,
    ) {
        for (credential in credentials) {
            if (policy == MergePolicy.RESTORE_MERGE && secrets.get(credential.origin) != null) continue
            secrets
                .put(credential.origin, credential.username, credential.password.toCharArray())
                ?.let { restored[credential.origin] = it }
        }
    }

    /**
     * A staged group's name through `GroupNames` (invalid → truncated, empty → "Group"), and
     * the staged groups' `nameKey` fold: staged groups sharing one merge into the first —
     * members unite, the folded-away groups' local rows are joined to the canonical one for
     * the post-commit `GroupMerger` pass.
     */
    private class StagedGroup(
        val primary: GroupV1,
        val name: String,
        val nameKey: String,
        val members: LinkedHashMap<String, MemberV1>,
    ) {
        val absorbed = mutableListOf<GroupV1>()

        /** Local row claimed by the match ladder; null = the group inserts. */
        var localId: Long? = null

        /** True when the uuid step missed and `nameKey` claimed the row (uuid adoption, Replace). */
        var matchedByNameKey = false

        /** Local rows a folded staged group's uuid still claims — `GroupMerger` inputs. */
        val absorbedLocalIds = mutableListOf<Long>()

        /** Set when this canonical's match was already claimed by an earlier staged group. */
        var foldedInto: StagedGroup? = null

        /** The local id the group's writes resolve to — set during transaction A. */
        var resolvedId: Long? = null

        fun root(): StagedGroup = foldedInto?.root() ?: this
    }

    /** Replace's settled order keys: per canonical group, and per group's member list. */
    private class ReplaceOrderKeys(
        val groups: Map<StagedGroup, String>,
        val members: Map<StagedGroup, List<String>>,
    )

    private fun foldStagedGroups(staged: List<GroupV1>): List<StagedGroup> {
        val folded = LinkedHashMap<String, StagedGroup>()
        for (group in staged) {
            val valid = GroupNames.truncate(group.name) ?: GroupNames.truncate(DEFAULT_GROUP_NAME)!!
            val existing = folded[valid.nameKey]
            if (existing == null) {
                folded[valid.nameKey] =
                    StagedGroup(
                        primary = group,
                        name = valid.name,
                        nameKey = valid.nameKey,
                        members = LinkedHashMap(group.members.associateBy { it.podcastKey }),
                    )
            } else {
                for (member in group.members) existing.members.putIfAbsent(member.podcastKey, member)
                existing.absorbed += group
            }
        }
        return folded.values.toList()
    }

    private suspend fun matchStagedPodcasts(staged: List<PodcastV1>): Map<PodcastV1, Long?> =
        staged.associateWith { podcasts.match(it.key, it.aliases, it.podcastGuid) }

    /**
     * The 02 group ladder per canonical staged group (`uuid`, then `nameKey`), then the uuid
     * evidence of the folded-away groups. A local row claimed twice makes the later staged
     * group fold into the earlier claimer; a folded group's local row stays claimed and is
     * merged post-commit. Returns every local group id the backup claims.
     */
    private suspend fun matchStagedGroups(groups: List<StagedGroup>): Set<Long> {
        val matched = HashSet<Long>()
        val claimed = HashMap<Long, StagedGroup>()
        val uuidSeen = HashMap<String, StagedGroup>()
        for (group in groups) {
            // A second staged group on one uuid folds into the first like a shared nameKey —
            // members unite — because the file cannot restore two rows to one uuid (the
            // unique index leaves a duplicate insert no way to land). Its nameKey can still
            // claim a local row for the post-commit `GroupMerger` pass.
            val earlier = uuidSeen[group.primary.uuid]?.root()
            if (earlier != null) {
                for (member in group.members.values) {
                    earlier.members.putIfAbsent(member.podcastKey, member)
                }
                group.foldedInto = earlier
                val localId = groupDao.groupByNameKey(group.nameKey)?.id
                if (localId != null) {
                    matched += localId
                    if (claimed.putIfAbsent(localId, earlier) == null) {
                        earlier.absorbedLocalIds += localId
                    }
                }
                continue
            }
            uuidSeen[group.primary.uuid] = group
            var localId = backupDao.matchGroupByUuid(group.primary.uuid)
            var viaNameKey = false
            if (localId == null) {
                localId = groupDao.groupByNameKey(group.nameKey)?.id
                viaNameKey = localId != null
            }
            if (localId == null) continue
            matched += localId
            val claimer = claimed[localId]
            if (claimer == null) {
                claimed[localId] = group
                group.localId = localId
                group.matchedByNameKey = viaNameKey
            } else {
                for (member in group.members.values) claimer.members.putIfAbsent(member.podcastKey, member)
                group.foldedInto = claimer
            }
        }
        for (group in groups) {
            val survivor = group.root()
            for (absorbed in group.absorbed) {
                val localId = backupDao.matchGroupByUuid(absorbed.uuid) ?: continue
                matched += localId
                if (localId == survivor.localId || localId in claimed) continue
                claimed[localId] = survivor
                survivor.absorbedLocalIds += localId
            }
        }
        return matched
    }

    /** Inserts a group the backup knows and no local row matched (05 transaction A). */
    private suspend fun insertGroup(
        group: StagedGroup,
        orderKey: String,
        createdAtMs: Long,
        now: Long,
        policy: MergePolicy,
    ): Long {
        val primary = group.primary
        warnSmartGroup(primary)
        return groupDao.insert(
            PodcastGroupEntity(
                // 05 IDs: a restore adopts a backup uuid only in Replace — under Merge a
                // locally-created group keeps a fresh one.
                uuid = if (policy == MergePolicy.RESTORE_REPLACE) primary.uuid else Uuid.random().toString(),
                name = group.name,
                nameKey = group.nameKey,
                orderKey = orderKey,
                colorArgb = primary.colorArgb,
                iconKey = primary.iconKey,
                kind = GroupKind.MANUAL,
                ruleJson = primary.rule?.toString(),
                feedOrder = enumNamed<FeedOrder>(primary.feedOrder) ?: FeedOrder.NEWEST_FIRST,
                playOrder = enumNamed<FeedOrder>(primary.playOrder) ?: FeedOrder.NEWEST_FIRST,
                filterFlags = primary.filterFlags,
                mediaFilter = enumNamed<MediaFilter>(primary.mediaFilter) ?: MediaFilter.ALL,
                hideOlderThanDays = primary.hideOlderThanDays,
                showAsTab = primary.showAsTab,
                createdAt = primary.createdAt ?: createdAtMs,
                updatedAt = now,
            ),
        )
    }

    /**
     * Replace's "matched groups take backup values": the row is rewritten to the staged
     * group; a `nameKey` match adopts the backup uuid (the uuid step missed, so it is free)
     * and the channel carry-over is recorded for after the commit.
     */
    private suspend fun replaceGroup(
        group: StagedGroup,
        localId: Long,
        orderKey: String,
        now: Long,
        uuidChanges: MutableList<UuidChange>,
    ) {
        val local = groupDao.groupById(localId) ?: return
        val primary = group.primary
        warnSmartGroup(primary)
        val uuid = if (group.matchedByNameKey) primary.uuid else local.uuid
        if (uuid != local.uuid) uuidChanges += UuidChange(local.uuid, uuid)
        backupDao.updateGroup(
            local.copy(
                uuid = uuid,
                name = group.name,
                nameKey = group.nameKey,
                orderKey = orderKey,
                colorArgb = primary.colorArgb,
                iconKey = primary.iconKey,
                kind = GroupKind.MANUAL,
                ruleJson = primary.rule?.toString(),
                feedOrder = enumNamed<FeedOrder>(primary.feedOrder) ?: FeedOrder.NEWEST_FIRST,
                playOrder = enumNamed<FeedOrder>(primary.playOrder) ?: FeedOrder.NEWEST_FIRST,
                filterFlags = primary.filterFlags,
                mediaFilter = enumNamed<MediaFilter>(primary.mediaFilter) ?: MediaFilter.ALL,
                hideOlderThanDays = primary.hideOlderThanDays,
                showAsTab = primary.showAsTab,
                createdAt = primary.createdAt ?: local.createdAt,
                updatedAt = now,
            ),
        )
    }

    /** 05 transaction A's podcast insert (02 Restore matching: pending, `initialFetch`, the backup's `subscribedAt`/`syncId`). */
    private suspend fun insertPodcast(
        podcast: PodcastV1,
        now: Long,
        credentialIds: Map<String, Long>,
    ): Long {
        val credentialId = credentialIdFor(podcast.credentialOrigin, credentialIds)
        return podcastDao.insertPodcast(
            PodcastEntity(
                syncId = podcasts.syncIdForInsert(podcast.syncId),
                sourceType = enumNamed<SourceType>(podcast.source) ?: SourceType.RSS,
                feedUrl = podcast.feedUrl,
                feedKey = podcast.key,
                youtubeChannelId = podcast.youtubeChannelId,
                youtubeVariants = podcast.youtubeVariants ?: DEFAULT_YOUTUBE_VARIANTS,
                podcastGuid = podcast.podcastGuid,
                title = podcast.title.ifBlank { podcast.feedUrl },
                link = podcast.link,
                artworkUrl = podcast.artworkUrl,
                artworkKey =
                    podcast.artworkUrl?.let(ArtworkKeys::forUrl) ?: ArtworkKeys.monogram(podcast.key),
                customTitle = podcast.customTitle,
                includeInAll = podcast.includeInAll,
                episodeOrder = enumNamed<FeedOrder>(podcast.episodeOrder),
                status = PodcastStatus.PENDING_FIRST_FETCH,
                initialFetch = true,
                subscribedAt = podcast.subscribedAt,
                nextRefreshAt = now,
                needsCredentials = podcast.credentialOrigin != null && credentialId == null,
                credentialId = credentialId,
                // D98: a row this build inserts is covered from insertion on.
                guidCoverageSince = now,
            ),
        )
    }

    /** The podcast's credential origin → the restored row, else a live local row for it. */
    private suspend fun credentialIdFor(
        origin: String?,
        restored: Map<String, Long>,
    ): Long? {
        if (origin == null) return null
        return restored[origin] ?: db
            .credentialDao()
            .byOrigin(origin)
            .minByOrNull { it.id }
            ?.id
    }

    /** 05's user-column row for a matched podcast: Merge keeps local, Replace takes the backup's. */
    private suspend fun applyMatchedPodcast(
        localId: Long,
        podcast: PodcastV1,
        policy: MergePolicy,
    ) {
        when (policy) {
            MergePolicy.RESTORE_MERGE -> {
                if (podcast.customTitle != null && podcastDao.byId(localId)?.customTitle == null) {
                    podcastDao.setCustomTitle(localId, podcast.customTitle)
                }
            }

            MergePolicy.RESTORE_REPLACE -> {
                backupDao.replacePodcastUserFields(
                    podcastId = localId,
                    customTitle = podcast.customTitle,
                    includeInAll = podcast.includeInAll,
                    episodeOrder = enumNamed<FeedOrder>(podcast.episodeOrder),
                    youtubeVariants = podcast.youtubeVariants ?: DEFAULT_YOUTUBE_VARIANTS,
                )
            }

            // LINK_* never reaches a write — mergeLibrary refuses those policies.
            else -> {}
        }
    }

    /**
     * D98: the backup's KNOWN_AMBIGUOUS set unions into the local provenance — a restore can
     * only add ambiguity evidence, never independence (`RestoreWrites.recordAmbiguousGuids`).
     */
    private suspend fun recordAmbiguousGuids(
        localId: Long,
        podcast: PodcastV1,
    ) {
        val guids = podcast.guidAmbiguous?.toSet() ?: return
        writes.recordAmbiguousGuids(localId, guids)
    }

    /** 05: aliases `INSERT OR IGNORE` with reason `RESTORE`, never equal to any `feedKey`. */
    private suspend fun restoreAliases(
        localId: Long,
        aliases: List<String>,
        addedAt: Long,
    ) {
        val rows =
            aliases.distinct().mapNotNull { url ->
                if (podcastDao.byFeedKey(url) != null || podcastDao.aliasOwner(url) != null) {
                    return@mapNotNull null
                }
                PodcastUrlAliasEntity(url, localId, AliasReason.RESTORE, addedAt)
            }
        if (rows.isNotEmpty()) podcastDao.insertAliases(rows)
    }

    /** `podcast_settings`/`podcast_group_settings` (05): the per-column Merge / verbatim Replace rule. */
    private suspend fun writePodcastSettings(
        podcastId: Long,
        staged: OverridesV1?,
        policy: MergePolicy,
    ) = writeScopeSettings(
        local = scopeDao.forPodcast(podcastId)?.o,
        staged = staged,
        policy = policy,
        upsert = { scopeDao.upsertPodcast(PodcastSettingsEntity(podcastId, it)) },
        delete = { scopeDao.deletePodcast(podcastId) },
    )

    private suspend fun writeGroupSettings(
        groupId: Long,
        staged: OverridesV1?,
        policy: MergePolicy,
    ) = writeScopeSettings(
        local = scopeDao.forGroup(groupId)?.o,
        staged = staged,
        policy = policy,
        upsert = { scopeDao.upsertGroup(PodcastGroupSettingsEntity(groupId, it)) },
        delete = { scopeDao.deleteGroup(groupId) },
    )

    /**
     * Merge: a local non-null column wins, else the backup's; Replace: the backup row replaces
     * the local row (an absent/empty staged row deletes it). LINK_* never reaches a write —
     * mergeLibrary refuses those policies.
     */
    private suspend fun writeScopeSettings(
        local: ScopeOverrides?,
        staged: OverridesV1?,
        policy: MergePolicy,
        upsert: suspend (ScopeOverrides) -> Unit,
        delete: suspend () -> Unit,
    ) {
        when (policy) {
            MergePolicy.RESTORE_MERGE -> {
                if (staged == null) return
                val merged = mergeOverrides(local, staged.toScopeOverrides())
                if (merged != local && merged != ScopeOverrides()) upsert(merged)
            }

            MergePolicy.RESTORE_REPLACE -> {
                val overrides = staged?.toScopeOverrides() ?: ScopeOverrides()
                if (overrides == ScopeOverrides()) delete() else upsert(overrides)
            }

            else -> {}
        }
    }

    /**
     * Memberships (05): Merge unions with `OrderKey.after(last)` keys in backup order (the
     * table's orderKey row assigns staged keys to Replace writes only); Replace replays the
     * member list with [replaceMemberKeys]'s verbatim-or-rewrite keys against the join table
     * the pre-transaction cleared.
     */
    private suspend fun writeMembers(
        groups: List<StagedGroup>,
        policy: MergePolicy,
        createdAtMs: Long,
        podcastIdByKey: Map<String, Long>,
        now: Long,
        replaceMemberKeys: Map<StagedGroup, List<String>>,
    ) {
        val touched = HashSet<Long>()
        for (group in groups) {
            if (group.foldedInto != null) continue
            val groupId = group.resolvedId ?: continue
            if (policy == MergePolicy.RESTORE_MERGE) {
                var lastKey = groupDao.lastMemberOrderKey(groupId)
                for (member in group.members.values) {
                    val podcastId = memberPodcastId(member.podcastKey, podcastIdByKey) ?: continue
                    val key = orderKeys.after(lastKey)
                    val inserted =
                        groupDao.insertMember(
                            PodcastGroupMemberEntity(
                                groupId = groupId,
                                podcastId = podcastId,
                                orderKey = key,
                                addedAt = member.addedAt ?: createdAtMs,
                                source = MemberSource.MANUAL,
                            ),
                        )
                    // An ignored insert (the member already exists) consumed no key.
                    if (inserted != -1L) {
                        lastKey = key
                        touched += groupId
                    }
                }
            } else {
                for ((member, orderKey) in group.members.values.zip(replaceMemberKeys.getValue(group))) {
                    val podcastId = memberPodcastId(member.podcastKey, podcastIdByKey) ?: continue
                    groupDao.insertMember(
                        PodcastGroupMemberEntity(
                            groupId = groupId,
                            podcastId = podcastId,
                            orderKey = orderKey,
                            addedAt = member.addedAt ?: createdAtMs,
                            source = MemberSource.MANUAL,
                        ),
                    )
                }
            }
        }
        if (touched.isNotEmpty()) groupDao.touchUpdatedAt(touched, now)
    }

    /** A member's `podcastKey` → the restored row's id, else a live feed-key lookup. */
    private suspend fun memberPodcastId(
        podcastKey: String,
        podcastIdByKey: Map<String, Long>,
    ): Long? = podcastIdByKey[podcastKey] ?: podcastDao.byFeedKey(podcastKey)?.id

    /**
     * Replace's order keys (05 "the backup's keys, else `OrderKey.rewrite(n)` in rank
     * order"), planned before the pre-step destroys local state: group keys walk the
     * canonical groups in `sortOrder` rank (list order breaks ties), member keys their
     * file order. Computed after the match pass, so folded groups take no key.
     */
    private fun planReplaceOrderKeys(groups: List<StagedGroup>): ReplaceOrderKeys {
        val ranked =
            groups
                .filter { it.foldedInto == null }
                .withIndex()
                .sortedWith(compareBy({ it.value.primary.sortOrder }, { it.index }))
                .map { it.value }
        return ReplaceOrderKeys(
            groups = ranked.zip(restoreOrderKeys(orderKeys, ranked.map { it.primary.orderKey })).toMap(),
            members =
                ranked.associateWith {
                    restoreOrderKeys(orderKeys, it.members.values.map(MemberV1::orderKey))
                },
        )
    }

    private fun warnSmartGroup(group: GroupV1) {
        if (group.kind != "MANUAL") {
            Log.w(TAG) { "Smart group '${group.name}' was imported as a regular group" }
        }
    }

    private companion object {
        const val TAG = "RestoreMerger"
        const val DEFAULT_GROUP_NAME = "Group"
        const val DEFAULT_YOUTUBE_VARIANTS = 1
    }
}

/**
 * Transaction A's settled output for the episode phase (05): the library [MergeSummary]
 * plus the identity maps the later transactions attach through — staged podcast `key`
 * (and each alias it claimed first) → local id, a canonical key winning over another
 * podcast's alias claim; and every staged group `uuid` → its survivor row, folded and
 * absorbed staged uuids included.
 */
internal class LibraryMergeResult(
    val summary: MergeSummary,
    val podcastIdByKey: Map<String, Long>,
    val groupIdByUuid: Map<String, Long>,
    /**
     * The local ids transaction A inserted fresh — B12b's finish writes these as QUEUED
     * `import_item`s (matched rows report ALREADY_SUBSCRIBED instead). Recorded at the
     * write, never re-derived by a live lookup.
     */
    val insertedPodcastIds: Set<Long> = emptySet(),
)

/**
 * The local row ids a backup's podcasts and groups claim — [RestoreMerger.matchedLocalIds]
 * (05 step 5's Replace dry run). Rows not in either set are the ones Replace would remove.
 */
internal class MatchedLocalIds(
    val podcastIds: Set<Long>,
    val groupIds: Set<Long>,
)

/**
 * One list's keys under Replace (05 "the backup's keys, else `OrderKey.rewrite(n)` in rank
 * order"), shared by the library, member and Up next planning: the staged keys verbatim
 * when every slot carries a valid `OrderKey` in strict ascending order; a missing,
 * malformed, duplicated or unordered key rewrites the whole list instead — an invalid key
 * neither persists nor feeds the generator as a bound. `after` runs the port's key
 * validation on its bound, which doubles it as the validity probe without copying the
 * grammar here.
 */
internal fun restoreOrderKeys(
    orderKeys: OrderKeys,
    wanted: List<String?>,
): List<String> {
    val keys = wanted.filterNotNull()
    if (
        keys.size == wanted.size &&
        keys.zipWithNext().all { (a, b) -> a < b } &&
        keys.all { runCatching { orderKeys.after(it) }.isSuccess }
    ) {
        return keys
    }
    return orderKeys.rewrite(wanted.size)
}

/** An adopted backup uuid — the `onUuidChanged` channel carry-over's `(old, new)`. */
private data class UuidChange(
    val from: String,
    val to: String,
)

/** Per-column merge of `ScopeOverrides` (05: local non-null wins, else the backup's). */
private fun mergeOverrides(
    local: ScopeOverrides?,
    staged: ScopeOverrides,
): ScopeOverrides {
    if (local == null) return staged
    return ScopeOverrides(
        playbackSpeed = local.playbackSpeed ?: staged.playbackSpeed,
        skipSilence = local.skipSilence ?: staged.skipSilence,
        boostDb = local.boostDb ?: staged.boostDb,
        introSkipMs = local.introSkipMs ?: staged.introSkipMs,
        outroSkipMs = local.outroSkipMs ?: staged.outroSkipMs,
        autoDownload = local.autoDownload ?: staged.autoDownload,
        autoDownloadKeepLatest = local.autoDownloadKeepLatest ?: staged.autoDownloadKeepLatest,
        autoDownloadNetwork = local.autoDownloadNetwork ?: staged.autoDownloadNetwork,
        autoDownloadRequireCharging =
            local.autoDownloadRequireCharging ?: staged.autoDownloadRequireCharging,
        deleteAfterPlayed = local.deleteAfterPlayed ?: staged.deleteAfterPlayed,
        includeVideoInAutoDownload =
            local.includeVideoInAutoDownload ?: staged.includeVideoInAutoDownload,
        notifyNewEpisodes = local.notifyNewEpisodes ?: staged.notifyNewEpisodes,
        refreshIntervalMinutes = local.refreshIntervalMinutes ?: staged.refreshIntervalMinutes,
    )
}

/** `OverridesV1` → `ScopeOverrides`; enum columns travel by name (05), unknown names read as null. */
private fun OverridesV1.toScopeOverrides(): ScopeOverrides =
    ScopeOverrides(
        playbackSpeed = playbackSpeed,
        skipSilence = skipSilence,
        boostDb = boostDb,
        introSkipMs = introSkipMs,
        outroSkipMs = outroSkipMs,
        autoDownload = autoDownload,
        autoDownloadKeepLatest = autoDownloadKeepLatest,
        autoDownloadNetwork = enumNamed<NetworkPolicy>(autoDownloadNetwork),
        autoDownloadRequireCharging = autoDownloadRequireCharging,
        deleteAfterPlayed = enumNamed<DeleteAfter>(deleteAfterPlayed),
        includeVideoInAutoDownload = includeVideoInAutoDownload,
        notifyNewEpisodes = notifyNewEpisodes,
        refreshIntervalMinutes = refreshIntervalMinutes,
    )

private inline fun <reified E : Enum<E>> enumNamed(name: String?): E? =
    name?.let { wanted -> enumValues<E>().firstOrNull { it.name == wanted } }
