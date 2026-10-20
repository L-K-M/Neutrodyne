// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.data.playback.PlaybackMediaLookupImpl
import ch.lkmc.neutrodyne.core.database.ArtworkEntity
import ch.lkmc.neutrodyne.core.database.EpisodeStateEntity
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackMediaLookupTest {
    private val db = newDb()
    private val lookup = PlaybackMediaLookupImpl(db)

    @AfterTest fun close() = db.close()

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
