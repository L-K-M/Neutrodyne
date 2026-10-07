// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.parse

import ch.lkmc.neutrodyne.feeds.model.ParsedFeed

/**
 * Parses one feed document into a [ParsedFeed] (03 Parser). Pure: reads only through [open], never throws
 * for malformed input. Implementations are JVM code in `:feeds:jvm` (`XmlPullFeedParser`) reached through
 * `PullParserFactory`-style factories; Android uses the platform parser, the desktop kxml2 at run time.
 */
public interface FeedParser {
    /**
     * Parses the document [open] yields. [httpCharset] is the HTTP `Content-Type` charset, consulted only
     * by the re-parse heuristic (an `ISO-8859-1` header on a UTF-8 body must not win the first pass).
     * [baseUrl] resolves relative URLs; `xml:base` in the document wins when present.
     */
    public fun parse(
        open: () -> okio.Source,
        httpCharset: String?,
        baseUrl: String,
    ): ParseResult

    public companion object {
        /**
         * One version for every implementation; stored per podcast after each successful ingest. Bumped
         * whenever a change would alter any value ingestion writes for an existing golden fixture
         * (03 Limits and version policy).
         */
        public const val VERSION: Int = 1
    }
}

/** Either a parsed feed or a failure; never an exception (03 Parser). */
public sealed interface ParseResult {
    public data class Ok(
        val feed: ParsedFeed,
    ) : ParseResult

    public data class Failed(
        val reason: ParseFailure,
        val detail: String,
    ) : ParseResult
}

/** Why a document could not be parsed (03 Parser). */
public enum class ParseFailure {
    NOT_A_FEED,
    HOSTILE,
    MALFORMED,
    TOO_DEEP,
}

/**
 * Protective limits (03 Limits and version policy). The fetch-side body cap (32 MB) is not the parser's;
 * these bound what a single document may cost to read.
 */
public data class ParseLimits(
    val maxDepth: Int = 64,
    val maxItems: Int = 10_000,
    val maxTextChars: Int = 512 * 1024,
    val maxUrlChars: Int = 4_096,
    val prologScanBytes: Int = 64 * 1024,
)
