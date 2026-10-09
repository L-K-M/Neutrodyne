// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.refresh

import ch.lkmc.neutrodyne.core.data.ingest.FetchMeta
import ch.lkmc.neutrodyne.core.data.ingest.RowHint
import ch.lkmc.neutrodyne.core.database.DueFeed
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

    /** Per-host concurrency key (03 Engine run step 5: 2 per host): the lowercase request host. */
    fun hostKey(feed: DueFeed): String

    /** Fetches and parses one feed; [mode] `OLDER_PAGE` fetches `pagingNextUrl` unconditionally. */
    suspend fun fetchAndParse(
        feed: DueFeed,
        mode: FetchMode,
    ): AdapterResult

    /** May only raise [base] (03 policy table: YouTube's 15-minute floor uses it). */
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
internal enum class FetchMode { REFRESH, FULL, OLDER_PAGE }

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
