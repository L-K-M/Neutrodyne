// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.database

import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeStateEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** W1 froze SQL alongside the interfaces. These are W2's real SQLite predicate proofs. */
class AutoDownloadQueriesTest {
    private val db = TestDb.inMemory()
    private val dao = db.downloadDao()

    @After fun close() = db.close()

    @Test fun candidatePredicatesExcludeBackCatalogueInvisibleUnavailableAndTombstones() =
        runTest {
            val podcast = podcast()
            val eligible = episode(podcast)
            episode(podcast) { copy(isNew = false) }
            episode(podcast) { copy(firstSeenAt = 100) }
            episode(podcast) { copy(firstSeenAt = 99) }
            episode(podcast) { copy(isShort = true) }
            episode(podcast) { copy(enclosureUrl = null) }
            for (availability in Availability.entries.filter { it != Availability.AVAILABLE }) {
                episode(podcast) { copy(availability = availability) }
            }
            val played = episode(podcast)
            db.episodeStateDao().upsert(episodeStateEntity(played, playedAt = 1))
            val dismissed = episode(podcast)
            db.episodeStateDao().upsert(episodeStateEntity(dismissed) { copy(downloadDismissedAt = 1) })
            val otherPodcast = episode(podcast())
            assertEquals(listOf(eligible), dao.autoCandidates(podcast, 100, 100, false, false).map { it.id })
            assertFalse(dao.autoCandidates(podcast, 100, 100, false, false).any { it.id == otherPodcast })
            assertTrue(dao.autoCandidates(podcast, 101, 100, false, false).isEmpty())
        }

    @Test fun includeVideoAndYoutubeCapabilityAreIndependentGates() =
        runTest {
            val podcast = podcast()
            val audio = episode(podcast)
            val video = episode(podcast) { copy(isVideo = true) }
            val externalAudio = episode(podcast) { copy(enclosureUrl = null, externalMediaId = "audio-id") }
            val externalVideo =
                episode(podcast) { copy(enclosureUrl = null, externalMediaId = "video-id", isVideo = true) }
            assertEquals(setOf(audio), candidates(podcast, includeVideo = false, youtube = false))
            assertEquals(setOf(audio, video), candidates(podcast, includeVideo = true, youtube = false))
            assertEquals(setOf(audio, externalAudio), candidates(podcast, includeVideo = false, youtube = true))
            assertEquals(
                setOf(audio, video, externalAudio, externalVideo),
                candidates(podcast, includeVideo = true, youtube = true),
            )
        }

    private suspend fun podcast() = db.podcastDao().insertPodcast(podcastEntity())

    private suspend fun episode(
        podcast: Long,
        change: EpisodeEntity.() -> EpisodeEntity = { this },
    ) = db.episodeDao().insertStub(
        episodeEntity(podcastId = podcast, sortDate = 200) {
            copy(isNew = true, firstSeenAt = 101, enclosureUrl = "https://example.com/audio.mp3").change()
        },
    )

    private suspend fun candidates(
        podcast: Long,
        includeVideo: Boolean,
        youtube: Boolean,
    ) = dao.autoCandidates(podcast, 100, 100, includeVideo, youtube).map { it.id }.toSet()
}
