// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feeds.jvm.html

import ch.lkmc.neutrodyne.feeds.discovery.Autodiscovery
import ch.lkmc.neutrodyne.feeds.discovery.DiscoveredFeed
import ch.lkmc.neutrodyne.feeds.discovery.DiscoverySource
import ch.lkmc.neutrodyne.feeds.discovery.HostAction
import ch.lkmc.neutrodyne.feeds.discovery.HostRecognizer
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import okio.Buffer
import okio.Source
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.nio.charset.Charset

/** Offline extraction only. Reading a prefix also bounds jsoup's DOM allocation. */
class JsoupAutodiscovery : Autodiscovery {
    override fun candidates(
        html: Source,
        charset: String?,
        pageUrl: String,
    ): List<DiscoveredFeed> {
        val prefix = Buffer()
        while (prefix.size < Autodiscovery.MAX_HTML_BYTES) {
            if (html.read(prefix, minOf(8192L, Autodiscovery.MAX_HTML_BYTES - prefix.size)) == -1L) break
        }
        val encoding = charset?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
        val document = Jsoup.parse(prefix.readByteArray().toString(encoding), pageUrl)
        val alternates =
            document
                .select("head link[href]")
                .filter {
                    it.attr("rel").split(Regex("\\s+")).any { rel -> rel.equals("alternate", true) } &&
                        it.attr("type").lowercase() in FEED_TYPES
                }.mapNotNull { candidate(it, DiscoverySource.ALTERNATE_LINK) }
                .sortedByDescending { PODCAST_WORDS.containsMatchIn(it.url + " " + it.title.orEmpty()) }
        if (alternates.isNotEmpty()) return alternates.distinctBy { it.url }.take(Autodiscovery.MAX_CANDIDATES)

        val anchors = document.select("a[href]")
        val apple =
            anchors
                .mapNotNull {
                    candidate(it, DiscoverySource.APPLE_ANCHOR)
                }.firstOrNull { HostRecognizer.recognize(it.url) is HostAction.AppleLookup }
        if (apple != null) return listOf(apple)
        return anchors
            .mapNotNull { candidate(it, DiscoverySource.SAME_SITE_ANCHOR) }
            .filter {
                UrlNormalizer.origin(it.url)?.substringAfter("://") ==
                    UrlNormalizer.origin(pageUrl)?.substringAfter("://") &&
                    FEED_PATH.containsMatchIn(it.url.substringBefore('?').substringBefore('#'))
            }.distinctBy { it.url }
            .take(Autodiscovery.MAX_CANDIDATES)
    }

    private fun candidate(
        element: Element,
        source: DiscoverySource,
    ): DiscoveredFeed? {
        val url = element.absUrl("href")
        val title = element.attr("title").ifBlank { element.text() }.takeIf(String::isNotBlank)
        if ((!url.startsWith("http://") && !url.startsWith("https://")) || UrlNormalizer.forIdentity(url) == null ||
            UrlNormalizer.splitUserInfo(url).second != null || url.contains("/comments/", true) ||
            title?.contains("comment", true) == true
        ) {
            return null
        }
        return DiscoveredFeed(url, title, source)
    }

    private companion object {
        val FEED_TYPES = setOf("application/rss+xml", "application/atom+xml", "application/x-rss+xml", "text/xml")
        val PODCAST_WORDS = Regex("podcast|audio|mp3|episodes", RegexOption.IGNORE_CASE)
        val FEED_PATH = Regex("(\\.rss|\\.xml)$|/(feed|rss)/?$|//feeds?\\.", RegexOption.IGNORE_CASE)
    }
}
