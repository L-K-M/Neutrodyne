// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.data.playback.PlaybackMediaLookupImpl
import ch.lkmc.neutrodyne.core.data.playback.PositionWriter
import ch.lkmc.neutrodyne.core.data.playback.QueueRepositoryImpl
import ch.lkmc.neutrodyne.core.data.playback.SessionWriter
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.QueueEntryEntity
import ch.lkmc.neutrodyne.core.domain.ContextRetention
import ch.lkmc.neutrodyne.core.domain.Departure
import ch.lkmc.neutrodyne.core.domain.SessionStart
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.model.PlayContextSpec
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.model.QueueOrigin
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.core.testing.ProtocolOrderKeys
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.FeedInvalidationProbe
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class InvalidationHygieneTest {
    private val context =
        PlayContextSpec(ContextType.ALL, null, FeedOrder.NEWEST_FIRST, 1, MediaFilter.ALL, null, false)

    @Test
    fun steadyTicksEmitNothingOnTheLowChurnFlows() =
        runTest {
            val db = TestDb.inMemory(queryContext = StandardTestDispatcher(testScheduler))
            val counter = FeedInvalidationProbe.Counter(db, this, FeedSource.All)
            try {
                val positions = PositionWriter(db)
                val sessions = SessionWriter(db, ProtocolOrderKeys, FakeYouTubeCapabilitiesSource())
                val queue =
                    QueueRepositoryImpl(
                        db,
                        sessions,
                        ProtocolOrderKeys,
                        TestClock(nowMs = NOW),
                        FakeYouTubeCapabilitiesSource(),
                    )
                val media = PlaybackMediaLookupImpl(db)
                val ids = seed(db, 4)
                db.queueDao().insert(QueueEntryEntity(episodeId = ids[3], orderKey = "a", addedAt = NOW))
                sessions.commitStart(SessionStart(ids[0], context, ContextRetention.REPLACE, ids[0], 400L), NOW)
                assertTrue(save(positions, ids[0], 5_000, NOW + 1))
                assertTrue(save(positions, ids[1], 5_000, NOW + 1))

                counter.open()
                var mediaEmissions = 0
                var upNextEmissions = 0
                var virtualEmissions = 0
                backgroundScope.launch { media.observe(ids.take(2).toSet()).collect { mediaEmissions++ } }
                backgroundScope.launch { queue.observeUpNext().collect { upNextEmissions++ } }
                backgroundScope.launch { queue.observeVirtualQueue(20).collect { virtualEmissions++ } }
                drain(db)
                drain(db)
                assertEquals(1, mediaEmissions, "media initial")
                assertEquals(1, upNextEmissions, "upNext initial")
                assertEquals(1, virtualEmissions, "virtualQueue initial")

                repeat(STEADY_TICKS) { tick ->
                    assertTrue(save(positions, ids[tick % 2], 6_000L + tick, NOW + 10 + tick))
                    counter.drainAndRearm()
                }
                assertEquals(0, counter.invalidations, "steady position saves")
                assertEquals(1, mediaEmissions, "media after $STEADY_TICKS ticks")
                assertEquals(1, upNextEmissions, "upNext after $STEADY_TICKS ticks")
                assertEquals(1, virtualEmissions, "virtualQueue after $STEADY_TICKS ticks")

                positions.markPlayed(ids[0], NOW + 200)
                counter.drainAndRearm()
                assertEquals(1, counter.invalidations, "one mark-played transaction")
                assertEquals(2, mediaEmissions, "media markPlayed")
                assertEquals(2, upNextEmissions, "upNext markPlayed")
                queue.remove(listOf(ids[3]))
                counter.drainAndRearm()
                assertEquals(1, counter.invalidations, "queue edits leave feed pages alone")
                assertEquals(2, mediaEmissions, "media untouched by queue writes")
                assertEquals(3, upNextEmissions, "upNext remove")
                assertEquals(2, virtualEmissions, "virtualQueue remove")
            } finally {
                counter.close()
                db.close()
            }
        }

    private suspend fun seed(
        db: NeutrodyneDatabase,
        count: Int,
    ): List<Long> {
        val podcast = db.podcastDao().insertPodcast(podcastEntity())
        return (count downTo 1).map {
            db.episodeDao().insertStub(episodeEntity(podcastId = podcast, sortDate = it * 100L))
        }
    }

    private suspend fun save(
        positions: PositionWriter,
        id: Long,
        pos: Long,
        now: Long,
    ): Boolean = positions.save(id, pos, null, PositionSource.STREAM, now, pinStartedAt = NOW) { true }

    private suspend fun TestScope.drain(db: NeutrodyneDatabase) {
        db.invalidationTracker.refresh()
        runCurrent()
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val STEADY_TICKS = 25
    }
}
