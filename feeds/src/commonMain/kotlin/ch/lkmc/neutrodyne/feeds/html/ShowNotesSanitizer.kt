// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.html

/**
 * Turns a raw episode description into the block model (03 Show notes). Implemented with jsoup in the
 * JVM island (`JsoupShowNotesSanitizer`); the interface stays common because the block model, the
 * timestamp linkifier and 08's renderer are common.
 */
public interface ShowNotesSanitizer {
    /**
     * Sanitises [raw] into a [ShowNotesDocument] at display time. [isHtml] is false for plain-text
     * descriptions (Atom `type=text`, YouTube); [baseUri] (episode `link`, else the feed URL) makes
     * relative `href`/`src` absolute; unresolvable ones are dropped.
     */
    public fun toDocument(
        raw: String,
        isHtml: Boolean,
        baseUri: String,
    ): ShowNotesDocument

    /**
     * The plain-text list snippet (03 At ingest): the text with whitespace collapsed, cut at the last
     * word boundary ≤ 199 chars plus `…`.
     */
    public fun snippet(
        text: String,
        isHtml: Boolean,
    ): String
}
