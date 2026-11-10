// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.sync

import ch.lkmc.neutrodyne.feeds.groups.GroupNames
import ch.lkmc.neutrodyne.feeds.identity.EpisodeKeys
import ch.lkmc.neutrodyne.feeds.identity.KeyInput
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.sync.api.LinkState
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.security.MessageDigest

/**
 * The desktop half of E12's config, fixture and result contract (09 End-to-end journeys, 10
 * Cross-device journey). The fields, algorithms and timestamps mirror the Android driver
 * (`CrossDeviceSyncAndroidTest`) byte for byte: every fixture UUID, episode GUID, identity key and
 * order key is derived from `runId` plus the record key, so the host and both devices agree without
 * a shared fixture file.
 *
 * Nothing here touches the network or the database; the runner owns the graph and the phases.
 */
internal data class CrossDeviceConfig(
    val runId: String,
    val serverUrl: String,
    val username: String,
    val password: String,
    val feedBaseUrl: String,
    val edits: List<CrossDeviceEdit> = emptyList(),
)

internal data class CrossDeviceEdit(
    val op: String,
    val podcast: Int? = null,
    val episode: Int? = null,
    val group: Int? = null,
    val value: Long? = null,
    val fav: Boolean? = null,
    val before: Int? = null,
)

internal data class FixturePodcast(
    val index: Int,
    val syncId: String,
    val feedUrl: String,
    val feedKey: String,
    val title: String,
    val author: String,
    val link: String,
    val artworkKey: String,
)

internal data class FixtureEpisode(
    val podcastIndex: Int,
    val identityKey: String,
    val guid: String,
    val title: String,
    val pubDate: Long,
    val feedOrder: Int,
    val enclosureUrl: String,
    val enclosureType: String,
    val enclosureLength: Long,
    val durationMs: Long,
    val link: String,
)

internal data class FixtureGroup(
    val index: Int,
    val uuid: String,
    val name: String,
    val nameKey: String,
    val memberPodcastIndices: List<Int>,
)

internal data class CrossDeviceFixture(
    val podcasts: List<FixturePodcast>,
    val episodes: List<FixtureEpisode>,
    val groups: List<FixtureGroup>,
)

/** A phase failure whose message is safe to write to `result.json` and log: field/phase only. */
internal class PhaseError(
    field: String,
    reason: String,
) : Exception("$field: $reason") {
    val safeMessage: String = "$field: $reason"
}

internal val crossDeviceJson: Json =
    Json {
        ignoreUnknownKeys = true
    }

internal fun parseConfig(text: String): CrossDeviceConfig {
    val root =
        try {
            crossDeviceJson.parseToJsonElement(text)
        } catch (_: Exception) {
            throw PhaseError("config", "config file is not valid JSON")
        }
    val obj = root as? JsonObject ?: throw PhaseError("config", "config file must be a JSON object")
    return CrossDeviceConfig(
        runId = obj.string("runId").orEmpty(),
        serverUrl = obj.string("serverUrl").orEmpty(),
        username = obj.string("username").orEmpty(),
        password = obj.string("password").orEmpty(),
        feedBaseUrl = obj.string("feedBaseUrl").orEmpty(),
        edits = parseEdits(obj),
    )
}

private fun parseEdits(obj: JsonObject): List<CrossDeviceEdit> {
    val array = obj["edits"] as? JsonArray ?: return emptyList()
    return array.mapIndexed { index, element ->
        val entry =
            element as? JsonObject
                ?: throw PhaseError("config", "edits[$index] must be an object")
        CrossDeviceEdit(
            op =
                entry.string("op")
                    ?: throw PhaseError("config", "edits[$index].op is required"),
            podcast = entry.int("podcast"),
            episode = entry.int("episode"),
            group = entry.int("group"),
            value = entry.long("value"),
            fav = entry.bool("fav"),
            before = entry.int("before"),
        )
    }
}

/**
 * The config gate every phase runs before any request or write: a non-blank `runId`, a non-blank
 * username, a non-empty password and loopback `http` URLs with an explicit port and no credentials,
 * query or fragment. An arbitrary remote server is refused.
 */
internal fun validateConfig(config: CrossDeviceConfig) {
    if (config.runId.isBlank()) throw PhaseError("config", "field 'runId' is required")
    requireLoopbackHttp(config.serverUrl, "serverUrl")
    if (config.username.isBlank()) throw PhaseError("config", "field 'username' is required")
    if (config.password.isEmpty()) throw PhaseError("config", "field 'password' is required")
    requireLoopbackHttp(config.feedBaseUrl, "feedBaseUrl")
}

private fun requireLoopbackHttp(
    value: String,
    field: String,
) {
    val uri =
        try {
            URI(value)
        } catch (_: Exception) {
            throw PhaseError("config", "field '$field' must be a loopback http URL")
        }
    val host = uri.host
    val loopback = host != null && (host == "127.0.0.1" || host.equals("localhost", ignoreCase = true))
    if (!loopback || uri.scheme != "http" || uri.port <= 0 ||
        uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null
    ) {
        throw PhaseError(
            "config",
            "field '$field' must be http://127.0.0.1:<port> or http://localhost:<port>" +
                " with no credentials, query or fragment",
        )
    }
}

/**
 * The deterministic 50-podcast fixture. `OrderKey.rewrite` supplies deterministic order keys (the
 * jittered `after`/`between` are not reproducible); every UUID and episode GUID is derived from
 * `runId` plus the record key.
 */
internal fun crossDeviceFixture(
    runId: String,
    feedBaseUrl: String,
): CrossDeviceFixture {
    val base = feedBaseUrl.trimEnd('/')
    val podcasts =
        (0 until PODCAST_COUNT).map { index ->
            val feedUrl = "$base/feed-$index.xml"
            FixturePodcast(
                index = index,
                syncId = fixtureUuid(runId, "podcast:$index"),
                feedUrl = feedUrl,
                feedKey = checkNotNull(UrlNormalizer.forIdentity(feedUrl)),
                title = "Cross Device Feed $index",
                author = "Cross Device Author $index",
                link = "$base/feed-$index",
                artworkKey = "cross-device-artwork-$index",
            )
        }
    val episodes =
        podcasts.map { podcast ->
            val index = podcast.index
            val enclosureUrl = "$base/episode-$index.mp3"
            FixtureEpisode(
                podcastIndex = index,
                identityKey =
                    EpisodeKeys.keyFor(
                        KeyInput(
                            guid = fixtureToken(runId, "episode:$index"),
                            enclosureUrl = enclosureUrl,
                            title = "Cross Device Episode $index",
                            pubDate = T_BASE - index * 1000,
                            link = "$base/episode-$index",
                        ),
                        EpisodeKeys.VERSION,
                    ),
                guid = fixtureToken(runId, "episode:$index"),
                title = "Cross Device Episode $index",
                pubDate = T_BASE - index * 1000,
                feedOrder = 0,
                enclosureUrl = enclosureUrl,
                enclosureType = "audio/mpeg",
                enclosureLength = 1_000_000L + index,
                durationMs = 1_800_000L + index * 1000,
                link = "$base/episode-$index",
            )
        }
    val groups =
        listOf(
            FixtureGroup(
                index = 0,
                uuid = fixtureUuid(runId, "group:0"),
                name = "Cross Device Group A",
                nameKey = fixtureGroupNameKey("Cross Device Group A"),
                memberPodcastIndices = (0 until 25).toList(),
            ),
            FixtureGroup(
                index = 1,
                uuid = fixtureUuid(runId, "group:1"),
                name = "Cross Device Group B",
                nameKey = fixtureGroupNameKey("Cross Device Group B"),
                memberPodcastIndices = (25 until PODCAST_COUNT).toList(),
            ),
        )
    return CrossDeviceFixture(podcasts, episodes, groups)
}

private fun fixtureGroupNameKey(name: String): String =
    (GroupNames.validate(name) as? GroupNames.Validation.Valid)?.nameKey
        ?: throw IllegalStateException("fixture group name is invalid")

private fun fixtureUuid(
    runId: String,
    key: String,
): String {
    val bytes =
        MessageDigest
            .getInstance("SHA-256")
            .digest("$runId:$key".toByteArray(Charsets.UTF_8))
            .copyOf(16)
    bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
    val hex = bytes.joinToString("") { "%02x".format(it) }
    return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-" +
        hex.substring(16, 20) + "-" + hex.substring(20)
}

private fun fixtureToken(
    runId: String,
    key: String,
): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest("$runId:$key".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(32)

/** The accepted wire state never carries the protected `auth` field into the result. */
internal fun sanitize(state: RecordState): RecordState =
    if (state.fields.containsKey("auth")) state.copy(fields = state.fields - "auth") else state

/** Canonical JSON with sorted object keys and preserved array order (09's comparison input). */
internal fun canonicalJson(element: JsonElement): String =
    when (element) {
        is JsonObject -> {
            element.keys.sorted().joinToString(",", "{", "}") { key ->
                "${JsonPrimitive(key)}:${canonicalJson(element.getValue(key))}"
            }
        }

        is JsonArray -> {
            element.joinToString(",", "[", "]") { canonicalJson(it) }
        }

        else -> {
            element.toString()
        }
    }

internal fun LinkState.safeName(): String = this::class.simpleName ?: "LinkState"

internal fun JsonObject.string(field: String): String? {
    val primitive = this[field] as? JsonPrimitive ?: return null
    return if (primitive === JsonNull) null else primitive.content
}

internal fun JsonObject.int(field: String): Int? = (this[field] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.long(field: String): Long? = (this[field] as? JsonPrimitive)?.longOrNull

internal fun JsonObject.bool(field: String): Boolean? = (this[field] as? JsonPrimitive)?.booleanOrNull

internal const val PODCAST_COUNT = 50
internal const val QUEUE_COUNT = 8
internal const val T_BASE = 1_730_000_000_000L
internal const val REFRESH_FAR_FUTURE_MS = 365L * 24 * 60 * 60 * 1000
internal const val POLL_MS = 50L
internal const val LINK_TIMEOUT_MS = 60_000L
internal const val SYNC_TIMEOUT_MS = 60_000L
internal const val STATUS_TIMEOUT_MS = 5_000L
internal const val CLOCK_TOLERANCE_MS = 5_000L
internal const val SYNC_ERROR_REVOKED = "Revoked"
