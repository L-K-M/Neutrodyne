// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.importing

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.data.refresh.FeedOutcome
import ch.lkmc.neutrodyne.core.data.refresh.FeedRefresher
import ch.lkmc.neutrodyne.core.data.refresh.RefreshOrigin
import ch.lkmc.neutrodyne.core.data.refresh.RefreshRequest
import ch.lkmc.neutrodyne.core.database.ImportItemEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastEntity
import ch.lkmc.neutrodyne.core.domain.ImportReportPoster
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.domain.SnapshotScheduler
import ch.lkmc.neutrodyne.core.model.FeedErrorKind
import ch.lkmc.neutrodyne.core.model.ImportFormat
import ch.lkmc.neutrodyne.core.model.ImportItemKind
import ch.lkmc.neutrodyne.core.model.ImportItemStatus
import ch.lkmc.neutrodyne.core.model.ImportOptions
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * 05 §7's fetch lane (`:core:data` commonMain): drives a session's `QUEUED` items through the
 * real [FeedRefresher] — there is no separate HTTP or ingest path. `ImportFetchWorker`
 * (Android, unique work `import-{sessionId}`) and the desktop `import-backup` lane are the two
 * hosts; both hand over the session id, their attempt counter and a soft deadline.
 *
 * The lane is durable, not in-memory: an item counts as fetched when its podcast's
 * `lastAttemptAt >= options.attemptMarkerAt`, so a restart or a periodic refresh that fetched
 * the feed first never fetches it twice. The finish is a single write transaction: an item
 * requeued by a concurrent fix-up is seen, and `markDone`'s conditional write loses to a
 * committed `CANCELLED` — the done notification is then skipped.
 *
 * Outcomes apply under the same rules in a second transaction: a §8 fix-up can requeue an
 * item and bump the marker while a fetch is in flight, so the write re-reads the session's
 * marker and each item's status. Only a still-`QUEUED` item takes an outcome, and an
 * attempt-stamping outcome is ignored when the podcast's committed `lastAttemptAt` is older
 * than the marker it was read against — the stale outcome cannot void the retry. A run the
 * engine finished without an outcome for a presented feed (no installed adapter can select
 * it) ends the item `NOT_ATTEMPTED` rather than deriving it forever; a deadline-stopped
 * report instead leaves the item queued for the retry.
 */
@SingleIn(AppScope::class)
@Inject
internal class ImportFetchRunner(
    private val db: NeutrodyneDatabase,
    private val refresher: FeedRefresher,
    private val snapshots: SnapshotScheduler,
    private val reports: ImportReportPoster,
    private val clock: Clock,
) {
    /**
     * One host invocation for [sessionId] (05 §7). [attempt] is the platform counter —
     * Android's `runAttemptCount`, the desktop lane's `ImportOptions.laneAttempts`.
     * [deadlineElapsedMs] is the monotonic soft deadline (Android's 8-minute window) or
     * [RefreshRequest.NO_DEADLINE] on the desktop.
     */
    suspend fun run(
        sessionId: Long,
        attempt: Int,
        deadlineElapsedMs: Long = RefreshRequest.NO_DEADLINE,
    ): ImportFetchResult {
        val dao = db.importDao()
        // COMMITTED → FETCHING, guarded: a cancel that already committed wins either way.
        dao.markFetching(sessionId)
        val session = dao.sessionById(sessionId) ?: return ImportFetchResult.COMPLETED
        if (session.state != ImportState.COMMITTED && session.state != ImportState.FETCHING) {
            return ImportFetchResult.COMPLETED
        }
        val origin =
            if (session.sourceFormat == ImportFormat.NEUTRODYNE_BACKUP) {
                RefreshOrigin.RESTORE
            } else {
                RefreshOrigin.IMPORT
            }
        if (attempt >= MAX_LANE_ATTEMPTS) {
            markUnattempted(sessionId)
            return finishOrDone(sessionId)
        }

        // FeedRunEvents are best effort (a bounded SharedFlow) but free to collect: every
        // event implies its fetch state already committed, so an outcome another run just
        // produced applies here without waiting a full loop for derive() to see the row.
        val seen = mutableMapOf<Long, FeedOutcome>()
        val seenLock = Mutex()
        return coroutineScope {
            val collector =
                launch {
                    suspendRunCatching {
                        refresher.events.collect { event ->
                            seenLock.withLock { seen[event.podcastId] = event.outcome }
                        }
                    }.onFailure { Log.w(TAG, it) { "outcome event collection failed" } }
                }
            try {
                fetchLoop(sessionId, origin, deadlineElapsedMs, seen, seenLock)
            } finally {
                collector.cancel()
            }
        }
    }

    /** 05 §7's loop: derive → fetch pending → apply → repeat, until finish or retry. */
    private suspend fun fetchLoop(
        sessionId: Long,
        origin: RefreshOrigin,
        deadlineElapsedMs: Long,
        seen: MutableMap<Long, FeedOutcome>,
        seenLock: Mutex,
    ): ImportFetchResult {
        while (true) {
            val pending = derive(sessionId) ?: return ImportFetchResult.COMPLETED
            if (pending.isEmpty()) {
                when (finish(sessionId)) {
                    Finish.DONE -> {
                        // DONE committed: its effects run as one non-cancellable unit so a
                        // host cancel landing now cannot leave a done session unannounced.
                        withContext(NonCancellable) { announceDone(sessionId) }
                        return ImportFetchResult.COMPLETED
                    }

                    // A cancel or delete committed under the run — the row is left alone.
                    Finish.NOT_ACTIVE -> {
                        return ImportFetchResult.COMPLETED
                    }

                    // A fix-up requeued items while the finish was checked; loop for them.
                    Finish.STILL_QUEUED -> {
                        continue
                    }
                }
            }
            if (deadlineElapsedMs != RefreshRequest.NO_DEADLINE &&
                deadlineElapsedMs - clock.elapsedRealtime() < HOST_DEADLINE_MARGIN_MS
            ) {
                return ImportFetchResult.RETRY
            }
            val report =
                suspendRunCatching {
                    refresher.run(
                        RefreshRequest(
                            scope = RefreshScope.Podcasts(pending.mapNotNull { it.podcastId }),
                            force = true,
                            pagesOnly = false,
                            origin = origin,
                            deadlineElapsedMs = deadlineElapsedMs,
                        ),
                    )
                }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    Log.w(TAG, error) { "refresh run failed for import session $sessionId" }
                    return ImportFetchResult.RETRY
                }
            val outcomes =
                seenLock
                    .withLock { seen.toMutableMap() }
                    .apply { putAll(report.outcomes) }
            val offline =
                applyOutcomes(
                    sessionId,
                    pending,
                    outcomes,
                    finalizeUnselected = !report.stoppedByDeadline,
                )
            if (offline || report.stoppedByDeadline) return ImportFetchResult.RETRY
        }
    }

    /**
     * 05 §7's derive step over one write transaction: each `QUEUED` item whose podcast row
     * shows an attempt at/after the session's marker — or is already blocked — gets its final
     * status; the rest come back as the pending set. `null` means the session left the
     * runnable states while the run was in flight (a committed cancel or a delete).
     */
    private suspend fun derive(sessionId: Long): List<ImportItemEntity>? =
        db.withWriteTransaction {
            val dao = db.importDao()
            val session = dao.sessionById(sessionId) ?: return@withWriteTransaction null
            if (session.state != ImportState.COMMITTED && session.state != ImportState.FETCHING) {
                return@withWriteTransaction null
            }
            val options = session.optionsJson?.let(ImportOptionsJson::decode) ?: ImportOptions()
            val pending = ArrayList<ImportItemEntity>()
            for (item in dao.itemsByStatus(sessionId, QUEUED_ONLY)) {
                if (finalizeItem(sessionId, item, options) == ItemVerdict.PENDING) {
                    pending += item
                }
            }
            pending
        }

    /**
     * One `QUEUED` row's verdict under the caller's write transaction — every terminal row of
     * 05 §7's table (merge-alias repoint, missing link, blocked row, marker-passed podcast) or
     * [ItemVerdict.PENDING] when the podcast has no attempt at/after [options]' marker. The
     * outcome sweep reuses it so a feed the engine could never select still ends on a status.
     */
    private suspend fun finalizeItem(
        sessionId: Long,
        item: ImportItemEntity,
        options: ImportOptions,
    ): ItemVerdict {
        val dao = db.importDao()
        val podcasts = db.podcastDao()
        val marker = options.attemptMarkerAt ?: options.committedAt ?: 0L
        val identity = item.normalizedUrl?.let(UrlNormalizer::forIdentity)
        val podcast = item.podcastId?.let { podcasts.byId(it) }
        if (identity != null &&
            (podcast == null || podcast.feedKey != identity) &&
            podcasts.mergeAliasOwner(identity) != null
        ) {
            // 02's merge repoints podcastId to the winner and leaves the consumed
            // feed's identity as a MERGE alias — "merged with existing".
            dao.setItemStatus(sessionId, item.ordinal, ImportItemStatus.MERGED, null)
            return ItemVerdict.FINALISED
        }
        if (podcast == null) {
            // No feed is linked: an unresolved YouTube ref stays explicitly
            // unsupported until M8 — it is never claimed fetched. Anything else simply
            // never reached a fetch.
            val unsupported = item.kind == ImportItemKind.YOUTUBE
            dao.setItemStatus(
                sessionId,
                item.ordinal,
                if (unsupported) ImportItemStatus.YOUTUBE_UNSUPPORTED_YET else ImportItemStatus.FETCH_FAILED,
                if (unsupported) null else DETAIL_NOT_ATTEMPTED,
            )
            return ItemVerdict.FINALISED
        }
        val attemptedAt = podcast.lastAttemptAt
        when {
            podcast.needsCredentials -> {
                dao.setItemStatus(sessionId, item.ordinal, ImportItemStatus.AUTH_REQUIRED, null)
            }

            podcast.gone -> {
                dao.setItemStatus(sessionId, item.ordinal, ImportItemStatus.GONE, null)
            }

            attemptedAt == null || attemptedAt < marker -> {
                return ItemVerdict.PENDING
            }

            else -> {
                writeRowStatus(sessionId, item, podcast, options.treatExistingAsPlayed)
            }
        }
        return ItemVerdict.FINALISED
    }

    /** The marker-passed row's final status — the row column of 05 §7's outcome table. */
    private suspend fun writeRowStatus(
        sessionId: Long,
        item: ImportItemEntity,
        podcast: PodcastEntity,
        treatAsPlayed: Boolean,
    ) {
        val error = podcast.lastErrorKind
        when {
            podcast.status == PodcastStatus.ACTIVE && error == null -> {
                writeStatus(sessionId, item, ImportItemStatus.SUBSCRIBED, null, treatAsPlayed)
            }

            error != null -> {
                val status = statusForError(error, item.kind) ?: ImportItemStatus.FETCH_FAILED
                writeStatus(
                    sessionId,
                    item,
                    status,
                    if (status == ImportItemStatus.FETCH_FAILED) error.name else null,
                    treatAsPlayed,
                )
            }

            // An attempt stamp with neither success nor an error kind should not exist; a
            // terminal failure still beats leaving the item QUEUED forever.
            else -> {
                writeStatus(
                    sessionId,
                    item,
                    ImportItemStatus.FETCH_FAILED,
                    FeedErrorKind.UNKNOWN.name,
                    treatAsPlayed,
                )
            }
        }
    }

    /**
     * The run's outcomes — the report plus collected events — applied in one transaction.
     * `OFFLINE` is no outcome: the item stays QUEUED and the caller retries the run.
     *
     * The transaction re-reads what changed while the fetch was in flight: the session (a
     * cancel or a §8 fix-up's bumped marker), each item's status (a fix-up's terminal write
     * wins) and the podcast's committed attempt stamp. Only a still-`QUEUED` item takes an
     * outcome, and only an outcome backed by `lastAttemptAt` at/after that marker can
     * finalise it — a pre-marker outcome is stale and the item stays queued for the retry.
     * `Merged` and `Deferred` are exempt: one deletes the podcast row it proves, the other
     * never stamps an attempt.
     *
     * [finalizeUnselected] is the report's own fact — the run finished, so a presented feed
     * with no outcome was never selectable (no installed adapter): the item finalises through
     * [finalizeItem]'s table, `NOT_ATTEMPTED` when nothing else applies. `false` (a
     * deadline-stopped or unowned report) leaves it queued for the retry instead.
     */
    private suspend fun applyOutcomes(
        sessionId: Long,
        pending: List<ImportItemEntity>,
        outcomes: Map<Long, FeedOutcome>,
        finalizeUnselected: Boolean,
    ): Boolean {
        val unselected =
            if (finalizeUnselected) {
                pending.filterTo(HashSet()) { it.podcastId == null || it.podcastId !in outcomes }
            } else {
                emptySet()
            }
        if (outcomes.isEmpty() && unselected.isEmpty()) return false
        var sawOffline = false
        db.withWriteTransaction {
            val dao = db.importDao()
            // A deleted session leaves nothing to write against; a cancelled one still
            // records what its committed fetches did (05 §7's cancel semantics).
            val session = dao.sessionById(sessionId) ?: return@withWriteTransaction
            val options = session.optionsJson?.let(ImportOptionsJson::decode) ?: ImportOptions()
            val marker = options.attemptMarkerAt ?: options.committedAt ?: 0L
            val podcasts = db.podcastDao()
            val queued = dao.itemsByStatus(sessionId, QUEUED_ONLY).associateBy { it.ordinal }
            val ordinalsByPodcast = HashMap<Long, Int>(pending.size)
            for (item in pending) {
                item.podcastId?.let { ordinalsByPodcast[it] = item.ordinal }
            }
            for ((podcastId, outcome) in outcomes) {
                val item = ordinalsByPodcast[podcastId]?.let { queued[it] } ?: continue
                if (outcome is FeedOutcome.Failed && outcome.kind == FeedErrorKind.OFFLINE) {
                    sawOffline = true
                    continue
                }
                val attemptProven =
                    outcome is FeedOutcome.Merged ||
                        outcome is FeedOutcome.Deferred ||
                        podcasts.byId(podcastId)?.lastAttemptAt?.let { it >= marker } == true
                if (!attemptProven) continue
                val derived = statusForOutcome(outcome, item.kind) ?: continue
                writeStatus(sessionId, item, derived.first, derived.second, options.treatExistingAsPlayed)
            }
            for (stale in unselected) {
                val item = queued[stale.ordinal] ?: continue
                if (finalizeItem(sessionId, item, options) == ItemVerdict.PENDING) {
                    dao.setItemStatus(sessionId, item.ordinal, ImportItemStatus.FETCH_FAILED, DETAIL_NOT_ATTEMPTED)
                }
            }
        }
        return sawOffline
    }

    /** 05 §7's outcome column; a `null` return means "no outcome" — the item stays QUEUED. */
    private fun statusForOutcome(
        outcome: FeedOutcome,
        kind: ImportItemKind,
    ): Pair<ImportItemStatus, String?>? =
        when (outcome) {
            is FeedOutcome.Ingested,
            FeedOutcome.NotModified,
            FeedOutcome.Unchanged,
            -> {
                ImportItemStatus.SUBSCRIBED to null
            }

            is FeedOutcome.Merged -> {
                ImportItemStatus.MERGED to null
            }

            is FeedOutcome.Deferred -> {
                ImportItemStatus.FETCH_FAILED to DETAIL_DEFERRED
            }

            is FeedOutcome.Failed -> {
                statusForError(outcome.kind, kind)?.let {
                    it to if (it == ImportItemStatus.FETCH_FAILED) outcome.kind.name else null
                }
            }
        }

    /**
     * The `Failed(kind)`/`lastErrorKind` half of 05 §7's table — shared by outcomes and the
     * persisted-row fallback. [FeedErrorKind.OFFLINE] maps to nothing: the engine writes no
     * state for it, so it can only mean "not attempted".
     */
    private fun statusForError(
        kind: FeedErrorKind,
        itemKind: ImportItemKind,
    ): ImportItemStatus? =
        when (kind) {
            FeedErrorKind.OFFLINE -> {
                null
            }

            FeedErrorKind.HTTP_AUTH -> {
                ImportItemStatus.AUTH_REQUIRED
            }

            FeedErrorKind.HTTP_GONE -> {
                ImportItemStatus.GONE
            }

            // A YouTube channel's 404 is transient (03); only an RSS 404 is GONE.
            FeedErrorKind.HTTP_NOT_FOUND -> {
                if (itemKind == ImportItemKind.RSS) {
                    ImportItemStatus.GONE
                } else {
                    ImportItemStatus.FETCH_FAILED
                }
            }

            FeedErrorKind.NO_MEDIA -> {
                ImportItemStatus.NO_MEDIA
            }

            FeedErrorKind.NOT_A_FEED,
            FeedErrorKind.PARSE_ERROR,
            FeedErrorKind.UNSUPPORTED_LIST_FEED,
            -> {
                ImportItemStatus.NOT_A_FEED
            }

            else -> {
                ImportItemStatus.FETCH_FAILED
            }
        }

    /**
     * `setItemStatus` plus the D66 side effect: "Treat existing episodes as played" runs the
     * mark-played chain (except the newest) in the same transaction as the `SUBSCRIBED` write.
     */
    private suspend fun writeStatus(
        sessionId: Long,
        item: ImportItemEntity,
        status: ImportItemStatus,
        errorDetail: String?,
        treatAsPlayed: Boolean,
    ) {
        val written = db.importDao().setItemStatus(sessionId, item.ordinal, status, errorDetail)
        if (written > 0 && status == ImportItemStatus.SUBSCRIBED && treatAsPlayed) {
            markPlayedExceptNewest(checkNotNull(item.podcastId))
        }
    }

    /** 02's mark-played chain over `playedExceptNewestIds`, chunked at the bind limit. */
    private suspend fun markPlayedExceptNewest(podcastId: Long) {
        val ids = db.episodeDao().playedExceptNewestIds(podcastId)
        if (ids.isEmpty()) return
        val now = clock.now()
        for (chunk in ids.chunked(BIND_CHUNK)) {
            db.episodeStateDao().ensureAll(chunk, now)
            db.episodeStateDao().markPlayed(chunk, now)
            db.positionDao().reset(chunk, now)
            db.queueDao().removeEpisodes(chunk)
        }
    }

    /** Attempt exhaustion (05 §7): every still-`QUEUED` item ends `FETCH_FAILED("NOT_ATTEMPTED")`. */
    private suspend fun markUnattempted(sessionId: Long) =
        db.withWriteTransaction {
            val dao = db.importDao()
            for (item in dao.itemsByStatus(sessionId, QUEUED_ONLY)) {
                dao.setItemStatus(sessionId, item.ordinal, ImportItemStatus.FETCH_FAILED, DETAIL_NOT_ATTEMPTED)
            }
        }

    /**
     * 05 §7's finish — one write transaction, so a fix-up cannot interleave between the queue
     * check and the `DONE` write: a requeued item is seen (`STILL_QUEUED`), and `markDone`
     * loses to a committed cancel (`NOT_ACTIVE`).
     */
    private suspend fun finish(sessionId: Long): Finish =
        db.withWriteTransaction {
            val dao = db.importDao()
            when {
                dao.queuedCount(sessionId) > 0 -> Finish.STILL_QUEUED
                dao.markDone(sessionId, clock.now()) > 0 -> Finish.DONE
                else -> Finish.NOT_ACTIVE
            }
        }

    private suspend fun finishOrDone(sessionId: Long): ImportFetchResult {
        if (finish(sessionId) == Finish.DONE) {
            withContext(NonCancellable) { announceDone(sessionId) }
        }
        return ImportFetchResult.COMPLETED
    }

    /** The post-`DONE` effects of 05 §7–8: one report notification, then the snapshot request. */
    private suspend fun announceDone(sessionId: Long) {
        val counts =
            db
                .importDao()
                .observeProgress(sessionId)
                .first()
                .associate { it.status to it.count }
        suspendRunCatching { reports.postDone(sessionId, counts) }
            .onFailure { Log.w(TAG, it) { "report notification failed for session $sessionId" } }
        suspendRunCatching { snapshots.requestSoon() }
            .onFailure { Log.w(TAG, it) { "snapshot request failed for session $sessionId" } }
    }

    /** [finish]'s verdict inside its transaction. */
    private enum class Finish { DONE, STILL_QUEUED, NOT_ACTIVE }

    /** [finalizeItem]'s verdict for one `QUEUED` row inside the caller's write transaction. */
    private enum class ItemVerdict { PENDING, FINALISED }

    private companion object {
        const val TAG = "ImportFetchRunner"

        /** 05 §7: after this many host attempts the remaining QUEUED items give up. */
        const val MAX_LANE_ATTEMPTS = 20

        /** 05 §7: a run that would start this close to the host deadline retries instead. */
        const val HOST_DEADLINE_MARGIN_MS = 30_000L

        /** `errorDetail` of a `Deferred` outcome — 05 §8's "Will load later" bucket. */
        const val DETAIL_DEFERRED = "DEFERRED"

        /** `errorDetail` of items the lane never fetched (exhaustion, a lost podcast row). */
        const val DETAIL_NOT_ATTEMPTED = "NOT_ATTEMPTED"

        /** 02 Transactions and threading: `IN (:ids)` lists are chunked at 500 bound variables. */
        const val BIND_CHUNK = 500

        val QUEUED_ONLY = listOf(ImportItemStatus.QUEUED)
    }
}

/** One [ImportFetchRunner.run] verdict for the host worker or lane (05 §7). */
internal enum class ImportFetchResult {
    /** The run is done: the session finished, or it can no longer run (cancelled, deleted). */
    COMPLETED,

    /** Offline, a busy engine, a near deadline or a failed run — the host re-runs it later. */
    RETRY,
}
