// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing.database

import ch.lkmc.neutrodyne.core.database.DownloadEntity
import ch.lkmc.neutrodyne.core.database.EpisodeEntity
import ch.lkmc.neutrodyne.core.database.EpisodeStateEntity
import ch.lkmc.neutrodyne.core.database.PodcastEntity
import ch.lkmc.neutrodyne.core.database.PodcastGroupEntity
import ch.lkmc.neutrodyne.core.database.PodcastGroupMemberEntity
import ch.lkmc.neutrodyne.core.database.QueueEntryEntity
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.SourceKind
import ch.lkmc.neutrodyne.core.model.SourceType
import kotlin.uuid.Uuid

/**
 * Entity fixtures with readable defaults (09 Shared helpers: data builders; 02 Testing). Every
 * `podcastEntity` carries a fresh random `syncId` — mirroring the production insert paths, which
 * assign a UUIDv4 on subscribe/import/restore — so `SyncInertTest` can assert uniqueness, and
 * callers opt into a fixed one explicitly.
 */
fun podcastEntity(
    id: Long = 0,
    syncId: String = Uuid.random().toString(),
    // feedKey is a unique index: a second default call would collide, like syncId/artworkKey.
    feedUrl: String = "https://example.com/f-${Uuid.random().toString().take(8)}.xml",
    feedKey: String = feedUrl,
    title: String = "Example podcast",
    artworkKey: String = "u-${Uuid.random().toString().take(8)}",
    status: PodcastStatus = PodcastStatus.ACTIVE,
    subscribedAt: Long = 1_700_000_000_000L,
    block: PodcastEntity.() -> PodcastEntity = { this },
): PodcastEntity =
    PodcastEntity(
        id = id,
        syncId = syncId,
        sourceType = SourceType.RSS,
        feedUrl = feedUrl,
        feedKey = feedKey,
        title = title,
        artworkKey = artworkKey,
        status = status,
        initialFetch = true,
        subscribedAt = subscribedAt,
    ).block()

fun episodeEntity(
    id: Long = 0,
    podcastId: Long,
    // (podcastId, identityKey) is a unique index: the default must differ per call, not per id.
    identityKey: String = "ep-$id-${Uuid.random().toString().take(8)}",
    title: String = "Episode $id",
    sortDate: Long = 1_700_000_000_000L,
    feedOrder: Int = 0,
    availability: Availability = Availability.AVAILABLE,
    block: EpisodeEntity.() -> EpisodeEntity = { this },
): EpisodeEntity =
    EpisodeEntity(
        id = id,
        podcastId = podcastId,
        identityKey = identityKey,
        title = title,
        sortDate = sortDate,
        feedOrder = feedOrder,
        firstSeenAt = sortDate,
        lastSeenAt = sortDate,
        availability = availability,
        contentHash = 0,
    ).block()

fun episodeStateEntity(
    episodeId: Long,
    playedAt: Long? = null,
    updatedAt: Long = 1_700_000_000_000L,
    block: EpisodeStateEntity.() -> EpisodeStateEntity = { this },
): EpisodeStateEntity = EpisodeStateEntity(episodeId = episodeId, playedAt = playedAt, updatedAt = updatedAt).block()

fun queueEntryEntity(
    episodeId: Long,
    orderKey: String,
    addedAt: Long = 1_700_000_000_000L,
): QueueEntryEntity = QueueEntryEntity(episodeId = episodeId, orderKey = orderKey, addedAt = addedAt)

fun groupEntity(
    uuid: String = Uuid.random().toString(),
    name: String = "Group",
    orderKey: String,
    createdAt: Long = 1_700_000_000_000L,
    updatedAt: Long = createdAt,
): PodcastGroupEntity =
    PodcastGroupEntity(
        uuid = uuid,
        name = name,
        nameKey = name.lowercase(),
        orderKey = orderKey,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

fun memberEntity(
    groupId: Long,
    podcastId: Long,
    orderKey: String,
    addedAt: Long = 1_700_000_000_000L,
): PodcastGroupMemberEntity =
    PodcastGroupMemberEntity(groupId = groupId, podcastId = podcastId, orderKey = orderKey, addedAt = addedAt)

fun downloadEntity(
    episodeId: Long,
    state: DownloadState = DownloadState.COMPLETED,
    lane: DownloadLane = DownloadLane.MANUAL,
    priority: Int = 100,
    requestedAt: Long = 1_700_000_000_000L,
    sourceKind: SourceKind = SourceKind.RSS_ENCLOSURE,
    sourceRef: String = "https://example.com/ep.mp3",
    rootId: String = "int",
): DownloadEntity =
    DownloadEntity(
        episodeId = episodeId,
        lane = lane,
        state = state,
        priority = priority,
        requestedAt = requestedAt,
        sourceKind = sourceKind,
        sourceRef = sourceRef,
        rootId = rootId,
        allowMetered = false,
    )
