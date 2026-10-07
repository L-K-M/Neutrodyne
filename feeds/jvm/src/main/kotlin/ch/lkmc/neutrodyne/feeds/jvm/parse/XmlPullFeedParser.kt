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
 * model. Runs with either platform parser through [PullParserFactory]; never throws for malformed input.
 */
public class XmlPullFeedParser(
    private val factory: PullParserFactory,
    private val limits: ParseLimits = ParseLimits(),
) : FeedParser {
    /** One pass over the document. */
    private inner class Pass(
        val feed: ParsedFeed,
        val textChars: Long,
        val replacementChars: Long,
    )

    private inner class Counters {
        var textChars = 0L
        var replacementChars = 0L
    }

    private val counters = Counters()
    private var warnings = mutableListOf<ParseWarning>()

    /**
     * Parses one document (03 Parser). The document is read through [open] once and buffered, so the
     * charset re-parse (step 5) compares two passes over the same bytes instead of calling [open] twice.
     * [httpCharset] is consulted only by that re-parse heuristic; [baseUrl] resolves relative URLs, and
     * `xml:base` in the document wins when present.
     */
    override fun parse(
        open: () -> okio.Source,
        httpCharset: String?,
        baseUrl: String,
    ): ParseResult {
        val bytes =
            try {
                open().buffer().readByteArray()
            } catch (e: Exception) {
                return ParseResult.Failed(ParseFailure.MALFORMED, "unreadable source: ${e.javaClass.simpleName}")
            }

        // Step 1: prolog guard, before any parser sees the document.
        if (PrologGuard.isHostile(bytes, limits.prologScanBytes)) {
            return ParseResult.Failed(ParseFailure.HOSTILE, "ENTITY declaration in the prolog")
        }

        // Step 3: first pass with encoding sniffing (never a Reader, never the HTTP charset).
        val first = runPass(bytes, charsetOverride = null, baseUrl)
        if (first is ParseResult) return first

        val stats = first as Pass

        // Step 5: re-parse heuristic. More than 0.5 % replacement characters triggers one second pass
        // with the HTTP charset when it differs from the detected one, else windows-1252; the result
        // with fewer replacement characters wins.
        val replacementRatio =
            if (stats.textChars == 0L) 0.0 else stats.replacementChars.toDouble() / stats.textChars
        if (replacementRatio > REPLACEMENT_RATIO_THRESHOLD) {
            val detected = detectedEncoding
            val secondCharset =
                httpCharset?.trim()?.takeIf { it.isNotEmpty() && !it.equals(detected, ignoreCase = true) }
                    ?: WINDOWS_1252.takeIf { !it.equals(detected, ignoreCase = true) }
            if (secondCharset != null) {
                val second = runPass(bytes, secondCharset, baseUrl)
                if (second is Pass && second.replacementChars < stats.replacementChars) {
                    val reparseWarning =
                        ParseWarning(WarningCode.CHARSET_REPARSED, detail = "re-parsed as $secondCharset")
                    return ParseResult.Ok(second.feed.copy(warnings = second.feed.warnings + reparseWarning))
                }
            }
        }

        return ParseResult.Ok(stats.feed)
    }

    private var detectedEncoding: String? = null

    /** One parse pass: `ParseResult` when it failed structurally, otherwise the feed with its stats. */
    private fun runPass(
        bytes: ByteArray,
        charsetOverride: String?,
        baseUrl: String,
    ): Any {
        counters.textChars = 0
        counters.replacementChars = 0
        warnings = mutableListOf()
        itemsTruncated = false
        undeclaredPrefixesSeen.clear()
        detectedEncoding = null

        val parser = factory.create()
        return try {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            runCatching { parser.setFeature(RELAXED_FEATURE, true) }

            if (charsetOverride == null) {
                parser.setInput(ByteArrayInputStream(bytes), null)
                detectedEncoding = parser.inputEncoding
            } else {
                val charset =
                    runCatching { Charset.forName(charsetOverride) }.getOrNull()
                        ?: return ParseResult.Failed(ParseFailure.MALFORMED, "unknown charset $charsetOverride")
                // setInput(InputStream, encoding), never a Reader: the Reader overloads differ between
                // kxml2's bundled xmlpull API and android.jar, this one exists on both.
                parser.setInput(ByteArrayInputStream(bytes), charset.name())
                detectedEncoding = charset.name()
            }

            // After setInput (kxml2 requires that order): predefine all HTML 4 named entities.
            for ((name, value) in HtmlEntities.ALL) {
                parser.defineEntityReplacementText(name, value)
            }

            val feed =
                parseDocument(parser, baseUrl)
                    ?: return ParseResult.Failed(ParseFailure.NOT_A_FEED, "root element was not rss, feed or RDF")
            Pass(feed, counters.textChars, counters.replacementChars)
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

            rootName.equals("feed", ignoreCase = true) && (rootKey == null || rootKey == Namespaces.Key.ATOM) -> {
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
                    readItem(parser, channel, items)
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

                    parser.name.equals("item", ignoreCase = true) -> {
                        readItem(parser, channel, items)
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

    /** Reads one item-ish element (RSS item or RDF item); assumes the parser sits on its START_TAG. */
    private fun readItem(
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

    private var itemsTruncated = false

    /** `xml:base` of the element the parser sits on, resolved against [current]; null when absent. */
    private fun baseOf(
        parser: XmlPullParser,
        current: String,
    ): String = xmlBase(parser)?.let { resolveUrl(it, current) ?: current } ?: current

    // ---------------------------------------------------------------------------
    // Channel elements
    // ---------------------------------------------------------------------------

    /** Mutable channel state, applied to the feed at the end of the walk. */
    private inner class ChannelBuilder(
        var base: String,
    ) {
        var title: String? = null
        var itunesTitle: String? = null
        var itunesAuthor: String? = null
        var dcCreator: String? = null
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
                title = firstNonBlank(title, itunesTitle),
                author = firstNonBlank(itunesAuthor, dcCreator, ownerName, managingEditor),
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
        val key = Namespaces.elementKey(parser)
        val name = parser.name

        when {
            name.equals("title", true) && key in CHANNEL_TITLE_KEYS -> {
                channel.title = plainText(parser)
            }

            name.equals("description", true) && key in CHANNEL_DESCRIPTION_KEYS -> {
                channel.description = htmlText(parser)
            }

            name.equals("link", true) && key in CHANNEL_LINK_KEYS -> {
                channel.link = urlOrNull(plainText(parser), channel.base)
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
                channel.newFeedUrl = urlOrNull(plainText(parser), channel.base)
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
                addFunding(parser, channel.funding)
            }

            "person" -> {
                channel.persons.add(parsePerson(parser))
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
                channel.title = plainText(parser)
            }

            "subtitle" -> {
                channel.atomSubtitle = htmlText(parser)
            }

            "author" -> {
                // Atom author/name; the first non-blank one wins (03 Field mapping author chain).
                if (channel.ownerName == null) channel.ownerName = parseAtomAuthorName(parser) else skipElement(parser)
            }

            "link" -> {
                parseAtomLink(parser, channel)
            }

            "logo" -> {
                addArtwork(channel.artwork, urlOrNull(plainText(parser), channel.base), ArtworkSource.ATOM_LOGO)
            }

            "icon" -> {
                addArtwork(channel.artwork, urlOrNull(plainText(parser), channel.base), ArtworkSource.ATOM_ICON)
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
                    urlOrNull(attr(parser, "url"), channel.base),
                    ArtworkSource.MEDIA_THUMBNAIL,
                    widthOf(parser),
                )
                skipElement(parser)
            }

            "content" -> {
                parseMediaContentImage(parser, channel)
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
    ) {
        if (attr(parser, "medium")?.equals("image", ignoreCase = true) == true) {
            addArtwork(
                channel.artwork,
                urlOrNull(attr(parser, "url"), channel.base),
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
        var url: String? = null
        var event = nextEvent(parser)
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && parser.depth == depth) break
            if (event == XmlPullParser.START_TAG && parser.name.equals("url", true)) {
                url = plainText(parser)
            }
            event = nextEvent(parser)
        }
        addArtwork(channel.artwork, urlOrNull(url, channel.base), ArtworkSource.RSS_IMAGE)
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

    /** Nested `itunes:category` elements build a path (`["Society & Culture","Documentary"]`). */
    private fun parseItunesCategory(
        parser: XmlPullParser,
        channel: ChannelBuilder,
    ) {
        val path = mutableListOf<String>()
        val topDepth = parser.depth
        var event: Int = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (Namespaces.elementKey(parser) == Namespaces.Key.ITUNES && parser.name == "category") {
                        attr(parser, "text")?.trim()?.takeIf { it.isNotEmpty() }?.let { path.add(it) }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.depth <= topDepth) break
                }
            }
            event = nextEvent(parser)
        }
        if (path.isNotEmpty()) channel.categories.add(path.toList())
    }

    private fun parseAtomLink(
        parser: XmlPullParser,
        channel: ChannelBuilder,
    ) {
        val rel = attr(parser, "rel")?.trim()
        val href = urlOrNull(attr(parser, "href"), channel.base)
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

    private fun parseAtomAuthorName(parser: XmlPullParser): String? {
        val depth = parser.depth
        var name: String? = null
        var event = nextEvent(parser)
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && parser.depth == depth) break
            if (event == XmlPullParser.START_TAG && parser.name == "name" && name == null) {
                name = plainText(parser)
            }
            event = nextEvent(parser)
        }
        return name?.trim()?.takeIf { it.isNotEmpty() }
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
        var atomEnclosure: Enclosure? = null
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
    }

    private fun parseItemElement(
        parser: XmlPullParser,
        item: ItemBuilder,
    ) {
        trackPrefixFallback(parser)
        val key = Namespaces.elementKey(parser)
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
                item.slotContentEncoded = htmlText(parser)
            }

            key == Namespaces.Key.ATOM -> {
                parseAtomItemElement(parser, item, name)
            }

            key == Namespaces.Key.DC && name == "date" -> {
                readDate(parser)?.let { (ms, raw) ->
                    item.dcDate = ms
                    item.dcDateRaw = raw
                }
            }

            key == Namespaces.Key.PSC && name == "chapters" -> {
                parsePscChapters(parser, item)
            }

            key == Namespaces.Key.YT && name == "videoId" -> {
                item.externalMediaId = plainText(parser).trim()
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
                item.guid = plainText(parser).trim().takeIf { it.isNotEmpty() }
            }

            name.equals("title", true) -> {
                item.title = plainText(parser)
            }

            name.equals("description", true) -> {
                item.slotDescription = htmlText(parser)
            }

            name.equals("link", true) -> {
                item.link = urlOrNull(plainText(parser), item.base)
            }

            name.equals("pubDate", true) -> {
                readDate(parser)?.let { (ms, raw) ->
                    item.pubDate = ms
                    item.rawPubDate = raw
                }
            }

            name.equals("enclosure", true) -> {
                addEnclosure(item, attr(parser, "url"), attr(parser, "type"), attr(parser, "length"))
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
            "title" -> item.itunesTitle = plainText(parser)
            "duration" -> item.durationMs = readDuration(plainText(parser))
            "summary" -> item.slotItunesSummary = htmlText(parser)
            "season" -> item.itunesSeason = plainText(parser).trim().toIntOrNull()
            "episode" -> item.itunesEpisode = plainText(parser).trim().takeIf { it.isNotEmpty() }
            "episodeType" -> item.episodeType = episodeTypeOf(plainText(parser))
            "explicit" -> item.explicit = explicitOf(plainText(parser))
            "image" -> addArtwork(item.artwork, artworkUrlFromElement(parser, item.base), ArtworkSource.ITUNES_IMAGE)
            else -> skipElement(parser)
        }
    }

    private fun parsePodcastItemElement(
        parser: XmlPullParser,
        item: ItemBuilder,
        name: String,
    ) {
        when (name) {
            "season" -> {
                item.season = plainText(parser).trim().toIntOrNull()
                item.seasonName = attr(parser, "name")?.trim()?.takeIf { it.isNotEmpty() }
            }

            "episode" -> {
                item.episodeNumber = plainText(parser).trim().takeIf { it.isNotEmpty() }
                item.episodeDisplay = attr(parser, "display")?.trim()?.takeIf { it.isNotEmpty() }
            }

            "transcript" -> {
                val url = urlOrNull(attr(parser, "url"), item.base)
                if (url != null) {
                    item.transcripts.add(
                        TranscriptRef(
                            url,
                            attr(parser, "type")?.trim()?.takeIf { it.isNotEmpty() },
                            attr(parser, "language")?.trim()?.takeIf { it.isNotEmpty() },
                            attr(parser, "rel")?.trim()?.takeIf { it.isNotEmpty() },
                        ),
                    )
                } else {
                    warn(WarningCode.BAD_URL, item.index, "transcript")
                }
                skipElement(parser)
            }

            "chapters" -> {
                item.chaptersUrl = urlOrNull(attr(parser, "url"), item.base)
                item.chaptersType = attr(parser, "type")?.trim()?.takeIf { it.isNotEmpty() }
                if (item.chaptersUrl == null) warn(WarningCode.BAD_URL, item.index, "chapters")
                skipElement(parser)
            }

            "person" -> {
                // Item-level persons REPLACE the channel list (Podcasting 2.0 spec).
                (item.persons ?: mutableListOf<Person>().also { item.persons = it }).add(parsePerson(parser))
            }

            "funding" -> {
                addFunding(parser, item.funding)
            }

            "alternateEnclosure" -> {
                item.alternateEnclosures.add(parseAlternateEnclosure(parser, item))
            }

            "image" -> {
                addArtwork(item.artwork, artworkUrlFromElement(parser, item.base), ArtworkSource.PODCAST_IMAGE)
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
                if (item.guid == null) item.guid = plainText(parser).trim().takeIf { it.isNotEmpty() }
            }

            "link" -> {
                val rel = attr(parser, "rel")?.trim()
                when (rel) {
                    "enclosure" -> item.atomEnclosure = enclosureOf(parser, item.base)
                    null, "", "alternate" -> item.atomAlternateLink = urlOrNull(attr(parser, "href"), item.base)
                }
                skipElement(parser)
            }

            "published" -> {
                readDate(parser)?.let { (ms, raw) ->
                    item.atomPublished = ms
                    item.atomPublishedRaw = raw
                }
            }

            "updated" -> {
                readDate(parser)?.let { (ms, raw) ->
                    item.atomUpdated = ms
                    item.atomUpdatedRaw = raw
                }
            }

            "summary" -> {
                if (item.slotAtomSummary == null) {
                    val (text, isHtml) = atomTextConstruct(parser)
                    item.slotAtomSummary = text
                    item.slotAtomSummaryIsHtml = isHtml
                }
            }

            "content" -> {
                if (item.slotAtomContent == null) {
                    val (text, isHtml) = atomTextConstruct(parser)
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
                item.mediaTitle = plainText(parser)
            }

            "description" -> {
                item.slotMediaDescription = plainText(parser)
            }

            "content" -> {
                parseMediaContent(parser, item)
            }

            "thumbnail" -> {
                addArtwork(
                    item.artwork,
                    urlOrNull(attr(parser, "url"), item.base),
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

    private fun parseMediaContent(
        parser: XmlPullParser,
        item: ItemBuilder,
    ) {
        val url = attr(parser, "url")
        val type = attr(parser, "type")
        val medium = attr(parser, "medium")?.lowercase()
        val isMedia =
            medium in AUDIO_VIDEO_MEDIA ||
                type?.startsWith("audio/") == true ||
                type?.startsWith("video/") == true
        if (url != null && isMedia) {
            val enclosure =
                Enclosure(url, type?.trim()?.takeIf { it.isNotEmpty() }, positiveLong(attr(parser, "fileSize")))
            // isDefault content comes first within its element (03 Field mapping).
            if (attr(parser, "isDefault")?.trim() ==
                "true"
            ) {
                item.mediaContent.add(0, enclosure)
            } else {
                item.mediaContent.add(enclosure)
            }
            if (item.mediaDurationMs == null) {
                item.mediaDurationMs = readDuration(attr(parser, "duration"))
            }
        }
        skipElement(parser)
    }

    /** `media:group` children: `media:content` and `media:thumbnail` (also the channel variant). */
    private fun parseMediaGroup(
        parser: XmlPullParser,
        channel: ChannelBuilder?,
        item: ItemBuilder?,
    ) {
        val depth = parser.depth
        var event = nextEvent(parser)
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && parser.depth == depth) break
            if (event == XmlPullParser.START_TAG && Namespaces.elementKey(parser) == Namespaces.Key.MEDIA) {
                val base = channel?.base ?: item!!.base
                when (parser.name) {
                    "content" -> {
                        if (item != null) parseMediaContent(parser, item) else parseMediaContentImage(parser, channel!!)
                    }

                    "thumbnail" -> {
                        addArtwork(
                            item?.artwork ?: channel!!.artwork,
                            urlOrNull(attr(parser, "url"), base),
                            ArtworkSource.MEDIA_THUMBNAIL,
                            widthOf(parser),
                        )
                        skipElement(parser)
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

    private fun parsePscChapters(
        parser: XmlPullParser,
        item: ItemBuilder,
    ) {
        val depth = parser.depth
        var event = nextEvent(parser)
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && parser.depth == depth) break
            if (event == XmlPullParser.START_TAG &&
                Namespaces.elementKey(parser) == Namespaces.Key.PSC &&
                parser.name == "chapter"
            ) {
                val startMs = Durations.parseMs(attr(parser, "start").orEmpty())
                if (startMs != null) {
                    item.inlineChapters.add(
                        InlineChapter(
                            startMs = startMs,
                            title = attr(parser, "title").orEmpty(),
                            href = attr(parser, "href")?.trim()?.takeIf { it.isNotEmpty() },
                            image = attr(parser, "image")?.trim()?.takeIf { it.isNotEmpty() },
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
                        val uri = urlOrNull(attr(parser, "uri"), item.base)
                        if (uri != null) {
                            sources.add(
                                AlternateEnclosureSource(
                                    uri,
                                    attr(parser, "contentType")?.trim()?.takeIf { it.isNotEmpty() },
                                ),
                            )
                        } else {
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

    private fun parsePerson(parser: XmlPullParser): Person {
        val name = plainText(parser).trim()
        return Person(
            name = name,
            role = attr(parser, "role")?.trim()?.takeIf { it.isNotEmpty() } ?: Person.ROLE_HOST,
            group = attr(parser, "group")?.trim()?.takeIf { it.isNotEmpty() } ?: Person.GROUP_CAST,
            img = attr(parser, "img")?.trim()?.takeIf { it.isNotEmpty() },
            href = attr(parser, "href")?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun addFunding(
        parser: XmlPullParser,
        target: MutableList<Funding>,
    ) {
        val url = attr(parser, "url")?.trim()?.takeIf { it.isNotEmpty() }
        val title = plainText(parser).trim().take(FUNDING_TITLE_MAX_CHARS)
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
        val url = urlOrNull(attr(parser, "url"), channel.base)
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

    /** The deprecated `podcast:images` srcset: one artwork candidate, the largest entry wins. */
    private fun parsePodcastImagesSrcset(
        parser: XmlPullParser,
        channel: ChannelBuilder,
    ) {
        val srcset = attr(parser, "srcset").orEmpty()
        var bestUrl: String? = null
        var bestWidth = -1
        for (entry in srcset.split(',')) {
            val parts = entry.trim().split(Regex("\\s+"))
            if (parts.size < 2) continue
            val width = srcsetWidth(parts[1]) ?: continue
            if (width > bestWidth) {
                bestWidth = width
                bestUrl = parts[0]
            }
        }
        if (bestUrl != null) {
            addArtwork(channel.artwork, urlOrNull(bestUrl, channel.base), ArtworkSource.PODCAST_IMAGES, bestWidth)
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
        val key = Namespaces.elementKey(parser)
        val name = parser.name

        when {
            key != Namespaces.Key.ATOM && key != Namespaces.Key.MEDIA &&
                key != Namespaces.Key.YT && key != Namespaces.Key.PODCAST -> {
                skipElement(parser)
            }

            name == "id" -> {
                item.guid = plainText(parser).trim().takeIf { it.isNotEmpty() }
            }

            name == "title" -> {
                item.title = plainText(parser)
            }

            name == "published" -> {
                readDate(parser)?.let { (ms, raw) ->
                    item.atomPublished = ms
                    item.atomPublishedRaw = raw
                }
            }

            name == "updated" -> {
                readDate(parser)?.let { (ms, raw) ->
                    item.atomUpdated = ms
                    item.atomUpdatedRaw = raw
                }
            }

            name == "summary" -> {
                if (item.slotAtomSummary == null) {
                    val (text, isHtml) = atomTextConstruct(parser)
                    item.slotAtomSummary = text
                    item.slotAtomSummaryIsHtml = isHtml
                }
            }

            name == "content" -> {
                if (item.slotAtomContent == null) {
                    val (text, isHtml) = atomTextConstruct(parser)
                    item.slotAtomContent = text
                    item.slotAtomContentIsHtml = isHtml
                }
            }

            name == "link" -> {
                val rel = attr(parser, "rel")?.trim()
                when (rel) {
                    "enclosure" -> item.atomEnclosure = enclosureOf(parser, item.base)
                    null, "", "alternate" -> item.atomAlternateLink = urlOrNull(attr(parser, "href"), item.base)
                }
                skipElement(parser)
            }

            name == "author" -> {
                skipElement(parser)
            }

            // item-level authors are not stored in v1
            key == Namespaces.Key.MEDIA -> {
                parseMediaItemElement(parser, item, name)
            }

            key == Namespaces.Key.YT && name == "videoId" -> {
                item.externalMediaId = plainText(parser).trim()
            }

            key == Namespaces.Key.PODCAST -> {
                parsePodcastItemElement(parser, item, name)
            }

            else -> {
                skipElement(parser)
            }
        }
    }

    /** Atom text constructs: `type="text"` is plain, `html` and `xhtml` are HTML (03 step 6). */
    private fun atomTextConstruct(parser: XmlPullParser): Pair<String?, Boolean> {
        val type = attr(parser, "type")?.trim()?.lowercase()
        return when (type) {
            "html" -> htmlText(parser) to true
            "xhtml" -> InnerXml.collect(parser, ::countText, limits.maxDepth).trim().takeIf { it.isNotEmpty() } to true
            else -> plainText(parser).takeIf { it.isNotEmpty() } to false
        }
    }

    // ---------------------------------------------------------------------------
    // Episode assembly (03 Field mapping)
    // ---------------------------------------------------------------------------

    private fun buildEpisode(item: ItemBuilder): ParsedEpisode {
        // Enclosure order: explicit enclosures, the Atom enclosure, then media:content entries.
        val rawEnclosures = item.rssEnclosures + listOfNotNull(item.atomEnclosure) + item.mediaContent
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
    private fun readDate(parser: XmlPullParser): Pair<Long?, String> {
        val raw = plainText(parser)
        val parsed = FeedDates.parse(raw)
        if (parsed == null) warn(WarningCode.UNKNOWN_DATE, null, raw.take(SHORT_DETAIL_CHARS))
        return parsed to raw
    }

    /** Reads an `itunes:duration` value; null plus `BAD_DURATION` on failure. */
    private fun readDuration(raw: String?): Long? {
        if (raw == null) return null
        val parsed = Durations.parseMs(raw)
        if (parsed == null) warn(WarningCode.BAD_DURATION, null, raw.trim().take(SHORT_DETAIL_CHARS))
        return parsed
    }

    /** Plain element text, trimmed; child markup is skipped, entity text included (03 steps 2–4). */
    private fun plainText(parser: XmlPullParser): String {
        val out = StringBuilder()
        // Enter the element: the caller leaves the parser on its START_TAG.
        var event = nextEvent(parser)
        while (event != XmlPullParser.END_TAG && event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                    countAndAppend(out, InnerXml.stripRelaxedError(parser.text))
                }

                XmlPullParser.ENTITY_REF -> {
                    parser.text?.let { countAndAppend(out, InnerXml.stripRelaxedError(it)) }
                }

                XmlPullParser.START_TAG -> {
                    skipElement(parser)
                }

                else -> {
                    Unit
                }
            }
            event = nextEvent(parser)
        }
        return truncateIfLong(out.toString().trim())
    }

    /** HTML-bearing element text: child markup is re-serialised, never dropped (03 step 6). */
    private fun htmlText(parser: XmlPullParser): String? {
        val collected = InnerXml.collect(parser, ::countText, limits.maxDepth).trim()
        return truncateIfLong(collected).takeIf { it.isNotEmpty() }
    }

    private fun truncateIfLong(text: String): String {
        if (text.length <= limits.maxTextChars) return text
        warnings.add(ParseWarning(WarningCode.TEXT_TRUNCATED, detail = "${text.length} chars"))
        return text.substring(0, limits.maxTextChars)
    }

    private fun countText(text: String) {
        counters.textChars += text.length
        counters.replacementChars += text.count { it == REPLACEMENT_CHAR }
    }

    private fun countAndAppend(
        out: StringBuilder,
        text: String,
    ) {
        countText(text)
        out.append(text)
    }

    /** Resolves [raw] against [base], then enforces absolute http(s) and the URL-length limit. */
    private fun urlOrNull(
        raw: String?,
        base: String,
    ): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val resolved = resolveUrl(trimmed, base) ?: return null
        if (!resolved.startsWith("http://") && !resolved.startsWith("https://")) return null
        if (resolved.length > limits.maxUrlChars) {
            warnings.add(ParseWarning(WarningCode.BAD_URL, detail = "${resolved.length} chars"))
            return null
        }
        return resolved
    }

    /** Lenient RFC 3986 reference resolution; `xml:base` has been folded into [base] already. */
    private fun resolveUrl(
        raw: String,
        base: String,
    ): String? {
        if (Regex("""^[A-Za-z][A-Za-z0-9+.\-]*://""").containsMatchIn(raw)) return raw

        val schemeMatch = Regex("""^([A-Za-z][A-Za-z0-9+.\-]*)://([^/?]*)([^\?#]*)?""").find(base) ?: return null
        val (scheme, authority, fullPath) = schemeMatch.destructured
        val hasPath = fullPath.isNotEmpty()
        return when {
            raw.startsWith("//") -> {
                "$scheme:$raw"
            }

            raw.startsWith("/") -> {
                "$scheme://$authority$raw"
            }

            raw.startsWith("?") -> {
                "$scheme://$authority$fullPath$raw"
            }

            raw.startsWith("#") -> {
                "$scheme://$authority$fullPath"
            }

            else -> {
                // Relative reference: resolve against the base's directory.
                val dir = if (hasPath) fullPath.substringBeforeLast('/', "/") else "/"
                "$scheme://$authority$dir$raw"
            }
        }
    }

    /**
     * The URL of `itunes:image`/`googleplay:image`/`podcast:image`: the href attribute, or the element
     * text (a common error, 03 Artwork candidates).
     */
    private fun artworkUrlFromElement(
        parser: XmlPullParser,
        base: String,
    ): String? {
        val href = attr(parser, "href")?.trim()?.takeIf { it.isNotEmpty() }
        return href ?: plainText(parser).trim().takeIf { it.isNotEmpty() }
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
        item: ItemBuilder,
        rawUrl: String?,
        type: String?,
        rawLength: String?,
    ) {
        val url = urlOrNull(rawUrl, item.base)
        if (url == null) {
            if (!rawUrl.isNullOrBlank()) warn(WarningCode.BAD_URL, item.index, "enclosure")
            return
        }
        item.rssEnclosures.add(Enclosure(url, type?.trim()?.takeIf { it.isNotEmpty() }, positiveLong(rawLength)))
    }

    private fun enclosureOf(
        parser: XmlPullParser,
        base: String,
    ): Enclosure? {
        val url = urlOrNull(attr(parser, "href"), base) ?: return null
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

    private val undeclaredPrefixesSeen = mutableSetOf<String>()

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

    public companion object {
        /** The desktop binding: XmlPull discovery (kxml2 at run time, 03 Parser). */
        public fun discovered(limits: ParseLimits = ParseLimits()): FeedParser =
            XmlPullFeedParser(PullParserFactory.Discovered, limits)

        private const val RELAXED_FEATURE = "http://xmlpull.org/v1/doc/features.html#relaxed"
        private const val REPLACEMENT_CHAR = '�'
        private const val REPLACEMENT_RATIO_THRESHOLD = 0.005
        private const val WINDOWS_1252 = "windows-1252"
        private const val XML_NS = "http://www.w3.org/XML/1998/namespace"
        private const val XML_BASE_NAME = "base"
        private const val FUNDING_TITLE_MAX_CHARS = 128
        private const val SHORT_DETAIL_CHARS = 64
        private const val BANNER_ASPECT = "16/9"
        private const val SQUARE_ASPECT = "1/1"
        private const val UNDETERMINED_LANGUAGE = "und"
        private const val SY_FREQUENCY_DEFAULT = 1

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

private fun firstNonBlank(vararg values: String?): String? = values.firstOrNull { !it.isNullOrBlank() }
