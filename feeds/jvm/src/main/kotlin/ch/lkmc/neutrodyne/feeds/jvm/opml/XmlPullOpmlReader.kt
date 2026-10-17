// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.opml

import ch.lkmc.neutrodyne.feeds.jvm.parse.DepthLimitExceeded
import ch.lkmc.neutrodyne.feeds.jvm.parse.HostileEntityDeclaration
import ch.lkmc.neutrodyne.feeds.jvm.parse.HtmlEntities
import ch.lkmc.neutrodyne.feeds.jvm.parse.PullParserFactory
import ch.lkmc.neutrodyne.feeds.opml.FolderRef
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
import java.nio.charset.Charset
import java.util.Locale

/**
 * The OPML read cascade (05 Sniff and parse): **strict**, then **relaxed**, then **salvage**, each
 * re-opening [open]. Every pull pass is one streaming walk over `XmlPullParser` events that maps
 * outlines to feeds and folders — no document is materialised — with [MarkupGapGuard] wrapping the
 * stream so the markup gap, the prolog `<!ENTITY` scan and the `<?xml` attribute bound hold before
 * parser buffers grow.
 *
 * Only `MALFORMED` (the parser failed on the document's syntax) falls through to the next pass; cap
 * failures and `NO_OUTLINES` are terminal in every pass. The relaxed leg enables kxml2's
 * `FEATURE_RELAXED` and predefines the HTML 4 named entities the feed parser already maps, so bare
 * ampersands, unquoted or single-quoted attributes, `&nbsp;` and friends, unclosed outlines at EOF
 * and damaged nesting parse. Parser setup mirrors `XmlPullFeedParser` minus namespaces (off —
 * prefixed attributes keep raw names such as `nd:source`). `setInput(stream, null)` lets the
 * parser detect BOMs and the declared encoding itself; a `Reader` would ignore
 * `encoding="ISO-8859-1"`.
 *
 * One addition the document model needs: a relaxed or salvage result decoded as UTF-8 whose fields
 * carry more than 0.5 % U+FFFD replacement characters re-parses once as windows-1252 — the same
 * heuristic 03's feed parser applies for misdeclared encodings — keeping whichever pass produced
 * fewer replacement characters. Files detected as UTF-16/32 are never re-decoded: a single-byte
 * re-read of a 16-bit stream is NUL-padded garbage, not mojibake repair.
 */
public class XmlPullOpmlReader(
    private val parsers: PullParserFactory,
) : OpmlReader {
    override fun read(
        open: () -> Source,
        limits: OpmlLimits,
    ): OpmlReadResult {
        val strict = pullPass(open, limits, ParseMode.STRICT).result
        if (strict !is OpmlReadResult.Failed || strict.failure != OpmlFailure.MALFORMED) {
            return strict
        }

        val relaxed = relaxedPass(open, limits)
        if (relaxed !is OpmlReadResult.Failed || relaxed.failure != OpmlFailure.MALFORMED) {
            return relaxed
        }

        return SalvageOpmlScanner(limits).scan(open)
    }

    /**
     * The relaxed pass plus the encoding re-parse: when the pass decoded a UTF-8-family charset and
     * the produced text is mojibake, one extra attempt decodes the raw bytes as windows-1252
     * (`setInput` with an explicit encoding name — the declared `encoding="UTF-8"` inside the file
     * is then inert). The attempt with fewer replacement characters wins.
     */
    private fun relaxedPass(
        open: () -> Source,
        limits: OpmlLimits,
    ): OpmlReadResult {
        val first = pullPass(open, limits, ParseMode.RELAXED)
        val firstDoc = first.result as? OpmlReadResult.Ok ?: return first.result
        if (!first.meter.mojibake() || !redecodable(first.detected)) return firstDoc

        val second = pullPass(open, limits, ParseMode.RELAXED, encodingOverride = REPARSE_CHARSET)
        val secondDoc = second.result as? OpmlReadResult.Ok ?: return firstDoc
        if (second.meter.replacementChars >= first.meter.replacementChars) return firstDoc
        return secondDoc
    }

    /**
     * One pull pass over [open]'s stream. [encodingOverride] pins the decode charset (the mojibake
     * re-parse leg); `null` leaves detection to `setInput`'s byte sniffing.
     */
    private fun pullPass(
        open: () -> Source,
        limits: OpmlLimits,
        mode: ParseMode,
        encodingOverride: Charset? = null,
    ): PullOutcome {
        val parser = parsers.create()
        // I/O failures from open() or the stream are not malformed input: they propagate per the
        // OpmlReader contract, which deliberately has no OpmlFailure reason for them.
        return open().buffer().use { source ->
            val detected = sniffedCharset(source)
            try {
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                if (mode == ParseMode.RELAXED) {
                    runCatching { parser.setFeature(RELAXED_FEATURE, true) }
                }
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
                if (encodingOverride != null) {
                    parser.setInput(stream, encodingOverride.name())
                } else {
                    parser.setInput(stream, null)
                }
                if (mode == ParseMode.RELAXED) {
                    // After setInput, as kxml2 requires: the HTML 4 named entities resolve instead
                    // of dropping silently (strict) or passing through raw (salvage).
                    for ((name, value) in HtmlEntities.ALL) {
                        parser.defineEntityReplacementText(name, value)
                    }
                }
                val session = ReadSession(parser, limits, mode)
                PullOutcome(session.run(), session.meter, detected)
            } catch (e: HostileEntityDeclaration) {
                PullOutcome(OpmlReadResult.Failed(OpmlFailure.ENTITY_DECLARED), detected = detected)
            } catch (e: DepthLimitExceeded) {
                PullOutcome(OpmlReadResult.Failed(OpmlFailure.TOO_DEEP), detected = detected)
            } catch (e: MarkupBoundExceeded) {
                PullOutcome(OpmlReadResult.Failed(OpmlFailure.TAG_TOO_LONG), detected = detected)
            } catch (e: OpmlCapExceeded) {
                PullOutcome(OpmlReadResult.Failed(e.failure), detected = detected)
            } catch (e: XmlPullParserException) {
                // The cascade fall-through boundary.
                PullOutcome(OpmlReadResult.Failed(OpmlFailure.MALFORMED), detected = detected)
            } catch (e: StackOverflowError) {
                PullOutcome(OpmlReadResult.Failed(OpmlFailure.TOO_DEEP), detected = detected)
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                PullOutcome(OpmlReadResult.Failed(OpmlFailure.MALFORMED), detected = detected)
            }
        }
    }

    /** A pass's result plus what the encoding re-parse needs: field-text stats and the charset. */
    private class PullOutcome(
        val result: OpmlReadResult,
        val meter: ReplacementMeter = ReplacementMeter(),
        val detected: Charset,
    )

    public companion object {
        /** The desktop binding: XmlPull discovery (kxml2 at run time, 05 OPML import). */
        public fun discovered(): OpmlReader = XmlPullOpmlReader(PullParserFactory.Discovered)

        private const val RELAXED_FEATURE = "http://xmlpull.org/v1/doc/features.html#relaxed"
    }
}

/** Thrown when a per-outline cap trips inside the document walk; carries the failure it maps to. */
internal class OpmlCapExceeded(
    val failure: OpmlFailure,
    message: String,
) : RuntimeException(message)

/**
 * All mutable state of one read: the outline walk, the entry and folder lists, and the
 * warning/ignored bookkeeping. A fresh session per pass keeps concurrent reads on one
 * `XmlPullOpmlReader` independent.
 */
private class ReadSession(
    private val parser: XmlPullParser,
    private val limits: OpmlLimits,
    private val mode: ParseMode,
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

    /** Field-text volume and U+FFFD counts for the encoding re-parse heuristic. */
    val meter = ReplacementMeter()

    fun run(): OpmlReadResult {
        var event = parser.eventType
        while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT) {
            event = nextEvent()
        }
        if (event == XmlPullParser.END_DOCUMENT || !parser.name.equals(ROOT_ELEMENT, ignoreCase = true)) {
            return OpmlReadResult.Failed(OpmlFailure.NO_OUTLINES)
        }
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
                mode = mode,
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

    /** Lowercased attribute-name map for the current element; counts what the fields receive. */
    private fun attrs(): Map<String, String> {
        val map = HashMap<String, String>(parser.attributeCount * 2)
        for (i in 0 until parser.attributeCount) {
            val name = parser.getAttributeName(i) ?: continue
            val value = parser.getAttributeValue(i).orEmpty()
            meter.add(value)
            map[name.lowercase(Locale.ROOT)] = value
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
                        headTitle = readElementText(parser.depth)
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
                    val text = parser.text
                    meter.add(text)
                    sb.append(text)
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
                        countIgnored(classifyOutline(attrs()))
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
        val isComment = attrs["iscomment"]?.trim().equals("true", ignoreCase = true)
        val effectiveCommented = commented || isComment
        val kind = classifyOutline(attrs)

        if (hasOversizedAttribute(attrs, limits)) {
            // An attribute over the cap drops the outline with a warning (05 caps). A folder's
            // children re-parent to the folder's own parent; anything else's subtree is ignored.
            droppedAttr++
            if (kind == OutlineKind.FOLDER) {
                walkChildren(parentFolderId, effectiveCommented)
            } else {
                ignoreSubtree()
            }
            return
        }

        when (kind) {
            OutlineKind.FEED -> {
                feedCount++
                if (feedCount > limits.maxFeeds) {
                    throw OpmlCapExceeded(OpmlFailure.TOO_MANY_FEEDS, "over ${limits.maxFeeds} feeds")
                }
                entries += outlineEntry(entries.size, attrs, parentFolderId, effectiveCommented)
                // Some writers nest episode outlines inside feeds; children are ignored (05).
                ignoreSubtree()
            }

            OutlineKind.IGNORED_EPISODES, OutlineKind.IGNORED_LISTS -> {
                ignoredOutlines++
                if (kind == OutlineKind.IGNORED_EPISODES) ignoredEpisodes++ else ignoredLists++
                ignoreSubtree()
            }

            OutlineKind.FOLDER -> {
                val id = nextFolderId++
                folders +=
                    FolderRef(
                        id = id,
                        name = (attrs["text"] ?: attrs["title"])?.trim().orEmpty(),
                        parentId = parentFolderId,
                        colorArgb = parseGroupColor(attrs["nd:groupcolor"]),
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

    private fun countIgnored(kind: OutlineKind) {
        ignoredOutlines++
        if (kind == OutlineKind.IGNORED_EPISODES) {
            ignoredEpisodes++
        } else if (kind == OutlineKind.IGNORED_LISTS) {
            ignoredLists++
        }
    }

    private fun warnings(): List<OpmlWarning> =
        buildList {
            if (droppedAttr > 0) add(OpmlWarning(OpmlWarningCode.ATTRIBUTE_TOO_LONG, droppedAttr))
            if (ignoredEpisodes > 0) add(OpmlWarning(OpmlWarningCode.IGNORED_OUTLINES, ignoredEpisodes))
            if (ignoredLists > 0) add(OpmlWarning(OpmlWarningCode.LINKED_LISTS, ignoredLists))
        }

    private companion object {
        const val ROOT_ELEMENT = "opml"
        const val HEAD = "head"
        const val BODY = "body"
        const val OUTLINE = "outline"
        const val NEUTRODYNE_NS = "urn:neutrodyne:opml:1"
    }
}
