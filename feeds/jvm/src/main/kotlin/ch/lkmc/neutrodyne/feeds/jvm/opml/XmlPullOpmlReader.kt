// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.opml

import ch.lkmc.neutrodyne.feeds.jvm.parse.DepthLimitExceeded
import ch.lkmc.neutrodyne.feeds.jvm.parse.HostileEntityDeclaration
import ch.lkmc.neutrodyne.feeds.jvm.parse.PullParserFactory
import ch.lkmc.neutrodyne.feeds.opml.FolderRef
import ch.lkmc.neutrodyne.feeds.opml.OpmlCategories
import ch.lkmc.neutrodyne.feeds.opml.OpmlDocument
import ch.lkmc.neutrodyne.feeds.opml.OpmlEntry
import ch.lkmc.neutrodyne.feeds.opml.OpmlFailure
import ch.lkmc.neutrodyne.feeds.opml.OpmlLimits
import ch.lkmc.neutrodyne.feeds.opml.OpmlReadResult
import ch.lkmc.neutrodyne.feeds.opml.OpmlReader
import ch.lkmc.neutrodyne.feeds.opml.OpmlWarning
import ch.lkmc.neutrodyne.feeds.opml.OpmlWarningCode
import ch.lkmc.neutrodyne.feeds.opml.ParseMode
import okio.Source
import okio.buffer
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.util.Locale

/**
 * The strict pass of the OPML read cascade (05 Sniff and parse): one streaming walk over
 * `XmlPullParser` events that maps outlines to feeds and folders. Reads through [open]'s source
 * without materialising the document — [MarkupGapGuard] wraps the stream so the markup gap, the
 * prolog `<!ENTITY` scan and the `<?xml` attribute bound hold before parser buffers grow. A4 adds
 * the relaxed and salvage passes behind the same [OpmlReader] signature: an `XmlPullParserException`
 * here is the `MALFORMED` result a relaxed pass falls through from.
 *
 * Parser setup mirrors `XmlPullFeedParser` minus namespaces (off — prefixed attributes keep raw
 * names such as `nd:source`) and minus the relaxed feature (strict pass). `setInput(stream, null)`
 * lets the parser detect BOMs and the declared encoding itself; a `Reader` would ignore
 * `encoding="ISO-8859-1"`.
 */
public class XmlPullOpmlReader(
    private val parsers: PullParserFactory,
) : OpmlReader {
    override fun read(
        open: () -> Source,
        limits: OpmlLimits,
    ): OpmlReadResult {
        val parser = parsers.create()
        // I/O failures from open() or the stream are not malformed input: they propagate per the
        // OpmlReader contract, which deliberately has no OpmlFailure reason for them.
        return open().buffer().use { source ->
            try {
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                if (runCatching { parser.getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL) }
                        .getOrDefault(false)
                ) {
                    parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
                }
                val stream =
                    MarkupGapGuard(
                        LeadingWhitespaceSkipper(source.inputStream()),
                        limits.maxMarkupGapBytes,
                        limits.maxTagAttributes,
                    )
                parser.setInput(stream, null)
                ReadSession(parser, limits).run()
            } catch (e: HostileEntityDeclaration) {
                OpmlReadResult.Failed(OpmlFailure.ENTITY_DECLARED)
            } catch (e: DepthLimitExceeded) {
                OpmlReadResult.Failed(OpmlFailure.TOO_DEEP)
            } catch (e: MarkupBoundExceeded) {
                OpmlReadResult.Failed(OpmlFailure.TAG_TOO_LONG)
            } catch (e: OpmlCapExceeded) {
                OpmlReadResult.Failed(e.failure)
            } catch (e: XmlPullParserException) {
                // kxml2 wraps any failure pulled from the stream inside `setInput`'s encoding
                // sniff into the exception's `detail`; a cap violation must keep its terminal
                // reason, never degrade to the MALFORMED a relaxed pass falls through from.
                when (val streamFailure = streamFailureIn(e)) {
                    is HostileEntityDeclaration -> OpmlReadResult.Failed(OpmlFailure.ENTITY_DECLARED)

                    is DepthLimitExceeded -> OpmlReadResult.Failed(OpmlFailure.TOO_DEEP)

                    is MarkupBoundExceeded -> OpmlReadResult.Failed(OpmlFailure.TAG_TOO_LONG)

                    is OpmlCapExceeded -> OpmlReadResult.Failed(streamFailure.failure)

                    // The cascade fall-through boundary: A4's relaxed pass retries these.
                    else -> OpmlReadResult.Failed(OpmlFailure.MALFORMED)
                }
            } catch (e: StackOverflowError) {
                OpmlReadResult.Failed(OpmlFailure.TOO_DEEP)
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                OpmlReadResult.Failed(OpmlFailure.MALFORMED)
            }
        }
    }

    public companion object {
        /** The desktop binding: XmlPull discovery (kxml2 at run time, 05 OPML import). */
        public fun discovered(): OpmlReader = XmlPullOpmlReader(PullParserFactory.Discovered)
    }
}

/**
 * The first stream-level failure chained under [e] — through `detail` (kxml2's wrap channel) or
 * `cause` — or null when the exception is a plain parse error. Guard and session cap exceptions
 * thrown while the parser pulls bytes surface wrapped; their own catch clauses only see direct
 * throws.
 */
private fun streamFailureIn(e: XmlPullParserException): Throwable? {
    var t: Throwable? = e.detail ?: e.cause
    val seen = HashSet<Throwable>()
    while (t != null && seen.add(t)) {
        when (t) {
            is HostileEntityDeclaration, is DepthLimitExceeded, is MarkupBoundExceeded, is OpmlCapExceeded -> return t
        }
        t = (t as? XmlPullParserException)?.detail ?: t.cause
    }
    return null
}

/** Thrown when a per-outline cap trips inside the document walk; carries the failure it maps to. */
internal class OpmlCapExceeded(
    val failure: OpmlFailure,
    message: String,
) : RuntimeException(message)

/**
 * All mutable state of one read: the outline walk, the entry and folder lists, and the
 * warning/ignored bookkeeping. A fresh session per [read] keeps concurrent reads on one
 * `XmlPullOpmlReader` independent.
 */
private class ReadSession(
    private val parser: XmlPullParser,
    private val limits: OpmlLimits,
) {
    private val entries = ArrayList<OpmlEntry>()
    private val folders = ArrayList<FolderRef>()

    private var headTitle: String? = null
    private var neutrodyne = false
    private var headSeen = false
    private var bodySeen = false

    private var totalOutlines = 0
    private var feedCount = 0
    private var nextFolderId = 0
    private var ignoredOutlines = 0
    private var ignoredEpisodes = 0
    private var ignoredLists = 0
    private var droppedAttr = 0

    fun run(): OpmlReadResult {
        var event = parser.eventType
        while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT) {
            event = nextEvent()
        }
        if (event == XmlPullParser.END_DOCUMENT || !parser.name.equals(ROOT_ELEMENT, ignoreCase = true)) {
            return OpmlReadResult.Failed(OpmlFailure.NO_OUTLINES)
        }
        // The root tag gets the same post-delivery bounds as every other start tag.
        onStartTag()
        neutrodyne = attrs()["xmlns:nd"] == NEUTRODYNE_NS

        val rootDepth = parser.depth
        while (true) {
            event = nextEvent()
            when {
                event == XmlPullParser.END_DOCUMENT -> {
                    break
                }

                event == XmlPullParser.END_TAG && parser.depth == rootDepth -> {
                    break
                }

                event == XmlPullParser.START_TAG -> {
                    onStartTag()
                    when (parser.name.lowercase(Locale.ROOT)) {
                        // An outline outside <body> still classifies — stray structure is
                        // tolerated here the way AntennaPod scans xmlUrl at any depth.
                        OUTLINE -> {
                            readOutline(null, commented = false)
                        }

                        HEAD -> {
                            if (!headSeen) {
                                headSeen = true
                                readHead()
                            } else {
                                ignoreSubtree()
                            }
                        }

                        BODY -> {
                            if (!bodySeen) {
                                bodySeen = true
                                walkChildren(null, commented = false)
                            } else {
                                ignoreSubtree()
                            }
                        }

                        // An unexpected element under <opml> is a transparent container: its
                        // outlines still count and classify, in the current folder context.
                        else -> {
                            walkChildren(null, commented = false)
                        }
                    }
                }
            }
        }

        if (totalOutlines == 0) return OpmlReadResult.Failed(OpmlFailure.NO_OUTLINES)
        return OpmlReadResult.Ok(
            OpmlDocument(
                entries = entries,
                folders = folders,
                headTitle = headTitle,
                neutrodyne = neutrodyne,
                mode = ParseMode.STRICT,
                ignoredOutlines = ignoredOutlines,
                warnings = warnings(),
            ),
        )
    }

    /** nextToken, not next(): the folded stream never reports DOCDECL — check it if it appears. */
    private fun nextEvent(): Int {
        val event = parser.nextToken()
        if (event == XmlPullParser.DOCDECL &&
            parser.text.orEmpty().contains("<!entity", ignoreCase = true)
        ) {
            throw HostileEntityDeclaration()
        }
        return event
    }

    /** Depth and per-tag attribute bounds on every start tag, after the parser delivered it. */
    private fun onStartTag() {
        if (parser.depth > limits.maxDepth) throw DepthLimitExceeded(limits.maxDepth)
        // kxml2 grows its attribute array quadratically inside nextToken; checking the count here
        // still bounds the total flood to this one tag (the gap bound keeps it ≤ ~1 MiB), because
        // the exception is terminal for the read.
        if (parser.attributeCount > limits.maxTagAttributes) {
            throw OpmlCapExceeded(OpmlFailure.TAG_TOO_LONG, "tag over ${limits.maxTagAttributes} attributes")
        }
    }

    /** Lowercased attribute-name map for the current element (05 walk rules). */
    private fun attrs(): Map<String, String> {
        val map = HashMap<String, String>(parser.attributeCount * 2)
        for (i in 0 until parser.attributeCount) {
            val name = parser.getAttributeName(i) ?: continue
            map[name.lowercase(Locale.ROOT)] = parser.getAttributeValue(i).orEmpty()
        }
        return map
    }

    /** `<head>`: the first `<title>` child text; every other child's subtree is ignored. */
    private fun readHead() {
        val headDepth = parser.depth
        while (true) {
            when (nextEvent()) {
                XmlPullParser.END_DOCUMENT -> {
                    return
                }

                XmlPullParser.END_TAG -> {
                    if (parser.depth == headDepth) return
                }

                XmlPullParser.START_TAG -> {
                    onStartTag()
                    if (parser.name.equals("title", ignoreCase = true) && headTitle == null) {
                        headTitle = readElementText(parser.depth).trim()
                    } else {
                        ignoreSubtree()
                    }
                }
            }
        }
    }

    /** Text of the element whose start tag the parser is on, up to its end tag. */
    private fun readElementText(depth: Int): String {
        val sb = StringBuilder()
        while (true) {
            when (nextEvent()) {
                XmlPullParser.END_DOCUMENT -> {
                    return sb.toString()
                }

                XmlPullParser.END_TAG -> {
                    if (parser.depth == depth) return sb.toString()
                }

                XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                    sb.append(parser.text)
                }

                XmlPullParser.START_TAG -> {
                    onStartTag()
                    ignoreSubtree()
                }
            }
        }
    }

    /**
     * The children of the element the parser is on — `<outline>` elements classify, anything else
     * recurses transparently in the same folder context. Returns at the container's end tag.
     */
    private fun walkChildren(
        parentFolderId: Int?,
        commented: Boolean,
    ) {
        val containerDepth = parser.depth
        while (true) {
            when (nextEvent()) {
                XmlPullParser.END_DOCUMENT -> {
                    return
                }

                XmlPullParser.END_TAG -> {
                    if (parser.depth == containerDepth) return
                }

                XmlPullParser.START_TAG -> {
                    onStartTag()
                    if (parser.name.equals(OUTLINE, ignoreCase = true)) {
                        readOutline(parentFolderId, commented)
                    } else {
                        walkChildren(parentFolderId, commented)
                    }
                }
            }
        }
    }

    /**
     * The whole subtree of the element the parser is on produces no entries: every outline inside
     * counts toward the caps, lands in `ignoredOutlines`, and self-buckets for the warnings.
     */
    private fun ignoreSubtree() {
        val startDepth = parser.depth
        while (true) {
            when (nextEvent()) {
                XmlPullParser.END_DOCUMENT -> {
                    return
                }

                XmlPullParser.END_TAG -> {
                    if (parser.depth == startDepth) return
                }

                XmlPullParser.START_TAG -> {
                    onStartTag()
                    if (parser.name.equals(OUTLINE, ignoreCase = true)) {
                        countOutline()
                        countIgnored(classify(attrs()))
                    }
                }
            }
        }
    }

    /** One `<outline>` start tag under a walked container: feed, folder or ignored subtree. */
    private fun readOutline(
        parentFolderId: Int?,
        commented: Boolean,
    ) {
        countOutline()
        val attrs = attrs()
        val type = attrs["type"]?.lowercase(Locale.ROOT)
        val xmlUrl = attrs["xmlurl"]
        val url = attrs["url"]
        val isComment = attrs["iscomment"]?.trim().equals("true", ignoreCase = true)
        val effectiveCommented = commented || isComment
        val kind = classify(attrs, type, xmlUrl, url)

        if (attrs.values.any { it.length > limits.maxAttrChars }) {
            // An attribute over the cap drops the outline with a warning (05 caps). A folder's
            // children re-parent to the folder's own parent; anything else's subtree is ignored.
            droppedAttr++
            if (kind == Kind.FOLDER) {
                walkChildren(parentFolderId, effectiveCommented)
            } else {
                ignoreSubtree()
            }
            return
        }

        when (kind) {
            Kind.FEED -> {
                feedCount++
                if (feedCount > limits.maxFeeds) {
                    throw OpmlCapExceeded(OpmlFailure.TOO_MANY_FEEDS, "over ${limits.maxFeeds} feeds")
                }
                entries +=
                    OpmlEntry(
                        ordinal = entries.size,
                        url = xmlUrl ?: url.orEmpty(),
                        text = attrs["text"],
                        title = attrs["title"],
                        htmlUrl = attrs["htmlurl"],
                        type = type,
                        folderId = parentFolderId,
                        categories = OpmlCategories.decode(attrs["category"]),
                        preselect =
                            !effectiveCommented &&
                                attrs["subscribed"]?.trim() != "0" &&
                                type != "link",
                        ndSource = attrs["nd:source"],
                        ndYtVariants = attrs["nd:ytvariants"],
                    )
                // Some writers nest episode outlines inside feeds; children are ignored (05).
                ignoreSubtree()
            }

            Kind.IGNORED_EPISODES, Kind.IGNORED_LISTS -> {
                ignoredOutlines++
                if (kind == Kind.IGNORED_EPISODES) ignoredEpisodes++ else ignoredLists++
                ignoreSubtree()
            }

            Kind.FOLDER -> {
                val id = nextFolderId++
                folders +=
                    FolderRef(
                        id = id,
                        name = (attrs["text"] ?: attrs["title"])?.trim().orEmpty(),
                        parentId = parentFolderId,
                        colorArgb = parseColor(attrs["nd:groupcolor"]),
                        iconKey = attrs["nd:groupicon"],
                    )
                walkChildren(id, effectiveCommented)
            }
        }
    }

    private fun countOutline() {
        totalOutlines++
        if (totalOutlines > limits.maxOutlines) {
            throw OpmlCapExceeded(OpmlFailure.TOO_MANY_OUTLINES, "over ${limits.maxOutlines} outlines")
        }
    }

    private fun countIgnored(kind: Kind) {
        ignoredOutlines++
        if (kind == Kind.IGNORED_EPISODES) {
            ignoredEpisodes++
        } else if (kind == Kind.IGNORED_LISTS) {
            ignoredLists++
        }
    }

    /**
     * The 05 classification: an outline with `xmlUrl` is a feed regardless of `type`; `url` is the
     * gPodder fallback when `type` is `rss` or `link` and the URL does not end in `.opml`. Episode,
     * playlist, include and `.opml`-link outlines are ignored with their subtree; anything else is
     * a folder.
     */
    private fun classify(
        attrs: Map<String, String>,
        type: String? = attrs["type"]?.lowercase(Locale.ROOT),
        xmlUrl: String? = attrs["xmlurl"],
        url: String? = attrs["url"],
    ): Kind =
        when {
            xmlUrl != null -> Kind.FEED

            type == "podcast-episode" || type == "podcast-playlist" -> Kind.IGNORED_EPISODES

            type == "include" -> Kind.IGNORED_LISTS

            type == "link" && url != null && url.endsWith(OPML_SUFFIX, ignoreCase = true) -> Kind.IGNORED_LISTS

            url != null && (type == "rss" || type == "link") &&
                !url.endsWith(
                    OPML_SUFFIX,
                    ignoreCase = true,
                )
            -> Kind.FEED

            else -> Kind.FOLDER
        }

    private fun warnings(): List<OpmlWarning> =
        buildList {
            if (droppedAttr > 0) add(OpmlWarning(OpmlWarningCode.ATTRIBUTE_TOO_LONG, droppedAttr))
            if (ignoredEpisodes > 0) add(OpmlWarning(OpmlWarningCode.IGNORED_OUTLINES, ignoredEpisodes))
            if (ignoredLists > 0) add(OpmlWarning(OpmlWarningCode.LINKED_LISTS, ignoredLists))
        }

    private fun parseColor(raw: String?): Int? {
        if (raw == null) return null
        val v = raw.trim()
        if (!v.startsWith('#')) return null
        val hex = v.substring(1)
        // Length first, then ASCII hex only: `toLong(16)` would throw on "" or an over-long
        // value and accept a sign or a non-ASCII digit.
        if (hex.length != RGB_HEX_DIGITS && hex.length != ARGB_HEX_DIGITS) return null
        if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val value = hex.toLong(16)
        return if (hex.length == RGB_HEX_DIGITS) (0xFF000000L or value).toInt() else value.toInt()
    }

    private enum class Kind {
        FEED,
        IGNORED_EPISODES,
        IGNORED_LISTS,
        FOLDER,
    }

    private companion object {
        const val ROOT_ELEMENT = "opml"
        const val HEAD = "head"
        const val BODY = "body"
        const val OUTLINE = "outline"
        const val NEUTRODYNE_NS = "urn:neutrodyne:opml:1"
        const val OPML_SUFFIX = ".opml"
        const val RGB_HEX_DIGITS = 6 // `#RRGGBB`
        const val ARGB_HEX_DIGITS = 8 // `#AARRGGBB`
    }
}
