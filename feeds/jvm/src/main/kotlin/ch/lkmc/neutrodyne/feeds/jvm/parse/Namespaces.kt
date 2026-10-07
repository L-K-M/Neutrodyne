// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.parse

import org.xmlpull.v1.XmlPullParser

/**
 * The namespace registry (03 Namespace registry): `canonical(namespaceUri) ?: prefixFallback[prefix]`,
 * where the prefix fallback covers feeds that forget the `xmlns` declaration (warning
 * `UNDECLARED_PREFIX`).
 */
internal object Namespaces {
    /** Every namespace the parser reads; the parser matches local names against these keys. */
    internal enum class Key {
        RSS,
        ITUNES,
        PODCAST,
        ATOM,
        CONTENT,
        MEDIA,
        DC,
        PSC,
        FH,
        SY,
        YT,
        GOOGLEPLAY,
        RDF,
        RSS1,
    }

    private val canonicalUris: Map<Key, String> =
        mapOf(
            Key.RSS to "",
            Key.ITUNES to "http://www.itunes.com/dtds/podcast-1.0.dtd",
            Key.PODCAST to "https://podcastindex.org/namespace/1.0",
            Key.ATOM to "http://www.w3.org/2005/Atom",
            Key.CONTENT to "http://purl.org/rss/1.0/modules/content/",
            Key.MEDIA to "http://search.yahoo.com/mrss/",
            Key.DC to "http://purl.org/dc/elements/1.1/",
            Key.PSC to "http://podlove.org/simple-chapters",
            Key.FH to "http://purl.org/syndication/history/1.0",
            Key.SY to "http://purl.org/rss/1.0/modules/syndication/",
            Key.YT to "http://www.youtube.com/xml/schemas/2015",
            Key.GOOGLEPLAY to "http://www.google.com/schemas/play-podcasts/1.0",
            Key.RDF to "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
            Key.RSS1 to "http://purl.org/rss/1.0/",
        )

    /**
     * URIs also accepted for their key: case variants, `http`/`https` swaps and trailing-slash drift
     * (03 Namespace registry table), including the Podcasting 2.0 GitHub-alias URI the spec says must be
     * treated as identical to the canonical one.
     */
    private val acceptedUris: Map<Key, List<String>> =
        mapOf(
            Key.ITUNES to
                listOf(
                    "http://www.itunes.com/dtds/podcast-1.0.dtd",
                    "http://www.itunes.com/dtds/podcast-1.0.dtd/",
                    "https://www.itunes.com/dtds/podcast-1.0.dtd",
                    "https://www.itunes.com/dtds/podcast-1.0.dtd/",
                ),
            Key.PODCAST to
                listOf(
                    "https://podcastindex.org/namespace/1.0",
                    "https://podcastindex.org/namespace/1.0/",
                    "http://podcastindex.org/namespace/1.0",
                    "http://podcastindex.org/namespace/1.0/",
                    "https://github.com/podcastindex-org/podcast-namespace/blob/main/docs/1.0.md",
                    "https://github.com/Podcastindex-org/podcast-namespace/blob/main/docs/1.0.md",
                ),
            Key.MEDIA to
                listOf(
                    "http://search.yahoo.com/mrss/",
                    "http://search.yahoo.com/mrss",
                ),
            Key.PSC to
                listOf(
                    "http://podlove.org/simple-chapters",
                    "http://podlove.org/simple-chapters/",
                ),
        )

    /** Prefixes used without their `xmlns` declaration (03 Namespace registry matching rule). */
    private val prefixFallback: Map<String, Key> =
        mapOf(
            "itunes" to Key.ITUNES,
            "podcast" to Key.PODCAST,
            "media" to Key.MEDIA,
            "content" to Key.CONTENT,
            "atom" to Key.ATOM,
            "psc" to Key.PSC,
        )

    /**
     * The registry key of [uri], matched case-insensitively against canonical and accepted forms.
     * The compare is length-aware — an inherited URI is looked up once per element, and lowercasing
     * a giant URI on each call would make parsing quadratic in its length.
     */
    internal fun keyOf(uri: String?): Key? {
        if (uri == null) return null
        if (uri.isEmpty()) return Key.RSS
        for ((key, canonical) in canonicalUris) {
            if (uri.equalFold(canonical)) return key
        }
        for ((key, accepted) in acceptedUris) {
            if (accepted.any { uri.equalFold(it) }) return key
        }
        return null
    }

    /** ASCII-style case-insensitive equality: the length check rejects long candidates up front. */
    private fun String.equalFold(other: String): Boolean =
        length == other.length && regionMatches(0, other, 0, length, ignoreCase = true)

    /**
     * Resolves the element the parser sits on: the registry key of its namespace URI, or the prefix
     * fallback when the URI is missing (kxml2's relaxed mode keeps the prefix on an undeclared one) or
     * unrecognized. An empty URI is the RSS namespace only for unprefixed names.
     */
    internal fun elementKey(parser: XmlPullParser): Key? {
        val uri = parser.namespace.orEmpty()
        val prefix = parser.prefix
        if (uri.isEmpty()) {
            return if (prefix.isNullOrEmpty()) Key.RSS else prefixFallback[prefix]
        }
        keyOf(uri)?.let { return it }
        return prefix?.let { prefixFallback[it] }
    }

    /** Whether the current element was matched through the prefix fallback (`UNDECLARED_PREFIX`). */
    internal fun usedPrefixFallback(parser: XmlPullParser): Boolean {
        val prefix = parser.prefix ?: return false
        if (prefix.isEmpty()) return false
        val uri = parser.namespace.orEmpty()
        if (uri.isNotEmpty() && keyOf(uri) != null) return false
        return prefixFallback.containsKey(prefix)
    }
}
