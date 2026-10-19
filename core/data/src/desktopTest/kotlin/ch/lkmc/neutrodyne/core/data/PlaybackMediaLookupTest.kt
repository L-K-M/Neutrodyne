// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.data.playback.PlaybackMediaLookupImpl
import ch.lkmc.neutrodyne.core.database.ArtworkEntity
import ch.lkmc.neutrodyne.core.database.EpisodeAltEnclosureEntity
import ch.lkmc.neutrodyne.core.database.EpisodePositionEntity
import ch.lkmc.neutrodyne.core.database.EpisodeStateEntity
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.AudioAlternate
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.PlaybackMediaInfo
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.testing.database.downloadEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackMediaLookupTest {
    private val db = newDb()
    private val lookup = PlaybackMediaLookupImpl(db)

    @AfterTest fun close() = db.close()

    @Test fun downloadMappingUsesSavedCompletedStateAndNullDefault() =
        runTest {
            val p = db.podcastDao().insertPodcast(podcastEntity())
            val id = db.episodeDao().insertStub(episodeEntity(podcastId = p))
            assertNull(lookup.get(id)!!.downloadState)
            db.downloadDao().insert(downloadEntity(id, state = DownloadState.COMPLETED))
            assertEquals(DownloadState.COMPLETED, lookup.get(id)!!.downloadState)
        }

    @Test fun alternateRankingTriesDefaultsThenOrdinalAndObservesReplacementWithoutPositionTicks() =
        runTest {
            val podcast = db.podcastDao().insertPodcast(podcastEntity())
            val id =
                db.episodeDao().insertStub(
                    episodeEntity(podcastId = podcast) {
                        copy(
                            isVideo = true,
                            enclosureUrl = "https://cdn/original.mp4",
                            enclosureType = "video/mp4",
                            chaptersUrl = "https://cdn/chapters.json",
                            chaptersType = "application/json+chapters",
                        )
                    },
                )
            val emissions = Channel<PlaybackMediaInfo>(Channel.UNLIMITED)
            val observer =
                backgroundScope.launch {
                    lookup.observe(setOf(id)).collect { rows -> rows[id]?.let { emissions.send(it) } }
                }

            suspend fun next() = withContext(Dispatchers.Default) { withTimeout(5000) { emissions.receive() } }

            suspend fun replace(rows: List<EpisodeAltEnclosureEntity>) {
                db.ingestDao().replaceChildren(id, null, emptyList(), rows, emptyList(), emptyList(), emptyList())
            }

            fun alternate(
                ordinal: Int,
                uri: String?,
                default: Boolean = false,
                type: String = "audio/mpeg",
            ) = EpisodeAltEnclosureEntity(
                id,
                ordinal,
                type,
                length = 42,
                isDefault = default,
                sourcesJson = uri?.let { """[{"uri":"$it"}]""" } ?: "[]",
            )
            try {
                val original = next()
                assertNull(original.audioAlternate)
                assertEquals("https://cdn/chapters.json", original.chaptersUrl)
                assertEquals("application/json+chapters", original.chaptersType)
                assertEquals("video/mp4", original.enclosureType)
                replace(
                    listOf(
                        alternate(0, null, true),
                        alternate(1, "https://cdn/ordinary.mp3"),
                        alternate(3, "https://cdn/later.mp3", true),
                        alternate(2, "https://cdn/earlier.mp3", true),
                        alternate(4, "https://cdn/video.mp4", true, "video/mp4"),
                    ),
                )
                assertEquals(AudioAlternate("https://cdn/earlier.mp3", "audio/mpeg", 42), next().audioAlternate)
                replace(
                    listOf(
                        alternate(0, null, true).copy(sourcesJson = "malformed"),
                        alternate(1, "relative.mp3", true),
                        alternate(2, "ftp://cdn/a", true),
                        alternate(3, "https://cdn/fallback.mp3"),
                    ),
                )
                val selected = next()
                assertEquals("https://cdn/fallback.mp3", selected.audioAlternate?.url)
                assertEquals("https://cdn/original.mp4", selected.enclosureUrl)
                assertTrue(selected.isVideo)
                assertEquals(selected, lookup.get(id))
                db.positionDao().upsert(
                    EpisodePositionEntity(id, 123, positionSource = PositionSource.STREAM, updatedAt = 1),
                )
                assertNull(withContext(Dispatchers.Default) { withTimeoutOrNull(100) { emissions.receive() } })
                replace(listOf(alternate(0, "file:///local")))
                assertNull(next().audioAlternate)
            } finally {
                observer.cancel()
            }
        }

    @Test fun mapsBothArtworkReferencesMeasuredDurationAndPlaybackInputs() =
        runTest {
            val podcast =
                db.podcastDao().insertPodcast(
                    podcastEntity {
                        copy(
                            customTitle = "Renamed",
                            author = "Host",
                            sourceType = SourceType.YOUTUBE_CHANNEL,
                            artworkKey = "u-pod",
                            artworkUrl = "https://img/podcast.jpg",
                        )
                    },
                )
            val episode =
                db.episodeDao().insertStub(
                    episodeEntity(podcastId = podcast, title = "Video", sortDate = 12345) {
                        copy(
                            enclosureUrl = "https://cdn/e.mp4",
                            enclosureLength = 42,
                            externalMediaId = "vid-1",
                            isVideo = true,
                            pubDate = 1_600_000_000_000L,
                            durationMs = 1000,
                            availability = Availability.LIVE,
                            artworkKey = "u-ep",
                            imageUrl = "https://img/ep.jpg",
                        )
                    },
                )
            db.artworkDao().insertIfAbsent(ArtworkEntity("u-ep", version = 7))
            db.artworkDao().insertIfAbsent(ArtworkEntity("u-pod", version = 3))
            db.episodeStateDao().upsert(
                EpisodeStateEntity(
                    episode,
                    playedAt =
                    500,
                    measuredDurationMs = 1500,
                    updatedAt = 1,
                ),
            )
            val info = lookup.get(episode)!!
            assertEquals(episode, info.episodeId)
            assertEquals(12345, info.sortDate)
            assertEquals(info, lookup.observe(setOf(episode)).first().getValue(episode))
            assertEquals(podcast, info.podcastId)
            assertEquals("Video", info.title)
            assertEquals("Renamed", info.podcastTitle)
            assertEquals("Host", info.podcastAuthor)
            assertEquals(1_600_000_000_000L, info.pubDate)
            assertEquals(ArtworkRef("u-ep", "https://img/ep.jpg", 7), info.artwork)
            assertEquals(ArtworkRef("u-pod", "https://img/podcast.jpg", 3), info.podcastArtwork)
            assertEquals(SourceType.YOUTUBE_CHANNEL, info.sourceType)
            assertEquals(Availability.LIVE, info.availability)
            assertTrue(info.isVideo)
            assertEquals("https://cdn/e.mp4", info.enclosureUrl)
            assertEquals(42, info.enclosureLength)
            assertEquals("vid-1", info.externalMediaId)
            assertEquals(1500, info.durationMs)
            assertEquals(1500, info.measuredDurationMs)
            assertEquals(500, info.playedAt)
            val plain = db.episodeDao().insertStub(episodeEntity(podcastId = podcast) { copy(durationMs = 2000) })
            val fallback = lookup.get(plain)!!
            assertEquals(info.podcastArtwork, fallback.artwork)
            assertEquals(2000, fallback.durationMs)
            assertNull(fallback.measuredDurationMs)
            assertNull(fallback.playedAt)
            assertNull(lookup.get(-1))
        }

    @Test fun observationChunksLargeSetsAndOmitsMissingRows() =
        runTest {
            val podcast = db.podcastDao().insertPodcast(podcastEntity())
            val ids = (1..503).map { db.episodeDao().insertStub(episodeEntity(podcastId = podcast)) }.toSet()
            val rows = lookup.observe(ids + -1L).first()
            assertEquals(ids, rows.keys)
            assertEquals(
                ids,
                db
                    .episodeDao()
                    .mediaInfo(ids.toList())
                    .map { it.id }
                    .toSet(),
            )
            assertTrue(lookup.observe(emptySet()).first().isEmpty())
        }
}
