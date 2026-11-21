// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.refresh

import ch.lkmc.neutrodyne.core.data.ingest.FetchMeta
import ch.lkmc.neutrodyne.core.data.ingest.RowHint
import ch.lkmc.neutrodyne.core.database.DueFeed
import ch.lkmc.neutrodyne.core.domain.ServerFeedSnapshot
import ch.lkmc.neutrodyne.core.model.FeedErrorKind
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.feeds.model.ParsedFeed
import dev.zacsweers.metro.MapKey
import okio.Path

/**
 * The source-adapter contract of 03 Source adapters (`:core:data` commonMain, internal): the
 * engine is source-agnostic; per-source fetching, parsing and post-ingest behaviour live behind
 * this interface. Adapters are a Metro map multibinding keyed by [SourceTypeKey] so both
 * platforms get the same set.
 */
internal interface SourceAdapter {
    val sourceType: SourceType

    /**
     * Per-host concurrency key (03 Engine run step 5: 2 per host): the lowercase host of
     * [requestUrl] — the feed URL for refreshes, the page URL for `OLDER_PAGE`, which may point
     * at a different host than the feed (e.g. a rel=next CDN).
     */
    fun hostKey(
        feed: DueFeed,
        requestUrl: String,
    ): String

    /** A digest may stand in for bytes when the device can reuse its last successful parse. */
    fun knownServerHash(feed: DueFeed): String? = null

    /**
     * Fetches and parses one feed; [mode] `OLDER_PAGE` fetches `pagingNextUrl` unconditionally.
     *
     * [cached] holds this run's fresh server snapshots keyed by their requested URL, empty when the
     * run had no usable entry. An adapter that can serve a document from the cache returns that
     * result without an upstream request. CACHED_ONLY mode must never fetch a publisher document.
     */
    suspend fun fetchAndParse(
        feed: DueFeed,
        mode: FetchMode,
        cached: Map<String, ServerFeedSnapshot> = emptyMap(),
    ): AdapterResult

    /** May raise [base]; only YouTube's bounded gap rule may lower a normal success interval.
     * Server/backoff/deferred deadlines still win; the 15-minute floor always applies there. */
    fun nextRefreshAt(
        feed: DueFeed,
        result: AdapterResult,
        base: Long,
    ): Long

    /**
     * Runs after the ingest commit (also after `Unchanged`, with empty lists) and before the
     * `NewEpisodes` emit; returns the IDs to announce. RSS returns [newIds] unchanged.
     */
    suspend fun afterIngest(
        podcastId: Long,
        inserted: List<Long>,
        newIds: List<Long>,
    ): List<Long> = newIds
}

/** `FULL` = unconditional (parser bump, fortnightly full fetch, previews, probes). */
internal enum class FetchMode { REFRESH, CACHED_ONLY, FULL, OLDER_PAGE }

/** The result of one `fetchAndParse` (03 Source adapters). */
internal sealed interface AdapterResult {
    data class Parsed(
        val feed: ParsedFeed,
        /** Page-1 partial flag: `(next ?: prevArchive) != null && !fhComplete` for RSS. */
        val partial: Boolean,
        val meta: FetchMeta,
        val rowHints: Map<String, RowHint> = emptyMap(),
        /** 04: overrides the partial-window floor of diff step 8. */
        val absenceFloor: Long? = null,
        /** A bodyless redirect confirmation supplies no tag-probe eligibility (03). */
        val purpose: ParsePurpose = ParsePurpose.CURRENT_FEED,
    ) : AdapterResult

    data class NotModified(
        val meta: FetchMeta?,
    ) : AdapterResult

    data class Unchanged(
        val meta: FetchMeta,
    ) : AdapterResult

    /**
     * `transient = true`: failure backoff and `failureCount + 1`, but never `gone` (03 Source
     * adapters). [htmlBody] keeps a `NOT_A_FEED` HTML page for pending autodiscovery (M3).
     */
    data class Failed(
        val kind: FeedErrorKind,
        val http: Int?,
        val retryAfterMs: Long?,
        val transient: Boolean,
        val htmlBody: Path? = null,
        /** Human-readable cause (parser failure, classifier type) for `lastErrorDetail`. */
        val detail: String? = null,
    ) : AdapterResult

    /** 04: not attempted; only `nextRefreshAt` changes. */
    data class Deferred(
        val untilMs: Long,
    ) : AdapterResult
}

/** Metro map key binding `SourceType` → `SourceAdapter` (03 Source adapters). */
@MapKey
internal annotation class SourceTypeKey(
    val value: SourceType,
)

/** Keeps redirect confirmation separate from a changed current feed body. */
internal enum class ParsePurpose { CURRENT_FEED, REDIRECT_CONFIRMATION }
