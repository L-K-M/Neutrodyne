// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.database.EpisodeEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastEntity
import ch.lkmc.neutrodyne.core.database.PodcastGroupEntity
import ch.lkmc.neutrodyne.core.database.PodcastGroupMemberEntity
import ch.lkmc.neutrodyne.core.database.QueueEntryEntity
import ch.lkmc.neutrodyne.core.database.ScopeOverrides
import ch.lkmc.neutrodyne.core.database.SyncOutboxEntity
import ch.lkmc.neutrodyne.core.domain.SecretStore
import ch.lkmc.neutrodyne.sync.protocol.FieldState
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.RecordId
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.dto.FieldValue
import ch.lkmc.neutrodyne.sync.protocol.dto.MatchHints
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The current source rows behind one record, read in the caller's transaction (10 Push). */
class SourceReader(
    private val db: NeutrodyneDatabase,
    private val secrets: SecretStore,
    private val sharePasswords: () -> Boolean,
) {
    suspend fun podcast(rid: String): PodcastEntity? {
        val id = RecordId.fromRid(SyncCollection.PODCAST, rid)
        return db.podcastDao().bySyncId((id as RecordId.Podcast).id.value)
    }

    suspend fun podcastField(
        rid: String,
        field: String,
    ): JsonElement? {
        val podcast = podcast(rid) ?: return null
        return podcastValue(podcast, field)
    }

    suspend fun group(rid: String): PodcastGroupEntity? = db.groupDao().groupByUuid(rid)

    suspend fun groupField(
        rid: String,
        field: String,
    ): JsonElement? {
        val group = group(rid) ?: return null
        return groupValue(group, field)
    }

    suspend fun member(rid: String): PodcastGroupMemberEntity? {
        val id = RecordId.fromRid(SyncCollection.MEMBER, rid) as RecordId.Member
        val group = db.groupDao().groupByUuid(id.group.value) ?: return null
        val podcast = db.podcastDao().bySyncId(id.podcast.value) ?: return null
        return db.groupDao().member(group.id, podcast.id)
    }

    suspend fun memberField(
        rid: String,
        field: String,
    ): JsonElement? {
        val member = member(rid) ?: return null
        return when (field) {
            "in" -> JsonPrimitive(true)
            "ok" -> JsonPrimitive(member.orderKey)
            "addedAt" -> JsonPrimitive(member.addedAt)
            else -> null
        }
    }

    suspend fun episode(rid: String): EpisodeSource? {
        val id = RecordId.fromRid(SyncCollection.EPISODE, rid) as RecordId.Episode
        val podcast = db.podcastDao().bySyncId(id.podcast.value) ?: return null
        val episode =
            db.episodeDao().byIdentityKey(podcast.id, id.key.value) ?: return null
        return episodeSource(episode)
    }

    suspend fun episodeField(
        rid: String,
        field: String,
    ): JsonElement? = episode(rid)?.field(field)

    suspend fun upnext(rid: String): UpNextSource? {
        val episode = episode(rid) ?: return null
        val entry = db.queueDao().entryByEpisode(episode.id) ?: return null
        return UpNextSource(entry, episode)
    }

    suspend fun upnextField(
        rid: String,
        field: String,
    ): JsonElement? {
        val entry = upnext(rid) ?: return null
        return when (field) {
            "in" -> JsonPrimitive(true)
            "ok" -> JsonPrimitive(entry.entry.orderKey)
            else -> null
        }
    }

    suspend fun session(): SessionSource? {
        val session = db.playSessionDao().get() ?: return null
        val episode = session.currentEpisodeId?.let { db.episodeDao().byId(it) }
        val podcast = episode?.let { db.podcastDao().byId(it.podcastId) }
        return SessionSource(session, episode, podcast)
    }

    suspend fun sessionField(field: String): JsonElement? = session()?.field(field)

    /** The whole current feed-key set (10 Push: `feedKeys` is always the full set). */
    suspend fun feedKeys(rid: String): List<String> {
        val podcast = podcast(rid) ?: return emptyList()
        return (listOf(podcast.feedKey) + db.podcastDao().aliases(podcast.id).map { it.url })
            .distinct()
            .sorted()
    }

    /** The opted-in `auth` field value; `null` when the podcast has no credential. */
    suspend fun authField(rid: String): FieldValue? {
        val podcast = podcast(rid) ?: return null
        val credentials = secrets.forPodcast(podcast.id) ?: return null
        return FieldValue(
            JsonObject(
                buildMap {
                    put("u", JsonPrimitive(credentials.username))
                    put("p", JsonPrimitive(credentials.password))
                },
            ),
        )
    }

    suspend fun field(
        collection: SyncCollection,
        rid: String,
        field: String,
    ): JsonElement? =
        when (collection) {
            SyncCollection.PODCAST -> podcastField(rid, field)
            SyncCollection.GROUP -> groupField(rid, field)
            SyncCollection.MEMBER -> memberField(rid, field)
            SyncCollection.EPISODE -> episodeField(rid, field)
            SyncCollection.UPNEXT -> upnextField(rid, field)
            SyncCollection.SESSION -> sessionField(field)
            SyncCollection.SETTING -> null
        }

    /** Every field of the record for a `*` row, in the record's collection order. */
    suspend fun allFields(
        collection: SyncCollection,
        rid: String,
    ): RecordState =
        when (collection) {
            SyncCollection.PODCAST -> {
                val podcast = podcast(rid) ?: return empty(collection)
                stateOf(
                    collection,
                    buildMap {
                        put("feedUrl", JsonPrimitive(podcast.feedUrl))
                        put("subscribed", JsonPrimitive(true))
                        put("subscribedAt", JsonPrimitive(podcast.subscribedAt))
                        put("sourceType", JsonPrimitive(podcast.sourceType.name))
                        podcast.youtubeChannelId?.let { put("youtubeChannelId", JsonPrimitive(it)) }
                        podcast.podcastGuid?.takeIf { !podcast.podcastGuidDerived }?.let {
                            put("podcastGuid", JsonPrimitive(it))
                        }
                        put("title", JsonPrimitive(podcast.title))
                        podcast.artworkUrl?.let { put("artworkUrl", JsonPrimitive(it)) }
                        podcast.link?.let { put("link", JsonPrimitive(it)) }
                        podcast.customTitle?.let { put("customTitle", JsonPrimitive(it)) }
                        put("includeInAll", JsonPrimitive(podcast.includeInAll))
                        podcast.episodeOrder?.let { put("episodeOrder", JsonPrimitive(it.name)) }
                        put("youtubeVariants", JsonPrimitive(podcast.youtubeVariants))
                        put("needsCredentials", JsonPrimitive(podcast.needsCredentials || podcast.credentialId != null))
                        overrides(rid, isGroup = false).forEach { (name, value) -> put(name, value) }
                    },
                    mapOf("feedKeys" to feedKeys(rid).toSet()),
                )
            }

            SyncCollection.GROUP -> {
                val group = group(rid) ?: return empty(collection)
                stateOf(
                    collection,
                    buildMap {
                        put("name", JsonPrimitive(group.name))
                        put("ok", JsonPrimitive(group.orderKey))
                        put("deleted", JsonPrimitive(false))
                        put("createdAt", JsonPrimitive(group.createdAt))
                        group.colorArgb?.let { put("colorArgb", JsonPrimitive(it)) }
                        group.iconKey?.let { put("iconKey", JsonPrimitive(it)) }
                        put("feedOrder", JsonPrimitive(group.feedOrder.name))
                        put("playOrder", JsonPrimitive(group.playOrder.name))
                        put("filterFlags", JsonPrimitive(group.filterFlags))
                        put("mediaFilter", JsonPrimitive(group.mediaFilter.name))
                        group.hideOlderThanDays?.let { put("hideOlderThanDays", JsonPrimitive(it)) }
                        put("showAsTab", JsonPrimitive(group.showAsTab))
                        overrides(rid, isGroup = true).forEach { (name, value) -> put(name, value) }
                    },
                )
            }

            SyncCollection.MEMBER -> {
                val member = member(rid) ?: return empty(collection)
                stateOf(
                    collection,
                    buildMap {
                        put("in", JsonPrimitive(true))
                        put("ok", JsonPrimitive(member.orderKey))
                        put("addedAt", JsonPrimitive(member.addedAt))
                    },
                )
            }

            SyncCollection.EPISODE -> {
                val episode = episode(rid) ?: return empty(collection)
                stateOf(
                    collection,
                    buildMap {
                        put("played", JsonPrimitive(episode.state?.playedAt != null))
                        put("playedAt", episode.state?.playedAt?.let(::JsonPrimitive) ?: JsonNull)
                        put("fav", JsonPrimitive(episode.state?.isFavorite == true))
                        put("playCount", JsonPrimitive(episode.state?.playCount ?: 0))
                        put("lastPlayedAt", episode.state?.lastPlayedAt?.let(::JsonPrimitive) ?: JsonNull)
                        put(
                            "measuredDurationMs",
                            episode.state?.measuredDurationMs?.let(::JsonPrimitive) ?: JsonNull,
                        )
                        put(
                            "pos",
                            JsonObject(
                                buildMap {
                                    put("ms", JsonPrimitive(episode.position?.positionMs ?: 0L))
                                },
                            ),
                        )
                    },
                )
            }

            SyncCollection.UPNEXT -> {
                val entry = upnext(rid) ?: return empty(collection)
                stateOf(
                    collection,
                    buildMap {
                        put("in", JsonPrimitive(true))
                        put("ok", JsonPrimitive(entry.entry.orderKey))
                    },
                )
            }

            SyncCollection.SESSION -> {
                val session = session() ?: return empty(collection)
                stateOf(
                    collection,
                    buildMap {
                        session.field("episode")?.let { put("episode", it) }
                        session.field("context")?.let { put("context", it) }
                    },
                )
            }

            SyncCollection.SETTING -> {
                empty(collection)
            }
        }

    private fun stateOf(
        collection: SyncCollection,
        fields: Map<String, JsonElement>,
        growSets: Map<String, Set<String>> = emptyMap(),
    ): RecordState = RecordState(collection, fields.mapValues { FieldState(it.value, null) }, growSets)

    private fun empty(collection: SyncCollection): RecordState = RecordState(collection)

    suspend fun overrides(
        rid: String,
        isGroup: Boolean,
    ): Map<String, JsonElement> {
        val overrides: ScopeOverrides =
            if (isGroup) {
                val group = group(rid) ?: return emptyMap()
                db.scopeSettingsDao().forGroup(group.id)?.o ?: return emptyMap()
            } else {
                val podcast = podcast(rid) ?: return emptyMap()
                db.scopeSettingsDao().forPodcast(podcast.id)?.o ?: return emptyMap()
            }
        return overrides.fields()
    }

    private suspend fun episodeSource(episode: EpisodeEntity): EpisodeSource {
        val state = db.episodeStateDao().byEpisode(episode.id)
        val position = db.positionDao().byEpisode(episode.id)
        return EpisodeSource(episode, state, position)
    }

    private suspend fun podcastValue(
        podcast: PodcastEntity,
        field: String,
    ): JsonElement? =
        when (field) {
            "feedUrl" -> JsonPrimitive(podcast.feedUrl)
            "subscribed" -> JsonPrimitive(true)
            "subscribedAt" -> JsonPrimitive(podcast.subscribedAt)
            "sourceType" -> JsonPrimitive(podcast.sourceType.name)
            "youtubeChannelId" -> podcast.youtubeChannelId?.let(::JsonPrimitive)
            "podcastGuid" -> podcast.podcastGuid?.takeIf { !podcast.podcastGuidDerived }?.let(::JsonPrimitive)
            "title" -> JsonPrimitive(podcast.title)
            "artworkUrl" -> podcast.artworkUrl?.let(::JsonPrimitive)
            "link" -> podcast.link?.let(::JsonPrimitive)
            "customTitle" -> podcast.customTitle?.let(::JsonPrimitive)
            "includeInAll" -> JsonPrimitive(podcast.includeInAll)
            "episodeOrder" -> podcast.episodeOrder?.let { JsonPrimitive(it.name) }
            "youtubeVariants" -> JsonPrimitive(podcast.youtubeVariants)
            "needsCredentials" -> JsonPrimitive(podcast.needsCredentials || podcast.credentialId != null)
            "credentialOrigin" -> podcast.credentialId?.let { originOf(it) }
            "auth" -> authOf(podcast)
            else -> null
        }

    private suspend fun originOf(credentialId: Long): JsonElement? =
        db
            .credentialDao()
            .byIds(listOf(credentialId))
            .firstOrNull()
            ?.origin
            ?.let(::JsonPrimitive)

    private suspend fun authOf(podcast: PodcastEntity): JsonElement? {
        val credentials = secrets.forPodcast(podcast.id) ?: return null
        return JsonObject(
            buildMap {
                put("u", JsonPrimitive(credentials.username))
                put("p", JsonPrimitive(credentials.password))
            },
        )
    }

    private fun groupValue(
        group: PodcastGroupEntity,
        field: String,
    ): JsonElement? =
        when (field) {
            "name" -> JsonPrimitive(group.name)
            "ok" -> JsonPrimitive(group.orderKey)
            "deleted" -> JsonPrimitive(false)
            "createdAt" -> JsonPrimitive(group.createdAt)
            "colorArgb" -> group.colorArgb?.let(::JsonPrimitive)
            "iconKey" -> group.iconKey?.let(::JsonPrimitive)
            "feedOrder" -> JsonPrimitive(group.feedOrder.name)
            "playOrder" -> JsonPrimitive(group.playOrder.name)
            "filterFlags" -> JsonPrimitive(group.filterFlags)
            "mediaFilter" -> JsonPrimitive(group.mediaFilter.name)
            "hideOlderThanDays" -> group.hideOlderThanDays?.let(::JsonPrimitive)
            "showAsTab" -> JsonPrimitive(group.showAsTab)
            else -> null
        }
}

/** One episode's merged source row for the wire (10 Push). */
class EpisodeSource(
    val episode: EpisodeEntity,
    val state: ch.lkmc.neutrodyne.core.database.EpisodeStateEntity?,
    val position: ch.lkmc.neutrodyne.core.database.EpisodePositionEntity?,
) {
    val id: Long get() = episode.id
    val guid: String? get() = episode.guid
    val enc: String? get() = episode.enclosureUrl
    val title: String? get() = episode.title
    val date: String? get() = null
    val link: String? get() = episode.link
    val ytId: String? get() = episode.externalMediaId

    fun field(name: String): JsonElement? =
        when (name) {
            "played" -> {
                JsonPrimitive(state?.playedAt != null)
            }

            "playedAt" -> {
                state?.playedAt?.let(::JsonPrimitive) ?: JsonNull
            }

            "fav" -> {
                JsonPrimitive(state?.isFavorite == true)
            }

            "playCount" -> {
                JsonPrimitive(state?.playCount ?: 0)
            }

            "lastPlayedAt" -> {
                state?.lastPlayedAt?.let(::JsonPrimitive) ?: JsonNull
            }

            "measuredDurationMs" -> {
                state?.measuredDurationMs?.let(::JsonPrimitive) ?: JsonNull
            }

            "pos" -> {
                JsonObject(
                    buildMap {
                        put("ms", JsonPrimitive(position?.positionMs ?: 0L))
                    },
                )
            }

            else -> {
                null
            }
        }
}

class UpNextSource(
    val entry: QueueEntryEntity,
    val episode: EpisodeSource,
)

class SessionSource(
    val session: ch.lkmc.neutrodyne.core.database.PlaySessionEntity,
    val episode: EpisodeEntity?,
    val podcast: PodcastEntity?,
) {
    fun field(name: String): JsonElement? =
        when (name) {
            "episode" -> {
                val episode = episode ?: return JsonNull
                val podcast = podcast ?: return null
                JsonObject(
                    buildMap {
                        put("p", JsonPrimitive(podcast.syncId))
                        put("k", JsonPrimitive(episode.identityKey))
                    },
                )
            }

            "context" -> {
                null
            }

            else -> {
                null
            }
        }
}

private fun ScopeOverrides.fields(): Map<String, JsonElement> {
    val values = mutableMapOf<String, JsonElement>()
    playbackSpeed?.let { values["s.playbackSpeed"] = JsonPrimitive(it) }
    skipSilence?.let { values["s.skipSilence"] = JsonPrimitive(it) }
    boostDb?.let { values["s.boostDb"] = JsonPrimitive(it) }
    introSkipMs?.let { values["s.introSkipMs"] = JsonPrimitive(it) }
    outroSkipMs?.let { values["s.outroSkipMs"] = JsonPrimitive(it) }
    return values
}

/** The wire clock of an outbox row; `null` when the row's clock is malformed. */
fun outboxClock(row: SyncOutboxEntity): Hlc? = runCatching { Hlc(row.hlc, NodeId(row.nodeId)) }.getOrNull()

/** The hints of an episode source, for `episode`, `upnext` and `session` changes. */
fun episodeHints(source: EpisodeSource): MatchHints? =
    MatchHints(
        guid = source.guid,
        enc = source.enc,
        title = source.title,
        date = source.date,
        link = source.link,
        ytId = source.ytId,
    ).takeIf { it != MatchHints() }
