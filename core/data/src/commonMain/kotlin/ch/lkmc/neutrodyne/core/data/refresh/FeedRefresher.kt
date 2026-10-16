// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.refresh

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.data.add.PreviewEntry
import ch.lkmc.neutrodyne.core.data.add.isAccepted
import ch.lkmc.neutrodyne.core.data.credentials.removeUnreferenced
import ch.lkmc.neutrodyne.core.data.fetch.FeedTempFiles
import ch.lkmc.neutrodyne.core.data.ingest.FeedIngestor
import ch.lkmc.neutrodyne.core.data.ingest.IngestContext
import ch.lkmc.neutrodyne.core.data.ingest.IngestMode
import ch.lkmc.neutrodyne.core.data.ingest.IngestResult
import ch.lkmc.neutrodyne.core.data.ingest.IngestValidators
import ch.lkmc.neutrodyne.core.data.ingest.IngestionEventBus
import ch.lkmc.neutrodyne.core.data.notifications.NewEpisodeNotifier
import ch.lkmc.neutrodyne.core.database.DueFeed
import ch.lkmc.neutrodyne.core.database.FetchStateBatcher
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastFetchState
import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.domain.CredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.domain.EffectiveSettingsResolver
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.domain.RefreshStatus
import ch.lkmc.neutrodyne.core.domain.SecretStore
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import ch.lkmc.neutrodyne.core.model.AliasReason
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.FeedErrorKind
import ch.lkmc.neutrodyne.core.model.NewEpisodes
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.feeds.identity.PodcastGuid
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.feeds.model.ParseWarning
import ch.lkmc.neutrodyne.feeds.model.isPartialWindow
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.jvm.JvmSuppressWildcards
import kotlin.random.Random

/**
 * The refresh engine of 03 Engine run (`:core:data` singleton): one run per process, a 6-wide
 * fan-out capped at 2 per host, per-feed transactions, the batched fetch-state write and the
 * paging sessions. Source-specific behaviour lives behind the [SourceAdapter] multibinding.
 *
 * Run-local state ([batcher], [intervals], [announcements]) is safe because [mutex]
 * serializes runs; parallel feed coroutines only append through the batcher's own lock or into
 * preallocated [slots] / a synchronized list.
 */
@SingleIn(AppScope::class)
@Inject
internal class FeedRefresher(
    private val db: NeutrodyneDatabase,
    private val adapters: Map<SourceType, @JvmSuppressWildcards SourceAdapter>,
    private val ingestor: FeedIngestor,
    private val eventBus: IngestionEventBus,
    private val syncHook: SyncIngestHook,
    private val tempFiles: FeedTempFiles,
    private val settings: SettingsRepository,
    private val effectiveSettings: EffectiveSettingsResolver,
    private val clock: Clock,
    private val random: Random,
    private val moves: FeedMoveCommitter,
    private val newFeedUrlProbe: NewFeedUrlProbe,
    private val scheduler: Provider<RefreshScheduler>,
    private val secrets: SecretStore,
    private val coordinator: CredentialCommitCoordinator,
    private val notifier: NewEpisodeNotifier,
) {
    // Lock order, outermost first: the run [mutex] → [commitMutex] →
    // CredentialCommitCoordinator → the Room writer ([batcherLock] is a leaf guard).
    // A holder of a later lock never waits on an earlier one — [withRunExclusive] and
    // [editFeedUrl] entrants must arrive holding none of them.
    private val mutex = Mutex()

    // Fetches remain parallel; commits and batch additions cannot race a collision survivor.
    private val commitMutex = Mutex()
    private var mergedThisRun = false
    private val probedThisRun = mutableSetOf<Long>()

    private val _status =
        MutableStateFlow(
            RefreshStatus(
                running = false,
                scope = null,
                done = 0,
                total = 0,
                lastRunFinishedAt = null,
            ),
        )

    /** The engine's progress snapshot (03 API); `RefreshControllerImpl.observeStatus` exposes it. */
    val status: StateFlow<RefreshStatus> = _status

    private val _events = MutableSharedFlow<FeedRunEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)

    /** Per-feed outcomes; best effort — consumers needing certainty re-derive it from the table. */
    val events: SharedFlow<FeedRunEvent> = _events

    /** Guards only the [batcher] reference so [flushFetchStates] never waits on a whole run. */
    private val batcherLock = Mutex()
    private var batcher: FetchStateBatcher? = null
    private var intervals: Map<Long, Int?> = emptyMap()
    private val announcements = mutableListOf<NewEpisodes>()
    private val announcementsLock = Mutex()
    private val warningsLock = Mutex()

    /** The last 50 feeds' parse warnings, kept for M11's diagnostics (insertion-order LRU). */
    private val parseWarnings = LinkedHashMap<Long, List<ParseWarning>>()

    /**
     * One engine run (03 Engine run): force-mark → mutex → sweep → select → fan out → page →
     * flush. A forced run marks its scope due *before* it waits, so a run that never wins the
     * mutex still leaves its scope for a later due-selection.
     */
    suspend fun run(request: RefreshRequest): RefreshReport {
        if (request.force) forceDue(request.scope)
        val deadline = request.deadlineElapsedMs
        var owned = false
        try {
            when {
                deadline == RefreshRequest.NO_DEADLINE -> {
                    mutex.lock()
                    owned = true
                }

                else -> {
                    val waitMs = deadline - clock.elapsedRealtime() - DEADLINE_MARGIN_MS
                    if (waitMs <= 0) {
                        owned = mutex.tryLock()
                    } else {
                        // Ownership is recorded inside the timeout: a grant that loses the race
                        // to the deadline still reaches the outer finally and is released.
                        withTimeoutOrNull(waitMs) {
                            mutex.lock()
                            owned = true
                        }
                    }
                }
            }
            if (!owned) {
                // An older run's outcome could have overwritten this request's marks while it
                // waited: re-apply them so the scope stays due for the next selection (r3 F3).
                reapplyIntent(request)
                if (request.hasUncontinuableIntent()) {
                    // …but the still-running run can commit another stale outcome *after* this
                    // re-apply and erase the marks a second time. The intent therefore rides the
                    // request itself: the caller re-enqueues it unchanged — the WorkManager
                    // input data on Android, the queued RefreshRequest on the desktop — and it
                    // is re-applied under the mutex when it runs (r4 F3).
                    return RefreshReport(
                        outcomes = emptyMap(),
                        newEpisodes = emptyList(),
                        remaining = selectDue(request).size,
                        stoppedByDeadline = true,
                        reenqueued = true,
                    )
                }
                // 03 step 1: a timed-out second run reports the scope's due count as remaining.
                return RefreshReport(
                    outcomes = emptyMap(),
                    newEpisodes = emptyList(),
                    remaining = selectDue(request).size,
                    stoppedByDeadline = true,
                )
            }
            return runLocked(request)
        } finally {
            if (owned) mutex.unlock()
        }
    }

    /**
     * 03's user actions (Retry, Edit URL, unsubscribe): a barrier over the running run's batched
     * fetch-state writes so a following user write never orders before a stale outcome. No run →
     * nothing buffered → no-op.
     */
    suspend fun flushFetchStates() {
        batcherLock.withLock { batcher }?.flush()
    }

    /**
     * The stronger form of [flushFetchStates]'s user-action barrier: [block] runs under the
     * run [mutex] itself, so every outcome of an in-flight run — not just the batched ones —
     * commits before [block] starts. `setCredentials` commits this way so a refresh that
     * fetched before the credentials existed cannot re-set `needsCredentials` behind them.
     * Not reentrant: the mutex is held for a whole run and by [editFeedUrl].
     */
    suspend fun <T> withRunExclusive(block: suspend () -> T): T = mutex.withLock { block() }

    /** Serialize the probe and commit with refresh runs and other edits, avoiding stale outcomes. */
    suspend fun editFeedUrl(
        podcastId: Long,
        probe: suspend () -> Outcome<PreviewEntry, AddPodcastError>,
    ): Outcome<Unit, AddPodcastError> =
        mutex.withLock {
            val podcasts = db.podcastDao()
            if (podcasts.byId(podcastId)?.sourceType != SourceType.RSS) {
                return@withLock Outcome.Failure(AddPodcastError.InvalidUrl)
            }
            val entry =
                when (val result = probe()) {
                    is Outcome.Success -> result.value
                    is Outcome.Failure -> return@withLock result
                }
            // A redirect can introduce userinfo too; it must never enter the stored feed URL.
            val targetUrl =
                UrlNormalizer
                    .splitUserInfo(
                        entry.meta.permanentUrl.takeIf { entry.feed.items.any { it.isAccepted() } }
                            ?: entry.meta.requestedUrl,
                    ).first
            if (UrlNormalizer.forIdentity(targetUrl) == null) {
                return@withLock Outcome.Failure(AddPodcastError.InvalidUrl)
            }
            val editIntervals = effectiveSettings.refreshIntervals()
            flushFetchStates()
            var targetId = podcastId
            var merged = false
            var committed: Pair<DueFeed, IngestResult>?
            while (true) {
                try {
                    committed =
                        inCredentialCommit(entry) { credentialId ->
                            db.withWriteTransaction {
                                val stored = podcasts.byId(targetId) ?: return@withWriteTransaction null
                                if (stored.sourceType != SourceType.RSS) return@withWriteTransaction null
                                if (!merged) moves.applyInTransaction(stored, targetUrl, AliasReason.SUBSCRIBE_INPUT)
                                if (credentialId != null) podcasts.setCredentialId(targetId, credentialId)
                                podcasts.clearRefreshBlock(targetId)
                                val current = podcasts.dueFeedById(targetId) ?: return@withWriteTransaction null
                                val mode =
                                    if (current.initialFetch || current.status == PodcastStatus.PENDING_FIRST_FETCH) {
                                        IngestMode.INITIAL
                                    } else {
                                        IngestMode.REFRESH
                                    }
                                current to
                                    ingestor.ingestInTransaction(
                                        current,
                                        entry.feed,
                                        IngestContext(
                                            mode = mode,
                                            partial = entry.feed.paging.isPartialWindow,
                                            fetch = entry.meta.copy(permanentUrl = null),
                                            validators = IngestValidators.DISCARD,
                                        ),
                                        editIntervals[current.id]?.value,
                                    )
                            }
                        }
                    break
                } catch (collision: FeedMoveCollision) {
                    // The commit block has unwound before credential cleanup acquires its coordinator.
                    val survivor = moves.mergeCollision(targetId, collision) ?: continue
                    targetId = survivor.id
                    merged = true
                }
            }
            val (feed, ingest) = committed ?: return@withLock Outcome.Failure(AddPodcastError.InvalidUrl)
            withContext(NonCancellable) {
                announceIngest(
                    feed,
                    adapters.getValue(SourceType.RSS),
                    ingest,
                    initialFetch = feed.initialFetch || feed.status == PodcastStatus.PENDING_FIRST_FETCH,
                )
                // Scheduling cannot undo the committed edit, as in the subscribe path.
                suspendRunCatching { scheduler().reschedulePeriodic() }
                    .onFailure { Log.w(TAG, it) { "post-edit reschedule failed" } }
            }
            Outcome.Success(Unit)
        }

    /**
     * 03's credential commit on the edit path (subscribe's `inCredentialCommit`): when the
     * probe request carried userinfo, `put` stores it under the ORIGINAL request's origin —
     * a redirect target never inherits the label — inside `withCredentialCommit`, and [tx]'s
     * write transaction lands the referencing `podcast.credentialId` in the same coordinated
     * block. A transaction that fails or commits no reference rolls the `put` back.
     *
     * Not reentrant: a `FeedMoveCollision` propagates out of the commit so the retry's
     * merge can acquire the coordinator. The retried `put` is idempotent for the same origin.
     */
    private suspend fun inCredentialCommit(
        entry: PreviewEntry,
        tx: suspend (credentialId: Long?) -> Pair<DueFeed, IngestResult>?,
    ): Pair<DueFeed, IngestResult>? {
        val credentials = entry.credentials ?: return tx(null)
        // `put` normalises the origin again; requestedUrl keeps the request-first rule.
        val origin = UrlNormalizer.origin(entry.meta.requestedUrl) ?: return tx(null)
        return coordinator.withCredentialCommit {
            val credentialId =
                secrets.put(origin, credentials.username, credentials.password.toCharArray())
            try {
                tx(credentialId).also { committed ->
                    if (committed == null) {
                        withContext(NonCancellable) {
                            secrets.removeUnreferenced(db.credentialDao(), origin, credentialId)
                        }
                    }
                }
            } catch (t: Throwable) {
                withContext(NonCancellable) {
                    secrets.removeUnreferenced(db.credentialDao(), origin, credentialId)
                }
                throw t
            }
        }
    }

    private suspend fun runLocked(request: RefreshRequest): RefreshReport {
        mergedThisRun = false
        probedThisRun.clear()
        // The run that just released the mutex may have committed an older outcome on top of
        // this request's marks (a backoff over `nextRefreshAt = 0`, a 410's `gone`): the
        // user's intent is re-applied inside the critical section, before selection (r3 F3).
        reapplyIntent(request)
        val previousFinishedAt =
            suspendRunCatching { settings.get(FeedsSettingKeys.LAST_RUN_FINISHED_AT) }
                .getOrNull()
                ?.takeIf { it > 0 }
        tempFiles.sweep(clock.now())
        val due = selectDue(request).filter { adapters.containsKey(it.sourceType) }
        val pendingPages =
            if (request.pagesOnly) {
                emptyList()
            } else {
                selectPaging(request.scope).filter { adapters.containsKey(it.sourceType) }
            }
        // Resolve after selection so concurrently subscribed feeds cannot enter without an interval.
        intervals = effectiveSettings.refreshIntervals().mapValues { it.value.value }
        val slots = arrayOfNulls<FeedOutcome>(due.size)
        announcementsLock.withLock { announcements.clear() }
        _status.value =
            RefreshStatus(
                running = true,
                scope = request.scope,
                done = 0,
                total = due.size,
                lastRunFinishedAt = previousFinishedAt,
            )

        var launched = 0
        var timedOut = false
        var completed = false
        var report: RefreshReport? = null
        var bodyFailure: Throwable? = null
        try {
            coroutineScope {
                batcherLock.withLock { batcher = FetchStateBatcher(db.podcastDao(), clock, this) }
                val global = Semaphore(GLOBAL_CONCURRENCY)
                // The key is the host the request will actually hit — `feedUrl` for refreshes,
                // the page URL for older-page requests (a rel=next link may point at another
                // host), so a shared CDN still gets the 2-per-host limit. Semaphores are created
                // lazily: a cursor that advances to a host unseen at run start must resolve too.
                val hostPermits = HostPermits()
                val deadline = request.deadlineElapsedMs

                val fanOut =
                    suspend {
                        coroutineScope {
                            for ((index, feed) in due.withIndex()) {
                                if (deadline != RefreshRequest.NO_DEADLINE &&
                                    clock.elapsedRealtime() >= deadline - DEADLINE_MARGIN_MS
                                ) {
                                    break
                                }
                                val adapter = adapters.getValue(feed.sourceType)
                                launched++
                                launch {
                                    global.withPermit {
                                        slots[index] =
                                            if (request.pagesOnly) {
                                                runPagingSession(feed, adapter, request, hostPermits)
                                            } else {
                                                refreshOne(feed, adapter, request, hostPermits) { slots[index] = it }
                                            }
                                    }
                                }
                            }
                        }
                    }
                if (deadline == RefreshRequest.NO_DEADLINE) {
                    fanOut()
                } else {
                    timedOut =
                        withTimeoutOrNull(
                            (deadline - clock.elapsedRealtime()).coerceAtLeast(0),
                        ) { fanOut() } == null
                }

                if (pendingPages.isNotEmpty()) {
                    runBackgroundPaging(request, pendingPages, global, hostPermits)
                }
            }
            completed = true
        } catch (e: Throwable) {
            // The body's failure is the run's primary cause: a finalisation flush failure
            // attaches to it as suppressed rather than replacing it.
            bodyFailure = e
            throw e
        } finally {
            // Finalisation survives cancellation (03 Engine run): the buffered fetch-state rows
            // flush, and the finished slots still report their committed outcomes so the summary
            // and status reflect what actually landed.
            withContext(NonCancellable) {
                val active = batcherLock.withLock { batcher.also { batcher = null } }
                val flushFailure = suspendRunCatching { active?.flush() }.exceptionOrNull()
                if (flushFailure != null) {
                    Log.e(TAG, flushFailure) { "final fetch-state flush failed" }
                }

                val outcomes = mutableMapOf<Long, FeedOutcome>()
                for ((index, feed) in due.withIndex()) {
                    val outcome = slots[index] ?: continue
                    outcomes[feed.id] = outcome
                    _events.tryEmit(FeedRunEvent(feed.id, request.origin, outcome))
                }
                val finishedAt = clock.now()
                val finalReport =
                    RefreshReport(
                        outcomes = outcomes,
                        newEpisodes = announcementsLock.withLock { announcements.toList() },
                        remaining = due.size - outcomes.size,
                        stoppedByDeadline = timedOut || launched < due.size || !completed,
                    )
                // Already committed ingests still notify after cancellation or a fetch-state flush failure.
                try {
                    notifier.post(finalReport.newEpisodes)
                } catch (error: Throwable) {
                    // A notifier's own cancellation must not interrupt run finalisation either.
                    Log.w(TAG, error) { "new-episode finalisation failed" }
                }
                // A failed flush means the outcome rows never committed: no completion
                // diagnostics (`lastAllRunFinishedAt` would suppress the foreground trigger's
                // next refresh for a whole tick) and no report — the failure propagates once
                // the status reset below has run. The diagnostics write itself is also guarded:
                // a throw here must not wedge `running` or mask the flush outcome.
                if (flushFailure == null) {
                    suspendRunCatching { writeDiagnostics(request, finalReport, finishedAt) }
                        .onFailure { Log.w(TAG, it) { "diagnostics write failed" } }
                }
                _status.value =
                    RefreshStatus(
                        running = false,
                        scope = null,
                        done = outcomes.size,
                        total = due.size,
                        lastRunFinishedAt =
                            if (flushFailure == null) finishedAt else previousFinishedAt,
                    )
                // A committed merge must rebase even if its subsequent ingest was cancelled.
                // A scheduler fault cannot turn committed outcomes into a failed run.
                if (mergedThisRun) {
                    suspendRunCatching { scheduler().reschedulePeriodic() }
                        .onFailure { Log.w(TAG, it) { "post-merge reschedule failed" } }
                }
                if (flushFailure == null) {
                    report = finalReport
                } else {
                    // A failed commit never reports a completed run. With no earlier failure the
                    // flush failure is the run's failure; otherwise it rides the body's exception
                    // as suppressed so the primary cause — including cancellation — is preserved.
                    val primary = bodyFailure
                    if (primary == null) throw flushFailure
                    primary.addSuppressed(flushFailure)
                }
            }
        }
        return report ?: error("report unset")
    }

    /**
     * One refresh feed of the fan-out: fetch → policy → ingest/write → outcome.
     * Cancellation propagates so an unfinished feed never publishes an outcome.
     */
    private suspend fun refreshOne(
        feed: DueFeed,
        adapter: SourceAdapter,
        request: RefreshRequest,
        hostPermits: HostPermits,
        committed: (FeedOutcome) -> Unit,
    ): FeedOutcome {
        val result =
            suspendRunCatching {
                hostPermits.semaphore(adapter.hostKey(feed, feed.feedUrl)).withPermit {
                    adapter.fetchAndParse(feed, FetchMode.REFRESH)
                }
            }.getOrElse { e ->
                if (e is CancellationException) throw e
                Log.w(TAG, e) { "fetchAndParse threw for podcast ${feed.id}" }
                // An eager batch flush can fault here too; isolate it just like an
                // outcome-handling failure so siblings finish before finalisation retries.
                suspendRunCatching {
                    writeFailure(feed, FeedErrorKind.UNKNOWN, retryAfterMs = null, detail = e.message)
                }.onFailure { Log.w(TAG, it) { "failure-state write failed for podcast ${feed.id}" } }
                return FeedOutcome.Failed(FeedErrorKind.UNKNOWN, null)
            }
        // Outcome handling (ingest, fetch-state rows, pending moves, hooks) is guarded per feed:
        // one feed's unexpected DAO fault records a STORAGE failure instead of cancelling the
        // run and every sibling feed with it (03's per-feed isolation).
        return suspendRunCatching {
            val outcome =
                commitMutex.withLock {
                    // Failures and deferrals cannot move a feed; retain their existing write path.
                    if (result is AdapterResult.Failed || result is AdapterResult.Deferred) {
                        return@withLock applyAdapterResult(feed, adapter, result)
                    }
                    val current =
                        db.podcastDao().dueFeedById(feed.id)
                            ?: return@withLock FeedOutcome.Failed(FeedErrorKind.UNKNOWN, null)
                    // A response for an address replaced during the fetch cannot undo that move.
                    if (current.feedUrl != feed.feedUrl) {
                        return@withLock FeedOutcome.Deferred(currentNextAt(current.id))
                    }
                    applyAdapterResult(current, adapter, result)
                }
            // Preserve the committed current-body outcome if cancellation interrupts a probe.
            committed(outcome)
            if (feed.sourceType != SourceType.RSS || result !is AdapterResult.Parsed ||
                result.purpose == ParsePurpose.REDIRECT_CONFIRMATION ||
                (outcome !is FeedOutcome.Ingested && outcome !is FeedOutcome.Merged)
            ) {
                return@suspendRunCatching outcome
            }

            val id = (outcome as? FeedOutcome.Merged)?.intoPodcastId ?: feed.id
            val claimed = commitMutex.withLock { probedThisRun.add(id) }
            if (!claimed) return@suspendRunCatching outcome

            var latest = outcome
            // The current ingest already committed: a probe fault must not reach the handler
            // below, which would record a STORAGE failure over that success.
            suspendRunCatching {
                newFeedUrlProbe.run(
                    podcastId = id,
                    current = result.feed,
                    deadlineElapsedMs = request.deadlineElapsedMs,
                    fetch = { target ->
                        hostPermits.semaphore(adapter.hostKey(target, target.feedUrl)).withPermit {
                            if (request.deadlineElapsedMs != RefreshRequest.NO_DEADLINE &&
                                clock.elapsedRealtime() >= request.deadlineElapsedMs - DEADLINE_MARGIN_MS
                            ) {
                                return@withPermit AdapterResult.Deferred(clock.now())
                            }

                            val fetched = adapter.fetchAndParse(target, FetchMode.FULL)
                            if (fetched is AdapterResult.Failed) fetched.htmlBody?.let { tempFiles.delete(it) }
                            fetched
                        }
                    },
                    adopt = { source, url, candidate ->
                        commitMutex.withLock {
                            val current = db.podcastDao().dueFeedById(source.id)
                            val pending = db.podcastDao().byId(source.id)?.pendingNewFeedUrl
                            if (current == null || current.feedUrl != source.feedUrl || pending != url) {
                                return@withLock FeedOutcome.Deferred(currentNextAt(source.id))
                            }
                            val adopted = ingestOutcome(current, adapter, candidate, clock.now(), newFeedUrl = url)
                            if (adopted is FeedOutcome.Ingested || adopted is FeedOutcome.Merged) {
                                val survivor = (adopted as? FeedOutcome.Merged)?.intoPodcastId
                                latest =
                                    if (survivor != null) {
                                        adopted
                                    } else if (latest is FeedOutcome.Merged) {
                                        latest
                                    } else {
                                        adopted
                                    }
                                committed(latest)
                            }
                            adopted
                        }
                    },
                    claimSurvivor = { survivor -> commitMutex.withLock { probedThisRun.add(survivor) } },
                )
            }.onFailure { e -> Log.w(TAG, e) { "new-feed-url probe failed for podcast $id" } }
            latest
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            Log.w(TAG, e) { "outcome handling failed for podcast ${feed.id}" }
            // The isolation guard holds here too: writeFailure hits the DAO (a full or
            // aged-out batch flushes inside `add`), and a second fault must not escape
            // refreshOne and cancel the run. suspendRunCatching keeps cancellation fatal.
            suspendRunCatching {
                writeFailure(feed, FeedErrorKind.STORAGE, retryAfterMs = null, detail = e.message)
            }.onFailure { Log.w(TAG, it) { "failure-state write failed for podcast ${feed.id}" } }
            FeedOutcome.Failed(FeedErrorKind.STORAGE, null)
        }
    }

    /** The outcome table of 03 Engine run: each adapter result becomes one `FeedOutcome`. */
    private suspend fun applyAdapterResult(
        feed: DueFeed,
        adapter: SourceAdapter,
        result: AdapterResult,
    ): FeedOutcome {
        val now = clock.now()
        val move =
            if (result is AdapterResult.NotModified || result is AdapterResult.Unchanged) {
                if (moves.willMove(feed.id)) flushFetchStates()
                moves.renormalise(feed.id)
            } else {
                null
            }
        if (move?.mergedInto != null) mergedThisRun = true
        if (move != null && move.feed == null) return FeedOutcome.Failed(FeedErrorKind.UNKNOWN, null)
        val refreshed = move?.feed ?: feed
        return when (result) {
            is AdapterResult.Parsed -> {
                ingestOutcome(feed, adapter, result, now)
            }

            is AdapterResult.NotModified -> {
                val meta = result.meta
                addFetchState(
                    fetchStateOf(
                        refreshed,
                        lastAttemptAt = now,
                        lastSuccessAt = now,
                        nextRefreshAt =
                            adapter.nextRefreshAt(refreshed, result, successNextAt(refreshed, now, meta?.maxAgeSec)),
                        failureCount = 0,
                        lastErrorKind = null,
                        lastErrorDetail = null,
                        etag =
                            if (move?.moved == true ||
                                move?.mergedInto != null
                            ) {
                                refreshed.etag
                            } else {
                                meta?.etag ?: refreshed.etag
                            },
                        lastModified =
                            if (move?.moved == true ||
                                move?.mergedInto != null
                            ) {
                                refreshed.lastModified
                            } else {
                                meta?.lastModified
                                    ?: refreshed.lastModified
                            },
                        lastFullFetchAt = refreshed.lastFullFetchAt,
                        lastParseOk = refreshed.lastParseOk,
                    ),
                )
                move?.mergedInto?.let { FeedOutcome.Merged(it) } ?: FeedOutcome.NotModified
            }

            is AdapterResult.Unchanged -> {
                addFetchState(
                    fetchStateOf(
                        refreshed,
                        lastAttemptAt = now,
                        lastSuccessAt = now,
                        nextRefreshAt =
                            adapter.nextRefreshAt(
                                refreshed,
                                result,
                                successNextAt(refreshed, now, result.meta.maxAgeSec),
                            ),
                        failureCount = 0,
                        lastErrorKind = null,
                        lastErrorDetail = null,
                        etag =
                            if (move?.moved == true ||
                                move?.mergedInto != null
                            ) {
                                refreshed.etag
                            } else {
                                result.meta.etag ?: refreshed.etag
                            },
                        lastModified =
                            if (move?.moved == true ||
                                move?.mergedInto != null
                            ) {
                                refreshed.lastModified
                            } else {
                                result.meta.lastModified
                                    ?: refreshed.lastModified
                            },
                        // `lastFullFetchAt` tracks unconditional 200s (03 full-fetch rule).
                        lastFullFetchAt = if (result.meta.unconditional) now else refreshed.lastFullFetchAt,
                        lastParseOk = refreshed.lastParseOk,
                    ),
                )
                // 03 Source adapters: `afterIngest` runs after Unchanged too, with empty lists.
                callAfterIngest(refreshed, adapter, emptyList(), emptyList())
                move?.mergedInto?.let { FeedOutcome.Merged(it) } ?: FeedOutcome.Unchanged
            }

            is AdapterResult.Deferred -> {
                // 04 only: "not attempted; only nextRefreshAt moves" — every other column keeps
                // the stored value. The row is re-read because a user action (Retry, Edit URL)
                // landing between due-selection and this write must not be clobbered by the
                // stale DueFeed snapshot.
                val stored = db.podcastDao().byId(feed.id)
                addFetchState(
                    fetchStateOf(
                        feed,
                        lastAttemptAt = stored?.lastAttemptAt ?: feed.lastAttemptAt,
                        lastSuccessAt = stored?.lastSuccessAt ?: feed.lastSuccessAt,
                        nextRefreshAt = adapter.nextRefreshAt(feed, result, result.untilMs),
                        failureCount = stored?.failureCount ?: feed.failureCount,
                        lastErrorKind = stored?.lastErrorKind ?: feed.lastErrorKind,
                        lastErrorDetail = stored?.lastErrorDetail,
                        gone = stored?.gone ?: false,
                        needsCredentials = stored?.needsCredentials ?: false,
                        etag = stored?.etag ?: feed.etag,
                        lastModified = stored?.lastModified ?: feed.lastModified,
                        lastFullFetchAt = stored?.lastFullFetchAt ?: feed.lastFullFetchAt,
                        lastParseOk = stored?.lastParseOk ?: feed.lastParseOk,
                    ),
                )
                FeedOutcome.Deferred(result.untilMs)
            }

            is AdapterResult.Failed -> {
                failedOutcome(feed, adapter, result, now)
            }
        }
    }

    /** The `Parsed` arm: ingest, then the step-11 hooks and the same-guid dedupe note. */
    private suspend fun ingestOutcome(
        feed: DueFeed,
        adapter: SourceAdapter,
        result: AdapterResult.Parsed,
        now: Long,
        newFeedUrl: String? = null,
    ): FeedOutcome {
        val permanentUrl =
            result.meta.permanentUrl.takeIf {
                result.feed.items.any { it.primaryEnclosure != null || it.externalMediaId != null }
            }
        if (moves.willMove(feed.id, newFeedUrl ?: permanentUrl)) flushFetchStates()
        var target = feed
        var mergedInto: Long? = null
        var mode: IngestMode
        var ingest: IngestResult
        while (true) {
            mode =
                if ((newFeedUrl == null || mergedInto != null) &&
                    (target.initialFetch || target.status == PodcastStatus.PENDING_FIRST_FETCH)
                ) {
                    IngestMode.INITIAL
                } else {
                    IngestMode.REFRESH
                }
            val ctx =
                IngestContext(
                    mode = mode,
                    partial = result.partial,
                    fetch = if (mergedInto == null) result.meta else result.meta.copy(permanentUrl = null),
                    rowHints = result.rowHints,
                    absenceFloor = result.absenceFloor,
                    validators = if (mergedInto == null) IngestValidators.STORE else IngestValidators.DISCARD,
                    newFeedUrl = newFeedUrl.takeIf { mergedInto == null },
                )
            try {
                ingest = ingestor.ingest(target, result.feed, ctx, intervals[target.id])
                break
            } catch (collision: FeedMoveCollision) {
                // The failed ingest released Room; flush before the merger changes its survivor.
                flushFetchStates()
                val survivor = moves.mergeCollision(target.id, collision) ?: continue
                target = survivor
                mergedInto = survivor.id
                mergedThisRun = true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, e) { "ingest failed for podcast ${target.id}" }
                val kind = if (isUniqueViolation(e)) FeedErrorKind.IDENTITY_CONFLICT else FeedErrorKind.STORAGE
                if (newFeedUrl == null) writeFailure(target, kind, retryAfterMs = null, detail = e.message)
                return FeedOutcome.Failed(kind, null)
            }
        }

        target = db.podcastDao().dueFeedById(target.id) ?: target
        rememberWarnings(target.id, ingest.warnings)
        // `vanished` means the podcast row was deleted mid-run — there is no row left to write a
        // fetch state onto, and the row being gone is itself the terminal state.
        if (ingest.vanished) return FeedOutcome.Failed(FeedErrorKind.UNKNOWN, null)

        val emptyKind = ingest.emptyKind
        if (emptyKind != null) {
            // NO_MEDIA/UNSUPPORTED_LIST_FEED: the kind is recorded but scheduling is the success
            // row. The response's validators are *not* adopted — the body produced no ingest, so
            // the stored validators (and `lastFullFetchAt`) keep the next attempt conditional on
            // what was actually committed; otherwise a 304 would quietly clear the error.
            addFetchState(
                fetchStateOf(
                    target,
                    lastAttemptAt = now,
                    lastSuccessAt = now,
                    nextRefreshAt =
                        adapter.nextRefreshAt(target, result, successNextAt(target, now, result.meta.maxAgeSec)),
                    failureCount = 0,
                    lastErrorKind = emptyKind,
                    lastErrorDetail = null,
                    etag = target.etag,
                    lastModified = target.lastModified,
                    lastFullFetchAt = target.lastFullFetchAt,
                    lastParseOk = target.lastParseOk,
                ),
            )
            return mergedInto?.let { FeedOutcome.Merged(it) } ?: FeedOutcome.Failed(emptyKind, null)
        }

        announceIngest(target, adapter, ingest, initialFetch = mode == IngestMode.INITIAL)
        if (mergedInto != null) return FeedOutcome.Merged(mergedInto)

        val realGuid = result.feed.podcastGuid?.let(PodcastGuid::parse)
        val sameGuidAs =
            realGuid?.let { guid ->
                db
                    .podcastDao()
                    .byRealGuid(guid)
                    .firstOrNull { it.id != target.id }
                    ?.id
            }
        return FeedOutcome.Ingested(
            inserted = ingest.inserted.size,
            newCount = ingest.newIds.size,
            firstIngest = ingest.firstIngest,
            sameGuidAs = sameGuidAs,
        )
    }

    /**
     * The failure-policy table of 03 Engine run: OFFLINE writes nothing, LAN failures schedule a
     * day out, 410 goes `gone`, a Basic challenge goes `needsCredentials`, everything else backs
     * off (`Retry-After` may push further).
     */
    private suspend fun failedOutcome(
        feed: DueFeed,
        adapter: SourceAdapter,
        result: AdapterResult.Failed,
        now: Long,
    ): FeedOutcome {
        result.htmlBody?.let { suspendRunCatching { tempFiles.delete(it) } }
        when {
            // The feed stays due; no column moves (03 policy table).
            result.kind == FeedErrorKind.OFFLINE -> {}

            result.kind == FeedErrorKind.LOCAL_NETWORK_UNSUPPORTED -> {
                addFetchState(
                    fetchStateOf(
                        feed,
                        lastAttemptAt = now,
                        lastSuccessAt = feed.lastSuccessAt,
                        nextRefreshAt = RefreshPolicy.localNetworkNextRefreshAt(now),
                        failureCount = feed.failureCount + 1,
                        lastErrorKind = result.kind,
                        lastErrorDetail = result.detail,
                        etag = feed.etag,
                        lastModified = feed.lastModified,
                        lastFullFetchAt = feed.lastFullFetchAt,
                        lastParseOk = feed.lastParseOk,
                    ),
                )
            }

            result.kind == FeedErrorKind.HTTP_GONE && !result.transient -> {
                val stored = db.podcastDao().byId(feed.id)
                addFetchState(
                    fetchStateOf(
                        feed,
                        lastAttemptAt = now,
                        lastSuccessAt = feed.lastSuccessAt,
                        // "unchanged; excluded by gone = 1" — keep the stored schedule.
                        nextRefreshAt = stored?.nextRefreshAt,
                        failureCount = feed.failureCount + 1,
                        lastErrorKind = result.kind,
                        lastErrorDetail = result.detail,
                        gone = true,
                        etag = feed.etag,
                        lastModified = feed.lastModified,
                        lastFullFetchAt = feed.lastFullFetchAt,
                        lastParseOk = feed.lastParseOk,
                    ),
                )
            }

            result.kind == FeedErrorKind.HTTP_AUTH -> {
                val stored = db.podcastDao().byId(feed.id)
                addFetchState(
                    fetchStateOf(
                        feed,
                        lastAttemptAt = now,
                        lastSuccessAt = feed.lastSuccessAt,
                        // "unchanged; excluded by needsCredentials = 1".
                        nextRefreshAt = stored?.nextRefreshAt,
                        failureCount = feed.failureCount + 1,
                        lastErrorKind = result.kind,
                        lastErrorDetail = result.detail,
                        needsCredentials = true,
                        etag = feed.etag,
                        lastModified = feed.lastModified,
                        lastFullFetchAt = feed.lastFullFetchAt,
                        lastParseOk = feed.lastParseOk,
                    ),
                )
            }

            else -> {
                val n = feed.failureCount + 1
                addFetchState(
                    fetchStateOf(
                        feed,
                        lastAttemptAt = now,
                        lastSuccessAt = feed.lastSuccessAt,
                        nextRefreshAt =
                            adapter.nextRefreshAt(
                                feed,
                                result,
                                RefreshPolicy.failureNextRefreshAt(now, n, result.retryAfterMs, random),
                            ),
                        failureCount = n,
                        lastErrorKind = result.kind,
                        lastErrorDetail = result.detail ?: result.http?.let { "HTTP $it" },
                        etag = feed.etag,
                        lastModified = feed.lastModified,
                        lastFullFetchAt = feed.lastFullFetchAt,
                        // A `PARSE_ERROR` writes `lastParseOk = 0` the same way (03 Validators).
                        lastParseOk = if (result.kind == FeedErrorKind.PARSE_ERROR) false else feed.lastParseOk,
                    ),
                )
            }
        }
        return FeedOutcome.Failed(result.kind, result.http)
    }

    /**
     * A paging session of 03 RFC 5005 paging: pages the feed until a link runs out, a stop
     * condition hits or the deadline's paging margin arrives. Returns the last page's outcome.
     * The loop is cancellation-bounded at `pagingStopAt`, not only checked between pages: a page
     * still in flight when the budget or deadline lands is cancelled — its ingest transaction
     * rolls back and the feed keeps its paging cursor for a later run (03 Engine run steps 7/9).
     */
    private suspend fun runPagingSession(
        feed: DueFeed,
        adapter: SourceAdapter,
        request: RefreshRequest,
        hostPermits: HostPermits,
    ): FeedOutcome? {
        val session = PagingSession(feed, adapter)
        val manual = request.origin == RefreshOrigin.MANUAL
        val stopAt = pagingStopAt(request)
        val pages: suspend () -> Unit = {
            while (session.active && clock.elapsedRealtime() < stopAt) {
                // A cursor can move hosts after ingest, so each page takes its current lane.
                hostPermits
                    .semaphore(adapter.hostKey(session.feed, session.feed.pagingNextUrl ?: session.feed.feedUrl))
                    .withPermit {
                        if (clock.elapsedRealtime() < stopAt) {
                            session.active = onePageGuarded(session, manual)
                        }
                    }
            }
        }
        if (stopAt == Long.MAX_VALUE) {
            pages()
        } else {
            withTimeoutOrNull((stopAt - clock.elapsedRealtime()).coerceAtLeast(0)) { pages() }
        }
        return session.lastOutcome
    }

    /**
     * [onePage] with per-session fault isolation: its own guards cover the fetch and the ingest,
     * but the surrounding DAO calls (markPagingComplete, the cursor reload, the episode cap) can
     * still throw. One session's fault ends that session — it must not escape the launch and
     * cancel every sibling's in-flight page (03's per-feed isolation).
     */
    private suspend fun onePageGuarded(
        session: PagingSession,
        manual: Boolean,
    ): Boolean =
        suspendRunCatching { onePage(session, manual) }
            .getOrElse { e ->
                if (e is CancellationException) throw e
                Log.w(TAG, e) { "page handling failed for podcast ${session.feed.id}" }
                session.lastOutcome =
                    FeedOutcome.Failed(
                        if (isUniqueViolation(e)) FeedErrorKind.IDENTITY_CONFLICT else FeedErrorKind.STORAGE,
                        null,
                    )
                false
            }

    /** The shared stop of the paging session loop and the background-paging round. */
    private suspend fun onePage(
        session: PagingSession,
        manual: Boolean,
    ): Boolean {
        val url = session.feed.pagingNextUrl ?: return false
        // Loop guard: a URL already fetched this run ends the session "not wanted".
        if (!session.visited.add(url)) {
            db.podcastDao().markPagingComplete(session.feed.id)
            return false
        }
        if (session.pagesUsed >= PAGES_PER_RUN) {
            db.podcastDao().markPagingComplete(session.feed.id)
            return false
        }
        val result =
            suspendRunCatching { session.adapter.fetchAndParse(session.feed, FetchMode.OLDER_PAGE) }
                .getOrElse { e ->
                    if (e is CancellationException) throw e
                    Log.w(TAG, e) { "page fetch threw for podcast ${session.feed.id}" }
                    session.lastOutcome = FeedOutcome.Failed(FeedErrorKind.UNKNOWN, null)
                    return false
                }
        return when (result) {
            is AdapterResult.Parsed -> {
                val ingest =
                    suspendRunCatching {
                        ingestor.ingest(
                            session.feed,
                            result.feed,
                            IngestContext(
                                mode = IngestMode.OLDER_PAGE,
                                partial = result.partial,
                                fetch = result.meta,
                                rowHints = result.rowHints,
                                absenceFloor = result.absenceFloor,
                            ),
                            intervals[session.feed.id],
                        )
                    }.getOrElse { e ->
                        if (e is CancellationException) throw e
                        Log.w(TAG, e) { "page ingest failed for podcast ${session.feed.id}" }
                        val kind =
                            if (isUniqueViolation(e)) FeedErrorKind.IDENTITY_CONFLICT else FeedErrorKind.STORAGE
                        session.lastOutcome = FeedOutcome.Failed(kind, null)
                        return false
                    }
                rememberWarnings(session.feed.id, ingest.warnings)
                session.pagesUsed++
                // Step 11 applies to page ingests too (hook, adapter filter, no new episodes).
                announceIngest(session.feed, session.adapter, ingest, initialFetch = false)
                session.lastOutcome =
                    FeedOutcome.Ingested(
                        inserted = ingest.inserted.size,
                        newCount = ingest.newIds.size,
                        firstIngest = false,
                        sameGuidAs = null,
                    )
                val link = result.feed.paging.next ?: result.feed.paging.prevArchive
                when {
                    // The ingest already wrote `pagingComplete = 1` (the page had no older link).
                    link == null -> {
                        false
                    }

                    // "a page yields no new keys" — inserted empty and nothing re-keyed by pass 2.
                    ingest.inserted.isEmpty() && ingest.rekeyed == 0 -> {
                        db.podcastDao().markPagingComplete(session.feed.id)
                        false
                    }

                    !manual && db.podcastDao().episodeCount(session.feed.id) >= EPISODE_CAP -> {
                        db.podcastDao().markPagingComplete(session.feed.id)
                        false
                    }

                    else -> {
                        // The ingest wrote this page's older link; the next fetch reads it back.
                        session.feed = db.podcastDao().dueFeedById(session.feed.id) ?: return false
                        true
                    }
                }
            }

            is AdapterResult.NotModified -> {
                session.lastOutcome = FeedOutcome.NotModified
                false
            }

            is AdapterResult.Unchanged -> {
                callAfterIngest(session.feed, session.adapter, emptyList(), emptyList())
                session.lastOutcome = FeedOutcome.Unchanged
                false
            }

            is AdapterResult.Deferred -> {
                // As in the refresh arm: only `nextRefreshAt` moves; the row is re-read so a
                // user action between page selection and this write isn't clobbered.
                val stored = db.podcastDao().byId(session.feed.id)
                addFetchState(
                    fetchStateOf(
                        session.feed,
                        lastAttemptAt = stored?.lastAttemptAt ?: session.feed.lastAttemptAt,
                        lastSuccessAt = stored?.lastSuccessAt ?: session.feed.lastSuccessAt,
                        nextRefreshAt =
                            session.adapter.nextRefreshAt(session.feed, result, result.untilMs),
                        failureCount = stored?.failureCount ?: session.feed.failureCount,
                        lastErrorKind = stored?.lastErrorKind ?: session.feed.lastErrorKind,
                        lastErrorDetail = stored?.lastErrorDetail,
                        gone = stored?.gone ?: false,
                        needsCredentials = stored?.needsCredentials ?: false,
                        etag = stored?.etag ?: session.feed.etag,
                        lastModified = stored?.lastModified ?: session.feed.lastModified,
                        lastFullFetchAt = stored?.lastFullFetchAt ?: session.feed.lastFullFetchAt,
                        lastParseOk = stored?.lastParseOk ?: session.feed.lastParseOk,
                    ),
                )
                session.lastOutcome = FeedOutcome.Deferred(result.untilMs)
                false
            }

            is AdapterResult.Failed -> {
                // "a fetch or parse failure leaves the state unchanged and retries in a later run".
                result.htmlBody?.let { suspendRunCatching { tempFiles.delete(it) } }
                session.lastOutcome = FeedOutcome.Failed(result.kind, result.http)
                false
            }
        }
    }

    /**
     * Step 7 of 03 Engine run: background paging, round-robin one page per podcast per round under
     * the fan-out semaphores, bounded by `pagingBudgetMs` and the deadline's 2-minute margin.
     */
    private suspend fun runBackgroundPaging(
        request: RefreshRequest,
        pendingPages: List<DueFeed>,
        global: Semaphore,
        hostPermits: HostPermits,
    ) {
        val stopAt = pagingStopAt(request)
        val sessions = pendingPages.map { PagingSession(it, adapters.getValue(it.sourceType)) }
        // The phase is cancellation-bounded at the stop, not only checked between rounds: a page
        // still in flight when the budget/deadline lands is cancelled — its transaction rolls
        // back and the feed stays paging-pending for a later run (03 Engine run step 9).
        val rounds: suspend () -> Unit = {
            while (clock.elapsedRealtime() < stopAt) {
                val active = sessions.filter { it.active }
                if (active.isEmpty()) break
                coroutineScope {
                    for (session in active) {
                        launch {
                            global.withPermit {
                                hostPermits
                                    .semaphore(
                                        session.adapter.hostKey(
                                            session.feed,
                                            session.feed.pagingNextUrl ?: session.feed.feedUrl,
                                        ),
                                    ).withPermit {
                                        // The bound is re-checked after the permit waits: a session
                                        // that spent the paging budget queued must not start a page
                                        // anyway.
                                        if (clock.elapsedRealtime() < stopAt) {
                                            session.active = onePageGuarded(session, manual = false)
                                        }
                                    }
                            }
                        }
                    }
                }
            }
        }
        if (stopAt == Long.MAX_VALUE) {
            rounds()
        } else {
            withTimeoutOrNull((stopAt - clock.elapsedRealtime()).coerceAtLeast(0)) { rounds() }
        }
    }

    /**
     * Per-host fetch limiter for a run (03's 2-per-host politeness cap). Semaphores are created
     * lazily under [lock]: an RFC 5005 `rel=next` link can point at a host that was not in the
     * run's initial key set, and the first lookup of that host must resolve rather than throw.
     */
    private class HostPermits {
        private val lock = Mutex()
        private val sems = HashMap<String, Semaphore>()

        suspend fun semaphore(key: String): Semaphore =
            lock.withLock { sems.getOrPut(key) { Semaphore(HOST_CONCURRENCY) } }
    }

    /** The paging phase's hard stop: `pagingBudgetMs` or, on Android, 2 min before the deadline. */
    private fun pagingStopAt(request: RefreshRequest): Long {
        val budgetEnd =
            if (request.pagingBudgetMs > 0) clock.elapsedRealtime() + request.pagingBudgetMs else Long.MAX_VALUE
        val deadlineEnd =
            if (request.deadlineElapsedMs == RefreshRequest.NO_DEADLINE) {
                Long.MAX_VALUE
            } else {
                request.deadlineElapsedMs - PAGING_MARGIN_MS
            }
        return minOf(budgetEnd, deadlineEnd)
    }

    /**
     * Step 11 of 03's diff algorithm (hook → adapter filter → emit) under `NonCancellable`, so a
     * deadline cancel inside `afterIngest` never loses the event.
     */
    private suspend fun announceIngest(
        feed: DueFeed,
        adapter: SourceAdapter,
        ingest: IngestResult,
        initialFetch: Boolean,
    ) {
        val announceIds =
            withContext(NonCancellable) {
                if (ingest.inserted.isNotEmpty() || ingest.rekeyed > 0) {
                    try {
                        syncHook.afterIngest(feed.id)
                    } catch (e: Throwable) {
                        // A failing hook is logged, never propagated (03 step 11).
                        Log.w(TAG, e) { "sync hook failed for podcast ${feed.id}" }
                    }
                }
                callAfterIngest(feed, adapter, ingest.inserted, ingest.newIds)
            }
        val emitIds = if (initialFetch) ingest.inserted else announceIds
        if (emitIds.isEmpty()) return
        // Emit and its bookkeeping share one non-cancellable section: a deadline landing between
        // them would fire the event while dropping the announcements record.
        withContext(NonCancellable) {
            eventBus.emit(feed.id, emitIds, initialFetch)
            announcementsLock.withLock { announcements += NewEpisodes(feed.id, emitIds, initialFetch) }
        }
    }

    /**
     * `adapter.afterIngest` guarded: a throwing adapter falls back to `newIds`, and "a cancelled
     * `afterIngest` emits `newIds`" (03 step 11) — inside `NonCancellable` the run's own deadline
     * cannot throw here, so a `CancellationException` is the adapter's internal deadline.
     */
    private suspend fun callAfterIngest(
        feed: DueFeed,
        adapter: SourceAdapter,
        inserted: List<Long>,
        newIds: List<Long>,
    ): List<Long> =
        withContext(NonCancellable) {
            try {
                adapter.afterIngest(feed.id, inserted, newIds)
            } catch (e: CancellationException) {
                newIds
            } catch (e: Throwable) {
                Log.w(TAG, e) { "afterIngest failed for podcast ${feed.id}" }
                newIds
            }
        }

    // --- Selection, force and fetch-state writes ------------------------------------------------

    private suspend fun selectDue(request: RefreshRequest): List<DueFeed> {
        val scope = request.scope
        return if (request.pagesOnly) {
            selectPaging(scope)
        } else {
            val dueBefore = clock.now() + request.dueSlackMs
            when (scope) {
                RefreshScope.All -> db.podcastDao().dueForRefresh(dueBefore, scopeAll = true)
                is RefreshScope.Group -> db.podcastDao().dueForRefreshGroup(dueBefore, scope.groupId)
                is RefreshScope.Podcasts -> db.podcastDao().dueForRefresh(dueBefore, scopeAll = false, ids = scope.ids)
            }
        }
    }

    private suspend fun selectPaging(scope: RefreshScope): List<DueFeed> =
        when (scope) {
            RefreshScope.All -> db.podcastDao().pagingPending(scopeAll = true)
            is RefreshScope.Group -> db.podcastDao().pagingPendingGroup(scope.groupId)
            is RefreshScope.Podcasts -> db.podcastDao().pagingPending(scopeAll = false, ids = scope.ids)
        }

    /** 03's persisted force step: `nextRefreshAt = 0` for the scope's unblocked podcasts. */
    private suspend fun forceDue(scope: RefreshScope) {
        when (scope) {
            RefreshScope.All -> db.podcastDao().forceDue(scopeAll = true)
            is RefreshScope.Group -> db.podcastDao().forceDueGroup(scope.groupId)
            is RefreshScope.Podcasts -> db.podcastDao().forceDue(scopeAll = false, ids = scope.ids)
        }
    }

    /**
     * The request's persisted user intent: a `RETRY` clears the scope's failure blocks and a
     * forced run re-marks it due. The entry writes land before the mutex wait, but an older
     * run's outcome can overwrite them while the request queues (a backoff lands on
     * `nextRefreshAt = 0`, a stale 410 re-sets `gone`), so they are re-applied after the grant
     * and again when the wait times out — the marks outlive stale outcomes either way (r3 F3).
     * Blocks clear before the force write: `forceDue` only touches unblocked rows.
     */
    private suspend fun reapplyIntent(request: RefreshRequest) {
        if (request.origin == RefreshOrigin.RETRY) clearBlocks(request.scope)
        if (request.force) forceDue(request.scope)
    }

    /**
     * Whether the request carries intent a generic `refresh-continuation` cannot express — an
     * `All`, unforced, full pass with no origin behaviour: a forced mark set, a narrowed
     * scope, a pages-only pass or the `RETRY` block clear. Such a request re-enqueues itself
     * on a mutex timeout (r4 F3); a plain due run keeps reporting `remaining` for the
     * caller's continuation chain.
     */
    private fun RefreshRequest.hasUncontinuableIntent(): Boolean =
        force || pagesOnly || scope != RefreshScope.All || origin == RefreshOrigin.RETRY

    /** The scoped variant of 03's `PodcastDao.clearRefreshBlock` ("Try again"). */
    private suspend fun clearBlocks(scope: RefreshScope) {
        when (scope) {
            RefreshScope.All -> {
                db.podcastDao().clearRefreshBlocks(scopeAll = true)
            }

            is RefreshScope.Group -> {
                db.podcastDao().clearRefreshBlocksGroup(scope.groupId)
            }

            is RefreshScope.Podcasts -> {
                db.podcastDao().clearRefreshBlocks(scopeAll = false, ids = scope.ids)
            }
        }
    }

    private suspend fun addFetchState(row: PodcastFetchState) {
        batcher?.add(row) ?: db.podcastDao().updateFetchStates(listOf(row))
    }

    private suspend fun currentNextAt(id: Long): Long = db.podcastDao().byId(id)?.nextRefreshAt ?: clock.now()

    private fun successNextAt(
        feed: DueFeed,
        now: Long,
        maxAgeSec: Long?,
    ): Long =
        RefreshPolicy.successNextRefreshAt(
            now,
            intervals[feed.id],
            feed.complete,
            feed.latestEpisodeAt,
            feed.ttlMinutes,
            maxAgeSec,
        )

    private suspend fun writeFailure(
        feed: DueFeed,
        kind: FeedErrorKind,
        retryAfterMs: Long?,
        detail: String?,
    ) {
        val now = clock.now()
        val n = feed.failureCount + 1
        addFetchState(
            fetchStateOf(
                feed,
                lastAttemptAt = now,
                lastSuccessAt = feed.lastSuccessAt,
                nextRefreshAt = RefreshPolicy.failureNextRefreshAt(now, n, retryAfterMs, random),
                failureCount = n,
                lastErrorKind = kind,
                lastErrorDetail = detail,
                etag = feed.etag,
                lastModified = feed.lastModified,
                lastFullFetchAt = feed.lastFullFetchAt,
                lastParseOk = feed.lastParseOk,
            ),
        )
    }

    /** A `PodcastFetchState` row: due feeds are never `gone`/`needsCredentials`, so 0/0 defaults. */
    private fun fetchStateOf(
        feed: DueFeed,
        lastAttemptAt: Long?,
        lastSuccessAt: Long?,
        nextRefreshAt: Long?,
        failureCount: Int,
        lastErrorKind: FeedErrorKind?,
        lastErrorDetail: String?,
        gone: Boolean = false,
        needsCredentials: Boolean = false,
        etag: String?,
        lastModified: String?,
        lastFullFetchAt: Long?,
        lastParseOk: Boolean,
    ) = PodcastFetchState(
        id = feed.id,
        lastAttemptAt = lastAttemptAt,
        lastSuccessAt = lastSuccessAt,
        nextRefreshAt = nextRefreshAt,
        failureCount = failureCount,
        lastErrorKind = lastErrorKind,
        lastErrorDetail = lastErrorDetail,
        gone = gone,
        needsCredentials = needsCredentials,
        etag = etag,
        lastModified = lastModified,
        lastFullFetchAt = lastFullFetchAt,
        lastParseOk = lastParseOk,
    )

    // --- Diagnostics ----------------------------------------------------------------------------

    private suspend fun rememberWarnings(
        podcastId: Long,
        warnings: List<ParseWarning>,
    ) {
        if (warnings.isEmpty()) return
        warningsLock.withLock {
            if (parseWarnings.size >= WARNINGS_LRU_SIZE) {
                parseWarnings.remove(parseWarnings.keys.first())
            }
            parseWarnings[podcastId] = warnings
        }
    }

    /** The last 50 feeds' parse warnings (03 Diagnostics). */
    suspend fun lastParseWarnings(podcastId: Long): List<ParseWarning> =
        warningsLock.withLock { parseWarnings[podcastId].orEmpty() }

    private suspend fun writeDiagnostics(
        request: RefreshRequest,
        report: RefreshReport,
        now: Long,
    ) {
        suspendRunCatching { settings.set(FeedsSettingKeys.LAST_RUN_FINISHED_AT, now) }
        if (request.scope == RefreshScope.All && !report.stoppedByDeadline) {
            suspendRunCatching { settings.set(FeedsSettingKeys.LAST_ALL_RUN_FINISHED_AT, now) }
        }
        suspendRunCatching {
            settings.set(FeedsSettingKeys.LAST_RUN_SUMMARY, summaryJson(request, report))
        }
    }

    private fun summaryJson(
        request: RefreshRequest,
        report: RefreshReport,
    ): String {
        val failed =
            report.outcomes.values
                .filterIsInstance<FeedOutcome.Failed>()
                .groupingBy { it.kind.name }
                .eachCount()
        return buildJsonObject {
            put("origin", request.origin.name)
            put("attempted", report.outcomes.size)
            put("ingested", report.outcomes.values.count { it is FeedOutcome.Ingested })
            put("notModified", report.outcomes.values.count { it is FeedOutcome.NotModified })
            put("unchanged", report.outcomes.values.count { it is FeedOutcome.Unchanged })
            put("merged", report.outcomes.values.count { it is FeedOutcome.Merged })
            putJsonObject("failed") { failed.forEach { (kind, count) -> put(kind, count) } }
            put("remaining", report.remaining)
            put("stoppedByDeadline", report.stoppedByDeadline)
        }.toString()
    }

    private fun isUniqueViolation(t: Throwable): Boolean {
        var cause: Throwable? = t
        while (cause != null) {
            // "UNIQUE constraint failed: …" — the tighter phrase keeps a generic "UNIQUE"
            // substring in an unrelated message from misclassifying a storage fault.
            if (cause.message?.contains("UNIQUE constraint", ignoreCase = true) == true) return true
            cause = cause.cause
        }
        return false
    }

    /** One paging session's mutable state (visited URLs, page count, last page outcome). */
    private class PagingSession(
        var feed: DueFeed,
        val adapter: SourceAdapter,
        val visited: MutableSet<String> = mutableSetOf(),
        var pagesUsed: Int = 0,
        var active: Boolean = true,
        var lastOutcome: FeedOutcome? = null,
    )

    private companion object {
        const val TAG = "FeedRefresher"

        /** The fan-out limits of 03 Engine run step 5: ≤ 6 concurrent feeds, ≤ 2 per host. */
        const val GLOBAL_CONCURRENCY = 6
        const val HOST_CONCURRENCY = 2

        /** Android's 8-min deadline leaves a 30 s launch/cleanup margin (03 Engine run). */
        const val DEADLINE_MARGIN_MS = 30_000L

        /** Paging never starts with less than 2 min to the deadline (03 Engine run step 7). */
        const val PAGING_MARGIN_MS = 120_000L

        /** The in-memory page budget of 03's session stops: 50 pages per podcast per run. */
        const val PAGES_PER_RUN = 50

        /** Automatic sessions stop at 5,000 stored episodes; manual ones have no cap (03). */
        const val EPISODE_CAP = 5_000

        const val WARNINGS_LRU_SIZE = 50
        const val EVENT_BUFFER_CAPACITY = 64
    }
}
