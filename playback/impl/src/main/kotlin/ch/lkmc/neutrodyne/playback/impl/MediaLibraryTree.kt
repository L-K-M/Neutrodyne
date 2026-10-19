// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.database.DatabaseOpener
import ch.lkmc.neutrodyne.core.database.FeedQueryBuilder
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastEntity
import ch.lkmc.neutrodyne.core.domain.PlaySessionStore
import ch.lkmc.neutrodyne.core.domain.PlaybackMediaLookup
import ch.lkmc.neutrodyne.core.domain.StartMembership
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.GroupCounts
import ch.lkmc.neutrodyne.core.model.PlayContextSpec
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.playback.core.StartRequest
import ch.lkmc.neutrodyne.playback.core.refusal
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** All suspend reads run on the callback's IO lane; providers are dereferenced after the DB gate. */
@Inject
internal class MediaLibraryTree(
    private val opener: DatabaseOpener,
    private val sessions: Provider<PlaySessionStore>,
    private val media: Provider<PlaybackMediaLookup>,
    private val items: Provider<MediaItemFactory>,
    private val capabilities: YouTubeCapabilitiesSource,
    private val database: Provider<NeutrodyneDatabase>,
    private val clock: Clock,
) {
    fun root(): MediaItem = folder(ROOT, "Neutrodyne")

    suspend fun children(
        parentId: String,
        page: Int,
        pageSize: Int,
        rootLimit: Int = ROOT_COUNT,
    ): List<MediaItem> {
        validatePage(page, pageSize)
        opener.awaitOpen()
        val db = database()
        val children =
            when (parentId) {
                ROOT -> {
                    listOf(
                        folder(UP_NEXT, "Up next"),
                        folder(GROUPS, "Groups"),
                        folder(DOWNLOADS, "Downloads"),
                        folder(PODCASTS, "Podcasts", GRID),
                    ).take(rootLimit.coerceIn(0, ROOT_COUNT))
                }

                UP_NEXT -> {
                    upNext()
                }

                GROUPS -> {
                    db.groupDao().groupsInOrder().map { group ->
                        val art =
                            db
                                .groupDao()
                                .membersOf(
                                    group.id,
                                ).firstOrNull()
                                ?.podcastId
                                ?.let { db.podcastDao().byId(it) }
                                ?.artworkKey
                        folder("group:${group.id}", group.name, artworkKey = art)
                    }
                }

                // M6 replaces this source with completed, verified files and enables Downloads starts.
                DOWNLOADS -> {
                    emptyList()
                }

                PODCASTS -> {
                    db.podcastDao().listForBrowse(youtubePlayable()).map(::podcastFolder)
                }

                else -> {
                    val request = contextRequest(parentId)
                    val spec = request.spec()
                    val ids =
                        db
                            .feedDao()
                            .contextList(
                                FeedQueryBuilder.contextList(spec, MAX_ITEMS, youtubePlayable()),
                            ).map {
                                it.episodeId
                            }
                    playable(ids, parentId)
                }
            }
        return page(children, page, pageSize)
    }

    suspend fun item(mediaId: String): MediaItem? {
        if (mediaId == ROOT) return root()
        if (mediaId in listOf(UP_NEXT, GROUPS, DOWNLOADS, PODCASTS)) {
            return children(ROOT, 0, ROOT_COUNT).first { it.mediaId == mediaId }
        }
        opener.awaitOpen()
        if (mediaId.startsWith("group:")) {
            val id = positiveId(mediaId.removePrefix("group:"))
            return database().groupDao().groupById(id)?.let { folder(mediaId, it.name) }
        }
        if (mediaId.startsWith("podcast:")) {
            val id = positiveId(mediaId.removePrefix("podcast:"))
            return database()
                .podcastDao()
                .listForBrowse(
                    youtubePlayable(),
                ).firstOrNull { it.id == id }
                ?.let(::podcastFolder)
        }
        val parsed = parseEpisode(mediaId)
        validateMembership(parsed)
        return playable(listOf(parsed.id), parsed.parent).singleOrNull()
    }

    suspend fun search(query: String): List<MediaItem> {
        require(query.isNotBlank() && query.length <= MAX_QUERY)
        opener.awaitOpen()
        val db = database()
        val groups =
            db
                .groupDao()
                .groupsInOrder()
                .filter {
                    it.name.contains(query, ignoreCase = true)
                }.take(SEARCH_LIMIT)
                .map { folder("group:${it.id}", it.name) }
        val podcasts =
            db
                .podcastDao()
                .listForBrowse(youtubePlayable())
                .filter {
                    (it.customTitle ?: it.title).contains(query, ignoreCase = true)
                }.take(SEARCH_LIMIT)
                .map(::podcastFolder)
        val episodes = db.episodeDao().searchTitles(literalPattern(query), SEARCH_LIMIT, youtubePlayable())
        return groups + podcasts + playable(episodes, null)
    }

    /** Resolve external identity/context before entering M4's gates and single-window start path. */
    suspend fun startRequest(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): StartRequest {
        require(mediaItems.size in 1..MAX_ITEMS)
        val index = if (startIndex == C.INDEX_UNSET) 0 else startIndex
        require(index in mediaItems.indices)
        val position = startPositionMs.takeUnless { it == C.TIME_UNSET }
        require(position == null || position >= 0)
        opener.awaitOpen()
        val query = mediaItems.singleOrNull()?.requestMetadata?.searchQuery
        if (query != null) {
            require(index == 0)
            return assistant(query)
        }
        val parsed = mediaItems.map { parseEpisode(it.mediaId) }
        if (parsed.size > 1) {
            require(parsed.all { it.parent == null })
            parsed.forEach { validateMembership(it) }
            return StartRequest.Episode(parsed[index].id, position, parsed.drop(index + 1).map { it.id })
        }
        val selected = parsed.single()
        validateMembership(selected)
        return when (selected.parent) {
            null, UP_NEXT -> {
                StartRequest.Episode(selected.id, position)
            }

            else -> {
                contextRequest(
                    selected.parent,
                ).copy(
                    startEpisodeId = selected.id,
                    positionMs = position,
                    membership = StartMembership.CONTEXT,
                )
            }
        }
    }

    private suspend fun assistant(query: String): StartRequest {
        require(query.length <= MAX_QUERY)
        if (query.isBlank()) return StartRequest.Resume
        val db = database()
        db.groupDao().groupsInOrder().firstOrNull { it.name.equals(query, ignoreCase = true) }?.let {
            return contextRequest("group:${it.id}")
        }
        db
            .podcastDao()
            .listForBrowse(youtubePlayable())
            .firstOrNull {
                (it.customTitle ?: it.title).contains(query, ignoreCase = true)
            }?.let {
                return contextRequest("podcast:${it.id}")
            }
        val id =
            db.episodeDao().searchTitles(literalPattern(query), 1, youtubePlayable()).firstOrNull()
                ?: throw UnsupportedOperationException("No matching library item")
        return StartRequest.Episode(id)
    }

    private suspend fun validateMembership(item: BrowseEpisode) {
        val parent = item.parent ?: return
        if (parent == UP_NEXT) {
            val queue = sessions().virtualQueue(0)
            require(
                (listOfNotNull(queue.current) + queue.upNext).any { it.episodeId == item.id },
            ) { "Episode left Up next" }
            return
        }
        val spec = contextRequest(parent).spec()
        require(
            database()
                .feedDao()
                .contextList(
                    FeedQueryBuilder.contextList(spec, 1, youtubePlayable(), item.id),
                ).isNotEmpty(),
        ) {
            "Episode is outside the requested context"
        }
    }

    private suspend fun contextRequest(parent: String): StartRequest.Feed {
        val db = database()
        if (parent.startsWith("group:")) {
            val group =
                requireNotNull(
                    db.groupDao().groupById(positiveId(parent.removePrefix("group:"))),
                ) { "Group was deleted" }
            val minDate =
                group.hideOlderThanDays?.let {
                    (clock.now() - it.toLong() * GroupCounts.DAY_MS).coerceAtLeast(
                        0,
                    )
                }
            return StartRequest.Feed(
                FeedSource.Group(group.id),
                FeedFilters.fromFlags(group.filterFlags, group.mediaFilter).copy(minSortDate = minDate),
                group.playOrder,
            )
        }
        require(parent.startsWith("podcast:")) { "Unknown browse parent" }
        val podcast =
            requireNotNull(db.podcastDao().byId(positiveId(parent.removePrefix("podcast:")))) { "Podcast was deleted" }
        require(youtubePlayable() || podcast.sourceType == SourceType.RSS)
        return StartRequest.Feed(
            FeedSource.Podcast(podcast.id),
            FeedFilters(unplayedOnly = true),
            podcast.episodeOrder ?: FeedOrder.NEWEST_FIRST,
        )
    }

    private fun StartRequest.Feed.spec(): PlayContextSpec =
        PlayContextSpec(
            type = if (source is FeedSource.Group) ContextType.GROUP else ContextType.PODCAST,
            contextId =
                when (val source = source) {
                    is FeedSource.Group -> source.groupId
                    is FeedSource.Podcast -> source.podcastId
                    else -> error("Not a browse context")
                },
            order = order,
            filterFlags = filters.toFlags(),
            mediaFilter = filters.media,
            minSortDate = filters.minSortDate,
            boundStartBySubscription = false,
        )

    private suspend fun upNext(): List<MediaItem> {
        val queue = sessions().virtualQueue(0)
        return playable(
            (listOfNotNull(queue.current) + queue.upNext).map { it.episodeId }.distinct().take(MAX_ITEMS),
            UP_NEXT,
        )
    }

    private suspend fun playable(
        ids: List<Long>,
        parent: String?,
    ): List<MediaItem> =
        ids.mapNotNull { id ->
            val row = media().get(id) ?: return@mapNotNull null
            if (row.refusal(capabilities.capabilities.value) != null) return@mapNotNull null
            val position = database().positionDao().byEpisode(id)?.positionMs
            items()
                .build(row, position)
                .buildUpon()
                .setMediaId("episode:$id" + (parent?.let { "@$it" } ?: ""))
                .build()
        }

    /** Only committed library changes, never episode_position ticks, invalidate subscribed nodes. */
    fun changes(): Flow<Unit> =
        flow {
            opener.awaitOpen()
            emitAll(
                combine(
                    database().invalidationTracker.createFlow(
                        "podcast",
                        "podcast_group",
                        "podcast_group_member",
                        "episode",
                        "episode_state",
                        "episode_alt_enclosure",
                        "download",
                        "artwork",
                        "queue_entry",
                        "play_session",
                    ),
                    capabilities.capabilities.map { it.inAppPlayback }.distinctUntilChanged(),
                ) { _, _ -> Unit },
            )
        }

    private fun youtubePlayable(): Boolean = capabilities.capabilities.value.inAppPlayback

    private fun podcastFolder(row: PodcastEntity): MediaItem =
        folder("podcast:${row.id}", row.customTitle ?: row.title, artworkKey = row.artworkKey)

    private fun folder(
        id: String,
        title: String,
        style: Int = LIST,
        artworkKey: String? = null,
    ): MediaItem =
        MediaItem
            .Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(
                        title,
                    ).setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setExtras(
                        Bundle().apply {
                            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, LIST)
                            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, style)
                        },
                    ).apply { artworkKey?.let { setArtworkUri(items().artworkUri(it)) } }
                    .build(),
            ).build()

    companion object {
        const val ROOT = "root"
        const val UP_NEXT = "upnext"
        const val GROUPS = "groups"
        const val DOWNLOADS = "downloads"
        const val PODCASTS = "podcasts"
        private const val ROOT_COUNT = 4
        private const val MAX_ITEMS = 100
        private const val SEARCH_LIMIT = 20
        private const val MAX_QUERY = 512
        private const val LIST = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        private const val GRID = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM

        fun validatePage(
            page: Int,
            pageSize: Int,
        ) {
            require(page >= 0 && pageSize > 0)
        }

        fun <T> page(
            items: List<T>,
            page: Int,
            pageSize: Int,
        ): List<T> {
            validatePage(page, pageSize)
            val start = (page.toLong() * pageSize).coerceAtMost(items.size.toLong()).toInt()
            return items.drop(start).take(pageSize.coerceAtMost(MAX_ITEMS))
        }

        fun parseEpisode(value: String): BrowseEpisode {
            val match =
                requireNotNull(
                    Regex(
                        "episode:([1-9][0-9]*)(?:@(upnext|group:[1-9][0-9]*|podcast:[1-9][0-9]*))?",
                    ).matchEntire(value),
                ) {
                    "Invalid episode identity"
                }
            return BrowseEpisode(positiveId(match.groupValues[1]), match.groupValues[2].ifEmpty { null })
        }

        private fun positiveId(value: String): Long =
            requireNotNull(
                value.toLongOrNull()?.takeIf {
                    it > 0 &&
                        it.toString() == value
                },
            )

        fun literalPattern(query: String): String =
            "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
    }
}

internal data class BrowseEpisode(
    val id: Long,
    val parent: String?,
)
