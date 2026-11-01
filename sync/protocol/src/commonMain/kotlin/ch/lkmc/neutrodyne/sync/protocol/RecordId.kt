// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
@JvmInline
value class PodcastId(
    val value: String,
) {
    init {
        validateUuid(value)
    }
}

@Serializable
@JvmInline
value class GroupId(
    val value: String,
) {
    init {
        validateUuid(value)
    }
}

@Serializable
@JvmInline
value class EpisodeKey(
    val value: String,
) {
    init {
        // Keys are opaque across EpisodeKeys versions; only the envelope is interpreted here.
        require(value.isNotEmpty() && fitsUtf8(value, ProtocolLimits.MAX_IDENTITY_KEY)) { "invalid episode key size" }
        require(value.any { it == ':' } && value.none { it.code < 0x20 || it == '\u007f' }) { "invalid episode key" }
    }
}

internal fun validateUuid(value: String) {
    require(
        value.length == UUID_LENGTH &&
            value.indices.all { index ->
                if (index in UUID_DASHES) value[index] == '-' else value[index] in '0'..'9' || value[index] in 'a'..'f'
            },
    ) { "UUID must use canonical lowercase syntax" }
}

private const val UUID_LENGTH = 36
private val UUID_DASHES = setOf(8, 13, 18, 23)

/** Typed identity within an account; episode and upnext deliberately share the same ID shape. */
@Serializable
sealed class RecordId {
    abstract val rid: String

    abstract fun toWire(): JsonElement

    @Serializable
    data class Podcast(
        val id: PodcastId,
    ) : RecordId() {
        override val rid: String get() = id.value

        override fun toWire(): JsonElement = JsonPrimitive(rid)
    }

    @Serializable
    data class Group(
        val id: GroupId,
    ) : RecordId() {
        override val rid: String get() = id.value

        override fun toWire(): JsonElement = JsonPrimitive(rid)
    }

    @Serializable
    data class Member(
        val group: GroupId,
        val podcast: PodcastId,
    ) : RecordId() {
        override val rid: String get() = group.value + podcast.value

        override fun toWire(): JsonElement =
            buildJsonObject {
                put("g", group.value)
                put("p", podcast.value)
            }
    }

    @Serializable
    data class Episode(
        val podcast: PodcastId,
        val key: EpisodeKey,
    ) : RecordId() {
        override val rid: String get() = podcast.value + key.value

        override fun toWire(): JsonElement =
            buildJsonObject {
                put("p", podcast.value)
                put("k", key.value)
            }
    }

    @Serializable
    data object Session : RecordId() {
        override val rid: String = "current"

        override fun toWire(): JsonElement = JsonPrimitive(rid)
    }

    @Serializable
    data class Setting(
        val key: String,
    ) : RecordId() {
        init {
            require(
                key.length in 1..ProtocolLimits.MAX_SETTING_KEY &&
                    key.all {
                        it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '.'
                    },
            ) { "invalid setting key" }
        }

        override val rid: String get() = key

        override fun toWire(): JsonElement = JsonPrimitive(rid)
    }

    companion object {
        fun fromWire(
            coll: SyncCollection,
            id: JsonElement,
        ): RecordId =
            when (coll) {
                SyncCollection.PODCAST -> {
                    Podcast(PodcastId(text(id)))
                }

                SyncCollection.GROUP -> {
                    Group(GroupId(text(id)))
                }

                SyncCollection.MEMBER -> {
                    Member(GroupId(member(id, "g")), PodcastId(member(id, "p")))
                }

                SyncCollection.EPISODE, SyncCollection.UPNEXT -> {
                    Episode(PodcastId(member(id, "p")), EpisodeKey(member(id, "k")))
                }

                SyncCollection.SESSION -> {
                    require(text(id) == "current") { "invalid session ID" }
                    Session
                }

                SyncCollection.SETTING -> {
                    Setting(text(id))
                }
            }

        fun fromRid(
            coll: SyncCollection,
            rid: String,
        ): RecordId =
            when (coll) {
                SyncCollection.PODCAST -> {
                    Podcast(PodcastId(rid))
                }

                SyncCollection.GROUP -> {
                    Group(GroupId(rid))
                }

                SyncCollection.MEMBER -> {
                    require(rid.length == UUID_LENGTH * 2) { "invalid member rid" }
                    Member(GroupId(rid.take(UUID_LENGTH)), PodcastId(rid.drop(UUID_LENGTH)))
                }

                SyncCollection.EPISODE, SyncCollection.UPNEXT -> {
                    require(rid.length > UUID_LENGTH) { "invalid episode rid" }
                    Episode(PodcastId(rid.take(UUID_LENGTH)), EpisodeKey(rid.drop(UUID_LENGTH)))
                }

                SyncCollection.SESSION -> {
                    require(rid == "current") { "invalid session rid" }
                    Session
                }

                SyncCollection.SETTING -> {
                    Setting(rid)
                }
            }

        private fun member(
            id: JsonElement,
            key: String,
        ): String = text((id as? JsonObject)?.get(key) ?: invalid("missing ID component"))

        private fun text(id: JsonElement): String =
            (id as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid("ID component must be a string")
    }
}

/** Canonical replicated ties use stable IDs; local SQL's row-ID fallback is a separate display rule. */
object ReplicatedOrder {
    fun compare(
        aKey: String,
        aId: RecordId,
        bKey: String,
        bId: RecordId,
    ): Int = compareValues(aKey, bKey).takeIf { it != 0 } ?: compareValues(aId.rid, bId.rid)
}
