// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.ingest

import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.FeedErrorKind
import ch.lkmc.neutrodyne.feeds.model.ParseWarning

/*
 * The ingest-side types of 03's diff algorithm (`:core:data` commonMain, internal). `FeedIngestor`
 * consumes a parsed feed plus this context; the refresh engine builds both and reads [IngestResult]
 * for its outcome, event and diagnostics accounting.
 */

/** Which ingest the transaction performs (03 Diff algorithm). */
internal enum class IngestMode { REFRESH, INITIAL, OLDER_PAGE }

/**
 * Everything the diff needs beyond the parsed document (03 Ingestion and diff): the fetch outcome's
 * validator and move data, 04's per-item overrides and the absent-row floor.
 */
internal data class IngestContext(
    val mode: IngestMode,
    /** Page-1 `partial` flag from the adapter (`paging.next`/`prevArchive` without `fh:complete`). */
    val partial: Boolean,
    val fetch: FetchMeta,
    /** Per-`externalMediaId` overrides of `availability`/`isShort`/`isVideo` (04). */
    val rowHints: Map<String, RowHint> = emptyMap(),
    /** 04's replacement for the partial-window floor of diff step 8 (`Long.MAX_VALUE` flips nothing). */
    val absenceFloor: Long? = null,
)

/** The validator and URL data of one 200 response that parsed (03 IngestContext). */
internal data class FetchMeta(
    val finalUrl: String,
    val permanentUrl: String?,
    val etag: String?,
    val lastModified: String?,
    val sha256Hex: String,
    val serverDateMs: Long?,
    val maxAgeSec: Long?,
    /** Whether the request was unconditional (full-fetch rule): `lastFullFetchAt` tracks those. */
    val unconditional: Boolean = false,
)

/** 04's per-item column override, keyed by `externalMediaId` (03 Column rules on update). */
internal data class RowHint(
    val availability: Availability?,
    val isShort: Boolean?,
    val isVideo: Boolean?,
)

/** What one ingest wrote (03 Ingestion and diff); the engine maps it onto `FeedOutcome` and events. */
internal data class IngestResult(
    /** Every inserted episode id in document order (`INITIAL` emits all of them). */
    val inserted: List<Long>,
    /** Inserted rows that kept `isNew = 1` (after the dump guard). */
    val newIds: List<Long>,
    /** Matched rows whose `contentHash` differed and were rewritten. */
    val updated: Int,
    /** Items accepted for diffing (primary enclosure or `externalMediaId`). */
    val accepted: Int,
    /** Stored rows flipped to `inFeed = 0` by step 8. */
    val flippedOut: Int,
    /** In-place re-keys of steps 4 and 5 (the sync hook's trigger, with inserts). */
    val rekeyed: Int,
    /** This ingest flipped `initialFetch`/activated a pending podcast. */
    val firstIngest: Boolean,
    /** `podcast.artworkKey` changed — the engine requests a re-pin from M4's artwork store. */
    val artworkChanged: Boolean,
    /** Parse warnings of the document, kept by the engine for diagnostics. */
    val warnings: List<ParseWarning>,
    /**
     * The kind an empty ingest reports — `NO_MEDIA`/`UNSUPPORTED_LIST_FEED` (03 Accepted items);
     * `null` when rows were written or the podcast vanished mid-run.
     */
    val emptyKind: FeedErrorKind? = null,
    /** The podcast row was deleted between selection and ingest — nothing was written. */
    val vanished: Boolean = false,
) {
    internal companion object {
        /** The `NO_MEDIA`/`UNSUPPORTED_LIST_FEED`/vanished result — nothing was written (03). */
        fun empty(
            warnings: List<ParseWarning>,
            kind: FeedErrorKind? = null,
            vanished: Boolean = false,
        ) = IngestResult(
            inserted = emptyList(),
            newIds = emptyList(),
            updated = 0,
            accepted = 0,
            flippedOut = 0,
            rekeyed = 0,
            firstIngest = false,
            artworkChanged = false,
            warnings = warnings,
            emptyKind = kind,
            vanished = vanished,
        )
    }
}
