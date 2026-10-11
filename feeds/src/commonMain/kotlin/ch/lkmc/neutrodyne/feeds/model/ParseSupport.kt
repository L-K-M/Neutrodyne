// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.model

import kotlinx.serialization.Serializable

/** The feed format a document was recognised as (03 Parser, root dispatch). */
@Serializable
public enum class FeedFormat {
    RSS2,
    ATOM,
    RDF,
}

/** Non-fatal parse findings, kept on the feed so diagnostics can show them (03 Limits and version policy). */
@Serializable
public enum class WarningCode {
    /** A prefix was used whose namespace URI was never declared; the prefix fallback table matched it. */
    UNDECLARED_PREFIX,

    /** The declared charset produced U+FFFD; the document was re-parsed with a better charset. */
    CHARSET_REPARSED,

    /** A date could not be parsed; `pubDate` is null and `rawPubDate` keeps the original. */
    UNKNOWN_DATE,

    /** `itunes:duration` was not a recognised duration. */
    BAD_DURATION,

    /** A URL was over 4,096 chars or not resolvable to absolute http(s); it was dropped. */
    BAD_URL,

    /** The document listed more than 10,000 items; the rest were not read. */
    ITEMS_TRUNCATED,

    /** A text value exceeded 512 Ki chars and was truncated. */
    TEXT_TRUNCATED,

    /** Ingestion: an item was dropped because every candidate key was already used within one document. */
    DUPLICATE_ITEM,

    /** Ingestion: a GUID repeated within one document. */
    DUPLICATE_GUID,

    /** Ingestion: an item has neither an enclosure nor an external media id (blog post). */
    NO_MEDIA_ITEM,

    /** Ingestion: more than 20 items qualified as new; only the newest 3 kept `isNew`. */
    BACK_CATALOGUE_DUMP,
}

/** One warning, with the item index it concerns (null = feed level). */
@Serializable
public data class ParseWarning(
    val code: WarningCode,
    val itemIndex: Int? = null,
    val detail: String = "",
)

/** RFC 5005 paging links and complete-feed flags (03 Feed moves, auth and paging). */
@Serializable
public data class Paging(
    val next: String? = null,
    val prevArchive: String? = null,
    val first: String? = null,
    val fhComplete: Boolean = false,
    val fhArchive: Boolean = false,
)
