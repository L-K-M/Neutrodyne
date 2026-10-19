// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.importing

import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.database.ImportItemEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.domain.PodcastRepository
import ch.lkmc.neutrodyne.core.domain.UnsubscribeUseCase
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.ImportItemStatus
import ch.lkmc.neutrodyne.core.model.ImportOptions
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * 05 §8's report actions — Retry, Edit URL, Enter password, Remove — on the rows an import
 * session created. Every ordinary podcast operation routes through the domain's
 * [PodcastRepository]/[UnsubscribeUseCase] (normalisation, credentials, engine locking and the
 * unsubscribe cascade live there); this class only owns the import-side choreography:
 *
 * - Eligibility is re-read inside each write transaction, so a fix-up never touches a row a
 *   racing run already finalised, and never a row whose identity a merge consumed
 *   ([sessionOwned]) — `ALREADY_SUBSCRIBED`/`MERGED`-bound podcasts are not this session's.
 * - Requeue, `attemptMarkerAt` and the `DONE` → `FETCHING` reopen commit in ONE write
 *   transaction, then the host enqueue follows non-cancellably — a finishing runner sees the
 *   requeued rows (`queuedCount`/`markDone` lose to the committed fix-up), and a request
 *   arriving while the worker ends still runs after it ([ImportWorkPolicy.APPEND_OR_REPLACE]).
 * - `PREVIEW` and `CANCELLED` sessions refuse fix-ups: preview rows are never in a report
 *   status, and a cancelled lane must not resurrect.
 */
@Inject
internal class ImportFixUps(
    private val db: NeutrodyneDatabase,
    private val podcasts: PodcastRepository,
    private val unsubscribe: UnsubscribeUseCase,
    private val workScheduler: ImportWorkScheduler,
    private val clock: Clock,
) {
    /**
     * "Retry" on the need-attention rows (05 §8): each podcast's refresh block clears through
     * the domain path (`refresh = false` — the import lane refetches, not a manual run), then
     * the still-eligible rows requeue and the session's marker advances.
     */
    suspend fun retry(
        sessionId: Long,
        ordinals: Set<Int>,
    ) {
        val eligible = eligibleItems(sessionId, ordinals, RETRYABLE_STATUSES, withPodcast = true)
        if (eligible.isEmpty()) return
        for (podcastId in eligible.mapTo(LinkedHashSet()) { it.podcastId }) {
            podcastId?.let { suspendRunCatching { podcasts.retry(it, refresh = false) } }
        }
        requeueAndSchedule(sessionId, ordinals, RETRYABLE_STATUSES)
    }

    /**
     * "Edit URL": the move goes through `PodcastRepository.editFeedUrl` (03's probe, atomic
     * move and ingest under the engine lock); the row then records `SUBSCRIBED` — or `MERGED`
     * when 02's merge repointed `podcastId` at the surviving podcast. Failures stay inline.
     */
    suspend fun editUrl(
        sessionId: Long,
        ordinal: Int,
        input: String,
    ): Outcome<Unit, AddPodcastError> {
        val item =
            eligibleItem(sessionId, ordinal, FIXUP_STATUSES) ?: return Outcome.Failure(AddPodcastError.InvalidUrl)
        val podcastId = checkNotNull(item.podcastId) { "eligible fix-up item without podcastId" }
        return when (val outcome = podcasts.editFeedUrl(podcastId, input)) {
            is Outcome.Failure -> {
                outcome
            }

            is Outcome.Success -> {
                db.withWriteTransaction {
                    val dao = db.importDao()
                    val current =
                        dao.itemsByOrdinals(sessionId, listOf(ordinal)).singleOrNull()
                            ?: return@withWriteTransaction Unit
                    when {
                        current.podcastId == podcastId -> {
                            dao.setItemStatus(sessionId, ordinal, ImportItemStatus.SUBSCRIBED, null)
                        }

                        current.podcastId != null -> {
                            dao.setItemStatus(sessionId, ordinal, ImportItemStatus.MERGED, null)
                        }

                        // The podcast vanished between the edit's commit and this write — keep
                        // the row's record; nothing subscribed to report.
                        else -> {
                            Unit
                        }
                    }
                }
                Outcome.Success(Unit)
            }
        }
    }

    /**
     * "Enter password": `PodcastRepository.setCredentials` probes, stores and refreshes the
     * podcast (03 Basic auth); a refused pair stays an inline `AuthRequired` and the row keeps
     * its status. On success the row requeues like Retry — whichever fetch lands first
     * (setCredentials' own manual refresh or the lane's) writes `lastAttemptAt`, and `derive()`
     * finalises the item without a second fetch.
     */
    suspend fun enterPassword(
        sessionId: Long,
        ordinal: Int,
        credentials: BasicCredentials,
    ): Outcome<Unit, AddPodcastError> {
        val item =
            eligibleItem(sessionId, ordinal, FIXUP_STATUSES) ?: return Outcome.Failure(AddPodcastError.InvalidUrl)
        val podcastId = checkNotNull(item.podcastId) { "eligible fix-up item without podcastId" }
        return when (val outcome = podcasts.setCredentials(podcastId, credentials)) {
            is Outcome.Failure -> {
                outcome
            }

            is Outcome.Success -> {
                requeueAndSchedule(sessionId, setOf(ordinal), FIXUP_STATUSES)
                Outcome.Success(Unit)
            }
        }
    }

    /**
     * "Remove"/"Remove all": unsubscribes only the podcasts this session created — the
     * ownership check excludes rows a merge repointed — then deletes their `import_item`
     * rows. Rows re-checked inside the delete transaction: a still-pointing row (a merge
     * repoint landing mid-remove) is the merge's record and survives.
     */
    suspend fun remove(
        sessionId: Long,
        ordinals: Set<Int>,
    ) {
        val removable = eligibleItems(sessionId, ordinals, REMOVABLE_STATUSES, withPodcast = false)
        if (removable.isEmpty()) return
        val podcastIds = removable.mapNotNull { it.podcastId }
        if (podcastIds.isNotEmpty()) unsubscribe(podcastIds)

        db.withWriteTransaction {
            val dao = db.importDao()
            // The cascade's SET NULL leaves a removed podcast's rows with `podcastId = null`;
            // a row still pointing anywhere was repointed by a racing merge — the merge's
            // record, not this remove's to delete.
            val delete =
                dao
                    .itemsByOrdinals(sessionId, removable.map { it.ordinal })
                    .filter { it.status in REMOVABLE_STATUSES && it.podcastId == null }
                    .map { it.ordinal }
            dao.deleteItems(sessionId, delete)
        }
    }

    /**
     * The requeue + marker + reopen + schedule quartet of §8's Retry and Enter password —
     * one non-cancellable stretch so the runner's `derive`/`finish` pair can never split
     * them (d92eeb42's ordering) and a committed transaction can never lose the
     * `APPEND_OR_REPLACE` enqueue owed to it: cancellation surfacing at Room's COMMIT used
     * to drop the schedule call that ran after the transaction returned.
     */
    private suspend fun requeueAndSchedule(
        sessionId: Long,
        ordinals: Set<Int>,
        statuses: Set<ImportItemStatus>,
    ) {
        withContext(NonCancellable) {
            val scheduled =
                db.withWriteTransaction {
                    val dao = db.importDao()
                    val session = dao.sessionById(sessionId) ?: return@withWriteTransaction false
                    if (session.state !in FIXUP_STATES) return@withWriteTransaction false

                    // An unreadable options row is never rewritten — defaults could erase a
                    // restore intent hidden inside it, so the whole fix-up stays unapplied.
                    val options = session.optionsJson?.let(ImportOptionsJson::decodeOrNull)
                    if (session.optionsJson != null && options == null) {
                        Log.w(TAG) { "import fix-up skipped: unreadable options for session $sessionId" }
                        return@withWriteTransaction false
                    }

                    val requeue =
                        dao
                            .itemsByOrdinals(sessionId, ordinals)
                            .filter { it.status in statuses && it.podcastId != null && it.sessionOwned() }
                            .map { it.ordinal }
                    if (requeue.isEmpty()) return@withWriteTransaction false

                    dao.requeueItems(sessionId, requeue)
                    dao.setOptionsJson(
                        sessionId,
                        ImportOptionsJson.encode(
                            (options ?: ImportOptions()).copy(attemptMarkerAt = clock.now(), laneAttempts = 0),
                        ),
                    )
                    if (session.state == ImportState.DONE) dao.setState(sessionId, ImportState.FETCHING)
                    true
                }
            if (!scheduled) return@withContext

            // §8: the fix-up enqueue must run after a finishing worker, never be dropped.
            suspendRunCatching { workScheduler.schedule(sessionId, ImportWorkPolicy.APPEND_OR_REPLACE) }
                .onFailure { Log.w(TAG, it) { "import fix-up scheduling failed for session $sessionId" } }
        }
    }

    /**
     * The session gate plus the item read in one snapshot: fix-ups run on committed work
     * (`COMMITTED`/`FETCHING`, and `DONE` which they reopen) — never `PREVIEW` or `CANCELLED`.
     */
    private suspend fun eligibleItem(
        sessionId: Long,
        ordinal: Int,
        statuses: Set<ImportItemStatus>,
    ): ImportItemEntity? = eligibleItems(sessionId, setOf(ordinal), statuses, withPodcast = true).singleOrNull()

    private suspend fun eligibleItems(
        sessionId: Long,
        ordinals: Set<Int>,
        statuses: Set<ImportItemStatus>,
        withPodcast: Boolean,
    ): List<ImportItemEntity> =
        db.withReadTransaction {
            val dao = db.importDao()
            val session = dao.sessionById(sessionId) ?: return@withReadTransaction emptyList()
            if (session.state !in FIXUP_STATES) return@withReadTransaction emptyList()
            dao
                .itemsByOrdinals(sessionId, ordinals)
                .filter { item ->
                    item.status in statuses &&
                        (!withPodcast || item.podcastId != null) &&
                        item.sessionOwned()
                }
        }

    /**
     * 05 §8's ownership rule: the podcast must be one this session created. A merge repoints
     * `podcastId` at the winner and leaves a `MERGE` alias on the consumed feed's identity —
     * [ImportFetchRunner.derive]'s own check, applied here so no fix-up touches a foreign
     * podcast. Rows without a resolvable identity can't be proven foreign, so they count as
     * owned.
     */
    private suspend fun ImportItemEntity.sessionOwned(): Boolean {
        val identity = normalizedUrl?.let(UrlNormalizer::forIdentity) ?: return true
        val podcast = podcastId?.let { db.podcastDao().byId(it) } ?: return true
        if (podcast.feedKey == identity) return true
        return db.podcastDao().mergeAliasOwner(identity) == null
    }

    private companion object {
        const val TAG = "ImportFixUps"

        /** §8's need-attention set — the statuses Retry re-queues. */
        val RETRYABLE_STATUSES: Set<ImportItemStatus> =
            setOf(
                ImportItemStatus.NOT_A_FEED,
                ImportItemStatus.NO_MEDIA,
                ImportItemStatus.AUTH_REQUIRED,
                ImportItemStatus.GONE,
                ImportItemStatus.FETCH_FAILED,
            )

        /** Remove/Edit URL/Enter password may also reach still-`QUEUED` rows. */
        val REMOVABLE_STATUSES: Set<ImportItemStatus> = RETRYABLE_STATUSES + ImportItemStatus.QUEUED
        val FIXUP_STATUSES: Set<ImportItemStatus> = REMOVABLE_STATUSES

        /** Sessions whose report may still be acted on; `DONE` reopens through the fix-ups. */
        val FIXUP_STATES: Set<ImportState> =
            setOf(ImportState.COMMITTED, ImportState.FETCHING, ImportState.DONE)
    }
}
