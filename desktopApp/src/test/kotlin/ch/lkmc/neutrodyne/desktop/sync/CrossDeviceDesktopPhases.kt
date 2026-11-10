// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.sync

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.database.EpisodeEntity
import ch.lkmc.neutrodyne.core.database.EpisodePositionEntity
import ch.lkmc.neutrodyne.core.database.EpisodeStateEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastEntity
import ch.lkmc.neutrodyne.core.database.PodcastGroupEntity
import ch.lkmc.neutrodyne.core.database.PodcastGroupMemberEntity
import ch.lkmc.neutrodyne.core.database.QueueEntryEntity
import ch.lkmc.neutrodyne.core.domain.OrderedRow
import ch.lkmc.neutrodyne.core.domain.planOrderKeyWrites
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.testing.ProtocolOrderKeys
import ch.lkmc.neutrodyne.core.testing.sync.SyncAddress
import ch.lkmc.neutrodyne.core.testing.sync.canonicalSyncSnapshot
import ch.lkmc.neutrodyne.sync.impl.RawSyncRecord
import ch.lkmc.neutrodyne.sync.protocol.OrderKey
import ch.lkmc.neutrodyne.sync.protocol.RecordId
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** The seed's row counts, written to the ownership marker and the phase result. */
internal data class CrossDeviceSeedCounts(
    val podcasts: Int,
    val groups: Int,
    val episodes: Int,
    val members: Int,
    val upNext: Int,
)

/**
 * The deterministic fixture write (09 E12 seed): 50 RSS podcasts, one episode each, two groups of
 * 25 members and representative played / in-progress / favourite / Up next values, all through the
 * production DAOs. The capture triggers stay silent while `sync_state.enabled = 0`, so the seed is
 * bootstrap state and the outbox stays empty.
 */
internal class CrossDeviceSeedWriter(
    private val db: NeutrodyneDatabase,
    private val fixture: CrossDeviceFixture,
) {
    /**
     * A fresh app only: an existing library, link or sync residue fails the phase rather than
     * being cleared.
     */
    suspend fun checkFresh() {
        val existingPodcasts = db.podcastDao().allForExport()
        val existingGroups = db.groupDao().groupsInOrder()
        val existingEpisodes = db.episodeDao().all()
        val existingQueue = db.queueDao().entries()
        val existingOutbox = db.syncOutboxDao().count()
        val existingClocks = db.syncClockDao().count()
        val existingHeld = db.syncHeldDao().count()
        val existingParked = db.syncParkedDao().count()
        val syncState = db.syncStateDao().get()
        if (existingPodcasts.isNotEmpty() || existingGroups.isNotEmpty() || existingEpisodes.isNotEmpty() ||
            existingQueue.isNotEmpty() || existingOutbox > 0 || existingClocks > 0 || existingHeld > 0 ||
            existingParked > 0
        ) {
            throw PhaseError("seed", "existing non-fixture library or sync state")
        }
        if (syncState.hasLinkOrSyncResidue()) {
            throw PhaseError("seed", "existing non-fixture link or sync state")
        }
    }

    suspend fun write(): CrossDeviceSeedCounts {
        db.withWriteTransaction {
            val podcastIds =
                fixture.podcasts.map { podcast ->
                    db.podcastDao().insertPodcast(
                        PodcastEntity(
                            syncId = podcast.syncId,
                            sourceType = SourceType.RSS,
                            feedUrl = podcast.feedUrl,
                            feedKey = podcast.feedKey,
                            title = podcast.title,
                            author = podcast.author,
                            link = podcast.link,
                            artworkKey = podcast.artworkKey,
                            status = PodcastStatus.ACTIVE,
                            initialFetch = false,
                            subscribedAt = T_BASE,
                            guidCoverageSince = T_BASE,
                            nextRefreshAt = fixtureNextRefreshAt(System.currentTimeMillis()),
                        ),
                    )
                }
            val episodeIds =
                fixture.episodes.map { episode ->
                    db
                        .ingestDao()
                        .insertEpisodes(
                            listOf(
                                EpisodeEntity(
                                    podcastId = podcastIds[episode.podcastIndex],
                                    identityKey = episode.identityKey,
                                    guid = episode.guid,
                                    title = episode.title,
                                    pubDate = episode.pubDate,
                                    sortDate = episode.pubDate,
                                    feedOrder = episode.feedOrder,
                                    firstSeenAt = T_BASE,
                                    lastSeenAt = T_BASE,
                                    enclosureUrl = episode.enclosureUrl,
                                    enclosureType = episode.enclosureType,
                                    enclosureLength = episode.enclosureLength,
                                    durationMs = episode.durationMs,
                                    link = episode.link,
                                    contentHash = episode.identityKey.hashCode().toLong(),
                                ),
                            ),
                        ).single()
                }

            // Representative, disjoint user state: played / in-progress / favourite / Up next.
            fixture.episodes.forEachIndexed { index, episode ->
                val episodeId = episodeIds[index]
                when (index % 6) {
                    0 -> {
                        db.episodeStateDao().upsert(
                            EpisodeStateEntity(
                                episodeId = episodeId,
                                playedAt = T_BASE + index,
                                playCount = 1,
                                lastPlayedAt = T_BASE + index,
                                updatedAt = T_BASE + index,
                            ),
                        )
                    }

                    1 -> {
                        db.episodeStateDao().upsert(
                            EpisodeStateEntity(
                                episodeId = episodeId,
                                startedAt = T_BASE + index,
                                updatedAt = T_BASE + index,
                            ),
                        )
                        db.positionDao().upsert(
                            EpisodePositionEntity(
                                episodeId = episodeId,
                                positionMs = 30_000L + index,
                                durationMs = episode.durationMs,
                                positionSource = PositionSource.STREAM,
                                updatedAt = T_BASE + index,
                            ),
                        )
                    }

                    2 -> {
                        db.episodeStateDao().upsert(
                            EpisodeStateEntity(
                                episodeId = episodeId,
                                isFavorite = true,
                                updatedAt = T_BASE + index,
                            ),
                        )
                    }
                }
            }

            val queueOrderKeys = OrderKey.rewrite(QUEUE_COUNT)
            var queueIndex = 0
            fixture.episodes.forEachIndexed { index, _ ->
                if (index % 6 == 3) {
                    db.queueDao().insert(
                        QueueEntryEntity(
                            episodeId = episodeIds[index],
                            orderKey = queueOrderKeys[queueIndex++],
                            addedAt = T_BASE,
                        ),
                    )
                }
            }

            val groupOrderKeys = OrderKey.rewrite(fixture.groups.size)
            fixture.groups.forEachIndexed { groupIndex, group ->
                val groupId =
                    db.groupDao().insert(
                        PodcastGroupEntity(
                            uuid = group.uuid,
                            name = group.name,
                            nameKey = group.nameKey,
                            orderKey = groupOrderKeys[groupIndex],
                            createdAt = T_BASE,
                            updatedAt = T_BASE,
                        ),
                    )
                val memberKeys = OrderKey.rewrite(group.memberPodcastIndices.size)
                group.memberPodcastIndices.forEachIndexed { memberIndex, podcastIndex ->
                    db.groupDao().insertMember(
                        PodcastGroupMemberEntity(
                            groupId = groupId,
                            podcastId = podcastIds[podcastIndex],
                            orderKey = memberKeys[memberIndex],
                            addedAt = T_BASE,
                        ),
                    )
                }
            }
        }
        return CrossDeviceSeedCounts(
            podcasts = fixture.podcasts.size,
            groups = fixture.groups.size,
            episodes = fixture.episodes.size,
            members = fixture.groups.sumOf { it.memberPodcastIndices.size },
            upNext = QUEUE_COUNT,
        )
    }
}

/**
 * The bounded offline edit sequence. Every operation is keyed by fixture index and writes through
 * the normal DAO writers, so the existing capture triggers own the HLC and outbox; no outbox or
 * accepted-clock row is injected.
 */
internal class CrossDeviceEditApplier(
    private val db: NeutrodyneDatabase,
    private val fixture: CrossDeviceFixture,
) {
    suspend fun apply(edits: List<CrossDeviceEdit>): Int {
        var applied = 0
        for (edit in edits) {
            db.withWriteTransaction { applyEdit(edit) }
            applied++
        }
        return applied
    }

    private suspend fun applyEdit(edit: CrossDeviceEdit) {
        val now = System.currentTimeMillis()
        when (edit.op) {
            "played" -> {
                val episodeId = requireEpisode(edit).id
                db.episodeStateDao().ensure(EpisodeStateEntity(episodeId = episodeId, updatedAt = now))
                db.episodeStateDao().markPlayed(
                    listOf(episodeId),
                    edit.value ?: throw PhaseError("edit", "played requires value"),
                )
            }

            "unplayed" -> {
                val episodeId = requireEpisode(edit).id
                db.episodeStateDao().ensure(EpisodeStateEntity(episodeId = episodeId, updatedAt = now))
                db.episodeStateDao().markUnplayed(listOf(episodeId), now)
            }

            "position" -> {
                val episode = requireEpisode(edit)
                db.episodeStateDao().ensure(EpisodeStateEntity(episodeId = episode.id, updatedAt = now))
                db.episodeStateDao().markStarted(episode.id, now)
                db.positionDao().upsert(
                    EpisodePositionEntity(
                        episodeId = episode.id,
                        positionMs = edit.value ?: throw PhaseError("edit", "position requires value"),
                        durationMs = episode.durationMs,
                        positionSource = PositionSource.STREAM,
                        updatedAt = now,
                    ),
                )
            }

            "fav" -> {
                val episodeId = requireEpisode(edit).id
                db.episodeStateDao().ensure(EpisodeStateEntity(episodeId = episodeId, updatedAt = now))
                db.episodeStateDao().setFavorite(
                    episodeId,
                    edit.fav ?: throw PhaseError("edit", "fav requires fav"),
                    now,
                )
            }

            "membership_add" -> {
                val (groupId, podcastId) = requireMembership(edit)
                val orderKey = ProtocolOrderKeys.after(db.groupDao().lastMemberOrderKey(groupId))
                db.groupDao().insertMember(
                    PodcastGroupMemberEntity(
                        groupId = groupId,
                        podcastId = podcastId,
                        orderKey = orderKey,
                        addedAt = now,
                    ),
                )
            }

            "membership_remove" -> {
                val (groupId, podcastId) = requireMembership(edit)
                db.groupDao().deleteMembers(groupId, listOf(podcastId))
            }

            "group_order" -> {
                val group =
                    fixture.groups.getOrNull(
                        edit.group ?: throw PhaseError("edit", "group_order requires group"),
                    ) ?: throw PhaseError("edit", "unknown group index")
                val current = db.groupDao().groupsInOrder()
                val movingId =
                    current.firstOrNull { it.uuid == group.uuid }?.id
                        ?: throw PhaseError("edit", "fixture group is missing")
                val requested = current.map { it.id }.toMutableList()
                requested.remove(movingId)
                val before = (edit.before ?: requested.size).coerceIn(0, requested.size)
                requested.add(before, movingId)
                val writes =
                    planOrderKeyWrites(
                        current.map { OrderedRow(it.id, it.orderKey) },
                        requested,
                        ProtocolOrderKeys,
                    ) ?: throw PhaseError("edit", "group_order is not a permutation")
                for ((groupId, orderKey) in writes) {
                    db.groupDao().setGroupOrderKey(groupId, orderKey, now)
                }
            }

            "queue_add" -> {
                val episodeId = requireEpisode(edit).id
                val entries = db.queueDao().entries()
                val orderKey =
                    if (edit.before == null) {
                        ProtocolOrderKeys.after(db.queueDao().lastOrderKey())
                    } else {
                        val anchor =
                            entries.getOrNull(edit.before)
                                ?: throw PhaseError("edit", "queue_add before index is out of range")
                        OrderKey.before(anchor.orderKey)
                    }
                db.queueDao().insert(
                    QueueEntryEntity(episodeId = episodeId, orderKey = orderKey, addedAt = now),
                )
            }

            "queue_remove" -> {
                val episodeId = requireEpisode(edit).id
                db.queueDao().removeEpisodes(listOf(episodeId))
            }

            "unsubscribe" -> {
                val podcast =
                    fixture.podcasts.getOrNull(
                        edit.podcast ?: throw PhaseError("edit", "unsubscribe requires podcast"),
                    ) ?: throw PhaseError("edit", "unknown podcast index")
                val row =
                    db.podcastDao().bySyncId(podcast.syncId)
                        ?: throw PhaseError("edit", "fixture podcast is missing")
                db.podcastDao().deleteCascade(row.id, now)
            }

            else -> {
                throw PhaseError("edit", "unknown operation")
            }
        }
    }

    private suspend fun requireEpisode(edit: CrossDeviceEdit): EpisodeEntity {
        val podcastIndex = edit.podcast ?: throw PhaseError("edit", "${edit.op} requires podcast")
        val episodeIndex = edit.episode ?: throw PhaseError("edit", "${edit.op} requires episode")
        val fixtureEpisode =
            fixture.episodes.getOrNull(episodeIndex)
                ?: throw PhaseError("edit", "unknown episode index")
        if (fixtureEpisode.podcastIndex != podcastIndex) {
            throw PhaseError("edit", "episode does not belong to podcast")
        }
        return db.episodeDao().all().firstOrNull { it.identityKey == fixtureEpisode.identityKey }
            ?: throw PhaseError("edit", "fixture episode is missing")
    }

    private suspend fun requireMembership(edit: CrossDeviceEdit): Pair<Long, Long> {
        val groupIndex = edit.group ?: throw PhaseError("edit", "${edit.op} requires group")
        val podcastIndex = edit.podcast ?: throw PhaseError("edit", "${edit.op} requires podcast")
        val group =
            fixture.groups.getOrNull(groupIndex)
                ?: throw PhaseError("edit", "unknown group index")
        val podcast =
            fixture.podcasts.getOrNull(podcastIndex)
                ?: throw PhaseError("edit", "unknown podcast index")
        val groupRow =
            db.groupDao().groupByUuid(group.uuid)
                ?: throw PhaseError("edit", "fixture group is missing")
        val podcastRow =
            db.podcastDao().bySyncId(podcast.syncId)
                ?: throw PhaseError("edit", "fixture podcast is missing")
        return groupRow.id to podcastRow.id
    }
}

internal fun defaultEdits(): List<CrossDeviceEdit> =
    listOf(
        CrossDeviceEdit("played", podcast = 1, episode = 1, value = T_BASE + 1_000),
        CrossDeviceEdit("unplayed", podcast = 0, episode = 0),
        CrossDeviceEdit("position", podcast = 2, episode = 2, value = 60_000L),
        CrossDeviceEdit("fav", podcast = 3, episode = 3, fav = true),
        CrossDeviceEdit("membership_add", group = 0, podcast = 25),
        CrossDeviceEdit("membership_remove", group = 0, podcast = 0),
        CrossDeviceEdit("group_order", group = 1, before = 0),
        CrossDeviceEdit("queue_add", podcast = 4, episode = 4, before = 0),
        CrossDeviceEdit("queue_remove", podcast = 3, episode = 3),
        CrossDeviceEdit("unsubscribe", podcast = 49),
    )

/**
 * The canonical accepted wire state plus the materialized fixture library and the held batch
 * summary. The wire snapshot comes from `sync_clock` through `:core:testing`'s
 * `canonicalSyncSnapshot`; `auth` fields are stripped and no credential, token, auth reference or
 * filesystem path is written.
 */
internal class CrossDeviceDumper(
    private val db: NeutrodyneDatabase,
    private val fixture: CrossDeviceFixture,
) {
    suspend fun dump(): JsonObject {
        val records = mutableMapOf<SyncAddress, RecordState>()
        var skippedClockRows = 0
        for (row in db.syncClockDao().all()) {
            try {
                val coll = SyncCollection.fromWire(row.coll)
                val id = RecordId.fromRid(coll, row.rid)
                val raw = SyncJson.decodeFromString<RawSyncRecord>(row.clocks)
                records[SyncAddress(coll, id)] = sanitize(raw.state)
            } catch (_: Exception) {
                skippedClockRows++
            }
        }
        // A malformed row silently removes a record from the canonical wire state the host
        // compares, so the phase must fail rather than report a partial dump.
        val dumpFailure = skippedClockRowsFailure(skippedClockRows)
        if (dumpFailure != null) {
            throw PhaseError("dump", dumpFailure)
        }
        val snapshot =
            try {
                canonicalSyncSnapshot(records)
            } catch (_: Exception) {
                throw PhaseError("dump", "canonical snapshot failed")
            }

        val podcastRows = db.podcastDao().allForExport().associateBy { it.syncId }
        val podcastIndexById =
            fixture.podcasts
                .mapIndexedNotNull { index, podcast ->
                    podcastRows[podcast.syncId]?.id?.let { it to index }
                }.toMap()
        val episodeRows = db.episodeDao().all()
        val episodesByKey = episodeRows.associateBy { it.identityKey }
        val groupRows = db.groupDao().groupsInOrder()
        val queueEntries = db.queueDao().entries()
        val queuedEpisodeIds = queueEntries.map { it.episodeId }.toSet()

        val library =
            buildJsonObject {
                put(
                    "podcasts",
                    buildJsonArray {
                        fixture.podcasts.forEach { podcast ->
                            add(
                                buildJsonObject {
                                    put("index", podcast.index)
                                    put("syncId", podcast.syncId)
                                    put("title", podcast.title)
                                    put("subscribed", podcastRows.containsKey(podcast.syncId))
                                },
                            )
                        }
                    },
                )
                put(
                    "episodes",
                    buildJsonArray {
                        fixture.episodes.forEach { episode ->
                            val row = episodesByKey[episode.identityKey]
                            val state = row?.let { db.episodeStateDao().byEpisode(it.id) }
                            val position = row?.let { db.positionDao().byEpisode(it.id) }
                            add(
                                buildJsonObject {
                                    put("podcastIndex", episode.podcastIndex)
                                    put("identityKey", episode.identityKey)
                                    put("title", episode.title)
                                    put("present", row != null)
                                    put("playedAt", JsonPrimitive(state?.playedAt))
                                    put("positionMs", position?.positionMs ?: 0)
                                    put("favorite", state?.isFavorite ?: false)
                                    put("inUpNext", row != null && row.id in queuedEpisodeIds)
                                },
                            )
                        }
                    },
                )
                put(
                    "groups",
                    buildJsonArray {
                        groupRows.forEach { group ->
                            add(
                                buildJsonObject {
                                    put("uuid", group.uuid)
                                    put("name", group.name)
                                    put("orderKey", group.orderKey)
                                    put(
                                        "members",
                                        buildJsonArray {
                                            db.groupDao().membersOf(group.id).forEach { member ->
                                                add(podcastIndexById[member.podcastId] ?: -1)
                                            }
                                        },
                                    )
                                },
                            )
                        }
                    },
                )
                put(
                    "upNext",
                    buildJsonArray {
                        queueEntries.forEach { entry ->
                            val episodeRow = episodeRows.firstOrNull { it.id == entry.episodeId }
                            val fixtureEpisode =
                                episodeRow?.let { row ->
                                    fixture.episodes.firstOrNull {
                                        it.identityKey ==
                                            row.identityKey
                                    }
                                }
                            add(
                                buildJsonObject {
                                    put("podcastIndex", fixtureEpisode?.podcastIndex ?: -1)
                                    put("identityKey", episodeRow?.identityKey.orEmpty())
                                    put("title", fixtureEpisode?.title ?: episodeRow?.title.orEmpty())
                                    put("orderKey", entry.orderKey)
                                },
                            )
                        }
                    },
                )
            }

        val held =
            buildJsonArray {
                db.syncHeldDao().all().forEach { row ->
                    val summary =
                        runCatching { crossDeviceJson.parseToJsonElement(row.summary).jsonObject }.getOrNull()
                    add(
                        buildJsonObject {
                            put("id", row.id)
                            put("heldAt", row.heldAt)
                            put("podcasts", summary.titles("podcasts"))
                            put("groups", summary.titles("groups"))
                        },
                    )
                }
            }

        return buildJsonObject {
            put("wire", crossDeviceJson.parseToJsonElement(snapshot.canonicalRaw()))
            put("library", library)
            put("held", held)
            put("skippedClockRows", skippedClockRows)
            put("counts", counts(db))
        }
    }

    private fun JsonObject?.titles(key: String): JsonArray =
        buildJsonArray {
            (this@titles?.get(key) as? JsonArray)?.forEach { add(it) }
        }
}

internal suspend fun counts(db: NeutrodyneDatabase): JsonObject =
    buildJsonObject {
        put("podcasts", db.podcastDao().allForExport().size)
        put("episodes", db.episodeDao().all().size)
        put("groups", db.groupDao().groupsInOrder().size)
        put("members", db.groupDao().membershipPairs().size)
        put("upNext", db.queueDao().entries().size)
        put("outbox", db.syncOutboxDao().count())
        put("clock", db.syncClockDao().count())
        put("held", db.syncHeldDao().count())
        put("parked", db.syncParkedDao().count())
    }
