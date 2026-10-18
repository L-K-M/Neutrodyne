// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.opml

import kotlinx.serialization.Serializable

/** Which cascade pass produced the document (05 Sniff and parse). */
@Serializable
public enum class ParseMode {
    STRICT,
    RELAXED,
    SALVAGE,
}

/**
 * One folder outline (`text ?: title`, trimmed): a group candidate for the import pipeline
 * (05 Sniff and parse). [parentId] points at the enclosing [FolderRef.id]; `nd:groupcolor` and
 * `nd:groupicon` carry Neutrodyne group looks when present.
 */
@Serializable
public data class FolderRef(
    val id: Int,
    val name: String,
    val parentId: Int? = null,
    val colorArgb: Int? = null,
    val iconKey: String? = null,
)

/**
 * One feed outline. [url] is `xmlUrl`, or `url` for the `type` = `rss`/`link` fallback. [type] is
 * the lowercased `type` attribute. [categories] holds the decoded `category` tokens (split on
 * literal commas, last non-empty `/` segment, percent-decoded exactly once). [folderId] is the
 * immediate parent folder. [preselect] is false inside an `isComment="true"` subtree and on
 * `subscribed="0"` or `type="link"` entries (05 walk rules).
 */
@Serializable
public data class OpmlEntry(
    val ordinal: Int,
    val url: String,
    val text: String? = null,
    val title: String? = null,
    val htmlUrl: String? = null,
    val type: String? = null,
    val folderId: Int? = null,
    val categories: List<String> = emptyList(),
    val preselect: Boolean = true,
    val ndSource: String? = null,
    val ndYtVariants: String? = null,
)

/** Why outlines produced no entry (05 walk rules); [count] carries the subtree sizes. */
@Serializable
public enum class OpmlWarningCode {
    /** An attribute over `OpmlLimits.maxAttrChars` dropped the outline. */
    ATTRIBUTE_TOO_LONG,

    /** `podcast-episode`/`podcast-playlist` outlines ignored with their subtrees. */
    IGNORED_OUTLINES,

    /** `type="include"` or `type="link"` `.opml` outlines — remote lists, never fetched. */
    LINKED_LISTS,

    /** The document only parsed through the salvage pass (A4). */
    SALVAGED,
}

/** One non-fatal read finding; the import pipeline turns these into user-facing warnings. */
@Serializable
public data class OpmlWarning(
    val code: OpmlWarningCode,
    val count: Int = 0,
)

/**
 * The read document (05 Sniff and parse). [ignoredOutlines] counts every outline that produced no
 * entry and was not dropped for an oversized attribute — ignored subtrees, feed children and
 * outlines inside skipped elements. [headTitle] is the first `<head><title>` text.
 * [neutrodyne] is set when the root declares `xmlns:nd="urn:neutrodyne:opml:1"`.
 */
@Serializable
public data class OpmlDocument(
    val entries: List<OpmlEntry>,
    val folders: List<FolderRef>,
    val headTitle: String? = null,
    val neutrodyne: Boolean = false,
    val mode: ParseMode,
    val ignoredOutlines: Int = 0,
    val warnings: List<OpmlWarning> = emptyList(),
)
