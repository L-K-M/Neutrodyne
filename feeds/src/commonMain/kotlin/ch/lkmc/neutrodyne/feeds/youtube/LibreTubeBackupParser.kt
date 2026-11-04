// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.youtube

import ch.lkmc.neutrodyne.feeds.importing.ImportDocument
import ch.lkmc.neutrodyne.feeds.importing.ImportEntry
import ch.lkmc.neutrodyne.feeds.importing.ImportGroup
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames
import okio.Source

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
public object LibreTubeBackupParser {
    public fun parse(source: Source): ImportDocument {
        val raw = boundedJson(source)
        val doc =
            try {
                json.decodeFromString<Document>(raw)
            } catch (e: ImportParseException) {
                throw e
            } catch (_: Exception) {
                throw ImportParseException(ImportParseException.Reason.MALFORMED)
            }
        checkItemCount(doc.subscriptions.size)
        checkItemCount(doc.groups.size)
        val groups = doc.groups.sortedBy { it.index }
        val memberships = LinkedHashMap<String, MutableList<String>>()
        for (group in groups) {
            for (id in group.channels) {
                val names =
                    memberships.getOrPut(id) {
                        checkItemCount(memberships.size + 1)
                        mutableListOf()
                    }
                names.add(group.name)
            }
        }
        val present = HashSet<String>()
        val entries =
            doc.subscriptions.mapIndexedTo(ArrayList()) { index, sub ->
                val id =
                    sub.channelId?.takeIf(CHANNEL_ID::matches)
                        ?: sub.url?.substringAfter("/channel/", "")?.takeIf(CHANNEL_ID::matches)
                id?.let(present::add)
                ImportEntry(index, id ?: sub.url.orEmpty(), sub.name, groupNames = memberships[id].orEmpty())
            }
        for ((id, names) in memberships) {
            if (id in present) continue
            checkItemCount(entries.size + 1)
            entries += ImportEntry(entries.size, id, groupNames = names)
        }
        return ImportDocument(
            "LIBRETUBE_JSON",
            entries,
            groups.map { ImportGroup(it.name) },
        )
    }

    @Serializable
    private data class Document(
        @Serializable(with = SubscriptionsSerializer::class) @JsonNames("subscriptions") val localSubscriptions:
            List<Subscription> = emptyList(),
        @JsonNames("channelGroups") @Serializable(with = GroupsSerializer::class) val groups: List<Group> = emptyList(),
    ) {
        val subscriptions: List<Subscription> get() = localSubscriptions
    }

    @Serializable
    private data class Subscription(
        val channelId: String? = null,
        val url: String? = null,
        val name: String? = null,
    )

    @Serializable
    private data class Group(
        @JsonNames("name") val groupName: String,
        @Serializable(with = ChannelsSerializer::class) val channels: List<String> = emptyList(),
        val index: Int = 0,
    ) {
        val name: String get() = groupName
    }

    private object GroupsSerializer : CappedListSerializer<Group>(Group.serializer())

    private object ChannelsSerializer : CappedListSerializer<String>(String.serializer())

    private object SubscriptionsSerializer : CappedListSerializer<Subscription>(Subscription.serializer())

    private val json = Json { ignoreUnknownKeys = true }
}
