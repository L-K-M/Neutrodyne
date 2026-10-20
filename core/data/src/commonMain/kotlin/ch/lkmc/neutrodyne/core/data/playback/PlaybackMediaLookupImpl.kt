// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.playback

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.database.EpisodeAltEnclosureEntity
import ch.lkmc.neutrodyne.core.database.MediaLookupRow
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.PlaybackMediaLookup
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.AudioAlternate
import ch.lkmc.neutrodyne.core.model.PlaybackMediaInfo
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.feeds.model.AlternateEnclosureSource
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * 06's media reads over 02's `MediaLookupRow` (the port contract in `PlaybackMediaLookup`): each
 * DAO batch reads metadata and alternates transactionally; `get` returns one committed snapshot, `observe` emits only on the
 * low-churn metadata/state/artwork/download tables the query joins, so position ticks never
 * re-emit. The `IN`-list chunking lives in `EpisodeDao.mediaInfo`/`observeMediaInfo`.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class PlaybackMediaLookupImpl(
    private val db: NeutrodyneDatabase,
) : PlaybackMediaLookup {
    override suspend fun get(episodeId: Long): PlaybackMediaInfo? =
        db
            .episodeDao()
            .mediaInfo(listOf(episodeId))
            .singleOrNull()
            ?.toInfo()

    override fun observe(episodeIds: Set<Long>): Flow<Map<Long, PlaybackMediaInfo>> =
        db
            .episodeDao()
            .observeMediaInfo(episodeIds.toList())
            .map { rows -> rows.associate { it.id to it.toInfo() } }
            .distinctUntilChanged()

    private fun MediaLookupRow.selectedAudioAlternate(): AudioAlternate? {
        val ranked =
            audioAlternates
                .filter { it.type.startsWith("audio/", ignoreCase = true) }
                .sortedWith(compareByDescending<EpisodeAltEnclosureEntity> { it.isDefault }.thenBy { it.ordinal })
        for (row in ranked) {
            val sources =
                try {
                    Json.decodeFromString<List<AlternateEnclosureSource>>(row.sourcesJson)
                } catch (_: SerializationException) {
                    continue
                }
            val source =
                sources.firstOrNull { source ->
                    UrlNormalizer.origin(source.uri) != null
                } ?: continue
            return AudioAlternate(source.uri, row.type, row.length)
        }
        return null
    }

    private fun MediaLookupRow.toInfo(): PlaybackMediaInfo =
        PlaybackMediaInfo(
            episodeId = id,
            podcastId = podcastId,
            title = title,
            podcastTitle = podcastTitle,
            artwork = ArtworkRef(artworkKey, artworkUrl, artworkVersion),
            sourceType = sourceType,
            availability = availability,
            isVideo = isVideo,
            enclosureUrl = enclosureUrl,
            enclosureLength = enclosureLength,
            externalMediaId = externalMediaId,
            durationMs = durationMs,
            measuredDurationMs = measuredDurationMs,
            playedAt = playedAt,
            sortDate = sortDate,
            podcastArtwork = ArtworkRef(podcastArtworkKey, podcastArtworkUrl, podcastArtworkVersion),
            podcastAuthor = author,
            pubDate = pubDate,
            enclosureType = enclosureType,
            chaptersUrl = chaptersUrl,
            chaptersType = chaptersType,
            audioAlternate = selectedAudioAlternate(),
            downloadState = downloadState,
        )
}
