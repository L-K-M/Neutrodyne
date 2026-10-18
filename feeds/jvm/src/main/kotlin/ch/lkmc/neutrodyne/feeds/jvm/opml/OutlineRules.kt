// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.opml

import ch.lkmc.neutrodyne.feeds.opml.OpmlCategories
import ch.lkmc.neutrodyne.feeds.opml.OpmlEntry
import ch.lkmc.neutrodyne.feeds.opml.OpmlLimits
import java.util.Locale

/**
 * The 05 outline classification shared by every pass that produces [OpmlEntry]s: the pull sessions
 * (strict and relaxed) feed it the parser's attribute array, the salvage scanner the regex map.
 * Element and attribute names arrive already lowercased by the caller's map building.
 */
internal enum class OutlineKind {
    /** An outline with `xmlUrl`, or `url` when `type` is `rss`/`link` and not `.opml`. */
    FEED,

    /** `podcast-episode`/`podcast-playlist`: ignored with its subtree (Overcast extended). */
    IGNORED_EPISODES,

    /** `type="include"`, or `type="link"` to a `.opml` URL: remote lists, never fetched. */
    IGNORED_LISTS,

    /** Anything else: a grouping outline whose name is `text ?: title`. */
    FOLDER,
}

/**
 * The 05 classification: an outline with `xmlUrl` is a feed regardless of `type`; `url` is the
 * gPodder fallback when `type` is `rss` or `link` and the URL does not end in `.opml`. Episode,
 * playlist, include and `.opml`-link outlines are ignored with their subtree; anything else is
 * a folder — a `type="rss"` outline at another list among them.
 */
internal fun classifyOutline(attrs: Map<String, String>): OutlineKind {
    val type = attrs["type"]?.lowercase(Locale.ROOT)
    val url = attrs["url"]
    return when {
        attrs["xmlurl"] != null -> {
            OutlineKind.FEED
        }

        type == "podcast-episode" || type == "podcast-playlist" -> {
            OutlineKind.IGNORED_EPISODES
        }

        type == "include" -> {
            OutlineKind.IGNORED_LISTS
        }

        type == "link" && url != null && url.endsWith(OPML_SUFFIX, ignoreCase = true) -> {
            OutlineKind.IGNORED_LISTS
        }

        url != null && (type == "rss" || type == "link") && !url.endsWith(OPML_SUFFIX, ignoreCase = true) -> {
            OutlineKind.FEED
        }

        else -> {
            OutlineKind.FOLDER
        }
    }
}

/**
 * An attribute over `OpmlLimits.maxAttrChars` drops the outline with a warning (05 caps): the
 * pull passes check the parser's decoded values, the salvage scanner its regex-extracted ones.
 */
internal fun hasOversizedAttribute(
    attrs: Map<String, String>,
    limits: OpmlLimits,
): Boolean = attrs.values.any { it.length > limits.maxAttrChars }

/**
 * The [OpmlEntry] a feed outline produces. [commented] is the enclosing `isComment="true"` state;
 * the outline's own `isComment` attr joins it. `preselect` is false inside an `isComment` subtree
 * and on `subscribed="0"` or `type="link"` entries (05 walk rules).
 */
internal fun outlineEntry(
    ordinal: Int,
    attrs: Map<String, String>,
    folderId: Int?,
    commented: Boolean,
): OpmlEntry {
    val type = attrs["type"]?.lowercase(Locale.ROOT)
    val isComment = attrs["iscomment"]?.trim().equals("true", ignoreCase = true)
    return OpmlEntry(
        ordinal = ordinal,
        url = attrs["xmlurl"] ?: attrs["url"].orEmpty(),
        text = attrs["text"],
        title = attrs["title"],
        htmlUrl = attrs["htmlurl"],
        type = type,
        folderId = folderId,
        categories = OpmlCategories.decode(attrs["category"]),
        preselect = !commented && !isComment && attrs["subscribed"]?.trim() != "0" && type != "link",
        ndSource = attrs["nd:source"],
        ndYtVariants = attrs["nd:ytvariants"],
    )
}

/** `#RRGGBB`/`#AARRGGBB` from an `nd:groupColor` attribute; anything else is not a colour. */
internal fun parseGroupColor(raw: String?): Int? {
    if (raw == null) return null
    val v = raw.trim()
    if (!v.startsWith('#')) return null
    val hex = v.substring(1)
    // ASCII hex only: `toLong(16)` alone would accept a sign or a non-ASCII digit.
    if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    val value = hex.toLong(16)
    return when (hex.length) {
        6 -> (0xFF000000L or value).toInt()
        8 -> value.toInt()
        else -> null
    }
}

private const val OPML_SUFFIX = ".opml"
