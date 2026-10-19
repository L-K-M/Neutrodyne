// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.impl

import ch.lkmc.neutrodyne.youtube.api.ChannelResolution
import ch.lkmc.neutrodyne.youtube.api.MetadataDepth
import ch.lkmc.neutrodyne.youtube.api.ResolvedVia
import ch.lkmc.neutrodyne.youtube.api.YouTubeIds
import ch.lkmc.neutrodyne.youtube.api.YouTubeUrlClassifier
import ch.lkmc.neutrodyne.youtube.api.YtRef
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.takeFrom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

internal object ChannelPageParser {
    data class Page(
        val channelId: String?,
        val title: String?,
        val avatar: String?,
        val description: String?,
    ) {
        fun resolved(
            id: String,
            banner: String? = null,
        ) = ChannelResolution.Resolved(id, title, avatar, banner, description, ResolvedVia.HTML)
    }

    fun parseHead(
        prefix: String,
        baseUri: String,
        depth: MetadataDepth,
    ): Page {
        val tags = HeadTagScanner.scan(prefix)
        val links = tags.filter { it.name == "link" }
        val meta = tags.filter { it.name == "meta" }
        val alternate =
            links.firstNotNullOfOrNull { tag ->
                val attributes = tag.attributes
                if (attributes["rel"].tokens().contains("alternate") &&
                    attributes["type"].equals("application/rss+xml", true)
                ) {
                    absolute(attributes["href"], baseUri)?.let { href ->
                        runCatching { Url(href).parameters["channel_id"] }.getOrNull()?.validId()
                    }
                } else {
                    null
                }
            }
        val canonical =
            links.firstNotNullOfOrNull { tag ->
                if (tag.attributes["rel"].tokens().contains("canonical")) {
                    absolute(tag.attributes["href"], baseUri)?.let(::channelFromUrl)
                } else {
                    null
                }
            }
        val identifier =
            meta.firstNotNullOfOrNull { tag ->
                if (tag.attributes["itemprop"].tokens().contains("identifier")) {
                    tag.attributes["content"]?.validId()
                } else {
                    null
                }
            }

        fun property(name: String) =
            meta
                .firstNotNullOfOrNull { tag ->
                    tag.attributes["content"]?.takeIf { tag.attributes["property"].equals(name, true) }
                }?.trim()
                ?.takeIf { it.isNotEmpty() }

        val id = alternate ?: canonical ?: identifier ?: externalId(prefix)
        if (depth == MetadataDepth.ID_ONLY) return Page(id, null, null, null)
        val title =
            property("og:title") ?: tags
                .firstOrNull { it.name == "title" }
                ?.text
                ?.trim()
                ?.removeSuffix(" - YouTube")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        return Page(id, title, absolute(property("og:image"), baseUri), property("og:description"))
    }

    fun watchId(prefix: String): String? =
        HeadTagScanner.scan(prefix).firstNotNullOfOrNull { tag ->
            if (tag.name == "meta" && tag.attributes["itemprop"].tokens().contains("channelid")) {
                tag.attributes["content"]?.validId()
            } else {
                null
            }
        } ?: WATCH_ID.findAll(prefix).firstNotNullOfOrNull { it.groupValues[1].validId() }

    fun externalId(text: String): String? =
        EXTERNAL_ID.findAll(text).firstNotNullOfOrNull { it.groupValues[1].validId() }

    /** The caller supplies only a sliding window, so malformed JSON cannot allocate a whole page. */
    fun bannerStart(text: String): Int? = BANNER.find(text)?.range?.first

    fun banner(text: String): String? {
        val marker = BANNER.find(text) ?: return null
        val start = marker.range.last
        var quoted = false
        var escaped = false
        var end = -1
        for (i in start + 1 until text.length) {
            val char = text[i]
            if (escaped) {
                escaped = false
            } else if (quoted && char == '\\') {
                escaped = true
            } else if (char == '"') {
                quoted = !quoted
            } else if (!quoted && char == ']') {
                end = i + 1
                break
            }
        }
        if (end < 0 || end - start > BANNER_WINDOW) return null
        val array =
            runCatching { Json.parseToJsonElement(text.substring(start, end)) as? JsonArray }.getOrNull()
                ?: return null
        return array
            .mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val url = (obj["url"] as? JsonPrimitive)?.contentOrNull
                val width = (obj["width"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                val height = (obj["height"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                if (width <= 0 || height <= 0) return@mapNotNull null
                absolute(url, "https://www.youtube.com/")?.let { it to width }
            }.maxByOrNull { it.second }
            ?.first
    }

    fun channelFromUrl(url: String): String? = (classifier.classify(url) as? YtRef.Channel)?.id

    private fun String?.tokens(): List<String> = this?.lowercase()?.split(Regex("\\s+")) ?: emptyList()

    private fun String.validId(): String? = takeIf(YouTubeIds.CHANNEL::matches)

    private fun absolute(
        value: String?,
        base: String,
    ): String? {
        if (value.isNullOrBlank()) return null
        val url = runCatching { URLBuilder(base).takeFrom(value).build() }.getOrNull() ?: return null
        return url.toString().takeIf {
            url.protocol.name in listOf("https", "http") &&
                url.user.isNullOrEmpty() && url.password.isNullOrEmpty()
        }
    }

    const val BANNER_WINDOW = 64 * 1024
    private val classifier = YouTubeUrlClassifier()
    private val EXTERNAL_ID = Regex("\"externalId\"\\s*:\\s*\"(UC[0-9A-Za-z_-]{22})\"")
    private val WATCH_ID = Regex("\"channelId\"\\s*:\\s*\"(UC[0-9A-Za-z_-]{22})\"")
    private val BANNER =
        Regex(
            "\"imageBannerViewModel\"\\s*:\\s*\\{" +
                "\\s*\"image\"\\s*:\\s*\\{\\s*\"sources\"\\s*:\\s*\\[",
        )
}
