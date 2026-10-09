// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.repo

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.Redactor
import ch.lkmc.neutrodyne.core.data.refresh.FeedRefresher
import ch.lkmc.neutrodyne.core.data.refresh.RefreshOrigin
import ch.lkmc.neutrodyne.core.data.refresh.RefreshScheduler
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastEntity
import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.domain.PodcastRepository
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.model.AliasReason
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.CategoryCount
import ch.lkmc.neutrodyne.core.model.ChangeOrigin
import ch.lkmc.neutrodyne.core.model.FeedErrorKind
import ch.lkmc.neutrodyne.core.model.FeedHealth
import ch.lkmc.neutrodyne.core.model.FeedInfo
import ch.lkmc.neutrodyne.core.model.FeedMove
import ch.lkmc.neutrodyne.core.model.LibraryTile
import ch.lkmc.neutrodyne.core.model.PodcastDetail
import ch.lkmc.neutrodyne.feeds.html.ShowNotesSanitizer
import ch.lkmc.neutrodyne.feeds.identity.PrivateFeedUrls
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * `PodcastRepository` (03 Unsubscribe and other podcast operations): read models mapped in Kotlin
 * from the 02 DAOs and the write paths M1a owns (unsubscribe, user columns, retry). `merge`,
 * `setCredentials` and `editFeedUrl` are M1b and throw; `SyncStateDao.withApplying` lands with
 * MS0 so `ChangeOrigin.SYNC` runs the same cascade for now (deviation 2026-10-07).
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class PodcastRepositoryImpl(
    private val db: NeutrodyneDatabase,
    private val refresher: FeedRefresher,
    private val scheduler: RefreshScheduler,
    private val sanitizer: ShowNotesSanitizer,
    private val clock: Clock,
) : PodcastRepository {
    private val json = Json { ignoreUnknownKeys = true }

    /** 02's 30-day counts window and the case-insensitive title sort (Collator in 02; M1a). */
    override fun observeLibraryTiles(groupId: Long?): Flow<List<LibraryTile>> =
        db
            .podcastDao()
            .observeLibraryTiles(clock.now() - TILE_COUNTS_WINDOW_MS, groupId)
            .map { rows ->
                rows
                    .map { row ->
                        LibraryTile(
                            podcastId = row.id,
                            displayTitle = row.title,
                            sourceType = row.sourceType,
                            status = row.status,
                            artwork = ArtworkRef(row.artworkKey, row.artworkUrl, row.artworkVersion),
                            artworkAvgArgb = row.artworkAvgArgb,
                            health =
                                healthOf(
                                    row.gone,
                                    row.needsCredentials,
                                    row.failureCount,
                                    row.lastSuccessAt,
                                    row.lastErrorKind,
                                    row.subscribedAt,
                                ),
                            latestEpisodeAt = row.latestEpisodeAt,
                            subscribedAt = row.subscribedAt,
                            unplayedCount = row.unplayedCount,
                        )
                    }.sortedWith { a, b -> a.displayTitle.compareTo(b.displayTitle, ignoreCase = true) }
            }

    override fun observePodcast(podcastId: Long): Flow<PodcastDetail?> =
        combine(
            db.podcastDao().observeById(podcastId),
            db.podcastDao().observeEpisodeCount(podcastId),
        ) { entity, count ->
            entity?.let { detailOf(it, count) }
        }

    override fun observeFeedInfo(podcastId: Long): Flow<FeedInfo?> =
        combine(
            db.podcastDao().observeById(podcastId),
            db.podcastDao().observeAliases(podcastId),
        ) { entity, aliases ->
            entity?.let {
                FeedInfo(
                    feedUrl = it.feedUrl,
                    redactedUrl = Redactor.url(it.feedUrl),
                    isPrivate = PrivateFeedUrls.looksPrivate(it.feedUrl),
                    moves =
                        aliases
                            .filter { a -> a.reason == AliasReason.REDIRECT || a.reason == AliasReason.NEW_FEED_URL }
                            .map { a -> FeedMove(a.url.substringBefore('/'), a.reason, a.addedAt) },
                    lastAttemptAt = it.lastAttemptAt,
                    lastSuccessAt = it.lastSuccessAt,
                    nextRefreshAt = it.nextRefreshAt,
                    lastErrorKind = it.lastErrorKind,
                    lastErrorDetail = it.lastErrorDetail,
                    pendingNewFeedUrl = it.pendingNewFeedUrl,
                )
            }
        }

    /**
     * 03's unsubscribe: flush batched fetch-state writes first, then the cascade per podcast.
     * M1b wraps the cascade in `CredentialCommitCoordinator.withCredentialCommit`; MS0 wraps
     * `ChangeOrigin.SYNC` in `withApplying`.
     */
    override suspend fun unsubscribe(
        podcastIds: List<Long>,
        origin: ChangeOrigin,
    ): List<Long> {
        refresher.flushFetchStates()
        val removed = mutableListOf<Long>()
        val dao = db.podcastDao()
        for (id in podcastIds) {
            if (dao.byId(id) == null) continue
            dao.deleteCascade(id, clock.now())
            removed += id
        }
        if (removed.isNotEmpty()) scheduler.reschedulePeriodic()
        return removed
    }

    /** Steps 1–3 of 03's "Podcast dedupe and merge" — an M1b path. */
    override suspend fun merge(
        loserId: Long,
        winnerId: Long,
        origin: ChangeOrigin,
    ): Unit = throw UnsupportedOperationException("merge is M1b")

    override suspend fun downloadedEpisodeIds(podcastIds: List<Long>): List<Long> =
        db.downloadDao().downloadedEpisodeIds(podcastIds)

    override suspend fun setIncludeInAll(
        podcastId: Long,
        include: Boolean,
    ) = db.podcastDao().setIncludeInAll(podcastId, include)

    override suspend fun setCustomTitle(
        podcastId: Long,
        title: String?,
    ) = db.podcastDao().setCustomTitle(podcastId, title)

    /** 03 Basic auth: `SecretStore` and `CredentialCommitCoordinator` land in M1b. */
    override suspend fun setCredentials(
        podcastId: Long,
        credentials: BasicCredentials,
    ): Outcome<Unit, AddPodcastError> = throw UnsupportedOperationException("setCredentials is M1b")

    /** 03 Edit URL: feed moves and `new-feed-url` aliases land in M1b. */
    override suspend fun editFeedUrl(
        podcastId: Long,
        input: String,
    ): Outcome<Unit, AddPodcastError> = throw UnsupportedOperationException("editFeedUrl is M1b")

    /**
     * "Try again" (03 Per-feed states): flush the batcher, clear the failure block, then a
     * forced refresh of the one podcast; `refresh = false` re-runs 05's import worker (M3+).
     * The `RETRY` origin re-applies the clear inside the engine's run mutex — a stale
     * in-flight outcome (a backoff or a 410's `gone`) landing while the run queued cannot
     * swallow the retry (r3 F3).
     */
    override suspend fun retry(
        podcastId: Long,
        refresh: Boolean,
    ) {
        refresher.flushFetchStates()
        db.podcastDao().clearRefreshBlock(podcastId)
        if (refresh) {
            scheduler.enqueueNow(
                scope = RefreshScope.Podcasts(listOf(podcastId)),
                force = true,
                pagesOnly = false,
                origin = RefreshOrigin.RETRY,
            )
        }
    }

    override fun observeCategoryCounts(): Flow<List<CategoryCount>> =
        db.podcastDao().observeCategoryRows().map { rows ->
            val byCategory = LinkedHashMap<String, MutableList<Long>>()
            for (row in rows) {
                val categories =
                    runCatching {
                        json.decodeFromString<List<List<String>>>(row.categoriesJson ?: return@runCatching null)
                    }.getOrNull() ?: continue
                for (path in categories) {
                    byCategory.getOrPut(path.joinToString(" > ")) { mutableListOf() } += row.id
                }
            }
            byCategory.map { (category, ids) -> CategoryCount(category, ids) }
        }

    private fun detailOf(
        p: PodcastEntity,
        episodeCount: Int,
    ): PodcastDetail =
        PodcastDetail(
            id = p.id,
            displayTitle = p.customTitle ?: p.title,
            author = p.author,
            description =
                p.descriptionHtml?.let {
                    sanitizer.toDocument(it, isHtml = true, baseUri = p.feedUrl).toModel()
                },
            artwork = ArtworkRef(p.artworkKey, p.artworkUrl, 0),
            bannerUrl = p.bannerUrl,
            sourceType = p.sourceType,
            link = p.link,
            episodeCount = episodeCount,
            latestEpisodeAt = p.latestEpisodeAt,
            status = p.status,
            health =
                healthOf(p.gone, p.needsCredentials, p.failureCount, p.lastSuccessAt, p.lastErrorKind, p.subscribedAt),
            isPrivate = PrivateFeedUrls.looksPrivate(p.feedUrl),
            episodeOrder = p.episodeOrder,
            showType = p.showType,
            includeInAll = p.includeInAll,
            hasOlderPages = p.pagingNextUrl != null,
        )

    /** 03 Per-feed states: `gone = 0 AND failureCount ≥ 10 AND COALESCE(lastSuccessAt, subscribedAt) < now − 7 d`. */
    private fun healthOf(
        gone: Boolean,
        needsCredentials: Boolean,
        failureCount: Int,
        lastSuccessAt: Long?,
        lastErrorKind: FeedErrorKind?,
        subscribedAt: Long,
    ): FeedHealth =
        FeedHealth(
            gone = gone,
            needsCredentials = needsCredentials,
            failureCount = failureCount,
            lastSuccessAt = lastSuccessAt,
            lastErrorKind = lastErrorKind,
            possiblyDead =
                !gone && failureCount >= DEAD_FAILURE_COUNT &&
                    (lastSuccessAt ?: subscribedAt) < clock.now() - DEAD_WINDOW_MS,
        )

    private companion object {
        const val TILE_COUNTS_WINDOW_MS = 30L * 86_400_000
        const val DEAD_FAILURE_COUNT = 10
        const val DEAD_WINDOW_MS = 7L * 86_400_000
    }
}
