// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.CategoryCount
import ch.lkmc.neutrodyne.core.model.ChangeOrigin
import ch.lkmc.neutrodyne.core.model.EpisodeDetail
import ch.lkmc.neutrodyne.core.model.FeedInfo
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.LibraryTile
import ch.lkmc.neutrodyne.core.model.PodcastDetail
import ch.lkmc.neutrodyne.core.model.ShowNotes
import kotlinx.coroutines.flow.Flow

/**
 * The podcast read/write contract (03 Unsubscribe and other podcast operations; SQL: 02,
 * rendering: 08). Implemented by `PodcastRepositoryImpl` (`:core:data`).
 */
interface PodcastRepository {
    /** The library cover grid's tiles (02 `PodcastDao.observeLibraryTiles`; null = every group). */
    fun observeLibraryTiles(groupId: Long?): Flow<List<LibraryTile>>

    fun observePodcast(podcastId: Long): Flow<PodcastDetail?>

    /** The feed-info section of podcast settings: redacted URL, aliases, refresh state, error. */
    fun observeFeedInfo(podcastId: Long): Flow<FeedInfo?>

    /**
     * LOCAL: the database part only — callers use `UnsubscribeUseCase`. SYNC (10, MS2): also
     * deletes the download files first, runs the cascade with `applying = 1`, never pauses, and
     * skips (and returns) podcasts that own the current item.
     */
    suspend fun unsubscribe(
        podcastIds: List<Long>,
        origin: ChangeOrigin = ChangeOrigin.LOCAL,
    ): List<Long>

    /** Steps 1–3 of 03's "Podcast dedupe and merge" (M1b). */
    suspend fun merge(
        loserId: Long,
        winnerId: Long,
        origin: ChangeOrigin,
    )

    /** The episode ids of these podcasts that have a `download` row (07's file deletion set). */
    suspend fun downloadedEpisodeIds(podcastIds: List<Long>): List<Long>

    suspend fun setIncludeInAll(
        podcastId: Long,
        include: Boolean,
    )

    suspend fun setCustomTitle(
        podcastId: Long,
        title: String?,
    )

    /** M1b: store Basic credentials for the podcast's feed origin and probe them once. */
    suspend fun setCredentials(
        podcastId: Long,
        credentials: BasicCredentials,
    ): Outcome<Unit, AddPodcastError>

    /** M1b: move the feed to a user-entered URL (03 Edit URL). */
    suspend fun editFeedUrl(
        podcastId: Long,
        input: String,
    ): Outcome<Unit, AddPodcastError>

    /**
     * "Try again" (03 Per-feed states): flushes `FetchStateBatcher`, clears `gone` /
     * `needsCredentials` and forces a refresh; [refresh] = false re-runs 05's import worker
     * instead.
     */
    suspend fun retry(
        podcastId: Long,
        refresh: Boolean = true,
    )

    /** Suggested groups' source (M7): categories with the podcasts carrying them. */
    fun observeCategoryCounts(): Flow<List<CategoryCount>>
}

/**
 * Episode reads and user-state writes (03's interface; the played chains are 02 User-state writes
 * with 06's semantics).
 */
interface EpisodeRepository {
    fun observeEpisode(episodeId: Long): Flow<EpisodeDetail?>

    /** The episode's show notes, decoded and sanitised lazily (LRU 16). */
    fun observeShowNotes(episodeId: Long): Flow<ShowNotes?>

    /** `played` applies 02's mark-played / mark-unplayed chains (06's semantics). */
    suspend fun setPlayed(
        episodeIds: List<Long>,
        played: Boolean,
    )

    /** R2.6's bulk "Mark all as played" (05's import option; SQL: 02 User-state writes). */
    suspend fun markFeedPlayed(
        source: FeedSource,
        sortDateBefore: Long?,
    )

    suspend fun setFavorite(
        episodeId: Long,
        favorite: Boolean,
    )
}
