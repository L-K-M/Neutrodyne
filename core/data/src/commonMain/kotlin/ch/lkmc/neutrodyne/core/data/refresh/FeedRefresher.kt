// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.refresh

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.data.fetch.FeedTempFiles
import ch.lkmc.neutrodyne.core.data.ingest.FeedIngestor
import ch.lkmc.neutrodyne.core.data.ingest.IngestContext
import ch.lkmc.neutrodyne.core.data.ingest.IngestMode
import ch.lkmc.neutrodyne.core.data.ingest.IngestResult
import ch.lkmc.neutrodyne.core.data.ingest.IngestionEventBus
import ch.lkmc.neutrodyne.core.database.DueFeed
import ch.lkmc.neutrodyne.core.database.FetchStateBatcher
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastFetchState
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.domain.RefreshStatus
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import ch.lkmc.neutrodyne.core.model.FeedErrorKind
import ch.lkmc.neutrodyne.core.model.NewEpisodes
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.feeds.identity.PodcastGuid
import ch.lkmc.neutrodyne.feeds.model.ParseWarning
import dev.zacsweers.metro.Inject
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
 * Run-local state ([batcher], [intervalMinutes], [announcements]) is safe because [mutex]
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
    private val clock: Clock,
    private val random: Random,
) {
    private val mutex = Mutex()

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
    private var intervalMinutes: Int? = null
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

    private suspend fun runLocked(request: RefreshRequest): RefreshReport {
        // The run that just released the mutex may have committed an older outcome on top of
        // this request's marks (a backoff over `nextRefreshAt = 0`, a 410's `gone`): the
        // user's intent is re-applied inside the critical section, before selection (r3 F3).
        reapplyIntent(request)
        val previousFinishedAt =
            suspendRunCatching { settings.get(FeedsSettingKeys.LAST_RUN_FINISHED_AT) }
                .getOrNull()
                ?.takeIf { it > 0 }
        tempFiles.sweep(clock.now())
        intervalMinutes =
            RefreshPolicy.effectiveIntervalMinutes(settings.get(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES))

        val due = selectDue(request).filter { adapters.containsKey(it.sourceType) }
        val pendingPages =
            if (request.pagesOnly) {
                emptyList()
            } else {
                selectPaging(request.scope).filter { adapters.containsKey(it.sourceType) }
            }
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
        try {
            coroutineScope {
                batcherLock.withLock { batcher = FetchStateBatcher(db.podcastDao(), clock, this) }
                val global = Semaphore(GLOBAL_CONCURRENCY)
                val hostSems =
                    (due + pendingPages)
                        .map { feed -> adapters.getValue(feed.sourceType).hostKey(feed) }
                        .toSet()
                        .associateWith { Semaphore(HOST_CONCURRENCY) }
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
                                        hostSems.getValue(adapter.hostKey(feed)).withPermit {
                                            slots[index] = refreshOne(feed, adapter, request)
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
                    runBackgroundPaging(request, pendingPages, global, hostSems)
                }
            }
            completed = true
        } finally {
            // Finalisation survives cancellation (03 Engine run): the buffered fetch-state rows
            // flush, and the finished slots still report their committed outcomes so the summary
            // and status reflect what actually landed.
            withContext(NonCancellable) {
                val active = batcherLock.withLock { batcher.also { batcher = null } }
                suspendRunCatching { active?.flush() }

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
                writeDiagnostics(request, finalReport, finishedAt)
                _status.value =
                    RefreshStatus(
                        running = false,
                        scope = null,
                        done = outcomes.size,
                        total = due.size,
                        lastRunFinishedAt = finishedAt,
                    )
                report = finalReport
            }
        }
        return report ?: error("report unset")
    }

    /**
     * One feed of the fan-out: a paging session for `pagesOnly` runs, else fetch → policy →
     * ingest/write → outcome. Returns `null` when the run was cancelled before the feed finished.
     */
    private suspend fun refreshOne(
        feed: DueFeed,
        adapter: SourceAdapter,
        request: RefreshRequest,
    ): FeedOutcome? {
        if (request.pagesOnly) return runPagingSession(feed, adapter, request)
        val result =
            suspendRunCatching { adapter.fetchAndParse(feed, FetchMode.REFRESH) }
                .getOrElse { e ->
                    if (e is CancellationException) throw e
                    Log.w(TAG, e) { "fetchAndParse threw for podcast ${feed.id}" }
                    writeFailure(feed, FeedErrorKind.UNKNOWN, retryAfterMs = null, detail = e.message)
                    return FeedOutcome.Failed(FeedErrorKind.UNKNOWN, null)
                }
        return applyAdapterResult(feed, adapter, result)
    }

    /** The outcome table of 03 Engine run: each adapter result becomes one `FeedOutcome`. */
    private suspend fun applyAdapterResult(
        feed: DueFeed,
        adapter: SourceAdapter,
        result: AdapterResult,
    ): FeedOutcome {
        val now = clock.now()
        return when (result) {
            is AdapterResult.Parsed -> {
                ingestOutcome(feed, adapter, result, now)
            }

            is AdapterResult.NotModified -> {
                val meta = result.meta
                addFetchState(
                    fetchStateOf(
                        feed,
                        lastAttemptAt = now,
                        lastSuccessAt = now,
                        nextRefreshAt =
                            adapter.nextRefreshAt(feed, result, successNextAt(feed, now, meta?.maxAgeSec)),
                        failureCount = 0,
                        lastErrorKind = null,
                        lastErrorDetail = null,
                        etag = meta?.etag ?: feed.etag,
                        lastModified = meta?.lastModified ?: feed.lastModified,
                        lastFullFetchAt = feed.lastFullFetchAt,
                        lastParseOk = feed.lastParseOk,
                    ),
                )
                FeedOutcome.NotModified
            }

            is AdapterResult.Unchanged -> {
                addFetchState(
                    fetchStateOf(
                        feed,
                        lastAttemptAt = now,
                        lastSuccessAt = now,
                        nextRefreshAt =
                            adapter.nextRefreshAt(feed, result, successNextAt(feed, now, result.meta.maxAgeSec)),
                        failureCount = 0,
                        lastErrorKind = null,
                        lastErrorDetail = null,
                        etag = result.meta.etag ?: feed.etag,
                        lastModified = result.meta.lastModified ?: feed.lastModified,
                        // `lastFullFetchAt` tracks unconditional 200s (03 full-fetch rule).
                        lastFullFetchAt = if (result.meta.unconditional) now else feed.lastFullFetchAt,
                        lastParseOk = feed.lastParseOk,
                    ),
                )
                // 03 Source adapters: `afterIngest` runs after Unchanged too, with empty lists.
                callAfterIngest(feed, adapter, emptyList(), emptyList())
                FeedOutcome.Unchanged
            }

            is AdapterResult.Deferred -> {
                // 04 only: "not attempted; only nextRefreshAt moves" — every other column keeps
                // the stored value.
                val stored = db.podcastDao().byId(feed.id)
                addFetchState(
                    fetchStateOf(
                        feed,
                        lastAttemptAt = stored?.lastAttemptAt ?: feed.lastAttemptAt,
                        lastSuccessAt = feed.lastSuccessAt,
                        nextRefreshAt = adapter.nextRefreshAt(feed, result, result.untilMs),
                        failureCount = feed.failureCount,
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
    ): FeedOutcome {
        val mode =
            if (feed.initialFetch || feed.status == PodcastStatus.PENDING_FIRST_FETCH) {
                IngestMode.INITIAL
            } else {
                IngestMode.REFRESH
            }
        val ctx =
            IngestContext(
                mode = mode,
                partial = result.partial,
                fetch = result.meta,
                rowHints = result.rowHints,
                absenceFloor = result.absenceFloor,
            )
        val ingest =
            suspendRunCatching { ingestor.ingest(feed, result.feed, ctx) }
                .getOrElse { e ->
                    if (e is CancellationException) throw e
                    Log.w(TAG, e) { "ingest failed for podcast ${feed.id}" }
                    val kind = if (isUniqueViolation(e)) FeedErrorKind.IDENTITY_CONFLICT else FeedErrorKind.STORAGE
                    writeFailure(feed, kind, retryAfterMs = null, detail = e.message)
                    return FeedOutcome.Failed(kind, null)
                }

        rememberWarnings(feed.id, ingest.warnings)
        if (ingest.vanished) return FeedOutcome.Failed(FeedErrorKind.UNKNOWN, null)

        val emptyKind = ingest.emptyKind
        if (emptyKind != null) {
            // NO_MEDIA/UNSUPPORTED_LIST_FEED: the kind is recorded but scheduling is the success
            // row. The response's validators are *not* adopted — the body produced no ingest, so
            // the stored validators (and `lastFullFetchAt`) keep the next attempt conditional on
            // what was actually committed; otherwise a 304 would quietly clear the error.
            addFetchState(
                fetchStateOf(
                    feed,
                    lastAttemptAt = now,
                    lastSuccessAt = now,
                    nextRefreshAt =
                        adapter.nextRefreshAt(feed, result, successNextAt(feed, now, result.meta.maxAgeSec)),
                    failureCount = 0,
                    lastErrorKind = emptyKind,
                    lastErrorDetail = null,
                    etag = feed.etag,
                    lastModified = feed.lastModified,
                    lastFullFetchAt = feed.lastFullFetchAt,
                    lastParseOk = feed.lastParseOk,
                ),
            )
            return FeedOutcome.Failed(emptyKind, null)
        }

        announceIngest(feed, adapter, ingest, initialFetch = mode == IngestMode.INITIAL)
        val realGuid = result.feed.podcastGuid?.let(PodcastGuid::parse)
        val sameGuidAs =
            realGuid?.let { guid ->
                db
                    .podcastDao()
                    .byRealGuid(guid)
                    .firstOrNull { it.id != feed.id }
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
    ): FeedOutcome? {
        val session = PagingSession(feed, adapter)
        val manual = request.origin == RefreshOrigin.MANUAL
        val stopAt = pagingStopAt(request)
        val pages: suspend () -> Unit = {
            while (session.active && clock.elapsedRealtime() < stopAt) {
                session.active = onePage(session, manual)
            }
        }
        if (stopAt == Long.MAX_VALUE) {
            pages()
        } else {
            withTimeoutOrNull((stopAt - clock.elapsedRealtime()).coerceAtLeast(0)) { pages() }
        }
        return session.lastOutcome
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
                val stored = db.podcastDao().byId(session.feed.id)
                addFetchState(
                    fetchStateOf(
                        session.feed,
                        lastAttemptAt = stored?.lastAttemptAt ?: session.feed.lastAttemptAt,
                        lastSuccessAt = session.feed.lastSuccessAt,
                        nextRefreshAt =
                            session.adapter.nextRefreshAt(session.feed, result, result.untilMs),
                        failureCount = session.feed.failureCount,
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
        hostSems: Map<String, Semaphore>,
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
                                hostSems.getValue(session.adapter.hostKey(session.feed)).withPermit {
                                    // The bound is re-checked after the permit waits: a session that
                                    // spent the paging budget queued must not start a page anyway.
                                    if (clock.elapsedRealtime() < stopAt) {
                                        session.active = onePage(session, manual = false)
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
        withContext(NonCancellable) { eventBus.emit(feed.id, emitIds, initialFetch) }
        announcementsLock.withLock { announcements += NewEpisodes(feed.id, emitIds, initialFetch) }
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

    private fun successNextAt(
        feed: DueFeed,
        now: Long,
        maxAgeSec: Long?,
    ): Long =
        RefreshPolicy.successNextRefreshAt(
            now,
            intervalMinutes,
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
            putJsonObject("failed") { failed.forEach { (kind, count) -> put(kind, count) } }
            put("remaining", report.remaining)
            put("stoppedByDeadline", report.stoppedByDeadline)
        }.toString()
    }

    private fun isUniqueViolation(t: Throwable): Boolean {
        var cause: Throwable? = t
        while (cause != null) {
            if (cause.message?.contains("UNIQUE") == true) return true
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
