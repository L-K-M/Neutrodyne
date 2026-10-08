// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.domain.PodcastRepository
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.CategoryCount
import ch.lkmc.neutrodyne.core.model.ChangeOrigin
import ch.lkmc.neutrodyne.core.model.FeedInfo
import ch.lkmc.neutrodyne.core.model.LibraryTile
import ch.lkmc.neutrodyne.core.model.PodcastDetail
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [PodcastRepository] for ViewModel/UI tests (09 `:core:testing` inventory): drives
 * the observable reads with `MutableStateFlow`s and records the writes. `unsubscribe` removes the
 * ids from [detail] and [feedInfo]; `retry` is a recorded no-op.
 */
class FakePodcastRepository : PodcastRepository {
    val tiles = MutableStateFlow<List<LibraryTile>>(emptyList())
    val detail = MutableStateFlow<PodcastDetail?>(null)
    val feedInfo = MutableStateFlow<FeedInfo?>(null)

    /** Removed by [unsubscribe]; the ids the cascade actually dropped. */
    val subscribedIds = MutableStateFlow<Set<Long>>(emptySet())

    /** Canned answers for the `Outcome` writes — success by default. */
    var credentialsOutcome: Outcome<Unit, AddPodcastError> = Outcome.Success(Unit)
    var editUrlOutcome: Outcome<Unit, AddPodcastError> = Outcome.Success(Unit)

    val calls = mutableListOf<String>()

    override fun observeLibraryTiles(groupId: Long?): Flow<List<LibraryTile>> = tiles

    override fun observePodcast(podcastId: Long): Flow<PodcastDetail?> = detail

    override fun observeFeedInfo(podcastId: Long): Flow<FeedInfo?> = feedInfo

    override suspend fun unsubscribe(
        podcastIds: List<Long>,
        origin: ChangeOrigin,
    ): List<Long> {
        calls += "unsubscribe($podcastIds)"
        subscribedIds.value -= podcastIds.toSet()
        return podcastIds
    }

    override suspend fun merge(
        loserId: Long,
        winnerId: Long,
        origin: ChangeOrigin,
    ) {
        calls += "merge($loserId, $winnerId)"
    }

    /** The `episode.id`s a podcast has downloaded — feeds `downloadedEpisodeIds`. */
    val downloadedIds = MutableStateFlow<Map<Long, List<Long>>>(emptyMap())

    override suspend fun downloadedEpisodeIds(podcastIds: List<Long>): List<Long> {
        calls += "downloadedEpisodeIds($podcastIds)"
        return podcastIds.flatMap { downloadedIds.value[it].orEmpty() }
    }

    override suspend fun setIncludeInAll(
        podcastId: Long,
        include: Boolean,
    ) {
        calls += "setIncludeInAll($podcastId, $include)"
    }

    override suspend fun setCustomTitle(
        podcastId: Long,
        title: String?,
    ) {
        calls += "setCustomTitle($podcastId, $title)"
    }

    override suspend fun setCredentials(
        podcastId: Long,
        credentials: BasicCredentials,
    ): Outcome<Unit, AddPodcastError> {
        calls += "setCredentials($podcastId, ${credentials.username})"
        return credentialsOutcome
    }

    override suspend fun editFeedUrl(
        podcastId: Long,
        input: String,
    ): Outcome<Unit, AddPodcastError> {
        calls += "editFeedUrl($podcastId, $input)"
        return editUrlOutcome
    }

    override suspend fun retry(
        podcastId: Long,
        refresh: Boolean,
    ) {
        calls += "retry($podcastId, $refresh)"
    }

    override fun observeCategoryCounts(): Flow<List<CategoryCount>> = MutableStateFlow(emptyList())
}
