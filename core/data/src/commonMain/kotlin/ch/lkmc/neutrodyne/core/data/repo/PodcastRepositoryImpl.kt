// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.repo

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.Redactor
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.data.add.HostChecks
import ch.lkmc.neutrodyne.core.data.add.PreviewEntryResolver
import ch.lkmc.neutrodyne.core.data.credentials.removeUnreferenced
import ch.lkmc.neutrodyne.core.data.fetch.FeedFetcher
import ch.lkmc.neutrodyne.core.data.fetch.FeedRequest
import ch.lkmc.neutrodyne.core.data.fetch.FeedTempFiles
import ch.lkmc.neutrodyne.core.data.fetch.FetchOutcome
import ch.lkmc.neutrodyne.core.data.refresh.FeedRefresher
import ch.lkmc.neutrodyne.core.data.refresh.RefreshOrigin
import ch.lkmc.neutrodyne.core.data.refresh.RefreshScheduler
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastEntity
import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.domain.CredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.domain.PodcastRepository
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.domain.SecretStore
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
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * `PodcastRepository` (03 Unsubscribe and other podcast operations): read models mapped in Kotlin
 * from the 02 DAOs and the write paths M1a owns (unsubscribe, user columns, retry) plus M1b's
 * `setCredentials`, `merge` and `editFeedUrl`; remote-origin cascades suppress capture.
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
    private val merger: PodcastMerger,
    private val resolver: PreviewEntryResolver,
    private val secrets: SecretStore,
    private val coordinator: CredentialCommitCoordinator,
    // Lazy (01 DI rule 7): the fetcher's `CredentialLookup` reaches the database, and building it
    // eagerly would resolve the whole client family for a path only `setCredentials` uses.
    private val fetcher: Provider<FeedFetcher>,
    private val tempFiles: FeedTempFiles,
) : PodcastRepository {
    private val json = Json { ignoreUnknownKeys = true }

    /** 02's 30-day counts window and the case-insensitive title sort (Collator in 02; M1a). */
    override fun observeLibraryTiles(groupId: Long?): Flow<List<LibraryTile>> =
        db
            .podcastDao()
            // The window is fixed at subscription time — a long-lived collector doesn't re-anchor
            // it (self-corrects on resubscribe).
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
     * 03's unsubscribe: flush batched fetch-state writes first, then the cascade per podcast —
     * inside `withCredentialCommit`, because `deleteCascade` runs 02's last-reference credential
     * delete and the coordinator is always acquired before the Room writer. MS0 wraps
     * `ChangeOrigin.SYNC` in `withApplying`.
     */
    override suspend fun unsubscribe(
        podcastIds: List<Long>,
        origin: ChangeOrigin,
    ): List<Long> {
        refresher.flushFetchStates()
        val removed = mutableListOf<Long>()
        val dao = db.podcastDao()
        // The cascade and its reschedule finish as one unit: Room re-checks cancellation as the
        // transaction returns, so a closing screen would otherwise skip the reschedule (and the
        // caller's cleanup of the returned ids). A failed reschedule never fails the cascade.
        return withContext(NonCancellable) {
            // One transaction for the whole list: a crash mid-loop must not leave a half-unsubscribed
            // library with stale periodic scheduling (03 Unsubscribe).
            coordinator.withCredentialCommit {
                db.withWriteTransaction {
                    suspend fun removePodcasts() {
                        for (id in podcastIds) {
                            if (dao.byId(id) == null) continue
                            dao.deleteCascade(id, clock.now())
                            removed += id
                        }
                    }
                    if (origin == ChangeOrigin.SYNC) {
                        db.syncStateDao().withApplying { removePodcasts() }
                    } else {
                        removePodcasts()
                    }
                }
            }
            if (removed.isNotEmpty()) {
                suspendRunCatching { scheduler.reschedulePeriodic() }
                    .onFailure { Log.w(TAG, it) { "post-unsubscribe reschedule failed" } }
            }
            removed
        }
    }

    /** Steps 1–3 of 03's "Podcast dedupe and merge" — an M1b path. */
    override suspend fun merge(
        loserId: Long,
        winnerId: Long,
        origin: ChangeOrigin,
    ) {
        refresher.flushFetchStates()
        merger.merge(loserId, winnerId, origin)
    }

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

    /**
     * 03 Basic auth, "On refresh": the typed credentials probe `feedUrl` once — only an auth
     * refusal rejects them; any other outcome (the server down today, a temporarily moved feed)
     * still stores them for the next refresh. The `put` and the `credentialId`/`needsCredentials`
     * writes commit inside `withCredentialCommit` under the refresher's run mutex — after
     * `FetchStateBatcher` is flushed, so no in-flight outcome can re-set the flag behind
     * them — then a manual refresh proves the pair end to end.
     */
    override suspend fun setCredentials(
        podcastId: Long,
        credentials: BasicCredentials,
    ): Outcome<Unit, AddPodcastError> {
        val podcast =
            db.podcastDao().byId(podcastId) ?: return Outcome.Failure(AddPodcastError.InvalidUrl)
        val origin =
            UrlNormalizer.origin(podcast.feedUrl) ?: return Outcome.Failure(AddPodcastError.InvalidUrl)

        when (
            val outcome =
                fetcher().fetch(
                    FeedRequest(
                        url = podcast.feedUrl,
                        etag = null,
                        lastModified = null,
                        conditional = false,
                        credentials = credentials,
                    ),
                )
        ) {
            is FetchOutcome.Http -> {
                if (outcome.code == HTTP_UNAUTHORIZED || outcome.code == HTTP_FORBIDDEN) {
                    return Outcome.Failure(AddPodcastError.AuthRequired(outcome.realm))
                }
            }

            // The probe body is the caller's to delete (03); nothing else leaves state behind.
            is FetchOutcome.Body -> {
                tempFiles.delete(outcome.file)
            }

            else -> {}
        }

        // An in-flight run can still commit a pre-credential outcome (a stale HTTP_AUTH
        // re-sets needsCredentials) after the batcher flush — flush and commit therefore
        // run under the engine's run mutex, keeping 02's lock order run mutex →
        // CredentialCommitCoordinator → Room writer. The probe above stays outside it.
        val committed =
            refresher.withRunExclusive {
                refresher.flushFetchStates()
                coordinator.withCredentialCommit {
                    val rowId = secrets.put(origin, credentials.username, credentials.password.toCharArray())
                    val linked =
                        try {
                            db.withWriteTransaction<Boolean> {
                                val dao = db.podcastDao()
                                if (dao.byId(podcastId) == null) {
                                    false
                                } else {
                                    dao.setCredentialId(podcastId, rowId)
                                    dao.clearNeedsCredentials(podcastId)
                                    true
                                }
                            }
                        } catch (t: Throwable) {
                            withContext(NonCancellable) {
                                secrets.removeUnreferenced(db.credentialDao(), origin, rowId)
                            }
                            throw t
                        }
                    if (!linked) {
                        withContext(NonCancellable) {
                            secrets.removeUnreferenced(db.credentialDao(), origin, rowId)
                        }
                    }
                    linked
                }
            }
        if (!committed) {
            // The podcast vanished mid-commit — an unsubscribe won the race; nothing is stored.
            return Outcome.Failure(AddPodcastError.InvalidUrl)
        }

        scheduler.enqueueNow(
            scope = RefreshScope.Podcasts(listOf(podcastId)),
            force = true,
            pagesOnly = false,
            origin = RefreshOrigin.MANUAL,
        )
        return Outcome.Success(Unit)
    }

    /** 03 Edit URL: validate before an atomic move and ingest, preserving user state. */
    override suspend fun editFeedUrl(
        podcastId: Long,
        input: String,
    ): Outcome<Unit, AddPodcastError> =
        refresher.editFeedUrl(podcastId) {
            when (val result = resolver.probe(input)) {
                is Outcome.Failure -> {
                    if (result.error == AddPodcastError.YouTubeNotYetSupported) {
                        Outcome.Failure(AddPodcastError.InvalidUrl)
                    } else {
                        result
                    }
                }

                is Outcome.Success -> {
                    val meta = result.value.meta
                    // Match subscribe's guard, including the URL that would become the identity.
                    if (HostChecks.isYouTube(meta.finalUrl) ||
                        HostChecks.isYouTube(meta.permanentUrl ?: meta.requestedUrl)
                    ) {
                        Outcome.Failure(AddPodcastError.InvalidUrl)
                    } else {
                        result
                    }
                }
            }
        }

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

    private suspend fun detailOf(
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
            // The version lives on the artwork row, not the podcast row — the tile projection
            // joins it, the detail flow reads it here. 0 when the row is absent.
            artwork = ArtworkRef(p.artworkKey, p.artworkUrl, db.artworkDao().byKey(p.artworkKey)?.version ?: 0),
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
        const val TAG = "Podcasts"
        const val TILE_COUNTS_WINDOW_MS = 30L * 86_400_000
        const val DEAD_FAILURE_COUNT = 10
        const val DEAD_WINDOW_MS = 7L * 86_400_000

        /** The auth refusals 03's `setCredentials` probe maps to `AuthRequired`. */
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
    }
}
