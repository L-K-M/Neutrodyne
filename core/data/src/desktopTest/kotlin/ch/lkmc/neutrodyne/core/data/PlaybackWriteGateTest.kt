// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.data.playback.PositionWriter
import ch.lkmc.neutrodyne.core.data.playback.SessionWriter
import ch.lkmc.neutrodyne.core.domain.ContextRetention
import ch.lkmc.neutrodyne.core.domain.Departure
import ch.lkmc.neutrodyne.core.domain.PositionWriteGate
import ch.lkmc.neutrodyne.core.domain.SessionRevision
import ch.lkmc.neutrodyne.core.domain.SessionStart
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.model.QueueOrigin
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.core.testing.ProtocolOrderKeys
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackWriteGateTest {
    private val clock = TestClock(nowMs = 100)
    private val db = newDb(clock)
    private var allowed = true
    private val gate = PositionWriteGate { allowed }
    private val positions = PositionWriter(db, clock, gate)
    private val sessions = SessionWriter(db, ProtocolOrderKeys, FakeYouTubeCapabilitiesSource(), gate)

    @AfterTest fun close() = db.close()

    private suspend fun seed(): List<Long> {
        val podcast = db.podcastDao().insertPodcast(podcastEntity(feedUrl = "https://gate.example/feed"))
        return db.ingestDao().insertEpisodes(
            listOf(
                episodeEntity(podcastId = podcast, identityKey = "g:first"),
                episodeEntity(podcastId = podcast, identityKey = "g:second"),
            ),
        )
    }

    @Test fun capturedDepartureCannotSaveOrMarkAfterTheFreeze() =
        runTest {
            val (first, second) = seed()
            positions.save(first, 20_000, 100_000, PositionSource.STREAM, 100, 1, { true })
            sessions.commitStart(SessionStart(first, null, ContextRetention.REPLACE, null, null), 100)
            val generation = requireNotNull(db.playSessionDao().get()).generation
            val captured = Departure(first, 80_000, 100_000, PositionSource.STREAM, 1, true, true)
            allowed = false
            sessions.onTransition(captured, second, QueueOrigin.UP_NEXT, SessionRevision(first, generation), 200)
            assertEquals(second, db.playSessionDao().get()?.currentEpisodeId, "the arrival still commits")
            assertEquals(
                20_000L,
                db.positionDao().byEpisode(first)?.positionMs,
                "the captured departure must recheck the freeze",
            )
            assertNull(db.episodeStateDao().byEpisode(first)?.playedAt, "the departure cannot stamp a new local mark")
        }

    @Test fun aQueuedResetCannotOverwriteTheFrozenPosition() =
        runTest {
            val id = seed().first()
            positions.save(id, 20_000, 100_000, PositionSource.STREAM, 100, 1, { true })
            val before = db.positionDao().byEpisode(id)
            allowed = false
            positions.reset(id, 200)
            assertEquals(before, db.positionDao().byEpisode(id), "the reset must recheck the freeze")
        }

    @Test fun aQueuedReplayCannotClearTheWinningRemoteMark() =
        runTest {
            val id = seed().first()
            positions.markPlayed(id, 100)
            val before = db.episodeStateDao().byEpisode(id)
            allowed = false
            positions.markUnplayedForReplay(id, 200)
            assertEquals(before, db.episodeStateDao().byEpisode(id), "the replay must recheck the freeze")
        }

    @Test fun aResetReadsTheGateAfterAcquiringTheWriter() =
        runTest {
            val id = seed().first()
            positions.save(id, 20_000, 100_000, PositionSource.STREAM, 100, 1, { true })
            val before = db.positionDao().byEpisode(id)
            val locked = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val blocker =
                async {
                    db.withWriteTransaction {
                        locked.complete(Unit)
                        release.await()
                    }
                }
            locked.await()
            val queued = async(start = CoroutineStart.UNDISPATCHED) { positions.reset(id, 200) }
            allowed = false
            release.complete(Unit)
            blocker.await()
            kotlin.test.assertFalse(queued.await())
            assertEquals(before, db.positionDao().byEpisode(id))
        }
}
