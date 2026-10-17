// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.youtube.api

import ch.lkmc.neutrodyne.core.common.Nfc
import dev.zacsweers.metro.Inject

/**
 * Turns a user-supplied string into a [YtRef] following 04's Input grammar. Pure, synchronous and
 * thread-safe: no network, no resolution and no extraction (that is M8/M9). The result is null when
 * the input is not a subscribable YouTube form; [Query] is only ever produced by [classifyOrQuery].
 */
@Inject
class YouTubeUrlClassifier {
    /**
     * Classifies [input]. Returns null when it is not a YouTube input we can act on. Callers that
     * need to distinguish "looks like YouTube but is not a channel or video link" from "not
     * YouTube at all" use [isYouTubeHost].
     */
    fun classify(input: String): YtRef? {
        val text = stripWrappers(input.trim())
        if (text.isEmpty()) return null

        val url = parseUrl(prependSchemeIfHost(text))
        if (url == null) return bareForm(text)
        if (url.host !in YOUTUBE_HOSTS) return null
        return classifyUrl(url)
    }

    /** [classify], falling back to a channel-search [YtRef.Query] for the YouTube search field. */
    fun classifyOrQuery(input: String): YtRef = classify(input) ?: YtRef.Query(input.trim())

    /** True when [input] points at a YouTube host, whatever the path (03's "not a channel or video link"). */
    fun isYouTubeHost(input: String): Boolean {
        val text = stripWrappers(input.trim())
        if (text.isEmpty()) return false
        val url = parseUrl(prependSchemeIfHost(text)) ?: return false
        return url.host in YOUTUBE_HOSTS
    }
}

private val YOUTUBE_HOSTS =
    setOf(
        "youtube.com",
        "www.youtube.com",
        "m.youtube.com",
        "music.youtube.com",
        "youtu.be",
        "youtube-nocookie.com",
        "www.youtube-nocookie.com",
    )

private const val SHORT_LINK_HOST = "youtu.be"
private const val FEED_PATH_SEGMENT = "feeds"
private const val FEED_FILE_SEGMENT = "videos.xml"
private val VIDEO_PATH_SEGMENTS = setOf("shorts", "live", "embed", "v", "e")
private val LEGACY_PATH_SEGMENTS = setOf("c", "user")

/** The length cap 04 applies to handles, custom names and the legacy `c/`/`user/`/`user=` names. */
private const val MAX_NAME_LENGTH = 100

/** Bare `@handle` (04 step 2); also re-checked after percent-decoding, so a decoded space is rejected. */
private val HANDLE = Regex("^@[^\\s/?#]{1,$MAX_NAME_LENGTH}$")

/** A bare custom URL: a single path segment of letters, digits, dots, underscores and hyphens. */
private val CUSTOM_NAME = Regex("^[A-Za-z0-9_.-]{1,$MAX_NAME_LENGTH}$")

private val RESERVED =
    setOf(
        "watch",
        "shorts",
        "live",
        "playlist",
        "playlists",
        "feeds",
        "feed",
        "results",
        "embed",
        "channel",
        "c",
        "user",
        "v",
        "e",
        "hashtag",
        "post",
        "premium",
        "account",
        "gaming",
        "music",
        "kids",
        "about",
        "redirect",
        "attribution_link",
        "signin",
        "upload",
        "studio",
        "oembed",
        "youtubei",
        "api",
        "t",
        "s",
        "browse",
        "podcasts",
        "trending",
        "downloads",
        "logout",
    )

/** Query parameters dropped before matching (04 step 4); `utm_*` is dropped by prefix. */
private val TRACKING_PARAMS = setOf("si", "pp", "feature", "app", "ab_channel", "t", "start", "index")

private const val FEED_PREFIX = "feed:"
private const val VIEW_SOURCE_PREFIX = "view-source:"

/**
 * Removes leading `feed:`/`view-source:` wrappers (they stack, e.g. `feed:view-source:`) and prepends
 * `https://` to a scheme-less input that starts with a YouTube host, so "youtube.com/@x" classifies
 * like the full URL. Returns the text unchanged when it is not on a YouTube host.
 */
private fun prependSchemeIfHost(value: String): String =
    if (hostFromLeadingToken(value) in YOUTUBE_HOSTS) "https://$value" else value

private fun stripWrappers(value: String): String {
    var text = value
    while (true) {
        text =
            when {
                text.startsWith(FEED_PREFIX, ignoreCase = true) -> text.substring(FEED_PREFIX.length)
                text.startsWith(VIEW_SOURCE_PREFIX, ignoreCase = true) -> text.substring(VIEW_SOURCE_PREFIX.length)
                else -> return text
            }
    }
}

/** The lowercased host of a scheme-less `host[:port]/…` token, or "" when the first token is not a host. */
private fun hostFromLeadingToken(text: String): String {
    val authority =
        text
            .substringBefore('/')
            .substringBefore('\\')
            .substringBefore('?')
            .substringBefore('#')
    // A leading token containing '@' is userinfo, not a host: spoofing stays rejected.
    if (authority.contains('@')) return ""
    return authority.substringBefore(':').lowercase()
}

private data class ParsedUrl(
    val host: String,
    val path: String,
    val query: Map<String, String>,
)

/** Splits an `http(s)://` URL. Returns null for any other scheme or a missing authority. */
private fun parseUrl(url: String): ParsedUrl? {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd <= 0) return null
    val scheme = url.substring(0, schemeEnd).lowercase()
    if (scheme != "http" && scheme != "https") return null

    val remainder = url.substring(schemeEnd + 3)
    val authorityEnd =
        remainder.indexOfAny(charArrayOf('/', '?', '#', '\\')).let { if (it < 0) remainder.length else it }
    val host =
        remainder
            .substring(0, authorityEnd)
            .substringAfterLast('@')
            .substringBefore(':')
            .lowercase()
    if (host.isEmpty()) return null

    val tail = remainder.substring(authorityEnd)
    val withoutFragment = tail.substringBefore('#')
    return ParsedUrl(host, withoutFragment.substringBefore('?'), parseQuery(withoutFragment.substringAfter('?', "")))
}

private fun parseQuery(text: String): Map<String, String> {
    if (text.isEmpty()) return emptyMap()

    val params = LinkedHashMap<String, String>()
    for (pair in text.split('&')) {
        if (pair.isEmpty()) continue
        val key = percentDecode(pair.substringBefore('='))
        if (key.isEmpty() || key in TRACKING_PARAMS || key.startsWith("utm_") || params.containsKey(key)) continue
        params[key] = percentDecode(pair.substringAfter('=', ""))
    }
    return params
}

private fun bareForm(text: String): YtRef? =
    handleFromRaw(text) ?: if (YouTubeIds.CHANNEL.matches(text)) YtRef.Channel(text) else null

private fun handleFromRaw(raw: String): YtRef.Handle? = handleFromDecoded(percentDecode(raw))

private fun handleFromDecoded(value: String): YtRef.Handle? =
    if (HANDLE.matches(value)) YtRef.Handle(Nfc.normalize(value)) else null

private fun classifyUrl(url: ParsedUrl): YtRef? {
    // Path segments are percent-decoded and empty ones (leading/trailing slashes, doubles) are dropped.
    val segments =
        url.path
            .split('/')
            .map(::percentDecode)
            .filter { it.isNotEmpty() }
    val first = segments.firstOrNull() ?: return null

    if (first == "channel") {
        val id = segments.getOrNull(1)
        return id?.takeIf(YouTubeIds.CHANNEL::matches)?.let { YtRef.Channel(it) }
    }

    if (first == FEED_PATH_SEGMENT && segments.size == 2 && segments[1] == FEED_FILE_SEGMENT) {
        return classifyFeedQuery(url.query)
    }

    if (first == "playlist") return url.query["list"]?.takeIf { it.isNotEmpty() }?.let(::playlistOrChannel)

    if (first == "watch") {
        val id = url.query["v"]
        return id?.takeIf(YouTubeIds.VIDEO::matches)?.let { YtRef.Video(it) }
    }

    if (first in VIDEO_PATH_SEGMENTS) {
        val id = segments.getOrNull(1)
        return id?.takeIf(YouTubeIds.VIDEO::matches)?.let { YtRef.Video(it) }
    }

    if (first.startsWith('@')) return handleFromDecoded(first)

    if (url.host == SHORT_LINK_HOST) {
        val id = segments.first()
        return id.takeIf(YouTubeIds.VIDEO::matches)?.let { YtRef.Video(it) }
    }

    if (first in LEGACY_PATH_SEGMENTS) {
        val name = segments.getOrNull(1)?.takeIf(::isValidLegacyName)
        return name?.let { YtRef.LegacyPath("$first/$it") }
    }

    if (segments.size == 1 && CUSTOM_NAME.matches(first) && first !in RESERVED) return YtRef.LegacyPath(first)

    return null
}

private fun classifyFeedQuery(query: Map<String, String>): YtRef? {
    query["channel_id"]?.takeIf(YouTubeIds.CHANNEL::matches)?.let { return YtRef.Channel(it) }
    query["playlist_id"]?.takeIf { it.isNotEmpty() }?.let { return playlistOrChannel(it) }
    query["user"]?.takeIf(::isValidLegacyName)?.let { return YtRef.LegacyPath("user/$it") }
    return null
}

/** A legacy `c/`/`user/`/`user=` name is non-empty and within the shared cap. */
private fun isValidLegacyName(name: String): Boolean = name.isNotEmpty() && name.length <= MAX_NAME_LENGTH

private fun playlistOrChannel(playlistId: String): YtRef {
    val uploads = YouTubeIds.uploadsToChannel(playlistId)
    return if (uploads == null) YtRef.Playlist(playlistId) else YtRef.Channel(uploads.first, uploads.second)
}

/**
 * Decodes every valid `%XX` escape; invalid escapes stay literal and `+` is never a space (the same
 * lenient rule 03's URL normalizer uses). Malformed UTF-8 decodes to U+FFFD instead of throwing.
 */
private fun percentDecode(text: String): String {
    if (!text.contains('%')) return text

    val bytes = ArrayList<Byte>(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        val high = hexValue(text.getOrNull(i + 1))
        val low = hexValue(text.getOrNull(i + 2))
        if (c == '%' && high >= 0 && low >= 0) {
            bytes.add(((high shl 4) or low).toByte())
            i += 3
        } else {
            for (byte in c.toString().encodeToByteArray()) bytes.add(byte)
            i++
        }
    }
    return bytes.toByteArray().decodeToString()
}

private fun hexValue(c: Char?): Int {
    if (c == null) return -1
    return when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
