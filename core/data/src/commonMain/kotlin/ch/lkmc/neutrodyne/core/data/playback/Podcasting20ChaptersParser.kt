// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data.playback

import ch.lkmc.neutrodyne.core.common.HttpClientKind
import ch.lkmc.neutrodyne.core.data.fetch.FeedHttpHeaders
import ch.lkmc.neutrodyne.core.model.ChapterSource
import ch.lkmc.neutrodyne.core.model.EpisodeChapter
import ch.lkmc.neutrodyne.core.network.NeutrodyneHttpClients
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import dev.zacsweers.metro.Inject
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import okio.Buffer

@Inject
internal class Podcasting20ChaptersParser(
    private val clients: NeutrodyneHttpClients,
) {
    suspend fun fetch(url: String): List<EpisodeChapter> =
        withTimeout(TIMEOUT_MS) {
            require(UrlNormalizer.origin(url) != null) { "Chapter URL must be HTTP(S)" }
            clients.client(HttpClientKind.API).prepareGet(url).execute { response ->
                require(response.status.isSuccess()) { "Chapter HTTP ${response.status.value}" }
                val channel = response.bodyAsChannel()
                val buffer = Buffer()
                val chunk = ByteArray(8192)
                while (true) {
                    val read =
                        channel.readAvailable(
                            chunk,
                            0,
                            minOf(chunk.size.toLong(), MAX_BYTES + 1 - buffer.size).toInt(),
                        )
                    if (read < 0) break
                    buffer.write(chunk, 0, read)
                    require(buffer.size <= MAX_BYTES) { "Chapter document exceeds 1 MiB" }
                }
                val text = buffer.readUtf8()
                withContext(Dispatchers.Default) { parse(text, url) }
            }
        }

    companion object {
        private const val TIMEOUT_MS = 8_000L
        private const val MAX_BYTES = 1024L * 1024
        private const val MAX_CHAPTERS = 1_000
        private const val MILLIS_PER_SECOND = 1_000

        fun parse(
            text: String,
            baseUrl: String,
        ): List<EpisodeChapter> {
            val root = Json.parseToJsonElement(text) as? JsonObject
            require(root != null && (root["version"] as? JsonPrimitive)?.isString == true) { "Missing chapter version" }
            val chapters = root["chapters"] as? JsonArray ?: throw IllegalArgumentException("Missing chapters array")
            val rows =
                chapters.mapNotNull { entry ->
                    val row = entry as? JsonObject ?: return@mapNotNull null
                    val start = millis(row["startTime"]) ?: return@mapNotNull null
                    EpisodeChapter(
                        start,
                        millis(row["endTime"]),
                        string(row["title"]),
                        safeUrl(baseUrl, string(row["img"])),
                        safeUrl(baseUrl, string(row["url"])),
                        ChapterSource.PODCASTING20_JSON,
                        (row["toc"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == false,
                    )
                }
            return ChapterSource.normalize(rows).take(MAX_CHAPTERS)
        }

        private fun string(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun millis(value: JsonElement?): Long? {
            val number = (value as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull ?: return null
            val millis = number * MILLIS_PER_SECOND
            return millis.takeIf { number >= 0 && it.isFinite() && it < Long.MAX_VALUE.toDouble() }?.toLong()
        }

        private fun safeUrl(
            base: String,
            value: String?,
        ): String? {
            if (value.isNullOrBlank()) return null
            val reference = value.trim()
            val target = reference.substringBefore('#')
            val resolved =
                FeedHttpHeaders.resolveLocation(base, target)
                    ?: base.takeIf { target.isEmpty() || target == base }
            val safe = resolved?.takeIf { UrlNormalizer.origin(it) != null } ?: return null
            return if ('#' in reference) safe + "#" + reference.substringAfter('#') else safe
        }
    }
}
