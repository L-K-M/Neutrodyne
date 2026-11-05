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
import ch.lkmc.neutrodyne.sync.protocol.EpisodeProgress
import ch.lkmc.neutrodyne.sync.protocol.EpisodeStateRules
import ch.lkmc.neutrodyne.sync.protocol.FieldState
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.OrderKey
import ch.lkmc.neutrodyne.sync.protocol.ProtocolValidation
import ch.lkmc.neutrodyne.sync.protocol.RecordId
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
    /** Podcasts that need a password no received `auth` covers (10 Feed passwords). */
    val passwordNeeded: List<Long> = emptyList(),
)

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
        val passwordNeeded = LinkedHashSet<Long>()
        // 10 Durable apply effects: stage every candidate secret before the page transaction; the
        // transaction then references only protected keys, and losing candidates leave again.
        val stage =
            AuthStage(
                secrets,
                db
                    .syncStateDao()
                    .get()
                    ?.accountId
                    .orEmpty(),
            )
        val marked = stage.admit(records)
        val referenced = HashSet<String>()
        db.withWriteTransaction {
            db.syncStateDao().withApplying {
                val ordered = marked.sortedBy { it.coll }
                for (dto in ordered) {
                    val result = applyRecord(dto, roundId, stage, referenced, passwordNeeded)
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
        stage.removeUnreferenced(referenced)
        return SyncApplyResult(applied, stale, parked, held, invalid, passwordNeeded.toList())
    }

    private suspend fun applyRecord(
        dto: RecordDto,
        roundId: Long,
        stage: AuthStage,
        referenced: MutableSet<String>,
        passwordNeeded: MutableCollection<Long>,
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
            // An equal-clock redelivery: its staged candidate IS the stored reference, so the
            // cleanup pass must not treat it as a losing candidate.
            val staged = stage.stagedKey(resolved.rid, incoming.fields["auth"]?.c?.toString())
            if (staged != null && stored?.authReference?.storeKey == staged) referenced += staged
            return RecordOutcome.Stale
        }
        val outcome =
            when (coll) {
                SyncCollection.PODCAST -> applyPodcast(resolved, merge, roundId, passwordNeeded)
                SyncCollection.GROUP -> applyGroup(resolved, merge, roundId)
                SyncCollection.MEMBER -> applyMember(resolved, merge)
                SyncCollection.EPISODE -> applyEpisode(resolved, merge, dto.by)
                SyncCollection.UPNEXT -> applyUpNext(resolved, merge)
                SyncCollection.SESSION -> applySession(resolved, merge)
                SyncCollection.SETTING -> applySetting(resolved, merge)
            }
        if (outcome is RecordOutcome.Applied || outcome is RecordOutcome.Held) {
            val pendingSetting =
                if (coll == SyncCollection.SETTING) {
                    settingPendingMarker(stored, merge.record)
                } else {
                    null
                }
            val protected = protectAuth(resolved.rid, merge, stored, referenced)
            clockStore.put(
                coll.wire,
                resolved.rid,
                RawSyncRecord(
                    state = protected.state,
                    by = dto.by ?: stored?.by,
                    pendingSetting = pendingSetting ?: stored?.pendingSetting,
                    authReference = protected.authReference ?: stored?.authReference,
                    pendingAuth = protected.pendingAuth,
                ),
            )
            deleteLostPending(coll.wire, resolved.rid, merge.advanced)
        }
        return outcome
    }

    /**
     * 10 Durable apply effects, passwords: no plaintext `auth` enters raw state ([protectAuthRecord]
     * owns the rules). This wrapper keeps the page's referenced staging keys alive.
     */
    private suspend fun protectAuth(
        rid: String,
        merge: RecordMerge,
        stored: RawSyncRecord?,
        referenced: MutableSet<String>,
    ): ProtectedAuth {
        val protection = protectAuthRecord(db, rid, merge.record, merge.advanced, stored)
        val key = protection.authReference?.storeKey
        if (key != null && key.startsWith(PENDING_ORIGIN_PREFIX)) referenced += key
        return ProtectedAuth(protection.state, protection.pendingAuth, protection.authReference)
    }

    private data class ProtectedAuth(
        val state: RecordState,
        val pendingAuth: PendingAuth?,
        val authReference: AuthReference?,
    )

    private suspend fun applyPodcast(
        id: RecordId,
        merge: RecordMerge,
        roundId: Long,
        passwordNeeded: MutableCollection<Long>,
    ): RecordOutcome {
        val rid = (id as RecordId.Podcast).id.value
        val existing = db.podcastDao().bySyncId(rid)
        val subscribed = merge.record.booleanField("subscribed") ?: true
        if (!subscribed) {
            if (existing == null) {
                // Unknown and dead: record the clocks so its child records are dropped.
                return RecordOutcome.Applied
            }
            return if (merge.advanced.contains("subscribed")) {
                // 10 Mass-change guard rule 4: a removal of the podcast loaded in a playing
                // player never interrupts playback — it defers into its own held row until the
                // player stops or the item changes.
                if (isPlayingPodcast(rid)) {
                    MassChangeGuard.stageDeferred(db, roundId, rid, merge.record.fieldClock("subscribed"))
                } else {
                    MassChangeGuard.stagePodcast(db, roundId, rid, merge.record.fieldClock("subscribed"))
                }
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
            if (needsPasswordWithoutAuth(merge.record, credentialId = null)) passwordNeeded += idInserted
            return RecordOutcome.Applied
        }
        applyPodcastFields(existing, merge.record, passwordNeeded)
        return RecordOutcome.Applied
    }

    /**
     * 10 Feed passwords: a podcast the record says needs a password, with no winning `auth` and no
     * local credential for it, keeps `needsCredentials = 1` and joins the pull's
     * `FeedPasswordNeeded` notice. The wire flag means "this feed requires a password", never this
     * device's error state, so a held credential answers it.
     */
    private fun needsPasswordWithoutAuth(
        record: RecordState,
        credentialId: Long?,
    ): Boolean =
        credentialId == null &&
            record.fields["auth"] == null &&
            (record.booleanField("needsCredentials") == true || record.stringField("credentialOrigin") != null)

    /**
     * 10 Mass-change guard rule 4: the podcast of the loaded, playing item. A paused player is
     * not deferred — only a playing one is interrupted by nothing (R7.5).
     */
    private suspend fun isPlayingPodcast(podcastRid: String): Boolean {
        val active = playback.active.value ?: return false
        if (!active.isPlaying) return false
        val episode = db.episodeDao().byId(active.episodeId) ?: return false
        return db.podcastDao().byId(episode.podcastId)?.syncId == podcastRid
    }

    private suspend fun applyPodcastFields(
        existing: PodcastEntity,
        record: RecordState,
        passwordNeeded: MutableCollection<Long>,
    ) {
        val now = clock.now()
        record.booleanField("needsCredentials")?.let { needs ->
            when {
                // A local credential already answers the flag; never re-flag this device.
                needs && existing.credentialId == null -> {
                    db.podcastDao().markNeedsCredentials(existing.id)
                }

                !needs -> {
                    db.podcastDao().clearNeedsCredentials(existing.id)
                }

                else -> {}
            }
        }
        if (needsPasswordWithoutAuth(record, existing.credentialId)) passwordNeeded += existing.id
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
        if (group == null || podcast == null) return RecordOutcome.Parked
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
    ): RecordOutcome {
        val episodeId = id as RecordId.Episode
        val podcast =
            db.podcastDao().bySyncId(episodeId.podcast.value)
                ?: return RecordOutcome.Parked
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
                        reportRemotePlayedIfLoaded(it.id, merge, by)
                        applyEpisodeState(it.id, merge.record)
                    }
            } else {
                reportRemotePlayedIfLoaded(idInserted, merge, by)
                applyEpisodeState(idInserted, merge.record)
            }
            return RecordOutcome.Applied
        }
        reportRemotePlayedIfLoaded(matched, merge, by)
        applyEpisodeState(matched, merge.record)
        return RecordOutcome.Applied
    }

    /**
     * 10 Episode-state rules rule 2: only a winning `played = true` that makes the merged episode
     * effectively played freezes the loaded player, and only before that state is projected — an
     * older mark hidden by a newer listening position derives to in-progress and calls nothing.
     */
    private suspend fun reportRemotePlayedIfLoaded(
        episodeId: Long,
        merge: RecordMerge,
        by: String?,
    ) {
        if (EpisodeStateRules.derive(merge.record.fields).state != EpisodeProgress.PLAYED) return
        if (playback.active.value?.episodeId != episodeId) return
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
                ?: return RecordOutcome.Parked
        val matched = matcher.match(podcast.id, episodeId.key.value, merge.record.match)
        if (matched == null) return RecordOutcome.Parked
        val inQueue = merge.record.booleanField("in") ?: true
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
        val incomingClock = merge.record.maxClock?.toString() ?: ""
        val pending = storedPending(settingKey.name, incomingClock) ?: PendingSetting(value, incomingClock)
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

    /**
     * 10 Durable apply effects: the marker this record's page journals. An undrained stored
     * marker at least as new as the record keeps its identity — a replay cannot reset the
     * effect's completion comparison (clock equality); anything older is re-created from the
     * merged winner.
     */
    private fun settingPendingMarker(
        stored: RawSyncRecord?,
        record: RecordState,
    ): PendingSetting? {
        val value = record.fields["v"]?.v
        val text = (value as? JsonPrimitive)?.content ?: value?.toString() ?: return stored?.pendingSetting
        val fresh = PendingSetting(text, record.maxClock?.toString() ?: "")
        val storedPending = stored?.pendingSetting ?: return fresh
        val storedClock = runCatching { Hlc.parse(storedPending.clock) }.getOrNull() ?: return fresh
        val freshClock = runCatching { Hlc.parse(fresh.clock) }.getOrNull() ?: return storedPending
        return if (storedClock >= freshClock) storedPending else fresh
    }

    /**
     * The stored pending marker of [key] when it names work at least as new as [incomingClock]
     * — the receive-side half of [settingPendingMarker]'s rule.
     */
    private suspend fun storedPending(
        key: String,
        incomingClock: String?,
    ): PendingSetting? {
        val raw = clockStore.get(SyncCollection.SETTING.wire, key) ?: return null
        val pending = raw.pendingSetting ?: return null
        val storedClock = runCatching { Hlc.parse(pending.clock) }.getOrNull() ?: return null
        val incoming =
            incomingClock?.let { runCatching { Hlc.parse(it) }.getOrNull() } ?: return pending
        return if (storedClock >= incoming) pending else null
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
