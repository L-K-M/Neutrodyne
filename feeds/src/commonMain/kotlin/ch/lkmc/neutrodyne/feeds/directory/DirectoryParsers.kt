// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feeds.directory

import ch.lkmc.neutrodyne.feeds.discovery.DirectoryRecord
import ch.lkmc.neutrodyne.feeds.parse.FeedDates
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Small untrusted directory documents; transport enforces the same byte bound before decoding. */
object DirectoryParsers {
    const val MAX_JSON_BYTES = 2 * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = true }

    fun apple(text: String): List<DirectoryRecord> =
        root(text).requiredArray("results").take(100).mapIndexedNotNull { i, value ->
            val row = value as? JsonObject ?: return@mapIndexedNotNull null
            DirectoryRecord(
                feedUrl = row.string("feedUrl"),
                title = row.string("collectionName"),
                author = row.string("artistName"),
                artworkUrl = row.string("artworkUrl600"),
                episodeCount = row.int("trackCount"),
                lastEpisodeAt = date(row.string("releaseDate")),
                genres = row.array("genres").mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                explicit = row.string("collectionExplicitness")?.let { it == "explicit" },
                directoryId = row.string("collectionId"),
                rank = i + 1,
            )
        }

    fun chartIds(text: String): List<String> {
        val feed = root(text)["feed"] as? JsonObject ?: error("shape")
        val modern = feed["results"] as? JsonArray
        val ids =
            if (modern != null) {
                modern.mapNotNull { (it as? JsonObject)?.string("id") }
            } else {
                feed.requiredArray("entry").mapNotNull {
                    (((it as? JsonObject)?.get("id") as? JsonObject)?.get("attributes") as? JsonObject)?.string("im:id")
                }
            }
        return ids.filter(DirectoryIds::isValid).distinct().take(100)
    }

    fun fyyd(text: String): List<DirectoryRecord> {
        val root = root(text)
        require(root.int("status") == 1)
        return root.requiredArray("data").take(50).mapIndexedNotNull { i, value ->
            val row = value as? JsonObject ?: return@mapIndexedNotNull null
            DirectoryRecord(
                feedUrl = row.string("xmlURL"),
                title = row.string("title"),
                author = row.string("author"),
                artworkUrl = row.string("imgURL"),
                episodeCount = row.int("episode_count"),
                description = row.string("description"),
                podcastGuid = row.string("podcastGuid"),
                lastEpisodeAt = date(row.string("lastpub")),
                directoryId = row.string("id"),
                rank = i + 1,
            )
        }
    }

    fun podcastIndex(text: String): List<DirectoryRecord> {
        val root = root(text)
        require(root.string("status") in listOf("true", "1"))
        val rows =
            (root["feeds"] as? JsonArray) ?: (root["feed"] as? JsonObject)?.let { JsonArray(listOf(it)) }
                ?: error("shape")
        return rows.take(50).mapIndexedNotNull { i, value ->
            val row = value as? JsonObject ?: return@mapIndexedNotNull null
            DirectoryRecord(
                feedUrl = row.string("url"),
                title = row.string("title"),
                author = row.string("author"),
                artworkUrl = row.string("artwork")?.takeIf(String::isNotBlank) ?: row.string("image"),
                description = row.string("description"),
                podcastGuid = row.string("podcastGuid"),
                episodeCount = row.int("episodeCount"),
                lastEpisodeAt = (row.long("newestItemPubdate") ?: row.long("newestItemPublishTime"))?.times(1000),
                explicit = row.int("explicit")?.let { it != 0 },
                directoryId = row.string("id"),
                rank = i + 1,
                dead = row.int("dead") == 1,
                medium = row.string("medium"),
                genres =
                    (row["categories"] as? JsonObject)?.values?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        ?: emptyList(),
            )
        }
    }

    private fun root(text: String): JsonObject {
        require(text.length <= MAX_JSON_BYTES && text.encodeToByteArray().size <= MAX_JSON_BYTES)
        return json.parseToJsonElement(text) as? JsonObject ?: error("shape")
    }

    private fun JsonObject.requiredArray(key: String): JsonArray = get(key) as? JsonArray ?: error("shape")

    private fun JsonObject.array(key: String): JsonArray = get(key) as? JsonArray ?: JsonArray(emptyList())

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? = string(key)?.toIntOrNull()

    private fun JsonObject.long(key: String): Long? = string(key)?.toLongOrNull()

    private fun date(value: String?): Long? = value?.let { FeedDates.parse(it) }
}
