// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.parse

import ch.lkmc.neutrodyne.feeds.identity.PodcastGuid
import ch.lkmc.neutrodyne.feeds.model.AlternateEnclosure
import ch.lkmc.neutrodyne.feeds.model.AlternateEnclosureIntegrity
import ch.lkmc.neutrodyne.feeds.model.AlternateEnclosureSource
import ch.lkmc.neutrodyne.feeds.model.ArtworkCandidate
import ch.lkmc.neutrodyne.feeds.model.ArtworkSource
import ch.lkmc.neutrodyne.feeds.model.Enclosure
import ch.lkmc.neutrodyne.feeds.model.FeedFormat
import ch.lkmc.neutrodyne.feeds.model.Funding
import ch.lkmc.neutrodyne.feeds.model.InlineChapter
import ch.lkmc.neutrodyne.feeds.model.Paging
import ch.lkmc.neutrodyne.feeds.model.ParseWarning
import ch.lkmc.neutrodyne.feeds.model.ParsedEpisode
import ch.lkmc.neutrodyne.feeds.model.ParsedFeed
import ch.lkmc.neutrodyne.feeds.model.Person
import ch.lkmc.neutrodyne.feeds.model.TranscriptRef
import ch.lkmc.neutrodyne.feeds.model.WarningCode
import ch.lkmc.neutrodyne.feeds.parse.Durations
import ch.lkmc.neutrodyne.feeds.parse.EnclosureTypes
import ch.lkmc.neutrodyne.feeds.parse.FeedDates
import ch.lkmc.neutrodyne.feeds.parse.FeedParser
import ch.lkmc.neutrodyne.feeds.parse.ParseFailure
import ch.lkmc.neutrodyne.feeds.parse.ParseLimits
import ch.lkmc.neutrodyne.feeds.parse.ParseResult
import okio.buffer
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.util.Locale

/**
 * Thrown when element nesting exceeds the limit; becomes `Failed(TOO_DEEP)` (03 Limits and version
 * policy). Also raised from [InnerXml], which walks nested markup below the element it is given.
 */
internal class DepthLimitExceeded(
    val limit: Int,
) : RuntimeException("element nesting over $limit")

/**
 * The streaming feed parser (03 Parser): one hand-written walk over `XmlPullParser` mapping RSS 2.0,
 * Atom, RSS 1.0/RDF, iTunes, Podcasting 2.0, Media RSS and Podlove Simple Chapters onto one normalised
 * model. Runs with either platform parser through [PullParserFactory]; never throws for malformed
 * input. All mutable state lives in a fresh [ParseSession] per pass, so the documented two concurrent
 * parses on one instance cannot interfere (03 Threading model).
 */
public class XmlPullFeedParser(
    private val factory: PullParserFactory,
    private val limits: ParseLimits = ParseLimits(),
) : FeedParser {
    /**
     * Parses one document (03 Parser). Reads [open] once and requests the first byte beyond
     * [ParseLimits.maxDocumentBytes] before materialization. Buffered reads can read ahead one
     * segment; oversized input returns `Failed(HOSTILE)`. The charset re-parse (step 5) compares
     * two passes over the same bytes. [httpCharset] is consulted only by that heuristic;
     * [baseUrl] resolves relative URLs, and `xml:base` in the document wins when present.
     */
    override fun parse(
        open: () -> okio.Source,
        httpCharset: String?,
        baseUrl: String,
    ): ParseResult {
        // Check the byte bound before materialization or decoding. Okio can read ahead one
        // segment while requesting the first byte over the bound.
        val bytes =
            try {
                open().buffer().use { source ->
                    if (source.request(limits.maxDocumentBytes + 1L)) {
                        return ParseResult.Failed(
                            ParseFailure.HOSTILE,
                            "document over ${limits.maxDocumentBytes} bytes",
                        )
                    }
                    source.readByteArray()
                }
            } catch (e: Exception) {
                return ParseResult.Failed(ParseFailure.MALFORMED, "unreadable source: ${e.javaClass.simpleName}")
            }

        // Step 3: first pass with encoding sniffing (never a Reader, never the HTTP charset). The
        // prolog and start-tag guards run inside runPass so the charset re-parse (step 5) is
        // checked on the stream exactly as that pass decodes it too. Whitespace before the
        // declaration is tolerated by kxml2's relaxed mode but refused by AOSP's KXmlParser, so
        // both passes see it stripped (corpus leg b); a BOM is never ASCII whitespace, so
        // encoding detection is untouched.
        val content = bytes.withoutLeadingWhitespace()
        val first = ParseSession(factory, limits).runPass(content, charsetOverride = null, baseUrl)
        if (first is ParseResult) return first

        val stats = first as ParseSession.Pass

        // Step 5: re-parse heuristic. More than 0.5 % replacement characters triggers one second pass
        // with the HTTP charset when it differs from the detected one, else windows-1252; the result
        // with fewer replacement characters wins.
        val replacementRatio =
            if (stats.textChars == 0L) 0.0 else stats.replacementChars.toDouble() / stats.textChars
        if (replacementRatio > REPLACEMENT_RATIO_THRESHOLD) {
            val detected = stats.detectedEncoding
            val secondCharset =
                httpCharset?.trim()?.takeIf { it.isNotEmpty() && !sameCharset(it, detected) }
                    ?: WINDOWS_1252.takeIf { !sameCharset(it, detected) }
            if (secondCharset != null) {
                val second = ParseSession(factory, limits).runPass(content, secondCharset, baseUrl)
                if (second is ParseSession.Pass && second.replacementChars < stats.replacementChars) {
                    val reparseWarning =
                        ParseWarning(WarningCode.CHARSET_REPARSED, detail = "re-parsed as $secondCharset")
                    return ParseResult.Ok(second.feed.copy(warnings = second.feed.warnings + reparseWarning))
                }
            }
        }

        return ParseResult.Ok(stats.feed)
    }

    public companion object {
        /** The desktop binding: XmlPull discovery (kxml2 at run time, 03 Parser). */
        public fun discovered(limits: ParseLimits = ParseLimits()): FeedParser =
            XmlPullFeedParser(PullParserFactory.Discovered, limits)

        private const val REPLACEMENT_RATIO_THRESHOLD = 0.005
        private const val WINDOWS_1252 = "windows-1252"
    }
}

/**
 * All mutable state of one parse pass: the document walk, the channel and item builders, and the
 * per-document namespace binding, warning and encoding bookkeeping. [XmlPullFeedParser.parse]
 * creates a fresh session for every pass — including the charset re-parse — so the documented two
 * concurrent parses on one parser instance share nothing (03 Threading model).
 */
private class ParseSession(
    private val factory: PullParserFactory,
    private val limits: ParseLimits,
) {
    /** One pass over the document: the feed, the charset counters and the detected encoding. */
    class Pass(
        val feed: ParsedFeed,
        val textChars: Long,
        val replacementChars: Long,
        val detectedEncoding: String?,
    )

    private class Counters {
        var textChars = 0L
        var replacementChars = 0L
    }

    private val counters = Counters()
    private var warnings = mutableListOf<ParseWarning>()
    private var detectedEncoding: String? = null
    private var itemsTruncated = false

    /**
     * The root element's namespace when the document parsed as Atom (03 step 7: `feed` with the Atom
     * namespace or none). Elements in that same namespace then carry the Atom key — a namespace-free
     * Atom document's `<entry>`/`<title>` otherwise resolve as RSS.
     */
    private var atomNamespace: String? = null

    private val undeclaredPrefixesSeen = mutableSetOf<String>()

    /**
     * The bytes and encoding to hand `setInput`: when the decoded document carries a numeric
     * reference past U+FFFF — which kxml2 truncates to a single `char` — the corrected text goes in
     * re-encoded as UTF-8 so the parser meets the literal character instead. Otherwise the original
     * bytes go in untouched, with the pass' own encoding (null = let the parser sniff).
     */
    private fun supplementarySafe(
        bytes: ByteArray,
        charset: Charset?,
        text: String,
    ): Pair<ByteArray, String?> {
        val rewritten = SupplementaryRefs.rewrite(text) ?: return bytes to charset?.name()
        return rewritten.toByteArray(Charsets.UTF_8) to Charsets.UTF_8.name()
    }

    /** One parse pass: `ParseResult` when it failed structurally, otherwise the feed with its stats. */
    fun runPass(
        bytes: ByteArray,
        charsetOverride: String?,
        baseUrl: String,
    ): Any {
        val parser = factory.create()
        return try {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            runCatching { parser.setFeature(RELAXED_FEATURE, true) }

            // Android's platform parser processes DOCTYPEs by default and then refuses
            // defineEntityReplacementText; kxml2 does not support the feature, so it is already off
            // and the getFeature call answers false. Disable only when it is actually on: a failed
            // disable must not be swallowed (03 step 1).
            if (runCatching { parser.getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL) }
                    .getOrDefault(false)
            ) {
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            }

            if (charsetOverride == null) {
                // The guards judge the document the pull parser will see: kxml2 keeps an ASCII/UTF-8
                // declaration's raw bytes and decodes only the remainder with the declared encoding.
                // A declaration its own setInput cannot decode (no `>` in reach, a bad encoding
                // name) fails the same way here.
                (EncodingSniff.view(bytes) as? EncodingSniff.View.Rejected)?.let {
                    return ParseResult.Failed(ParseFailure.MALFORMED, it.detail)
                }

                // Step 1: prolog guard, before any parser sees the document.
                if (PrologGuard.isHostile(bytes, limits.prologScanBytes)) {
                    return ParseResult.Failed(ParseFailure.HOSTILE, "ENTITY declaration in the prolog")
                }

                // Bound start-tag work before the pull parser sees any tag (03 Limits and version
                // policy): kxml2 grows its attribute and namespace arrays quadratically inside
                // next(), so checking attributeCount after the event returns is already too late.
                // A document its own setInput cannot decode yields no view for the check.
                val text =
                    EncodingSniff.decode(bytes)
                        ?: return ParseResult.Failed(
                            ParseFailure.MALFORMED,
                            "encoding declaration the parser cannot decode",
                        )
                try {
                    TagBounds.checkText(text, limits)
                } catch (e: XmlPullParserException) {
                    return ParseResult.Failed(ParseFailure.MALFORMED, e.message.orEmpty())
                }

                val (input, encoding) = supplementarySafe(bytes, charset = null, text)
                parser.setInput(ByteArrayInputStream(input), encoding)
                // kxml2 reports null for inputEncoding right after setInput; the document encoding
                // is decided only once the declaration is processed, so it is sniffed here with the
                // same precedence (BOM, XML declaration, UTF-8 default).
                detectedEncoding = EncodingSniff.sniff(bytes).name()
            } else {
                val charset =
                    runCatching { Charset.forName(charsetOverride) }.getOrNull()
                        ?: return ParseResult.Failed(ParseFailure.MALFORMED, "unknown charset $charsetOverride")
                // The override decoding can expose markup the sniffed view did not contain, so the
                // guards run on the stream exactly as this pass decodes it: the prolog window is
                // the same first bytes, decoded with the override.
                val prolog = String(bytes, 0, minOf(bytes.size, limits.prologScanBytes), charset)
                if (PrologGuard.isHostileText(prolog)) {
                    return ParseResult.Failed(ParseFailure.HOSTILE, "ENTITY declaration in the prolog")
                }
                val text = String(bytes, charset)
                try {
                    TagBounds.checkText(text, limits)
                } catch (e: XmlPullParserException) {
                    return ParseResult.Failed(ParseFailure.MALFORMED, e.message.orEmpty())
                }

                val (input, encoding) = supplementarySafe(bytes, charset, text)
                // setInput(InputStream, encoding), never a Reader: the Reader overloads differ between
                // kxml2's bundled xmlpull API and android.jar, this one exists on both.
                parser.setInput(ByteArrayInputStream(input), encoding)
                detectedEncoding = charset.name()
            }

            // After setInput (kxml2 requires that order): predefine all HTML 4 named entities.
            for ((name, value) in HtmlEntities.ALL) {
                parser.defineEntityReplacementText(name, value)
            }

            val feed =
                parseDocument(parser, baseUrl)
                    ?: return ParseResult.Failed(ParseFailure.NOT_A_FEED, "root element was not rss, feed or RDF")
            Pass(feed, counters.textChars, counters.replacementChars, detectedEncoding)
        } catch (e: DepthLimitExceeded) {
            ParseResult.Failed(ParseFailure.TOO_DEEP, "element nesting over ${limits.maxDepth}")
        } catch (e: XmlPullParserException) {
            ParseResult.Failed(ParseFailure.MALFORMED, "line ${e.lineNumber}: ${e.message.orEmpty()}")
        } catch (e: StackOverflowError) {
            ParseResult.Failed(ParseFailure.TOO_DEEP, "nesting exceeded the parser stack")
        } catch (e: Exception) {
            ParseResult.Failed(ParseFailure.MALFORMED, e.toString())
        }
    }

    // ---------------------------------------------------------------------------
    // Document walk and root dispatch (03 step 7)
    // ---------------------------------------------------------------------------

    private fun parseDocument(
        parser: XmlPullParser,
        baseUrl: String,
    ): ParsedFeed? {
        var event = parser.eventType
        while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT) {
            event = nextEvent(parser)
        }
        if (event == XmlPullParser.END_DOCUMENT) return null

        val rootName = parser.name
        val rootKey = Namespaces.elementKey(parser)
        val base = baseOf(parser, baseUrl)

        return when {
            rootName.equals("rss", ignoreCase = true) -> {
                parseRss(parser, base)
            }

            rootName.equals("feed", ignoreCase = true) &&
                (rootKey == null || rootKey == Namespaces.Key.ATOM || rootKey == Namespaces.Key.RSS) -> {
                // A namespace-free (or unrecognized-namespace) feed still reads as Atom; the RSS key
                // only reports the empty namespace here. Element keys inside resolve against the
                // document's own namespace (see elementKey).
                atomNamespace = parser.namespace.orEmpty()
                parseAtom(parser, base)
            }

            rootName.equals("RDF", ignoreCase = true) || rootKey == Namespaces.Key.RDF -> {
                parseRdf(parser, base)
            }

            else -> {
                null
            }
        }
    }

    private fun parseRss(
        parser: XmlPullParser,
        base: String,
    ): ParsedFeed? {
        val channel = ChannelBuilder(base)
        val items = mutableListOf<ParsedEpisode>()

        // Descend to <channel>; an <rss> root without one is not a feed.
        var event = nextEvent(parser)
        while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT) event = nextEvent(parser)
        if (event == XmlPullParser.END_DOCUMENT || !parser.name.equals("channel", ignoreCase = true)) return null
        channel.base = baseOf(parser, base)

        val channelDepth = parser.depth
        event = nextEvent(parser)
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && parser.depth == channelDepth) break
            if (event == XmlPullParser.START_TAG) {
                if (parser.depth > limits.maxDepth) throw DepthLimitExceeded(limits.maxDepth)
                if (parser.name.equals("item", ignoreCase = true)) {
                    readItem(parser, channel, items, channel.base)
                } else {
                    parseChannelElement(parser, channel)
                }
            }
            event = nextEvent(parser)
        }
        return channel.build(FeedFormat.RSS2, items)
    }

    private fun parseRdf(
        parser: XmlPullParser,
        base: String,
    ): ParsedFeed? {
        val channel = ChannelBuilder(base)
        val items = mutableListOf<ParsedEpisode>()

        val rootDepth = parser.depth
        var event = nextEvent(parser)
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && parser.depth == rootDepth) break
            if (event == XmlPullParser.START_TAG) {
                if (parser.depth > limits.maxDepth) throw DepthLimitExceeded(limits.maxDepth)
                when {
                    // The RDF channel is a sibling of the items: loop its children like parseRss does.
                    parser.name.equals("channel", ignoreCase = true) -> {
                        val channelDepth = parser.depth
                        channel.base = baseOf(parser, base)
                        var channelEvent = nextEvent(parser)
                        while (channelEvent != XmlPullParser.END_DOCUMENT) {
                            if (channelEvent == XmlPullParser.END_TAG && parser.depth == channelDepth) break
                            if (channelEvent == XmlPullParser.START_TAG) {
                                if (parser.depth > limits.maxDepth) throw DepthLimitExceeded(limits.maxDepth)
                                parseChannelElement(parser, channel)
                            }
                            channelEvent = nextEvent(parser)
                        }
                    }

                    // RDF items are siblings of the channel, not children: they inherit the root's
                    // effective base, while the channel-local base applies only inside the channel.
                    parser.name.equals("item", ignoreCase = true) -> {
                        readItem(parser, channel, items, base)
                    }

                    else -> {
                        skipElement(parser)
                    }
                }
            }
            event = nextEvent(parser)
        }
        return channel.build(FeedFormat.RDF, items)
    }

    private fun parseAtom(
        parser: XmlPullParser,
        base: String,
    ): ParsedFeed? {
        val channel = ChannelBuilder(base)
        val items = mutableListOf<ParsedEpisode>()

        val feedDepth = parser.depth
        var event = nextEvent(parser)
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && parser.depth == feedDepth) break
            if (event == XmlPullParser.START_TAG) {
                if (parser.depth > limits.maxDepth) throw DepthLimitExceeded(limits.maxDepth)
                if (parser.name == "entry") {
                    readAtomEntry(parser, channel, items)
                } else {
                    parseChannelElement(parser, channel)
                }
            }
            event = nextEvent(parser)
        }
        return channel.build(FeedFormat.ATOM, items)
    }

    /**
     * Reads one item-ish element (RSS item or RDF item); assumes the parser sits on its START_TAG.
     * [parentBase] is the base the item inherits: the channel's own `xml:base` for RSS items (they
     * are children of `channel`), the document root's for RDF items (they are its siblings).
     */
    private fun readItem(
        parser: XmlPullParser,
        channel: ChannelBuilder,
        items: MutableList<ParsedEpisode>,
        parentBase: String,
    ) {
        if (items.size >= limits.maxItems) {
            stopRecordingItems()
            skipElement(parser)
            return
        }
        val item = ItemBuilder(items.size, baseOf(parser, parentBase))
        val depth = parser.depth
        var event = nextEvent(parser)
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && parser.depth == depth) break
            if (event == XmlPullParser.START_TAG) {
                if (parser.depth > limits.maxDepth) throw DepthLimitExceeded(limits.maxDepth)
                parseItemElement(parser, item)
            }
            event = nextEvent(parser)
        }
        items.add(buildEpisode(item))
    }

    /** Reads one Atom entry; assumes the parser sits on its START_TAG. */
    private fun readAtomEntry(
        parser: XmlPullParser,
        channel: ChannelBuilder,
        items: MutableList<ParsedEpisode>,
    ) {
        if (items.size >= limits.maxItems) {
            stopRecordingItems()
            skipElement(parser)
            return
        }
        val item = ItemBuilder(items.size, baseOf(parser, channel.base))
        val depth = parser.depth
        var event = nextEvent(parser)
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && parser.depth == depth) break
            if (event == XmlPullParser.START_TAG) {
                if (parser.depth > limits.maxDepth) throw DepthLimitExceeded(limits.maxDepth)
                parseAtomEntryElement(parser, item)
            }
            event = nextEvent(parser)
        }
        items.add(buildEpisode(item))
    }

    /** Warns once when the item cap stops recording (03 Limits and version policy). */
    private fun stopRecordingItems() {
        if (itemsTruncated) return
        itemsTruncated = true
        warnings.add(ParseWarning(WarningCode.ITEMS_TRUNCATED, detail = "stopped after ${limits.maxItems} items"))
    }

    /**
     * The registry key of the element the parser sits on; inside a namespace-free (or
     * unrecognized-namespace) Atom document, unprefixed elements in the document's own namespace read
     * as ATOM. Elements matched through the prefix fallback keep their extension key.
     */
    private fun elementKey(parser: XmlPullParser): Namespaces.Key? {
        val key = Namespaces.elementKey(parser)
        if (atomNamespace == null || key == Namespaces.Key.ATOM) return key
        if (!parser.prefix.isNullOrEmpty() || parser.namespace.orEmpty() != atomNamespace) return key
        return if (key == null || key == Namespaces.Key.RSS) Namespaces.Key.ATOM else key
    }

    /**
     * `xml:base` of the element the parser sits on, resolved against [current]; overlong or
     * unresolvable values fall back to [current] (resolveUrl already bounds the raw value).
     */
    private fun baseOf(
        parser: XmlPullParser,
        current: String,
    ): String =
        xmlBase(parser)
            ?.let { resolveUrl(it, current) }
            ?.takeIf { it.length <= limits.maxUrlChars }
            ?: current

    // ---------------------------------------------------------------------------
    // Channel elements
    // ---------------------------------------------------------------------------

    /** Mutable channel state, applied to the feed at the end of the walk. */
    private inner class ChannelBuilder(
        var base: String,
    ) {
        var title: String? = null
        var itunesTitle: String? = null
        var atomTitle: String? = null
        var itunesAuthor: String? = null
        var dcCreator: String? = null
        var atomAuthor: String? = null
        var authorUri: String? = null
        var ownerName: String? = null
        var managingEditor: String? = null
        var description: String? = null
        var itunesSummary: String? = null
        var contentEncoded: String? = null
        var atomSubtitle: String? = null
        var googlePlayDescription: String? = null
        var link: String? = null
        var atomAlternateLink: String? = null
        var language: String? = null
        val categories = mutableListOf<List<String>>()
        var explicit: Boolean? = null
        var showType: String? = null
        var itunesComplete = false
        var updateFrequencyComplete = false
        var newFeedUrl: String? = null
        var podcastGuid: String? = null
        var locked: Boolean? = null
        var medium: String? = null
        val artwork = mutableListOf<ArtworkCandidate>()
        var bannerUrl: String? = null
        val funding = mutableListOf<Funding>()
        val persons = mutableListOf<Person>()
        var updateFrequencyRrule: String? = null
        var ttlMinutes: Int? = null
        var syPeriod: String? = null
        var syFrequency: Int? = null
        var pagingNext: String? = null
        var pagingFirst: String? = null
        var pagingPrevArchive: String? = null
        var fhComplete = false
        var fhArchive = false
        var hubUrl: String? = null
        var usesPodping = false
        var ytChannelId: String? = null
        var ytPlaylistId: String? = null

        fun build(
            format: FeedFormat,
            items: List<ParsedEpisode>,
        ): ParsedFeed {
            // RSS ttl, else the sy:updatePeriod table ÷ sy:updateFrequency (03 Field mapping).
            val ttl =
                ttlMinutes ?: syPeriod?.let { period ->
                    val minutes = UPDATE_PERIOD_MINUTES[period.trim().lowercase()]
                    val frequency = syFrequency ?: SY_FREQUENCY_DEFAULT
                    if (minutes != null && frequency > 0) minutes / frequency else null
                }

            return ParsedFeed(
                format = format,
                title = firstNonBlank(title, itunesTitle, atomTitle),
                author = firstNonBlank(itunesAuthor, dcCreator, atomAuthor, ownerName, managingEditor),
                descriptionHtml =
                    firstNonBlank(description, itunesSummary, contentEncoded, atomSubtitle, googlePlayDescription),
                link = firstNonBlank(link, atomAlternateLink),
                language = language,
                categories = categories.toList(),
                explicit = explicit,
                showType = showType,
                complete = itunesComplete || updateFrequencyComplete,
                newFeedUrl = newFeedUrl,
                podcastGuid = podcastGuid,
                locked = locked,
                medium = medium,
                artwork = inPrecedenceOrder(artwork),
                bannerUrl = bannerUrl,
                funding = funding.toList(),
                persons = persons.toList(),
                updateFrequencyRrule = updateFrequencyRrule,
                ttlMinutes = ttl,
                paging = Paging(pagingNext, pagingPrevArchive, pagingFirst, fhComplete, fhArchive),
                hubUrl = hubUrl,
                usesPodping = usesPodping,
                ytChannelId = ytChannelId,
                ytPlaylistId = ytPlaylistId,
                authorUri = authorUri,
                items = items.toList(),
                warnings = warnings.toList(),
            )
        }
    }

    /** One channel-level element; consumes it through its END_TAG. */
    private fun parseChannelElement(
        parser: XmlPullParser,
        channel: ChannelBuilder,
    ) {
        trackPrefixFallback(parser)
        val key = elementKey(parser)
        val name = parser.name

        when {
            name.equals("title", true) && key in CHANNEL_TITLE_KEYS -> {
                channel.title = plainText(parser)
            }

            name.equals("description", true) && key in CHANNEL_DESCRIPTION_KEYS -> {
                channel.description = htmlText(parser)
            }

            name.equals("link", true) && key in CHANNEL_LINK_KEYS -> {
                // The element's own xml:base must be read while the parser still sits on its tag.
                val elementBase = baseOf(parser, channel.base)
                channel.link = urlOrNull(plainText(parser), elementBase)
            }

            name.equals("language", true) && key == Namespaces.Key.RSS -> {
                channel.language = languageOf(plainText(parser))
            }

            name.equals("managingEditor", true) && key == Namespaces.Key.RSS -> {
                channel.managingEditor = plainText(parser)
            }

            name.equals("ttl", true) && key == Namespaces.Key.RSS -> {
                channel.ttlMinutes = plainText(parser).trim().toIntOrNull()?.takeIf { it > 0 }
            }

            name.equals("image", true) && key in CHANNEL_IMAGE_KEYS -> {
                parseRssImage(parser, channel)
            }

            key == Namespaces.Key.ITUNES -> {
                parseItunesChannelElement(parser, channel, name)
            }

            key == Namespaces.Key.PODCAST -> {
                parsePodcastChannelElement(parser, channel, name)
            }

            key == Namespaces.Key.ATOM -> {
                parseAtomChannelElement(parser, channel, name)
            }

            key == Namespaces.Key.CONTENT && name.equals("encoded", true) -> {
                channel.contentEncoded = htmlText(parser)
            }

            key == Namespaces.Key.DC && name == "creator" -> {
                channel.dcCreator = plainText(parser)
            }

            key == Namespaces.Key.SY && name == "updatePeriod" -> {
                channel.syPeriod = plainText(parser)
            }

            key == Namespaces.Key.SY && name == "updateFrequency" -> {
                channel.syFrequency = plainText(parser).trim().toIntOrNull()
            }

            key == Namespaces.Key.FH && name == "complete" -> {
                channel.fhComplete = true
            }

            key == Namespaces.Key.FH && name == "archive" -> {
                channel.fhArchive = true
            }

            key == Namespaces.Key.YT && name == "channelId" -> {
                channel.ytChannelId = plainText(parser)
            }

            key == Namespaces.Key.YT && name == "playlistId" -> {
                channel.ytPlaylistId = plainText(parser)
            }

            key == Namespaces.Key.GOOGLEPLAY -> {
                parseGooglePlayChannelElement(parser, channel, name)
            }

            key == Namespaces.Key.MEDIA -> {
                parseMediaChannelElement(parser, channel, name)
            }

            else -> {
                skipElement(parser)
            }
        }
    }

    private fun parseItunesChannelElement(
        parser: XmlPullParser,
        channel: ChannelBuilder,
        name: String,
    ) {
        when (name) {
            "title" -> {
                channel.itunesTitle = plainText(parser)
            }

            "author" -> {
                channel.itunesAuthor = plainText(parser)
            }

            "summary" -> {
                channel.itunesSummary = htmlText(parser)
            }

            "explicit" -> {
                channel.explicit = explicitOf(plainText(parser))
            }

            "type" -> {
                channel.showType = showTypeOf(plainText(parser))
            }

            "complete" -> {
                channel.itunesComplete = plainText(parser).trim().equals("yes", ignoreCase = true)
            }

            "new-feed-url" -> {
                val elementBase = baseOf(parser, channel.base)
                channel.newFeedUrl = urlOrNull(plainText(parser), elementBase)
            }

            "owner" -> {
                parseOwner(parser, channel)
            }

            "category" -> {
                parseItunesCategory(parser, channel)
            }

            "image" -> {
                addArtwork(
                    channel.artwork,
                    artworkUrlFromElement(parser, channel.base),
                    ArtworkSource.ITUNES_IMAGE,
                )
            }

            else -> {
                skipElement(parser)
            }
        }
    }

    private fun parsePodcastChannelElement(
        parser: XmlPullParser,
        channel: ChannelBuilder,
        name: String,
    ) {
        when (name) {
            "guid" -> {
                channel.podcastGuid = PodcastGuid.parse(plainText(parser))
            }

            "locked" -> {
                channel.locked = explicitOf(plainText(parser))
            }

            "medium" -> {
                channel.medium = plainText(parser).trim().takeIf { it.isNotEmpty() }
            }

            "funding" -> {
                addFunding(parser, channel.funding, channel.base)
            }

            "person" -> {
                channel.persons.add(parsePerson(parser, channel.base))
            }

            "updateFrequency" -> {
                channel.updateFrequencyRrule = attr(parser, "rrule")?.trim()?.takeIf { it.isNotEmpty() }
                if (attr(parser, "complete")?.trim() == "true") channel.updateFrequencyComplete = true
                skipElement(parser)
            }

            "image" -> {
                parsePodcastImage(parser, channel)
            }

            "images" -> {
                parsePodcastImagesSrcset(parser, channel)
            }

            "podping" -> {
                channel.usesPodping = attr(parser, "usesPodping")?.trim() == "true"
                skipElement(parser)
            }

            else -> {
                skipElement(parser)
            }
        }
    }

    private fun parseAtomChannelElement(
        parser: XmlPullParser,
        channel: ChannelBuilder,
        name: String,
    ) {
        when (name) {
            "title" -> {
                channel.atomTitle = plainText(parser)
            }

            "subtitle" -> {
                channel.atomSubtitle = htmlText(parser)
            }

            "author" -> {
                // Atom author/name and author/uri sit in their own slots; the source precedence of
                // 03 Field mapping is applied at build time, never by document order.
                val (authorName, uri) = parseAtomAuthor(parser)
                if (channel.atomAuthor == null) channel.atomAuthor = authorName
                if (channel.authorUri == null) channel.authorUri = uri
            }

            "link" -> {
                parseAtomLink(parser, channel)
            }

            "logo" -> {
                val elementBase = baseOf(parser, channel.base)
                addArtwork(channel.artwork, urlOrNull(plainText(parser), elementBase), ArtworkSource.ATOM_LOGO)
            }

            "icon" -> {
                val elementBase = baseOf(parser, channel.base)
                addArtwork(channel.artwork, urlOrNull(plainText(parser), elementBase), ArtworkSource.ATOM_ICON)
            }

            else -> {
                skipElement(parser)
            }
        }
    }

    private fun parseGooglePlayChannelElement(
        parser: XmlPullParser,
        channel: ChannelBuilder,
        name: String,
    ) {
        when (name) {
            "description" -> {
                channel.googlePlayDescription = htmlText(parser)
            }

            "image" -> {
                addArtwork(
                    channel.artwork,
                    artworkUrlFromElement(parser, channel.base),
                    ArtworkSource.GOOGLEPLAY_IMAGE,
                )
            }

            else -> {
                skipElement(parser)
            }
        }
    }

    private fun parseMediaChannelElement(
        parser: XmlPullParser,
        channel: ChannelBuilder,
        name: String,
    ) {
        when (name) {
            "thumbnail" -> {
                addArtwork(
                    channel.artwork,
                    urlOrNull(attr(parser, "url"), baseOf(parser, channel.base)),
                    ArtworkSource.MEDIA_THUMBNAIL,
                    widthOf(parser),
                )
                skipElement(parser)
            }

            "content" -> {
                parseMediaContentImage(parser, channel, channel.base)
            }

            "group" -> {
                parseMediaGroup(parser, channel, null)
            }

            else -> {
                skipElement(parser)
            }
        }
    }

    private fun parseMediaContentImage(
        parser: XmlPullParser,
        channel: ChannelBuilder,
        parentBase: String,
    ) {
        if (attr(parser, "medium")?.equals("image", ignoreCase = true) == true) {
            addArtwork(
                channel.artwork,
                urlOrNull(attr(parser, "url"), baseOf(parser, parentBase)),
                ArtworkSource.MEDIA_CONTENT,
                widthOf(parser),
                heightOf(parser),
            )
        }
        skipElement(parser)
    }

    /** An RSS 2.0 `<image>` element: its `<url>` child is the artwork. */
    private fun parseRssImage(
        parser: XmlPullParser,
        channel: ChannelBuilder,
    ) {
        val depth = parser.depth
        val imageBase = baseOf(parser, channel.base)
        var url: String? = null
        var event = nextEvent(parser)
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && parser.depth == depth) break
            if (event == XmlPullParser.START_TAG && parser.name.equals("url", true)) {
                val urlBase = baseOf(parser, imageBase)
                url = urlOrNull(plainText(parser), urlBase)
            }
            event = nextEvent(parser)
        }
        addArtwork(channel.artwork, url, ArtworkSource.RSS_IMAGE)
    }

    /** `itunes:owner` > `itunes:name` is the last author fallback. */
    private fun parseOwner(
        parser: XmlPullParser,
        channel: ChannelBuilder,
    ) {
        val depth = parser.depth
        var event = nextEvent(parser)
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && parser.depth == depth) break
            if (event == XmlPullParser.START_TAG &&
                Namespaces.elementKey(parser) == Namespaces.Key.ITUNES &&
                parser.name == "name"
            ) {
                if (channel.ownerName == null) channel.ownerName = plainText(parser).trim().takeIf { it.isNotEmpty() }
            }
            event = nextEvent(parser)
        }
    }

    /**
     * One `itunes:category` element: every nested `itunes:category` is a child slot, so sibling
     * categories emit separate root-to-leaf paths (03 Field mapping categories).
     */
    private fun parseItunesCategory(
        parser: XmlPullParser,
        channel: ChannelBuilder,
    ) {
        channel.categories.addAll(categoryPaths(parser))
    }

    /** All root-to-leaf paths under the `itunes:category` the parser sits on. */
    private fun categoryPaths(parser: XmlPullParser): List<List<String>> {
        val text = attr(parser, "text")?.trim()?.takeIf { it.isNotEmpty() }
        val childPaths = mutableListOf<List<String>>()
        val depth = parser.depth
        var event = nextEvent(parser)
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && parser.depth <= depth) break
            if (event == XmlPullParser.START_TAG &&
                Namespaces.elementKey(parser) == Namespaces.Key.ITUNES &&
                parser.name == "category"
            ) {
                childPaths.addAll(categoryPaths(parser))
            }
            event = nextEvent(parser)
        }
        return when {
            text == null -> childPaths
            childPaths.isEmpty() -> listOf(listOf(text))
            else -> childPaths.map { listOf(text) + it }
        }
    }

    private fun parseAtomLink(
        parser: XmlPullParser,
        channel: ChannelBuilder,
    ) {
        val rel = attr(parser, "rel")?.trim()
        val href = urlOrNull(attr(parser, "href"), baseOf(parser, channel.base))
        when (rel) {
            null, "", "alternate" -> {
                if (href != null &&
                    channel.atomAlternateLink == null
                ) {
                    channel.atomAlternateLink = href
                }
            }

            "next" -> {
                if (href != null && channel.pagingNext == null) channel.pagingNext = href
            }

            "first" -> {
                if (href != null && channel.pagingFirst == null) channel.pagingFirst = href
            }

            "prev-archive" -> {
                if (href != null && channel.pagingPrevArchive == null) channel.pagingPrevArchive = href
            }

            "hub" -> {
                if (href != null && channel.hubUrl == null) channel.hubUrl = href
            }
        }
        skipElement(parser)
    }

    /** Atom `author/name` and `author/uri`; the first non-blank of each wins. */
    private fun parseAtomAuthor(parser: XmlPullParser): Pair<String?, String?> {
        val depth = parser.depth
        var name: String? = null
        var uri: String? = null
        var event = nextEvent(parser)
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && parser.depth == depth) break
            if (event == XmlPullParser.START_TAG) {
                when {
                    parser.name == "name" && name == null -> name = plainText(parser)
                    parser.name == "uri" && uri == null -> uri = plainText(parser)
                    else -> skipElement(parser)
                }
            }
            event = nextEvent(parser)
        }
        return name?.trim()?.takeIf { it.isNotEmpty() } to uri?.trim()?.takeIf { it.isNotEmpty() }
    }

    // ---------------------------------------------------------------------------
    // Item elements (RSS item / RDF item)
    // ---------------------------------------------------------------------------

    /** Mutable item state; [slot] fields keep the description precedence of 03 Field mapping. */
    private inner class ItemBuilder(
        val index: Int,
        val base: String,
    ) {
        var guid: String? = null
        var title: String? = null
        var itunesTitle: String? = null
        var mediaTitle: String? = null
        var pubDate: Long? = null
        var rawPubDate: String? = null
        var atomPublished: Long? = null
        var atomPublishedRaw: String? = null
        var atomUpdated: Long? = null
        var atomUpdatedRaw: String? = null
        var dcDate: Long? = null
        var dcDateRaw: String? = null

        // Description slots, in precedence order; html flags per 03 Field mapping.
        var slotContentEncoded: String? = null
        var slotDescription: String? = null
        var slotItunesSummary: String? = null
        var slotAtomContent: String? = null
        var slotAtomContentIsHtml = false
        var slotAtomSummary: String? = null
        var slotAtomSummaryIsHtml = false
        var slotMediaDescription: String? = null

        var link: String? = null
        var atomAlternateLink: String? = null
        var rssEnclosures = mutableListOf<Enclosure>()
        var atomEnclosures = mutableListOf<Enclosure>()

        // media:content splits into two buckets; inserting every isDefault entry at index 0 would
        // reverse their order and shift the list once per default (quadratic on default-heavy items).
        var mediaContentDefaults = mutableListOf<Enclosure>()
        var mediaContent = mutableListOf<Enclosure>()
        val alternateEnclosures = mutableListOf<AlternateEnclosure>()
        var durationMs: Long? = null
        var mediaDurationMs: Long? = null
        var season: Int? = null
        var seasonName: String? = null
        var itunesSeason: Int? = null
        var episodeNumber: String? = null
        var episodeDisplay: String? = null
        var itunesEpisode: String? = null
        var episodeType: String? = null
        var explicit: Boolean? = null
        val artwork = mutableListOf<ArtworkCandidate>()
        var chaptersUrl: String? = null
        var chaptersType: String? = null
        var inlineChapters = mutableListOf<InlineChapter>()
        val transcripts = mutableListOf<TranscriptRef>()
        var persons: MutableList<Person>? = null
        val funding = mutableListOf<Funding>()
        var externalMediaId: String? = null
        var ytChannelId: String? = null
        var mediaStatisticsViews: Long? = null
    }

    private fun parseItemElement(
        parser: XmlPullParser,
        item: ItemBuilder,
    ) {
        trackPrefixFallback(parser)
        val key = elementKey(parser)
        val name = parser.name

        when {
            key == Namespaces.Key.RSS || key == Namespaces.Key.RSS1 -> {
                parseRssItemElement(parser, item, name)
            }

            key == Namespaces.Key.ITUNES -> {
                parseItunesItemElement(parser, item, name)
            }

            key == Namespaces.Key.PODCAST -> {
                parsePodcastItemElement(parser, item, name)
            }

            key == Namespaces.Key.MEDIA -> {
                parseMediaItemElement(parser, item, name)
            }

            key == Namespaces.Key.CONTENT && name.equals("encoded", true) -> {
                item.slotContentEncoded = htmlText(parser, item.index)
            }

            key == Namespaces.Key.ATOM -> {
                parseAtomItemElement(parser, item, name)
            }

            key == Namespaces.Key.DC && name == "date" -> {
                readDate(parser, item.index).let { (ms, raw) ->
                    item.dcDate = ms
                    item.dcDateRaw = raw
                }
            }

            key == Namespaces.Key.PSC && name == "chapters" -> {
                parsePscChapters(parser, item)
            }

            key == Namespaces.Key.YT && name == "videoId" -> {
                item.externalMediaId = plainText(parser, item.index).trim()
            }

            key == Namespaces.Key.YT && name == "channelId" -> {
                item.ytChannelId = plainText(parser, item.index).trim()
            }

            else -> {
                skipElement(parser)
            }
        }
    }

    private fun parseRssItemElement(
        parser: XmlPullParser,
        item: ItemBuilder,
        name: String,
    ) {
        when {
            name.equals("guid", true) -> {
                item.guid = plainText(parser, item.index).trim().takeIf { it.isNotEmpty() }
            }

            name.equals("title", true) -> {
                item.title = plainText(parser, item.index)
            }

            name.equals("description", true) -> {
                item.slotDescription = htmlText(parser, item.index)
            }

            name.equals("link", true) -> {
                val elementBase = baseOf(parser, item.base)
                item.link = urlOrNull(plainText(parser, item.index), elementBase, item.index)
            }

            name.equals("pubDate", true) -> {
                readDate(parser, item.index).let { (ms, raw) ->
                    item.pubDate = ms
                    item.rawPubDate = raw
                }
            }

            name.equals("enclosure", true) -> {
                addEnclosure(parser, item, attr(parser, "url"), attr(parser, "type"), attr(parser, "length"))
                skipElement(parser)
            }

            else -> {
                skipElement(parser)
            }
        }
    }

    private fun parseItunesItemElement(
        parser: XmlPullParser,
        item: ItemBuilder,
        name: String,
    ) {
        when (name) {
            "title" -> {
                item.itunesTitle = plainText(parser, item.index)
            }

            "duration" -> {
                item.durationMs = readDuration(plainText(parser, item.index), item.index)
            }

            "summary" -> {
                item.slotItunesSummary = htmlText(parser, item.index)
            }

            "season" -> {
                item.itunesSeason = plainText(parser, item.index).trim().toIntOrNull()
            }

            "episode" -> {
                item.itunesEpisode = plainText(parser, item.index).trim().takeIf { it.isNotEmpty() }
            }

            "episodeType" -> {
                item.episodeType = episodeTypeOf(plainText(parser, item.index))
            }

            "explicit" -> {
                item.explicit = explicitOf(plainText(parser, item.index))
            }

            "image" -> {
                addArtwork(
                    item.artwork,
                    artworkUrlFromElement(parser, item.base, item.index),
                    ArtworkSource.ITUNES_IMAGE,
                )
            }

            else -> {
                skipElement(parser)
            }
        }
    }

    private fun parsePodcastItemElement(
        parser: XmlPullParser,
        item: ItemBuilder,
        name: String,
    ) {
        when (name) {
            "season" -> {
                // Attributes belong to the START_TAG: read them before plainText moves the parser.
                item.seasonName = attr(parser, "name")?.trim()?.takeIf { it.isNotEmpty() }
                item.season = plainText(parser, item.index).trim().toIntOrNull()
            }

            "episode" -> {
                item.episodeDisplay = attr(parser, "display")?.trim()?.takeIf { it.isNotEmpty() }
                item.episodeNumber = plainText(parser, item.index).trim().takeIf { it.isNotEmpty() }
            }

            "transcript" -> {
                val rawUrl = attr(parser, "url")
                val url = urlOrNull(rawUrl, baseOf(parser, item.base), item.index)
                if (url != null) {
                    item.transcripts.add(
                        TranscriptRef(
                            url,
                            attr(parser, "type")?.trim()?.takeIf { it.isNotEmpty() },
                            attr(parser, "language")?.trim()?.takeIf { it.isNotEmpty() },
                            attr(parser, "rel")?.trim()?.takeIf { it.isNotEmpty() },
                        ),
                    )
                } else if (rawUrl.isNullOrBlank()) {
                    warn(WarningCode.BAD_URL, item.index, "transcript")
                }
                skipElement(parser)
            }

            "chapters" -> {
                val rawUrl = attr(parser, "url")
                item.chaptersUrl = urlOrNull(rawUrl, baseOf(parser, item.base), item.index)
                item.chaptersType = attr(parser, "type")?.trim()?.takeIf { it.isNotEmpty() }
                if (rawUrl.isNullOrBlank()) warn(WarningCode.BAD_URL, item.index, "chapters")
                skipElement(parser)
            }

            "person" -> {
                // Item-level persons REPLACE the channel list (Podcasting 2.0 spec).
                (item.persons ?: mutableListOf<Person>().also { item.persons = it }).add(
                    parsePerson(parser, item.base, item.index),
                )
            }

            "funding" -> {
                addFunding(parser, item.funding, item.base, item.index)
            }

            "alternateEnclosure" -> {
                item.alternateEnclosures.add(parseAlternateEnclosure(parser, item))
            }

            "image" -> {
                // Item-level podcast:image carries its URL in the `url` attribute like the channel
                // form (03 Artwork candidates), through the shared resolve/validate path.
                addArtwork(
                    item.artwork,
                    urlOrNull(attr(parser, "url"), baseOf(parser, item.base), item.index),
                    ArtworkSource.PODCAST_IMAGE,
                    widthOf(parser),
                    heightOf(parser),
                )
                skipElement(parser)
            }

            else -> {
                skipElement(parser)
            }
        }
    }

    /** Atom constructs that also appear inside RSS items (`atom:link`, `atom:id` in the wild). */
    private fun parseAtomItemElement(
        parser: XmlPullParser,
        item: ItemBuilder,
        name: String,
    ) {
        when (name) {
            "id" -> {
                if (item.guid == null) item.guid = plainText(parser, item.index).trim().takeIf { it.isNotEmpty() }
            }

            "link" -> {
                val rel = attr(parser, "rel")?.trim()
                when (rel) {
                    "enclosure" -> {
                        enclosureOf(parser, item.base, item.index)?.let(item.atomEnclosures::add)
                    }

                    null, "", "alternate" -> {
                        item.atomAlternateLink = urlOrNull(attr(parser, "href"), baseOf(parser, item.base), item.index)
                    }
                }
                skipElement(parser)
            }

            "published" -> {
                readDate(parser, item.index).let { (ms, raw) ->
                    item.atomPublished = ms
                    item.atomPublishedRaw = raw
                }
            }

            "updated" -> {
                readDate(parser, item.index).let { (ms, raw) ->
                    item.atomUpdated = ms
                    item.atomUpdatedRaw = raw
                }
            }

            "summary" -> {
                if (item.slotAtomSummary == null) {
                    val (text, isHtml) = atomTextConstruct(parser, item.index)
                    item.slotAtomSummary = text
                    item.slotAtomSummaryIsHtml = isHtml
                }
            }

            "content" -> {
                if (item.slotAtomContent == null) {
                    val (text, isHtml) = atomTextConstruct(parser, item.index)
                    item.slotAtomContent = text
                    item.slotAtomContentIsHtml = isHtml
                }
            }

            else -> {
                skipElement(parser)
            }
        }
    }

    private fun parseMediaItemElement(
        parser: XmlPullParser,
        item: ItemBuilder,
        name: String,
    ) {
        when (name) {
            "title" -> {
                item.mediaTitle = plainText(parser, item.index)
            }

            "description" -> {
                item.slotMediaDescription = plainText(parser, item.index)
            }

            "content" -> {
                parseMediaContent(parser, item, item.base)
            }

            "thumbnail" -> {
                addArtwork(
                    item.artwork,
                    urlOrNull(attr(parser, "url"), baseOf(parser, item.base), item.index),
                    ArtworkSource.MEDIA_THUMBNAIL,
                    widthOf(parser),
                )
                skipElement(parser)
            }

            "group" -> {
                parseMediaGroup(parser, null, item)
            }

            else -> {
                skipElement(parser)
            }
        }
    }

    /** [parentBase] is the enclosing container's base — a `media:group`'s `xml:base` included. */
    private fun parseMediaContent(
        parser: XmlPullParser,
        item: ItemBuilder,
        parentBase: String,
    ) {
        val type = attr(parser, "type")
        val medium = attr(parser, "medium")?.lowercase()
        // MIME matching is case-insensitive (RFC 2045 §5.1); the declared type is stored verbatim.
        // Normalisation mirrors EnclosureTypes.effective: parameters stripped, trimmed, lowercased.
        val typeKey = type?.substringBefore(';')?.trim()?.lowercase()
        val isMedia =
            medium in AUDIO_VIDEO_MEDIA ||
                typeKey?.startsWith("audio/") == true ||
                typeKey?.startsWith("video/") == true
        if (isMedia) {
            // Media RSS enclosure URLs go through the same resolve/validate path as every other URL.
            val url = urlOrNull(attr(parser, "url"), baseOf(parser, parentBase), item.index)
            if (url != null) {
                val enclosure =
                    Enclosure(url, type?.trim()?.takeIf { it.isNotEmpty() }, positiveLong(attr(parser, "fileSize")))
                // isDefault content comes first within its element (03 Field mapping); the buckets
                // merge once in buildEpisode, keeping document order inside each class.
                if (attr(parser, "isDefault")?.trim() ==
                    "true"
                ) {
                    item.mediaContentDefaults.add(enclosure)
                } else {
                    item.mediaContent.add(enclosure)
                }
                if (item.mediaDurationMs == null) {
                    item.mediaDurationMs = readDuration(attr(parser, "duration"), item.index)
                }
            }
        }
        skipElement(parser)
    }

    /**
     * `media:group` children: `media:content` and `media:thumbnail` (also the channel variant) plus
     * the group-level `media:title`/`media:description`/`media:community` YouTube carries (04 Atom
     * feed ingestion); the channel form stores none of those.
     */
    private fun parseMediaGroup(
        parser: XmlPullParser,
        channel: ChannelBuilder?,
        item: ItemBuilder?,
    ) {
        val depth = parser.depth
        // The group's own xml:base becomes the parent base for every URL-bearing child.
        val groupBase = baseOf(parser, channel?.base ?: item!!.base)
        var event = nextEvent(parser)
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && parser.depth == depth) break
            if (event == XmlPullParser.START_TAG && Namespaces.elementKey(parser) == Namespaces.Key.MEDIA) {
                val base = baseOf(parser, groupBase)
                when (parser.name) {
                    "content" -> {
                        if (item != null) {
                            parseMediaContent(parser, item, groupBase)
                        } else {
                            parseMediaContentImage(parser, channel!!, groupBase)
                        }
                    }

                    "thumbnail" -> {
                        addArtwork(
                            item?.artwork ?: channel!!.artwork,
                            urlOrNull(attr(parser, "url"), base, item?.index),
                            ArtworkSource.MEDIA_THUMBNAIL,
                            widthOf(parser),
                        )
                        skipElement(parser)
                    }

                    "title" -> {
                        if (item != null) item.mediaTitle = plainText(parser, item.index) else skipElement(parser)
                    }

                    "description" -> {
                        if (item !=
                            null
                        ) {
                            item.slotMediaDescription = plainText(parser, item.index)
                        } else {
                            skipElement(parser)
                        }
                    }

                    "community" -> {
                        if (item != null) parseMediaCommunity(parser, item) else skipElement(parser)
                    }

                    else -> {
                        skipElement(parser)
                    }
                }
            } else if (event == XmlPullParser.START_TAG) {
                skipElement(parser)
            }
            event = nextEvent(parser)
        }
    }

    /** `media:community` > `media:statistics@views`, the play-count hint of 04 Atom feed ingestion. */
    private fun parseMediaCommunity(
        parser: XmlPullParser,
        item: ItemBuilder,
    ) {
        val depth = parser.depth
        var event = nextEvent(parser)
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && parser.depth == depth) break
            if (event == XmlPullParser.START_TAG &&
                Namespaces.elementKey(parser) == Namespaces.Key.MEDIA &&
                parser.name == "statistics" &&
                item.mediaStatisticsViews == null
            ) {
                item.mediaStatisticsViews =
                    attr(parser, "views")?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
            }
            event = nextEvent(parser)
        }
    }

    private fun parsePscChapters(
        parser: XmlPullParser,
        item: ItemBuilder,
    ) {
        // The chapters container carries its own xml:base into every chapter's href and image.
        val chaptersBase = baseOf(parser, item.base)
        val depth = parser.depth
        var event = nextEvent(parser)
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && parser.depth == depth) break
            if (event == XmlPullParser.START_TAG &&
                Namespaces.elementKey(parser) == Namespaces.Key.PSC &&
                parser.name == "chapter"
            ) {
                val chapterBase = baseOf(parser, chaptersBase)
                val startMs = Durations.parseMs(attr(parser, "start").orEmpty())
                if (startMs != null) {
                    item.inlineChapters.add(
                        InlineChapter(
                            startMs = startMs,
                            title = attr(parser, "title").orEmpty(),
                            href = urlOrNull(attr(parser, "href"), chapterBase, item.index),
                            image = urlOrNull(attr(parser, "image"), chapterBase, item.index),
                        ),
                    )
                }
            }
            event = nextEvent(parser)
        }
    }

    private fun parseAlternateEnclosure(
        parser: XmlPullParser,
        item: ItemBuilder,
    ): AlternateEnclosure {
        val depth = parser.depth
        val enclosureBase = baseOf(parser, item.base)
        val type = attr(parser, "type")?.trim()?.takeIf { it.isNotEmpty() }
        val length = positiveLong(attr(parser, "length"))
        val bitrate = positiveLong(attr(parser, "bitrate"))
        val height = positiveLong(attr(parser, "height"))
        val lang = attr(parser, "lang")?.trim()?.takeIf { it.isNotEmpty() }
        val title = attr(parser, "title")?.trim()?.takeIf { it.isNotEmpty() }
        val rel = attr(parser, "rel")?.trim()?.takeIf { it.isNotEmpty() }
        val codecs = attr(parser, "codecs")?.trim()?.takeIf { it.isNotEmpty() }
        val isDefault = attr(parser, "default")?.trim() == "true"
        val sources = mutableListOf<AlternateEnclosureSource>()
        val integrity = mutableListOf<AlternateEnclosureIntegrity>()

        var event = nextEvent(parser)
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && parser.depth == depth) break
            if (event == XmlPullParser.START_TAG && Namespaces.elementKey(parser) == Namespaces.Key.PODCAST) {
                when (parser.name) {
                    "source" -> {
                        val rawUri = attr(parser, "uri")
                        val uri = urlOrNull(rawUri, baseOf(parser, enclosureBase), item.index)
                        if (uri != null) {
                            sources.add(
                                AlternateEnclosureSource(
                                    uri,
                                    attr(parser, "contentType")?.trim()?.takeIf { it.isNotEmpty() },
                                ),
                            )
                        } else if (rawUri.isNullOrBlank()) {
                            warn(WarningCode.BAD_URL, item.index, "alternateEnclosure source")
                        }
                    }

                    "integrity" -> {
                        val intType = attr(parser, "type")?.trim()
                        val value = attr(parser, "value")?.trim()
                        if (!intType.isNullOrEmpty() && !value.isNullOrEmpty()) {
                            integrity.add(AlternateEnclosureIntegrity(intType, value))
                        }
                    }
                }
            }
            event = nextEvent(parser)
        }
        return AlternateEnclosure(
            type,
            length,
            bitrate,
            height,
            lang,
            title,
            rel,
            codecs,
            isDefault,
            sources,
            integrity,
        )
    }

    private fun parsePerson(
        parser: XmlPullParser,
        parentBase: String,
        itemIndex: Int? = null,
    ): Person {
        // Attributes belong to the START_TAG; feeds carry them both plain and podcast-namespaced.
        val personBase = baseOf(parser, parentBase)
        val role = attr(parser, "role") ?: attr(parser, "role", PODCAST_NS)
        val group = attr(parser, "group") ?: attr(parser, "group", PODCAST_NS)
        val img = urlOrNull(attr(parser, "img") ?: attr(parser, "img", PODCAST_NS), personBase, itemIndex)
        val href = urlOrNull(attr(parser, "href") ?: attr(parser, "href", PODCAST_NS), personBase, itemIndex)
        val name = plainText(parser, itemIndex).trim()
        return Person(
            name = name,
            role = role?.trim()?.takeIf { it.isNotEmpty() } ?: Person.ROLE_HOST,
            group = group?.trim()?.takeIf { it.isNotEmpty() } ?: Person.GROUP_CAST,
            img = img,
            href = href,
        )
    }

    private fun addFunding(
        parser: XmlPullParser,
        target: MutableList<Funding>,
        parentBase: String,
        itemIndex: Int? = null,
    ) {
        // The element's own xml:base resolves the url; a bad one warns inside urlOrNull already.
        val url = urlOrNull(attr(parser, "url"), baseOf(parser, parentBase), itemIndex)
        val title = plainText(parser, itemIndex).trim().take(FUNDING_TITLE_MAX_CHARS)
        if (url != null) target.add(Funding(url, title))
    }

    /**
     * `podcast:image`: banner when its purpose is banner/canvas with aspect 16/9; artwork for purpose
     * artwork (or absent) with a square or absent aspect; the URL sits in the `url` attribute.
     */
    private fun parsePodcastImage(
        parser: XmlPullParser,
        channel: ChannelBuilder,
    ) {
        val purpose = attr(parser, "purpose")?.lowercase().orEmpty()
        val aspect = attr(parser, "aspect")?.trim().orEmpty()
        val url = urlOrNull(attr(parser, "url"), baseOf(parser, channel.base))
        if (url != null) {
            if ((purpose.contains("banner") || purpose.contains("canvas")) && aspect == BANNER_ASPECT) {
                channel.bannerUrl = url
            } else if ((purpose.isEmpty() || purpose.contains("artwork")) &&
                (aspect.isEmpty() || aspect == SQUARE_ASPECT)
            ) {
                addArtwork(channel.artwork, url, ArtworkSource.PODCAST_IMAGE, widthOf(parser), heightOf(parser))
            }
        }
        skipElement(parser)
    }

    /** The deprecated `podcast:images` srcset: one artwork candidate, the largest valid entry wins. */
    private fun parsePodcastImagesSrcset(
        parser: XmlPullParser,
        channel: ChannelBuilder,
    ) {
        val srcset = attr(parser, "srcset").orEmpty()
        val srcsetBase = baseOf(parser, channel.base)
        var bestUrl: String? = null
        var bestWidth = -1
        for (entry in srcset.split(',')) {
            val parts = entry.trim().split(Regex("\\s+"))
            if (parts.size < 2) continue
            val width = srcsetWidth(parts[1]) ?: continue
            // Entries are validated before their widths compete; an unresolvable candidate cannot
            // win on width alone (03 Artwork candidates).
            val url = urlOrNull(parts[0], srcsetBase) ?: continue
            if (width > bestWidth) {
                bestWidth = width
                bestUrl = url
            }
        }
        if (bestUrl != null) {
            addArtwork(channel.artwork, bestUrl, ArtworkSource.PODCAST_IMAGES, bestWidth)
        }
        skipElement(parser)
    }

    /** srcset widths appear as `600w`, `600x600` or a plain number. */
    private fun srcsetWidth(raw: String): Int? =
        raw
            .removeSuffix("w")
            .substringBefore('x')
            .toIntOrNull()
            ?.takeIf { it > 0 }

    // ---------------------------------------------------------------------------
    // Atom entry
    // ---------------------------------------------------------------------------

    private fun parseAtomEntryElement(
        parser: XmlPullParser,
        item: ItemBuilder,
    ) {
        trackPrefixFallback(parser)
        val key = elementKey(parser)
        val name = parser.name

        // Extension namespaces dispatch before Atom local names: a media:title or media:content is
        // an extension element even though Atom has names with the same local name (03 step 7).
        // iTunes, content, Dublin Core and PSC run the same item handlers as inside an RSS item.
        when {
            key == Namespaces.Key.MEDIA -> {
                parseMediaItemElement(parser, item, name)
            }

            key == Namespaces.Key.ITUNES -> {
                parseItunesItemElement(parser, item, name)
            }

            key == Namespaces.Key.CONTENT && name.equals("encoded", true) -> {
                item.slotContentEncoded = htmlText(parser, item.index)
            }

            key == Namespaces.Key.DC && name == "date" -> {
                readDate(parser, item.index).let { (ms, raw) ->
                    item.dcDate = ms
                    item.dcDateRaw = raw
                }
            }

            key == Namespaces.Key.PSC && name == "chapters" -> {
                parsePscChapters(parser, item)
            }

            key == Namespaces.Key.YT && name == "videoId" -> {
                item.externalMediaId = plainText(parser, item.index).trim()
            }

            key == Namespaces.Key.YT && name == "channelId" -> {
                item.ytChannelId = plainText(parser, item.index).trim()
            }

            key == Namespaces.Key.YT -> {
                skipElement(parser)
            }

            key == Namespaces.Key.PODCAST -> {
                parsePodcastItemElement(parser, item, name)
            }

            key != Namespaces.Key.ATOM -> {
                skipElement(parser)
            }

            name == "id" -> {
                item.guid = plainText(parser, item.index).trim().takeIf { it.isNotEmpty() }
            }

            name == "title" -> {
                item.title = plainText(parser, item.index)
            }

            name == "published" -> {
                readDate(parser, item.index).let { (ms, raw) ->
                    item.atomPublished = ms
                    item.atomPublishedRaw = raw
                }
            }

            name == "updated" -> {
                readDate(parser, item.index).let { (ms, raw) ->
                    item.atomUpdated = ms
                    item.atomUpdatedRaw = raw
                }
            }

            name == "summary" -> {
                if (item.slotAtomSummary == null) {
                    val (text, isHtml) = atomTextConstruct(parser, item.index)
                    item.slotAtomSummary = text
                    item.slotAtomSummaryIsHtml = isHtml
                }
            }

            name == "content" -> {
                if (item.slotAtomContent == null) {
                    val (text, isHtml) = atomTextConstruct(parser, item.index)
                    item.slotAtomContent = text
                    item.slotAtomContentIsHtml = isHtml
                }
            }

            name == "link" -> {
                val rel = attr(parser, "rel")?.trim()
                when (rel) {
                    "enclosure" -> {
                        enclosureOf(parser, item.base, item.index)?.let(item.atomEnclosures::add)
                    }

                    null, "", "alternate" -> {
                        item.atomAlternateLink = urlOrNull(attr(parser, "href"), baseOf(parser, item.base), item.index)
                    }
                }
                skipElement(parser)
            }

            name == "author" -> {
                // Item-level authors are not stored in v1.
                skipElement(parser)
            }

            else -> {
                skipElement(parser)
            }
        }
    }

    /** Atom text constructs: `type="text"` is plain, `html` and `xhtml` are HTML (03 step 6). */
    private fun atomTextConstruct(
        parser: XmlPullParser,
        itemIndex: Int? = null,
    ): Pair<String?, Boolean> {
        val type = attr(parser, "type")?.trim()?.lowercase()
        return when (type) {
            "html" -> htmlText(parser, itemIndex) to true
            "xhtml" -> htmlText(parser, itemIndex) to true
            else -> plainText(parser, itemIndex).takeIf { it.isNotEmpty() } to false
        }
    }

    // ---------------------------------------------------------------------------
    // Episode assembly (03 Field mapping)
    // ---------------------------------------------------------------------------

    private fun buildEpisode(item: ItemBuilder): ParsedEpisode {
        // Enclosure order: explicit enclosures, the Atom enclosures, then media:content entries —
        // defaults ahead of the rest, each class in document order.
        val rawEnclosures =
            item.rssEnclosures + item.atomEnclosures + item.mediaContentDefaults + item.mediaContent
        val enclosures = rawEnclosures.map { it.copy(effectiveType = EnclosureTypes.effective(it.type, it.url)) }
        val primary = EnclosureTypes.primary(enclosures)

        val pubDate = item.pubDate ?: item.atomPublished ?: item.atomUpdated ?: item.dcDate
        val rawPubDate = item.rawPubDate ?: item.atomPublishedRaw ?: item.atomUpdatedRaw ?: item.dcDateRaw

        // Description precedence with the html flag of the winning slot (03 Field mapping).
        var descriptionHtml: String? = null
        var descriptionIsHtml = false
        for (slot in DESCRIPTION_PRECEDENCE) {
            val (value, isHtml) = slotOf(item, slot)
            if (value != null) {
                descriptionHtml = value
                descriptionIsHtml = isHtml
                break
            }
        }

        val season = item.season ?: item.itunesSeason
        return ParsedEpisode(
            feedOrder = item.index,
            guid = item.guid,
            title = firstNonBlank(item.title, item.itunesTitle, item.mediaTitle),
            pubDate = pubDate,
            rawPubDate = rawPubDate,
            descriptionHtml = descriptionHtml,
            descriptionIsHtml = descriptionIsHtml,
            link = firstNonBlank(item.link, item.atomAlternateLink),
            enclosures = enclosures,
            primaryEnclosure = primary,
            alternateEnclosures = item.alternateEnclosures.toList(),
            durationMs = item.durationMs ?: item.mediaDurationMs,
            season = season,
            seasonName = if (item.season != null) item.seasonName else null,
            episodeNumber = item.episodeNumber ?: item.itunesEpisode,
            episodeDisplay = if (item.episodeNumber != null) item.episodeDisplay else null,
            episodeType = item.episodeType,
            explicit = item.explicit,
            artwork = inPrecedenceOrder(item.artwork),
            chaptersUrl = item.chaptersUrl,
            chaptersType = item.chaptersType,
            inlineChapters = item.inlineChapters.toList(),
            transcripts = item.transcripts.toList(),
            persons = item.persons?.toList(),
            funding = item.funding.toList(),
            externalMediaId = item.externalMediaId,
            ytChannelId = item.ytChannelId,
            mediaStatisticsViews = item.mediaStatisticsViews,
        )
    }

    private enum class DescriptionSlot {
        CONTENT_ENCODED,
        DESCRIPTION,
        ITUNES_SUMMARY,
        ATOM_CONTENT,
        ATOM_SUMMARY,
        MEDIA_DESCRIPTION,
    }

    private fun slotOf(
        item: ItemBuilder,
        slot: DescriptionSlot,
    ): Pair<String?, Boolean> =
        when (slot) {
            DescriptionSlot.CONTENT_ENCODED -> item.slotContentEncoded to true
            DescriptionSlot.DESCRIPTION -> item.slotDescription to true
            DescriptionSlot.ITUNES_SUMMARY -> item.slotItunesSummary to true
            DescriptionSlot.ATOM_CONTENT -> item.slotAtomContent to item.slotAtomContentIsHtml
            DescriptionSlot.ATOM_SUMMARY -> item.slotAtomSummary to item.slotAtomSummaryIsHtml
            DescriptionSlot.MEDIA_DESCRIPTION -> item.slotMediaDescription to false
        }

    // ---------------------------------------------------------------------------
    // Text, attribute and URL helpers
    // ---------------------------------------------------------------------------

    /** Reads a date element: the parsed epoch-ms (null plus `UNKNOWN_DATE` on failure) and the raw text. */
    private fun readDate(
        parser: XmlPullParser,
        itemIndex: Int?,
    ): Pair<Long?, String> {
        val raw = plainText(parser, itemIndex)
        val parsed = FeedDates.parse(raw)
        if (parsed == null) warn(WarningCode.UNKNOWN_DATE, itemIndex, raw.take(SHORT_DETAIL_CHARS))
        return parsed to raw
    }

    /** Reads an `itunes:duration` or `media:content@duration` value; null plus `BAD_DURATION` on failure. */
    private fun readDuration(
        raw: String?,
        itemIndex: Int?,
    ): Long? {
        if (raw == null) return null
        val parsed = Durations.parseMs(raw)
        if (parsed == null) warn(WarningCode.BAD_DURATION, itemIndex, raw.trim().take(SHORT_DETAIL_CHARS))
        return parsed
    }

    /** Plain element text, trimmed; child markup is skipped, entity text included (03 steps 2–4). */
    private fun plainText(
        parser: XmlPullParser,
        itemIndex: Int? = null,
    ): String {
        val out = StringBuilder()
        var total = 0

        // The collector is bounded: text past the cap is counted (for the warning detail and the
        // U+FFFD heuristic) but not retained (03 Limits and version policy).
        fun appendBounded(text: String) {
            countText(text)
            total += text.length
            val room = limits.maxTextChars - out.length
            if (room > 0) out.append(text, 0, minOf(text.length, room))
        }
        // Enter the element: the caller leaves the parser on its START_TAG.
        var event = nextEvent(parser)
        while (event != XmlPullParser.END_TAG && event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                    appendBounded(InnerXml.stripRelaxedError(parser.text))
                }

                XmlPullParser.ENTITY_REF -> {
                    parser.text?.let { appendBounded(InnerXml.stripRelaxedError(it)) }
                }

                XmlPullParser.START_TAG -> {
                    skipElement(parser)
                }
            }
            event = nextEvent(parser)
        }
        if (total > limits.maxTextChars) {
            warnings.add(ParseWarning(WarningCode.TEXT_TRUNCATED, itemIndex, "$total chars"))
        }
        return out.toString().trim()
    }

    /** HTML-bearing element text: child markup is re-serialised, never dropped (03 step 6). */
    private fun htmlText(
        parser: XmlPullParser,
        itemIndex: Int? = null,
    ): String? {
        val collected = InnerXml.collect(parser, ::countText, limits.maxDepth, limits.maxTextChars)
        if (collected.totalChars > limits.maxTextChars) {
            warnings.add(ParseWarning(WarningCode.TEXT_TRUNCATED, itemIndex, "${collected.totalChars} chars"))
        }
        return collected.text.trim().takeIf { it.isNotEmpty() }
    }

    private fun countText(text: String) {
        counters.textChars += text.length
        counters.replacementChars += text.count { it == REPLACEMENT_CHAR }
    }

    /**
     * Resolves [raw] against [base], then enforces absolute http(s) with an authority and the
     * URL-length limit (`BAD_URL`, 03 Limits and version policy); a blank value is no URL at all and
     * stays silent. The raw reference itself is bounded before resolution so a `"../"` × 1,000,000
     * attack never reaches [removeDotSegments].
     */
    private fun urlOrNull(
        raw: String?,
        base: String,
        itemIndex: Int? = null,
    ): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (trimmed.length > limits.maxUrlChars) {
            warnings.add(ParseWarning(WarningCode.BAD_URL, itemIndex, "${trimmed.length} chars"))
            return null
        }
        val resolved = resolveUrl(trimmed, base)
        if (resolved == null || !isHttpAbsolute(resolved)) {
            warnings.add(ParseWarning(WarningCode.BAD_URL, itemIndex, trimmed.take(SHORT_DETAIL_CHARS)))
            return null
        }
        if (resolved.length > limits.maxUrlChars) {
            warnings.add(ParseWarning(WarningCode.BAD_URL, itemIndex, "${resolved.length} chars"))
            return null
        }
        return resolved
    }

    /** Absolute http(s) with a non-empty authority; the scheme matches case-insensitively. */
    private fun isHttpAbsolute(url: String): Boolean {
        val authorityStart =
            when {
                url.regionMatches(0, HTTP_PREFIX, 0, HTTP_PREFIX.length, ignoreCase = true) -> HTTP_PREFIX.length
                url.regionMatches(0, HTTPS_PREFIX, 0, HTTPS_PREFIX.length, ignoreCase = true) -> HTTPS_PREFIX.length
                else -> return false
            }
        val authorityEnd = url.indexOfAny(URI_DELIMS, authorityStart)
        return (if (authorityEnd < 0) url.length else authorityEnd) > authorityStart
    }

    /**
     * RFC 3986 §5.2.2 reference resolution; `xml:base` has been folded into [base] already. `?` in
     * the reference means an explicitly empty query — distinct from an absent query, which inherits
     * the base's.
     */
    private fun resolveUrl(
        raw: String,
        base: String,
    ): String? {
        // Bound the raw reference (also how raw xml:base values are bounded, via baseOf).
        if (raw.length > limits.maxUrlChars) return null

        // An absolute URI (scheme present) wins outright.
        if (ABSOLUTE_URI.containsMatchIn(raw)) return raw
        val baseNoFragment = base.substringBefore('#')
        val baseMatch = BASE_URI.find(baseNoFragment) ?: return null
        val scheme = baseMatch.groupValues[1]
        val authority = baseMatch.groupValues[2]
        val basePath = baseMatch.groupValues[3]
        val baseQuery = if ('?' in baseNoFragment) baseMatch.groupValues[4] else null

        val refPathQuery = raw.substringBefore('#')
        val refPath = refPathQuery.substringBefore('?')
        val refQuery = if ('?' in refPathQuery) refPathQuery.substringAfter('?') else null
        val fragment =
            raw
                .substringAfter('#', "")
                .ifEmpty { null }
                ?.let { "#$it" }
                .orEmpty()

        val target =
            when {
                refPath.startsWith("//") -> "$scheme:$refPath"
                refPath.startsWith("/") -> "$scheme://$authority" + removeDotSegments(refPath)
                refPath.isEmpty() -> "$scheme://$authority$basePath"
                else -> "$scheme://$authority" + removeDotSegments(mergePath(basePath, refPath))
            }
        val query =
            refQuery?.let { "?$it" }
                ?: if (refPath.isEmpty() && baseQuery != null) "?$baseQuery" else ""
        return target + query + fragment
    }

    /** The merge rule of RFC 3986 §5.2.3: the reference hangs on the base's directory. */
    private fun mergePath(
        basePath: String,
        refPath: String,
    ): String {
        val cut = basePath.lastIndexOf('/')
        return if (cut < 0) "/$refPath" else basePath.substring(0, cut + 1) + refPath
    }

    /**
     * RFC 3986 §5.2.4 dot-segment removal; `..` at the root stays at the root. Single index pass —
     * the input is never re-scanned or shifted, so per-reference work stays linear in its length.
     */
    private fun removeDotSegments(path: String): String {
        val out = StringBuilder(path.length)
        var i = 0
        while (i < path.length) {
            when {
                path.startsWith("../", i) -> {
                    i += 3
                }

                path.startsWith("./", i) -> {
                    i += 2
                }

                path.startsWith("/./", i) -> {
                    i += 2
                }

                path.startsWith("/.", i) && i + 2 == path.length -> {
                    out.append('/')
                    i += 2
                }

                path.startsWith("/../", i) -> {
                    i += 3
                    dropLastSegment(out)
                }

                path.startsWith("/..", i) && i + 3 == path.length -> {
                    dropLastSegment(out)
                    out.append('/')
                    i += 3
                }

                path.startsWith("..", i) && i + 2 == path.length -> {
                    i += 2
                }

                path[i] == '.' && i + 1 == path.length -> {
                    i += 1
                }

                else -> {
                    // Move the first path segment, including its leading '/', to the output.
                    val start = if (path[i] == '/') i + 1 else i
                    val end = path.indexOf('/', start).let { if (it < 0) path.length else it }
                    out.append(path, i, end)
                    i = end
                }
            }
        }
        return out.toString()
    }

    /** `/a/b` → `/a`: the segment just appended to [out] is removed, amortised O(segment). */
    private fun dropLastSegment(out: StringBuilder) {
        out.setLength(out.lastIndexOf("/").takeIf { it >= 0 } ?: 0)
    }

    /**
     * The URL of `itunes:image`/`googleplay:image`: the href attribute, or the element text (a common
     * error, 03 Artwork candidates), through the shared resolve/validate path.
     */
    private fun artworkUrlFromElement(
        parser: XmlPullParser,
        base: String,
        itemIndex: Int? = null,
    ): String? {
        val elementBase = baseOf(parser, base)
        val href = attr(parser, "href")?.trim()?.takeIf { it.isNotEmpty() }
        val raw =
            if (href != null) {
                skipElement(parser)
                href
            } else {
                plainText(parser, itemIndex).trim().takeIf { it.isNotEmpty() }
            }
        return urlOrNull(raw, elementBase, itemIndex)
    }

    private fun addArtwork(
        target: MutableList<ArtworkCandidate>,
        url: String?,
        source: ArtworkSource,
        width: Int? = null,
        height: Int? = null,
    ) {
        if (url != null) target.add(ArtworkCandidate(url, source, width, height))
    }

    /**
     * Artwork candidates in precedence order (03 Artwork candidates), regardless of document order;
     * within one source the largest width comes first (podcast:image, srcset, media sizes).
     */
    private fun inPrecedenceOrder(candidates: List<ArtworkCandidate>): List<ArtworkCandidate> =
        candidates
            .sortedWith(compareByDescending<ArtworkCandidate> { it.width ?: -1 })
            .groupBy(ArtworkCandidate::source)
            .entries
            .sortedBy { (source, _) -> ARTWORK_RANK.getValue(source) }
            .flatMap { (_, group) -> group }

    private fun addEnclosure(
        parser: XmlPullParser,
        item: ItemBuilder,
        rawUrl: String?,
        type: String?,
        rawLength: String?,
    ) {
        // A non-blank URL that fails resolution or validation warns inside urlOrNull already; the
        // element's own xml:base resolves it.
        val url = urlOrNull(rawUrl, baseOf(parser, item.base), item.index) ?: return
        item.rssEnclosures.add(Enclosure(url, type?.trim()?.takeIf { it.isNotEmpty() }, positiveLong(rawLength)))
    }

    private fun enclosureOf(
        parser: XmlPullParser,
        base: String,
        itemIndex: Int? = null,
    ): Enclosure? {
        val url = urlOrNull(attr(parser, "href"), baseOf(parser, base), itemIndex) ?: return null
        return Enclosure(
            url,
            attr(parser, "type")?.trim()?.takeIf { it.isNotEmpty() },
            positiveLong(attr(parser, "length")),
        )
    }

    private fun positiveLong(raw: String?): Long? = raw?.trim()?.toLongOrNull()?.takeIf { it > 0 }

    private fun widthOf(parser: XmlPullParser): Int? = attr(parser, "width")?.trim()?.toIntOrNull()?.takeIf { it > 0 }

    private fun heightOf(parser: XmlPullParser): Int? = attr(parser, "height")?.trim()?.toIntOrNull()?.takeIf { it > 0 }

    private fun languageOf(raw: String): String? =
        runCatching { Locale.forLanguageTag(raw.trim().replace('_', '-')).toLanguageTag() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() && it != UNDETERMINED_LANGUAGE }

    private fun explicitOf(raw: String): Boolean? =
        when (raw.trim().lowercase()) {
            "yes", "true", "explicit" -> true
            "no", "false", "clean" -> false
            else -> null
        }

    private fun showTypeOf(raw: String): String? = raw.trim().lowercase().takeIf { it == "episodic" || it == "serial" }

    private fun episodeTypeOf(raw: String): String? =
        raw.trim().lowercase().takeIf { it == "full" || it == "trailer" || it == "bonus" }

    /** Records a warning and returns null, so callers can write `parse(x) ?: warn(...)`. */
    private fun warn(
        code: WarningCode,
        itemIndex: Int?,
        detail: String,
    ): Nothing? {
        warnings.add(ParseWarning(code, itemIndex, detail))
        return null
    }

    private fun xmlBase(parser: XmlPullParser): String? = attr(parser, XML_BASE_NAME, XML_NS)

    /** Warns once per prefix matched through the fallback table (03 Namespace registry). */
    private fun trackPrefixFallback(parser: XmlPullParser) {
        if (!Namespaces.usedPrefixFallback(parser)) return
        val prefix = parser.prefix ?: return
        if (undeclaredPrefixesSeen.add(prefix)) {
            warnings.add(ParseWarning(WarningCode.UNDECLARED_PREFIX, detail = prefix))
        }
    }

    private fun attr(
        parser: XmlPullParser,
        name: String,
    ): String? = parser.getAttributeValue(null, name)

    private fun attr(
        parser: XmlPullParser,
        name: String,
        namespace: String,
    ): String? = parser.getAttributeValue(namespace, name)

    /** Advances the parser and guards the element-depth limit on every event. */
    private fun nextEvent(parser: XmlPullParser): Int {
        val event = parser.next()
        if (parser.depth > limits.maxDepth) throw DepthLimitExceeded(limits.maxDepth)
        return event
    }

    /** Consumes the current element through its END_TAG (the parser sits on a START_TAG). */
    private fun skipElement(parser: XmlPullParser) {
        var depth = 0
        var event: Int = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    depth++
                }

                XmlPullParser.END_TAG -> {
                    depth--
                    if (depth == 0) return
                }
            }
            event = nextEvent(parser)
        }
    }

    private companion object {
        private const val RELAXED_FEATURE = "http://xmlpull.org/v1/doc/features.html#relaxed"
        private const val PODCAST_NS = "https://podcastindex.org/namespace/1.0"
        private const val REPLACEMENT_CHAR = '�'
        private const val XML_NS = "http://www.w3.org/XML/1998/namespace"
        private const val XML_BASE_NAME = "base"
        private const val FUNDING_TITLE_MAX_CHARS = 128
        private const val SHORT_DETAIL_CHARS = 64
        private const val BANNER_ASPECT = "16/9"
        private const val SQUARE_ASPECT = "1/1"
        private const val UNDETERMINED_LANGUAGE = "und"
        private const val SY_FREQUENCY_DEFAULT = 1

        /** `scheme:` — anything with a URI scheme is already absolute (RFC 3986 §4.3). */
        private val ABSOLUTE_URI = Regex("""^[A-Za-z][A-Za-z0-9+.\-]*:""")

        private const val HTTP_PREFIX = "http://"
        private const val HTTPS_PREFIX = "https://"

        /** The characters that can end a URI authority (`/` path, `?` query, `#` fragment). */
        private val URI_DELIMS = charArrayOf('/', '?', '#')

        /** `scheme://authority/path?query` of the base URL; a fragment is excluded before matching. */
        private val BASE_URI =
            Regex("""^([A-Za-z][A-Za-z0-9+.\-]*)://([^/?#]*)([^?#]*)(?:\?([^#]*))?""")

        /** `sy:updatePeriod` → minutes (03 Field mapping). */
        private val UPDATE_PERIOD_MINUTES =
            mapOf(
                "hourly" to 60,
                "daily" to 1440,
                "weekly" to 10080,
                "monthly" to 43200,
                "yearly" to 525600,
            )

        private val AUDIO_VIDEO_MEDIA = setOf("audio", "video")

        /** The precedence of 03 Artwork candidates (episode art uses the first three of its own list). */
        private val ARTWORK_RANK =
            mapOf(
                ArtworkSource.ITUNES_IMAGE to 0,
                ArtworkSource.PODCAST_IMAGE to 1,
                ArtworkSource.PODCAST_IMAGES to 2,
                ArtworkSource.MEDIA_THUMBNAIL to 3,
                ArtworkSource.MEDIA_CONTENT to 4,
                ArtworkSource.RSS_IMAGE to 5,
                ArtworkSource.ATOM_LOGO to 6,
                ArtworkSource.ATOM_ICON to 7,
                ArtworkSource.GOOGLEPLAY_IMAGE to 8,
            )

        /** Channel element keys that map to the plain RSS channel names (RSS 1.0 uses its own URI). */
        private val CHANNEL_TITLE_KEYS = setOf(Namespaces.Key.RSS, Namespaces.Key.RSS1)
        private val CHANNEL_DESCRIPTION_KEYS = setOf(Namespaces.Key.RSS, Namespaces.Key.RSS1)
        private val CHANNEL_LINK_KEYS = setOf(Namespaces.Key.RSS, Namespaces.Key.RSS1)
        private val CHANNEL_IMAGE_KEYS = setOf(Namespaces.Key.RSS, Namespaces.Key.RSS1)
        private val DESCRIPTION_PRECEDENCE =
            listOf(
                DescriptionSlot.CONTENT_ENCODED,
                DescriptionSlot.DESCRIPTION,
                DescriptionSlot.ITUNES_SUMMARY,
                DescriptionSlot.ATOM_CONTENT,
                DescriptionSlot.ATOM_SUMMARY,
                DescriptionSlot.MEDIA_DESCRIPTION,
            )
    }
}

/** Whether two charset names denote the same charset (`utf-16` vs `UTF-16`, `utf8` vs `UTF-8`). */
private fun sameCharset(
    a: String,
    b: String?,
): Boolean {
    if (b == null) return false
    val ca = runCatching { Charset.forName(a) }.getOrNull()
    val cb = runCatching { Charset.forName(b) }.getOrNull()
    if (ca != null && cb != null) return ca == cb
    return a.equals(b, ignoreCase = true)
}

private fun firstNonBlank(vararg values: String?): String? = values.firstOrNull { !it.isNullOrBlank() }

/** Drops ASCII whitespace before the first byte of markup (see [XmlPullFeedParser.parse] step 3). */
private fun ByteArray.withoutLeadingWhitespace(): ByteArray {
    var i = 0
    while (i < size &&
        (
            this[i] == ' '.code.toByte() || this[i] == '\t'.code.toByte() || this[i] == '\r'.code.toByte() ||
                this[i] == '\n'.code.toByte()
        )
    ) {
        i++
    }
    return if (i == 0) this else copyOfRange(i, size)
}
