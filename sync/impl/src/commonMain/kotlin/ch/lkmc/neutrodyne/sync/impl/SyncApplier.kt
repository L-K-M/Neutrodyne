// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.database.EpisodeEntity
import ch.lkmc.neutrodyne.core.database.EpisodePositionEntity
import ch.lkmc.neutrodyne.core.database.EpisodeStateEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastEntity
import ch.lkmc.neutrodyne.core.database.PodcastGroupEntity
import ch.lkmc.neutrodyne.core.database.PodcastGroupMemberEntity
import ch.lkmc.neutrodyne.core.database.PodcastGroupSettingsEntity
import ch.lkmc.neutrodyne.core.database.PodcastSettingsEntity
import ch.lkmc.neutrodyne.core.database.QueueEntryEntity
import ch.lkmc.neutrodyne.core.database.ScopeOverrides
import ch.lkmc.neutrodyne.core.database.SyncOutboxEntity
import ch.lkmc.neutrodyne.core.database.SyncParkedEntity
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import ch.lkmc.neutrodyne.core.domain.PlaybackSyncPort
import ch.lkmc.neutrodyne.core.domain.SecretStore
import ch.lkmc.neutrodyne.core.domain.SettingsSyncPort
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import ch.lkmc.neutrodyne.core.model.AliasReason
import ch.lkmc.neutrodyne.core.model.MemberSource
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.feeds.groups.GroupNames
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.sync.protocol.EpisodeStateRules
import ch.lkmc.neutrodyne.sync.protocol.FieldState
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.OrderKey
import ch.lkmc.neutrodyne.sync.protocol.ProtocolValidation
import ch.lkmc.neutrodyne.sync.protocol.RecordId
import ch.lkmc.neutrodyne.sync.protocol.RecordMerger
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import ch.lkmc.neutrodyne.sync.protocol.dto.RecordDto
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import ch.lkmc.neutrodyne.core.model.PodcastStatus as Status

/** One page's outcome (10 Sync round). */
data class SyncApplyResult(
    val applied: Int,
    val stale: Int,
    val parked: Int,
    val held: Int,
    val invalid: Int,
    /** Remote played marks that hit the loaded item inside this page's committed transaction. */
    val remotePlayed: List<RemotePlayedMark> = emptyList(),
)

/** A remote `played` the committed page projected onto the loaded item (10 Remote played marks). */
data class RemotePlayedMark(
    val episodeId: Long,
    val by: String,
)

/** The parked `identityKey` of a membership row: `@member:<groupUuid>` (10 Parked state). */
internal const val MEMBER_KEY_PREFIX = "@member:"

/**
 * The pull side of 10 Pull and apply: one write transaction per page with `applying = 1`, field
 * merge against `sync_clock` and the pending outbox, then the collection-specific writes. Removals
 * are staged through [MassChangeGuard]; settings and credentials are recorded as durable effects.
 */
class SyncApplier(
    private val db: NeutrodyneDatabase,
    private val clock: Clock,
    private val secrets: SecretStore,
    private val settings: SettingsSyncPort,
    private val playback: PlaybackSyncPort,
    private val syncIngestHook: SyncIngestHook,
    private val channelSync: GroupChannelSync,
    private val sharePasswords: () -> Boolean,
    private val notices: SyncNoticeBus,
    private val deviceNames: DeviceNames,
) {
    private val clockStore = SyncClockStore(db)
    private val matcher = EpisodeMatcher(db.backupDao())

    suspend fun applyPage(
        records: List<RecordDto>,
        roundId: Long,
    ): SyncApplyResult {
        var applied = 0
        var stale = 0
        var parked = 0
        var held = 0
        var invalid = 0
        val remotePlayed = mutableListOf<RemotePlayedMark>()
        try {
            db.withWriteTransaction<Unit> {
                db.syncStateDao().withApplying<Unit> {
                    val ordered = records.sortedBy { it.coll }
                    for (dto in ordered) {
                        val result = applyRecord(dto, roundId, remotePlayed)
                        when (result) {
                            is RecordOutcome.Applied -> applied++
                            is RecordOutcome.Stale -> stale++
                            is RecordOutcome.Parked -> parked++
                            is RecordOutcome.Held -> held++
                            is RecordOutcome.Invalid -> invalid++
                        }
                    }
                }
            }
        } catch (failure: Throwable) {
            // The marks the page installed never committed: undo exactly their freezes and post
            // no notices. A mark an earlier page committed keeps its freeze untouched.
            revertMarks(remotePlayed)
            throw failure
        }
        // Notices post only after the page's transaction committed (10 Remote played marks).
        postMarks(remotePlayed)
        return SyncApplyResult(applied, stale, parked, held, invalid, remotePlayed.toList())
    }

    /** Rolled-back marks release their freeze; the revert must not mask the apply's own failure. */
    private suspend fun revertMarks(marks: List<RemotePlayedMark>) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            for (mark in marks) {
                runCatching { playback.onRemoteMarkReverted(mark.episodeId) }
            }
        }
    }

    private suspend fun postMarks(marks: List<RemotePlayedMark>) {
        for (mark in marks) {
            val title = db.episodeDao().byId(mark.episodeId)?.title ?: continue
            notices.post(
                ch.lkmc.neutrodyne.sync.api.SyncNotice
                    .MarkedPlayedElsewhere(mark.episodeId, title, deviceNames.name(mark.by) ?: mark.by),
            )
        }
    }

    private suspend fun applyRecord(
        dto: RecordDto,
        roundId: Long,
        remotePlayed: MutableList<RemotePlayedMark>,
    ): RecordOutcome = applyValidated(dto, roundId, journalParked = true, remotePlayed)

    /**
     * One record through 10 Pull and apply steps 1-3. [journalParked] parks an unresolvable
     * record in `sync_parked` (the page path); the parked-state retry passes `false` because its
     * rows already exist and must not be rewritten from a replay of their own merged state.
     */
    private suspend fun applyValidated(
        dto: RecordDto,
        roundId: Long,
        journalParked: Boolean,
        remotePlayed: MutableList<RemotePlayedMark>,
    ): RecordOutcome {
        val coll =
            runCatching { SyncCollection.fromWire(dto.coll) }.getOrNull() ?: return RecordOutcome.Invalid
        val id =
            runCatching { RecordId.fromWire(coll, dto.id) }.getOrNull() ?: return RecordOutcome.Invalid
        val incoming =
            runCatching { ProtocolValidation.record(dto) }.getOrNull() ?: return RecordOutcome.Invalid
        val resolved = resolve(id)
        val stored = clockStore.get(coll.wire, resolved.rid)
        val pending = pendingState(coll, resolved.rid)
        val merge = SyncRecordMerger.merge(stored?.state, pending, incoming)
        if (merge.advanced.isEmpty() && merge.lost.isEmpty() && merge.record == stored?.state) {
            return RecordOutcome.Stale
        }
        val outcome =
            when (coll) {
                SyncCollection.PODCAST -> applyPodcast(resolved, merge, roundId)
                SyncCollection.GROUP -> applyGroup(resolved, merge, roundId)
                SyncCollection.MEMBER -> applyMember(resolved, merge)
                SyncCollection.EPISODE -> applyEpisode(resolved, merge, dto.by ?: stored?.by, remotePlayed)
                SyncCollection.UPNEXT -> applyUpNext(resolved, merge)
                SyncCollection.SESSION -> applySession(resolved, merge)
                SyncCollection.SETTING -> applySetting(resolved, merge)
            }
        if (outcome is RecordOutcome.Applied || outcome is RecordOutcome.Held) {
            val pendingSetting =
                if (coll == SyncCollection.SETTING) {
                    // The marker carries the value's JSON text, not the bare primitive content: the
                    // drain forwards it to SettingsSyncPort, which decodes the key's wire shape.
                    val text =
                        merge.record.fields["v"]
                            ?.v
                            ?.toString()
                    text?.let { PendingSetting(it, merge.record.maxClock?.toString() ?: "") }
                } else {
                    null
                }
            clockStore.put(
                coll.wire,
                resolved.rid,
                RawSyncRecord(
                    state = merge.record,
                    by = dto.by ?: stored?.by,
                    pendingSetting = pendingSetting ?: stored?.pendingSetting,
                    pendingAuth = stored?.pendingAuth,
                ),
            )
            deleteLostPending(coll.wire, resolved.rid, merge.advanced)
        }
        if (outcome is RecordOutcome.Parked && journalParked) {
            park(coll, resolved, incoming, dto)
        }
        return outcome
    }

    private suspend fun applyPodcast(
        id: RecordId,
        merge: RecordMerge,
        roundId: Long,
    ): RecordOutcome {
        val rid = (id as RecordId.Podcast).id.value
        val existing = db.podcastDao().bySyncId(rid)
        val subscribed = merge.record.booleanField("subscribed") ?: true
        if (!subscribed) {
            if (existing == null) {
                // Unknown and dead: record the clocks so its child records are dropped, and the
                // parked rows leave with the podcast's tombstone (10 Pull and apply).
                deleteParkedForPodcast(rid)
                return RecordOutcome.Applied
            }
            return if (merge.advanced.contains("subscribed")) {
                MassChangeGuard.stagePodcast(db, roundId, rid, merge.record.fieldClock("subscribed"))
                RecordOutcome.Held
            } else {
                RecordOutcome.Stale
            }
        }
        if (existing == null) {
            val feedUrl = merge.record.stringField("feedUrl") ?: return RecordOutcome.Invalid
            val feedKey = UrlNormalizer.forIdentity(feedUrl) ?: return RecordOutcome.Invalid
            // Another podcast already holds this feed: the merge belongs to it, not to a second row.
            if (db.podcastDao().byFeedKey(feedKey) != null) return RecordOutcome.Invalid
            val now = clock.now()
            val syncId = rid
            val title = merge.record.stringField("title") ?: feedUrl
            val idInserted =
                db
                    .podcastDao()
                    .insertPodcastOrIgnore(
                        PodcastEntity(
                            syncId = syncId,
                            sourceType =
                                merge.record.enumField("sourceType")
                                    ?: ch.lkmc.neutrodyne.core.model.SourceType.RSS,
                            feedUrl = feedUrl,
                            feedKey = feedKey,
                            youtubeChannelId = merge.record.stringField("youtubeChannelId"),
                            youtubeVariants =
                                merge.record
                                    .intField("youtubeVariants")
                                    ?.takeIf(ch.lkmc.neutrodyne.core.model.YouTubeVariantBits::isValid) ?: 1,
                            title = title,
                            artworkUrl = merge.record.stringField("artworkUrl"),
                            link = merge.record.stringField("link"),
                            customTitle = merge.record.stringField("customTitle"),
                            includeInAll = merge.record.booleanField("includeInAll") ?: true,
                            episodeOrder = merge.record.enumField("episodeOrder"),
                            status = Status.PENDING_FIRST_FETCH,
                            initialFetch = true,
                            subscribedAt = merge.record.longField("subscribedAt") ?: now,
                            nextRefreshAt = now,
                            needsCredentials = merge.record.booleanField("needsCredentials") ?: false,
                            artworkKey = "u-$syncId",
                        ),
                    )
            if (idInserted == -1L) return RecordOutcome.Invalid
            // A `*` row's aliases and the overrides land with the projection.
            merge.record.growSets["feedKeys"]?.forEach { key ->
                if (key != feedKey) {
                    db
                        .podcastDao()
                        .insertAliasOrIgnore(
                            ch.lkmc.neutrodyne.core.database.PodcastUrlAliasEntity(
                                url = key,
                                podcastId = idInserted,
                                reason = AliasReason.SYNC,
                                addedAt = now,
                            ),
                        )
                }
            }
            applyOverrides(idInserted, isGroup = false, merge.record)
            // 10 Parked state: the podcast's insert releases its parked members in this
            // transaction; episode and Up-next rows keep waiting for the ingest hook.
            releaseParkedMembersByPodcast(syncId)
            return RecordOutcome.Applied
        }
        applyPodcastFields(existing, merge.record)
        return RecordOutcome.Applied
    }

    private suspend fun applyPodcastFields(
        existing: PodcastEntity,
        record: RecordState,
    ) {
        val now = clock.now()
        record.stringField("feedUrl")?.let { feedUrl ->
            val feedKey = UrlNormalizer.forIdentity(feedUrl)
            if (feedKey != null && feedKey != existing.feedKey) {
                db.podcastDao().moveFeed(existing.id, feedUrl, feedKey)
                record.growSets["feedKeys"]?.forEach { key ->
                    if (key != feedKey) {
                        db
                            .podcastDao()
                            .insertAliasOrIgnore(
                                ch.lkmc.neutrodyne.core.database.PodcastUrlAliasEntity(
                                    url = key,
                                    podcastId = existing.id,
                                    reason = AliasReason.SYNC,
                                    addedAt = now,
                                ),
                            )
                    }
                }
            }
        }
        record.stringField("customTitle")?.let { db.podcastDao().setCustomTitle(existing.id, it) }
        record.booleanField("includeInAll")?.let { db.podcastDao().setIncludeInAll(existing.id, it) }
        record.enumField<ch.lkmc.neutrodyne.core.model.FeedOrder>("episodeOrder")?.let {
            db.podcastDao().setEpisodeOrder(existing.id, it)
        }
        // The user's Shorts/Live choice: a merged value is the field merge's winner, so a
        // stale remote record cannot overwrite a newer local one (10 Pull and apply).
        record
            .intField("youtubeVariants")
            ?.takeIf(ch.lkmc.neutrodyne.core.model.YouTubeVariantBits::isValid)
            ?.let { db.podcastDao().setYouTubeVariants(existing.id, it) }
        applyOverrides(existing.id, isGroup = false, record)
    }

    private suspend fun applyOverrides(
        id: Long,
        isGroup: Boolean,
        record: RecordState,
    ) {
        val overrides =
            ScopeOverrides(
                playbackSpeed = record.floatField("s.playbackSpeed"),
                skipSilence = record.booleanField("s.skipSilence"),
                boostDb = record.floatField("s.boostDb"),
                introSkipMs = record.longField("s.introSkipMs"),
                outroSkipMs = record.longField("s.outroSkipMs"),
            )
        val hasAny =
            listOfNotNull(
                overrides.playbackSpeed,
                overrides.skipSilence,
                overrides.boostDb,
                overrides.introSkipMs,
                overrides.outroSkipMs,
            ).isNotEmpty()
        if (isGroup) {
            if (hasAny) {
                db.scopeSettingsDao().upsertGroup(PodcastGroupSettingsEntity(id, overrides))
            } else {
                db.scopeSettingsDao().deleteGroup(id)
            }
        } else {
            if (hasAny) {
                db.scopeSettingsDao().upsertPodcast(PodcastSettingsEntity(id, overrides))
            } else {
                db.scopeSettingsDao().deletePodcast(id)
            }
        }
    }

    private suspend fun applyGroup(
        id: RecordId,
        merge: RecordMerge,
        roundId: Long,
    ): RecordOutcome {
        val uuid = (id as RecordId.Group).id.value
        val existing = db.groupDao().groupByUuid(uuid)
        val deleted = merge.record.booleanField("deleted") ?: false
        if (deleted) {
            if (existing == null) return RecordOutcome.Applied
            return if (merge.advanced.contains("deleted")) {
                MassChangeGuard.stageGroup(db, roundId, uuid, merge.record.fieldClock("deleted"))
                RecordOutcome.Held
            } else {
                RecordOutcome.Stale
            }
        }
        val name = merge.record.stringField("name") ?: return RecordOutcome.Invalid
        val validation = GroupNames.validate(name)
        if (validation is GroupNames.Validation.Empty) return RecordOutcome.Invalid
        val valid = validation as GroupNames.Validation.Valid
        val now = clock.now()
        if (existing == null) {
            val orderKey =
                merge.record.stringField("ok") ?: db.groupDao().lastGroupOrderKey()?.let { OrderKey.after(it) }
                    ?: OrderKey.after(null)
            db
                .groupDao()
                .insert(
                    PodcastGroupEntity(
                        uuid = uuid,
                        name = valid.name,
                        nameKey = valid.nameKey,
                        orderKey = orderKey,
                        colorArgb = merge.record.intField("colorArgb"),
                        iconKey = merge.record.stringField("iconKey"),
                        feedOrder =
                            merge.record.enumField("feedOrder")
                                ?: ch.lkmc.neutrodyne.core.model.FeedOrder.NEWEST_FIRST,
                        playOrder =
                            merge.record.enumField("playOrder")
                                ?: ch.lkmc.neutrodyne.core.model.FeedOrder.NEWEST_FIRST,
                        filterFlags = merge.record.intField("filterFlags") ?: 0,
                        mediaFilter =
                            merge.record.enumField("mediaFilter")
                                ?: ch.lkmc.neutrodyne.core.model.MediaFilter.ALL,
                        hideOlderThanDays = merge.record.intField("hideOlderThanDays"),
                        showAsTab = merge.record.booleanField("showAsTab") ?: true,
                        createdAt = merge.record.longField("createdAt") ?: now,
                        updatedAt = now,
                    ),
                )
            applyOverrides(db.groupDao().groupByUuid(uuid)?.id ?: return RecordOutcome.Applied, true, merge.record)
            // 10 Parked state: the group's insert releases its parked members in this transaction.
            releaseParkedMembersByGroup(uuid)
            return RecordOutcome.Applied
        }
        val orderKey = merge.record.stringField("ok")
        if (orderKey != null && orderKey != existing.orderKey) {
            db.groupDao().setGroupOrderKey(existing.id, orderKey, now)
        }
        if (valid.name != existing.name) {
            db.groupDao().rename(existing.id, valid.name, valid.nameKey, now)
        }
        merge.record.intField("colorArgb")?.let { color ->
            db.groupDao().setLook(existing.id, color, merge.record.stringField("iconKey"), now)
        } ?: merge.record.stringField("iconKey")?.let { icon ->
            db.groupDao().setLook(existing.id, existing.colorArgb, icon, now)
        }
        merge.record.enumField<ch.lkmc.neutrodyne.core.model.FeedOrder>("feedOrder")?.let { order ->
            db
                .groupDao()
                .setOrders(
                    existing.id,
                    order,
                    merge.record.enumField("playOrder") ?: existing.playOrder,
                    now,
                )
        } ?: merge.record.enumField<ch.lkmc.neutrodyne.core.model.FeedOrder>("playOrder")?.let { order ->
            db.groupDao().setOrders(existing.id, existing.feedOrder, order, now)
        }
        merge.record.intField("filterFlags")?.let { flags ->
            db
                .groupDao()
                .setView(
                    existing.id,
                    flags,
                    merge.record.enumField("mediaFilter") ?: existing.mediaFilter,
                    merge.record.intField("hideOlderThanDays") ?: existing.hideOlderThanDays,
                    now,
                )
        } ?: merge.record.enumField<ch.lkmc.neutrodyne.core.model.MediaFilter>("mediaFilter")?.let { media ->
            db
                .groupDao()
                .setView(existing.id, existing.filterFlags, media, existing.hideOlderThanDays, now)
        } ?: merge.record.intField("hideOlderThanDays")?.let { days ->
            db.groupDao().setView(existing.id, existing.filterFlags, existing.mediaFilter, days, now)
        }
        merge.record.booleanField("showAsTab")?.let { db.groupDao().setShowAsTab(existing.id, it, now) }
        applyOverrides(existing.id, true, merge.record)
        return RecordOutcome.Applied
    }

    private suspend fun applyMember(
        id: RecordId,
        merge: RecordMerge,
    ): RecordOutcome {
        val member = id as RecordId.Member
        val group = db.groupDao().groupByUuid(member.group.value)
        val podcast = db.podcastDao().bySyncId(member.podcast.value)
        if (group == null || podcast == null) {
            // An end known dead (or the membership's own removal) drops instead of parking.
            return if (podcastDead(member.podcast.value) ||
                groupDead(member.group.value) ||
                RecordMerger.isDead(merge.record)
            ) {
                RecordOutcome.Stale
            } else {
                RecordOutcome.Parked
            }
        }
        val inGroup = merge.record.booleanField("in") ?: true
        val existing = db.groupDao().member(group.id, podcast.id)
        if (!inGroup) {
            if (existing == null) return RecordOutcome.Stale
            db.groupDao().deleteMembers(group.id, listOf(podcast.id))
            return RecordOutcome.Applied
        }
        if (existing == null) {
            db
                .groupDao()
                .insertMember(
                    PodcastGroupMemberEntity(
                        groupId = group.id,
                        podcastId = podcast.id,
                        orderKey =
                            merge.record.stringField("ok")
                                ?: db
                                    .groupDao()
                                    .lastMemberOrderKey(group.id)
                                    ?.let { OrderKey.after(it) } ?: OrderKey.after(null),
                        addedAt = merge.record.longField("addedAt") ?: clock.now(),
                        source = MemberSource.MANUAL,
                    ),
                )
            return RecordOutcome.Applied
        }
        val orderKey = merge.record.stringField("ok")
        if (orderKey != null && orderKey != existing.orderKey) {
            db
                .groupDao()
                .insertMember(
                    existing.copy(orderKey = orderKey, addedAt = merge.record.longField("addedAt") ?: existing.addedAt),
                )
        }
        return RecordOutcome.Applied
    }

    private suspend fun applyEpisode(
        id: RecordId,
        merge: RecordMerge,
        by: String?,
        remotePlayed: MutableList<RemotePlayedMark>,
    ): RecordOutcome {
        val episodeId = id as RecordId.Episode
        val podcast =
            db.podcastDao().bySyncId(episodeId.podcast.value)
                ?: return if (podcastDead(episodeId.podcast.value)) {
                    // A dead podcast's records drop instead of parking (10 Pull and apply).
                    RecordOutcome.Stale
                } else {
                    RecordOutcome.Parked
                }
        val hints = merge.record.match
        val matched = matcher.match(podcast.id, episodeId.key.value, hints)
        if (matched == null) {
            val effect = EpisodeStateRules.derive(merge.record.fields)
            val canStub =
                (effect.state == ch.lkmc.neutrodyne.sync.protocol.EpisodeProgress.IN_PROGRESS || effect.favourite) &&
                    (hints?.enc != null || hints?.ytId != null)
            if (!canStub) return RecordOutcome.Parked
            val idInserted =
                db
                    .episodeDao()
                    .insertStub(
                        episodeEntity(podcast.id, episodeId.key.value, hints, clock.now()),
                    )
            if (idInserted == -1L) {
                db
                    .episodeDao()
                    .byIdentityKey(
                        podcast.id,
                        episodeId.key.value,
                    )?.let {
                        applyEpisodeState(it.id, merge.record)
                        reportRemotePlayedIfLoaded(it.id, merge, by, remotePlayed)
                    }
            } else {
                applyEpisodeState(idInserted, merge.record)
                reportRemotePlayedIfLoaded(idInserted, merge, by, remotePlayed)
            }
            return RecordOutcome.Applied
        }
        applyEpisodeState(matched, merge.record)
        reportRemotePlayedIfLoaded(matched, merge, by, remotePlayed)
        return RecordOutcome.Applied
    }

    /**
     * 10 Remote played marks 2 + 06 Sync interplay rule 3: a mark on the loaded item whose merged
     * state is effectively played freezes the loaded player, and only inside the applying
     * transaction, so the freeze is already set when the mark's commit lands. An older mark a
     * newer listening position hides derives to in-progress and calls nothing. The mark is also
     * collected for the post-commit "Marked played on <device>" notice.
     */
    private suspend fun reportRemotePlayedIfLoaded(
        episodeId: Long,
        merge: RecordMerge,
        by: String?,
        remotePlayed: MutableList<RemotePlayedMark>,
    ) {
        if (EpisodeStateRules.derive(merge.record.fields).state !=
            ch.lkmc.neutrodyne.sync.protocol.EpisodeProgress.PLAYED
        ) {
            return
        }
        if (playback.active.value.episodeId != episodeId) return
        // Register before the callback: a port that froze before throwing still gets its
        // freeze released by the rollback's revert, and reverting a mark that never froze is
        // a counted no-op.
        remotePlayed += RemotePlayedMark(episodeId, by ?: "")
        playback.onRemoteMarkedPlayed(episodeId, by ?: "")
    }

    private suspend fun applyEpisodeState(
        episodeId: Long,
        record: RecordState,
    ) {
        val effect = EpisodeStateRules.derive(record.fields)
        val current = db.episodeStateDao().byEpisode(episodeId)
        db
            .episodeStateDao()
            .ensure(EpisodeStateEntity(episodeId = episodeId, updatedAt = clock.now()))
        val playedAt = record.nullableLongField("playedAt", current?.playedAt)
        val favourite = record.booleanField("fav") ?: (current?.isFavorite ?: false)
        val playCount =
            maxOf(record.longField("playCount") ?: 0L, current?.playCount?.toLong() ?: 0L)
        val lastPlayedAt =
            maxOfNullable(record.longField("lastPlayedAt"), current?.lastPlayedAt)
        db
            .episodeStateDao()
            .upsert(
                EpisodeStateEntity(
                    episodeId = episodeId,
                    startedAt =
                        if (effect.state == ch.lkmc.neutrodyne.sync.protocol.EpisodeProgress.PLAYED) {
                            null
                        } else {
                            current?.startedAt ?: effect.startedAt
                        },
                    playedAt = playedAt,
                    playCount = playCount.toInt(),
                    lastPlayedAt = lastPlayedAt,
                    isFavorite = favourite,
                    downloadDismissedAt = current?.downloadDismissedAt,
                    measuredDurationMs =
                        record.nullableLongField("measuredDurationMs", current?.measuredDurationMs),
                    updatedAt = clock.now(),
                ),
            )
        val pos = record.fields["pos"]
        if (pos != null) {
            val ms = (pos.v as? JsonObject)?.get("ms")?.jsonPrimitive?.longOrNull ?: 0L
            db
                .positionDao()
                .upsert(
                    EpisodePositionEntity(
                        episodeId = episodeId,
                        positionMs = ms,
                        durationMs = null,
                        positionSource = PositionSource.STREAM,
                        updatedAt = clock.now(),
                    ),
                )
        }
    }

    private suspend fun applyUpNext(
        id: RecordId,
        merge: RecordMerge,
    ): RecordOutcome {
        val episodeId = id as RecordId.Episode
        val podcast =
            db.podcastDao().bySyncId(episodeId.podcast.value)
                ?: return if (podcastDead(episodeId.podcast.value)) {
                    RecordOutcome.Stale
                } else {
                    RecordOutcome.Parked
                }
        val inQueue = merge.record.booleanField("in") ?: true
        val hints = merge.record.match
        var matched = matcher.match(podcast.id, episodeId.key.value, hints)
        if (matched == null && inQueue && (hints?.enc != null || hints?.ytId != null)) {
            // 10 Pull and apply: an unresolved Up-next reference with enclosure or YouTube hints
            // becomes a stub through 02's stub insert, so the queue entry survives before the
            // first ingest instead of parking.
            val inserted =
                db
                    .episodeDao()
                    .insertStub(episodeEntity(podcast.id, episodeId.key.value, hints, clock.now()))
            matched =
                if (inserted == -1L) {
                    db.episodeDao().byIdentityKey(podcast.id, episodeId.key.value)?.id
                } else {
                    inserted
                }
        }
        if (matched == null) {
            return if (RecordMerger.isDead(merge.record)) RecordOutcome.Stale else RecordOutcome.Parked
        }
        val existing = db.queueDao().entryByEpisode(matched)
        if (!inQueue) {
            if (existing == null) return RecordOutcome.Stale
            db.queueDao().removeEpisodes(listOf(matched))
            return RecordOutcome.Applied
        }
        if (existing == null) {
            db
                .queueDao()
                .insert(
                    QueueEntryEntity(
                        episodeId = matched,
                        orderKey =
                            merge.record.stringField("ok") ?: db
                                .queueDao()
                                .lastOrderKey()
                                ?.let { OrderKey.after(it) } ?: OrderKey.after(null),
                        addedAt = clock.now(),
                    ),
                )
            return RecordOutcome.Applied
        }
        val orderKey = merge.record.stringField("ok")
        if (orderKey != null && orderKey != existing.orderKey) {
            db.queueDao().updateOrderKey(existing.id, orderKey)
        }
        return RecordOutcome.Applied
    }

    private suspend fun applySession(
        id: RecordId,
        merge: RecordMerge,
    ): RecordOutcome {
        // MS3 adopts the session; MS2 retains the raw inputs and the writer.
        return RecordOutcome.Applied
    }

    private suspend fun applySetting(
        id: RecordId,
        merge: RecordMerge,
    ): RecordOutcome {
        val key = (id as RecordId.Setting).key
        val settingKey =
            ch.lkmc.neutrodyne.core.model.settings.AllSettingKeys.list
                .firstOrNull { it.name == key }
                ?: return RecordOutcome.Invalid
        val value =
            merge.record.fields["v"]
                ?.v
                ?.toString() ?: return RecordOutcome.Stale
        val pending = storedPending(settingKey.name) ?: PendingSetting(value, merge.record.maxClock?.toString() ?: "")
        db.syncClockDao().upsert(
            ch.lkmc.neutrodyne.core.database.SyncClockEntity(
                SyncCollection.SETTING.wire,
                key,
                SyncJson.encodeToString(
                    RawSyncRecord.serializer(),
                    RawSyncRecord(
                        state = merge.record,
                        pendingSetting = pending,
                    ),
                ),
            ),
        )
        return RecordOutcome.Applied
    }

    private suspend fun storedPending(key: String): PendingSetting? = null

    // --- Parked state (10 Parked state and stubs) ---------------------------------------------------

    /** One parked row's identity: the podcast end plus the episode key or `@member:<group>`. */
    private data class ParkedIdentity(
        val podcastSyncId: String,
        val identityKey: String,
    )

    /**
     * Journals a live record whose projection cannot run yet. Arrivals for the same identity and
     * collection merge field by field with their clocks, so disjoint updates that span pages or
     * repeated pulls accumulate losslessly and an identical replay rewrites the same row instead
     * of growing a duplicate. The stored JSON keeps the record's complete merged raw state:
     * values, match hints and every field clock.
     */
    private suspend fun park(
        coll: SyncCollection,
        id: RecordId,
        incoming: RecordState,
        dto: RecordDto,
    ) {
        val parked =
            when (id) {
                is RecordId.Member -> ParkedIdentity(id.podcast.value, MEMBER_KEY_PREFIX + id.group.value)
                is RecordId.Episode -> ParkedIdentity(id.podcast.value, id.key.value)
                else -> return
            }
        val candidates = db.syncParkedDao().forPodcast(parked.podcastSyncId)
        var existing: SyncParkedEntity? = null
        var existingDto: RecordDto? = null
        for (row in candidates) {
            if (row.identityKey != parked.identityKey) continue
            val decoded = decodeParked(row) ?: continue
            if (decoded.coll == coll.wire) {
                existing = row
                existingDto = decoded
                break
            }
        }
        val existingState =
            existingDto?.let { runCatching { ProtocolValidation.record(it) }.getOrNull() }
        val merged =
            if (existingState != null) {
                // An equal-clock value conflict is a protocol violation; the journaled row wins
                // and the conflicting arrival is left out rather than failing the page.
                runCatching { RecordMerger.merge(existingState, incoming).record }.getOrNull() ?: return
            } else {
                incoming
            }
        val mergedDto = merged.toDto(id, maxOf(existingDto?.seq ?: 0L, dto.seq), dto.by ?: existingDto?.by)
        db
            .syncParkedDao()
            .upsert(
                SyncParkedEntity(
                    id = existing?.id ?: 0L,
                    podcastSyncId = parked.podcastSyncId,
                    identityKey = parked.identityKey,
                    guid = merged.match?.guid,
                    enclosureKey = merged.match?.enc?.let(UrlNormalizer::forIdentity),
                    record = SyncJson.encodeToString(RecordDto.serializer(), mergedDto),
                    receivedAt = clock.now(),
                ),
            )
    }

    /**
     * Retries parked rows through the standard merge and apply rules. A row leaves the table only
     * in the same transaction as a terminal outcome (applied, held, superseded or invalid); a row
     * that still cannot resolve stays parked for the next ingest, dependency arrival or restart.
     * The caller owns the write transaction and `applying = 1`, so the projections stay echo-free.
     */
    internal suspend fun applyParkedRows(
        rows: List<SyncParkedEntity>,
        remotePlayed: MutableList<RemotePlayedMark> = mutableListOf(),
    ): SyncApplyResult {
        var applied = 0
        var stale = 0
        var parked = 0
        var held = 0
        var invalid = 0
        val resolved = ArrayList<Long>(rows.size)
        for (snapshot in rows) {
            // Rows were read before this transaction. Unlink may have removed them, or a
            // later page may have accumulated new fields; apply only the current journal row.
            val row = db.syncParkedDao().byId(snapshot.id) ?: continue
            val dto = decodeParked(row)
            val coll = dto?.let { runCatching { SyncCollection.fromWire(it.coll) }.getOrNull() }
            val retryable =
                coll == SyncCollection.MEMBER || coll == SyncCollection.EPISODE || coll == SyncCollection.UPNEXT
            if (!retryable) {
                // A row that no longer decodes to a parkable record can never apply.
                resolved += row.id
                invalid++
                continue
            }
            when (applyValidated(dto!!, roundId = 0L, journalParked = false, remotePlayed)) {
                RecordOutcome.Applied -> {
                    resolved += row.id
                    applied++
                }

                RecordOutcome.Held -> {
                    resolved += row.id
                    held++
                }

                RecordOutcome.Stale -> {
                    resolved += row.id
                    stale++
                }

                RecordOutcome.Invalid -> {
                    resolved += row.id
                    invalid++
                }

                RecordOutcome.Parked -> {
                    parked++
                }
            }
        }
        if (resolved.isNotEmpty()) db.syncParkedDao().delete(resolved)
        return SyncApplyResult(applied, stale, parked, held, invalid, remotePlayed.toList())
    }

    /** [applyParkedRows] in its own write transaction with the capture triggers silenced. */
    suspend fun applyParkedRecords(rows: List<SyncParkedEntity>): SyncApplyResult {
        val remotePlayed = mutableListOf<RemotePlayedMark>()
        val result =
            try {
                db.withWriteTransaction<SyncApplyResult> {
                    db.syncStateDao().withApplying<SyncApplyResult> { applyParkedRows(rows, remotePlayed) }
                }
            } catch (failure: Throwable) {
                revertMarks(remotePlayed)
                throw failure
            }
        postMarks(remotePlayed)
        return result
    }

    private fun decodeParked(row: SyncParkedEntity): RecordDto? =
        runCatching { SyncJson.decodeFromString(RecordDto.serializer(), row.record) }.getOrNull()

    /** A stored tombstone for a podcast this device does not have: its children drop, not park. */
    private suspend fun podcastDead(syncId: String): Boolean =
        db.podcastDao().bySyncId(syncId) == null &&
            clockStore.get(SyncCollection.PODCAST.wire, syncId)?.state?.let(RecordMerger::isDead) == true

    /** A stored `deleted` group with no local row (a held removal is not dead yet). */
    private suspend fun groupDead(uuid: String): Boolean =
        db.groupDao().groupByUuid(uuid) == null &&
            clockStore.get(SyncCollection.GROUP.wire, uuid)?.state?.let(RecordMerger::isDead) == true

    private suspend fun deleteParkedForPodcast(syncId: String) {
        val rows = db.syncParkedDao().forPodcast(syncId)
        if (rows.isNotEmpty()) db.syncParkedDao().delete(rows.map { it.id })
    }

    private suspend fun releaseParkedMembersByPodcast(syncId: String) {
        val rows =
            db.syncParkedDao().forPodcast(syncId).filter { it.identityKey.startsWith(MEMBER_KEY_PREFIX) }
        if (rows.isNotEmpty()) applyParkedRows(rows)
    }

    private suspend fun releaseParkedMembersByGroup(uuid: String) {
        val rows = db.syncParkedDao().all().filter { it.identityKey == MEMBER_KEY_PREFIX + uuid }
        if (rows.isNotEmpty()) applyParkedRows(rows)
    }

    private suspend fun deleteLostPending(
        coll: String,
        rid: String,
        advanced: Set<String>,
    ) {
        val rows = db.syncOutboxDao().rowsFor(coll, listOf(rid))
        for (row in rows) {
            if (row.field == "*") continue
            val wireFields = wireFieldsOf(row.field)
            if (wireFields.any { it in advanced }) {
                db.syncOutboxDao().deleteIfUnchanged(coll, rid, row.field, row.hlc, row.nodeId)
            }
        }
    }

    private fun wireFieldsOf(field: String): List<String> =
        if (field == "played") listOf("played", "playedAt") else listOf(field)

    /** Resolves a record ID through at most 8 redirect/alias hops (10 Redirects on clients). */
    private suspend fun resolve(id: RecordId): RecordId {
        var current = id
        repeat(8) {
            val raw = clockStore.get(current.collWire(), current.rid) ?: return current
            val next = raw.redirect ?: raw.alias ?: return current
            val parsed =
                runCatching { RecordId.fromRid(SyncCollection.fromWire(current.collWire()), next) }.getOrNull()
                    ?: return current
            current = parsed
        }
        return current
    }

    private fun RecordId.collWire(): String =
        when (this) {
            is RecordId.Podcast -> SyncCollection.PODCAST.wire
            is RecordId.Group -> SyncCollection.GROUP.wire
            is RecordId.Member -> SyncCollection.MEMBER.wire
            is RecordId.Episode -> SyncCollection.EPISODE.wire
            RecordId.Session -> SyncCollection.SESSION.wire
            is RecordId.Setting -> SyncCollection.SETTING.wire
        }

    private suspend fun pendingState(
        coll: SyncCollection,
        rid: String,
    ): RecordState? {
        val rows = db.syncOutboxDao().rowsFor(coll.wire, listOf(rid))
        if (rows.isEmpty()) return null
        val fields = mutableMapOf<String, FieldState>()
        val growSets = mutableMapOf<String, Set<String>>()
        for (row in rows) {
            if (row.field == "~rekey") continue
            val clock = outboxClock(row) ?: continue
            if (row.field == "*") {
                val all = SourceReader(db, secrets, sharePasswords).allFields(coll, rid)
                all.fields.forEach { (name, value) -> fields[name] = value.copy(c = clock) }
                all.growSets.forEach { (name, values) -> growSets[name] = values }
                continue
            }
            when (row.field) {
                "played" -> {
                    val literal =
                        row.value?.let {
                            runCatching {
                                SyncJson.parseToJsonElement(
                                    it,
                                ) as? JsonObject
                            }.getOrNull()
                        }
                    val played = (literal?.get("played") as? JsonPrimitive)?.booleanOrNull
                    val playedAt = (literal?.get("playedAt") as? JsonPrimitive)?.longOrNull
                    if (played != null) fields["played"] = FieldState(JsonPrimitive(played), clock)
                    fields["playedAt"] = FieldState(playedAt?.let(::JsonPrimitive) ?: JsonNull, clock)
                }

                "pos" -> {
                    val literal =
                        row.value?.let {
                            runCatching {
                                SyncJson.parseToJsonElement(
                                    it,
                                ) as? JsonObject
                            }.getOrNull()
                        }
                    val ms = (literal?.get("ms") as? JsonPrimitive)?.longOrNull ?: 0L
                    val reset = (literal?.get("reset") as? JsonPrimitive)?.booleanOrNull == true
                    fields["pos"] = FieldState(JsonObject(buildMap { put("ms", JsonPrimitive(ms)) }), clock, reset)
                }

                "feedKeys" -> {
                    val keys = SourceReader(db, secrets, sharePasswords).feedKeys(rid).toSet()
                    if (keys.isNotEmpty()) growSets["feedKeys"] = keys
                }

                "v" -> {
                    val value =
                        row.value?.let { runCatching { SyncJson.parseToJsonElement(it) }.getOrNull() } ?: continue
                    fields["v"] = FieldState(value, clock)
                }

                else -> {
                    val value =
                        row.value?.let { runCatching { SyncJson.parseToJsonElement(it) }.getOrNull() }
                            ?: SourceReader(db, secrets, sharePasswords).field(coll, rid, row.field)
                            ?: continue
                    fields[row.field] = FieldState(value, clock)
                }
            }
        }
        if (fields.isEmpty() && growSets.isEmpty()) return null
        return RecordState(coll, fields, growSets)
    }
}

private sealed interface RecordOutcome {
    data object Applied : RecordOutcome

    data object Stale : RecordOutcome

    data object Parked : RecordOutcome

    data object Held : RecordOutcome

    data object Invalid : RecordOutcome
}

private fun RecordState.booleanField(name: String): Boolean? = (fields[name]?.v as? JsonPrimitive)?.booleanOrNull

private fun RecordState.stringField(name: String): String? =
    (fields[name]?.v as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun RecordState.longField(name: String): Long? = (fields[name]?.v as? JsonPrimitive)?.longOrNull

/** Three states: an absent field keeps [fallback], `JsonNull` clears, a number wins. */
private fun RecordState.nullableLongField(
    name: String,
    fallback: Long?,
): Long? =
    when (val value = fields[name]?.v) {
        null -> fallback
        JsonNull -> null
        is JsonPrimitive -> value.longOrNull
        else -> null
    }

private fun RecordState.intField(name: String): Int? = (fields[name]?.v as? JsonPrimitive)?.intOrNull

private fun RecordState.floatField(name: String): Float? =
    (fields[name]?.v as? JsonPrimitive)?.contentOrNull?.toFloatOrNull()

private inline fun <reified E : Enum<E>> RecordState.enumField(name: String): E? =
    stringField(name)?.let { value -> enumValues<E>().firstOrNull { it.name == value } }

private fun RecordState.fieldClock(name: String): String? = fields[name]?.c?.toString()

private fun maxOfNullable(
    a: Long?,
    b: Long?,
): Long? =
    when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }

/** The stub row of 02 Restore matching: `inFeed = 0`, `isNew = 0`, `contentHash = 0`. */
private fun episodeEntity(
    podcastId: Long,
    key: String,
    hints: ch.lkmc.neutrodyne.sync.protocol.dto.MatchHints?,
    now: Long,
): EpisodeEntity {
    val sortDate =
        hints?.date?.let { date ->
            runCatching {
                kotlin.time.Instant
                    .parse(date)
                    .toEpochMilliseconds()
            }.getOrNull()
        } ?: now
    return EpisodeEntity(
        podcastId = podcastId,
        identityKey = key,
        guid = hints?.guid,
        title = hints?.title ?: key,
        pubDate = sortDate,
        sortDate = minOf(sortDate, now + 86_400_000L),
        feedOrder = 0,
        firstSeenAt = now,
        lastSeenAt = now,
        inFeed = false,
        isNew = false,
        enclosureUrl = hints?.enc,
        externalMediaId = hints?.ytId,
        link = hints?.link,
        contentHash = 0,
    )
}
