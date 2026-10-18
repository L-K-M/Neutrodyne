// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.opml

/**
 * Reads one OPML document into an [OpmlDocument] (05 OPML import, step 2 "Sniff and parse").
 * Implementations run the strict → relaxed → salvage cascade over the payload, re-opening [open]
 * per pass; `XmlPullOpmlReader` in `:feeds:jvm` is the only implementation, over Android's platform
 * parser or kxml2 on the desktop. Never throws for malformed input.
 */
public interface OpmlReader {
    /**
     * Reads the document [open] yields. [open] is invoked once per cascade pass and must return a
     * fresh, readable source; the reader consumes and closes it. A throw from [open] or the read
     * itself (an I/O failure, not malformed input) propagates to the caller — [OpmlFailure]
     * deliberately has no reason for it. Only [OpmlFailure.MALFORMED] falls through to a later
     * pass; cap violations and [OpmlFailure.NO_OUTLINES] (a well-formed document without outlines)
     * are terminal.
     */
    public fun read(
        open: () -> okio.Source,
        limits: OpmlLimits = OpmlLimits(),
    ): OpmlReadResult
}

/** Either a read document or a failure; never an exception (05 Sniff and parse). */
public sealed interface OpmlReadResult {
    public data class Ok(
        val document: OpmlDocument,
    ) : OpmlReadResult

    public data class Failed(
        val failure: OpmlFailure,
    ) : OpmlReadResult
}

/** Why a document could not be read (05 Sniff and parse). */
public enum class OpmlFailure {
    /** An `<!ENTITY` declaration — the expansion attack the prolog guard exists to reject. */
    ENTITY_DECLARED,

    /** More than `maxMarkupGapBytes` of markup without a `<`, or a tag over `maxTagAttributes`. */
    TAG_TOO_LONG,

    /** Element nesting past `maxDepth`. */
    TOO_DEEP,

    /** More than `maxFeeds` feed outlines. */
    TOO_MANY_FEEDS,

    /** More than `maxOutlines` outlines. */
    TOO_MANY_OUTLINES,

    /** The document parsed but holds no `<outline>` at all. */
    NO_OUTLINES,

    /**
     * The document is not well-formed XML. Terminal only once every cascade pass failed; A4's
     * relaxed pass is what a strict-leg `MALFORMED` falls through to.
     */
    MALFORMED,
}

/**
 * Protective limits (05 Sniff and parse). `maxMarkupGapBytes` is the `MarkupGapGuard` bound and
 * `maxTagAttributes` bounds the pull parser's attribute array, which kxml2 grows quadratically.
 */
public data class OpmlLimits(
    val maxDepth: Int = 32,
    val maxFeeds: Int = 10_000,
    val maxOutlines: Int = 200_000,
    val maxAttrChars: Int = 8_192,
    val maxMarkupGapBytes: Long = 1024 * 1024,
    val maxTagAttributes: Int = 1_000,
)
