// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.app.Application
import android.os.Looper
import androidx.media3.common.Player
import ch.lkmc.neutrodyne.core.domain.ContextRetention
import ch.lkmc.neutrodyne.core.domain.Departure
import ch.lkmc.neutrodyne.core.domain.PlaySessionStore
import ch.lkmc.neutrodyne.core.domain.SessionRevision
import ch.lkmc.neutrodyne.core.domain.SessionStart
import ch.lkmc.neutrodyne.core.model.QueueOrigin
import ch.lkmc.neutrodyne.playback.core.QueueWindowPlanner
import ch.lkmc.neutrodyne.playback.core.StartOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class QueueProjectorEventFenceTest {
    @Test fun oldEndCannotClearANewerStartDuringProjection() = runTest { staleEvent(Event.END) }

    @Test fun oldArrivalCannotPromoteOverANewerStartDuringProjection() = runTest { staleEvent(Event.ARRIVAL) }

    @Test fun consecutiveArrivalsUseTheRevisionCommittedByThePreviousEvent() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            var holdFirst = true
            var commits = 0
            val f =
                AdapterFixture(this, sessionStore = { real ->
                    object : PlaySessionStore by real {
                        override suspend fun onTransition(
                            departure: Departure?,
                            arrivedEpisodeId: Long,
                            arrivedOrigin: QueueOrigin,
                            expected: SessionRevision,
                            now: Long,
                        ): Long? {
                            if (holdFirst) {
                                holdFirst = false
                                entered.complete(Unit)
                                release.await()
                            }
                            return real.onTransition(departure, arrivedEpisodeId, arrivedOrigin, expected, now).also {
                                commits++
                                if (commits == 2) finished.complete(Unit)
                            }
                        }
                    }
                })
            try {
                val ids = f.seed(3)
                f.attach()
                f.start(ids)
                val generation = f.sessions.virtualQueue(QueueWindowPlanner.CONTEXT_SIZE).generation
                f.player.seekToNextMediaItem()
                awaitAdapter { entered.isCompleted }
                f.player.seekToNextMediaItem()
                release.complete(Unit)
                awaitAdapter { finished.isCompleted }
                val persisted = f.sessions.virtualQueue(QueueWindowPlanner.CONTEXT_SIZE)
                assertEquals(ids[2], persisted.current?.episodeId)
                assertEquals(generation + 2, persisted.generation)
                assertTrue(f.failures.isEmpty())
                assertEquals(emptyList(), persisted.upNext)
            } finally {
                release.complete(Unit)
                f.close()
            }
        }

    @Test fun aNewSessionCanEndWhileTheOldSessionsEndIsWaiting() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            var ends = 0
            val f =
                AdapterFixture(this, sessionStore = { real ->
                    object : PlaySessionStore by real {
                        override suspend fun endSession(
                            departure: Departure?,
                            expected: SessionRevision,
                            now: Long,
                        ): Long? {
                            ends++
                            if (ends == 1) {
                                entered.complete(Unit)
                                release.await()
                            }
                            return real.endSession(departure, expected, now).also {
                                if (ends == 2) finished.complete(Unit)
                            }
                        }
                    }
                })
            try {
                val ids = f.seed(2)
                f.attach()
                f.start(listOf(ids[0]))
                f.projector.onPlaybackStateChanged(Player.STATE_ENDED)
                awaitAdapter { entered.isCompleted }
                f.projector.beginOwnWrite()
                val generation =
                    f.sessions.commitStart(SessionStart(ids[1], null, ContextRetention.KEEP, null, null), f.clock.now())
                f.projector.applyStart(
                    StartOutcome.Committed(f.sessions.virtualQueue(QueueWindowPlanner.CONTEXT_SIZE), 0),
                )
                f.projector.endOwnWrite(generation)
                f.projector.onPlaybackStateChanged(Player.STATE_ENDED)
                release.complete(Unit)
                awaitAdapter { finished.isCompleted }
                val persisted = f.sessions.virtualQueue(QueueWindowPlanner.CONTEXT_SIZE)
                assertEquals(null, persisted.current)
                assertEquals(generation + 1, persisted.generation)
            } finally {
                release.complete(Unit)
                f.close()
            }
        }

    @Test fun retriedEndCannotBorrowANewerStartsRevision() = runTest { staleRetry(Event.END) }

    @Test fun retriedArrivalCannotBorrowANewerStartsRevision() = runTest { staleRetry(Event.ARRIVAL) }

    private suspend fun TestScope.staleRetry(event: Event) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
        var firstAttempt = true
        val f =
            AdapterFixture(this, sessionStore = { real ->
                SignallingSessions(real, completed) {
                    if (firstAttempt) {
                        firstAttempt = false
                        entered.complete(Unit)
                        release.await()
                        throw IOException("Injected first-attempt failure")
                    }
                }
            })
        try {
            val ids = f.seed(3)
            f.attach()
            f.start(if (event == Event.END) listOf(ids[0]) else ids)
            when (event) {
                Event.END -> f.projector.onPlaybackStateChanged(Player.STATE_ENDED)
                Event.ARRIVAL -> f.player.seekToNextMediaItem()
            }
            awaitAdapter { entered.isCompleted }
            f.start(listOf(ids[2]))
            val committed = f.sessions.virtualQueue(QueueWindowPlanner.CONTEXT_SIZE)
            release.complete(Unit)
            awaitAdapter { completed.isCompleted }
            val persisted = f.sessions.virtualQueue(QueueWindowPlanner.CONTEXT_SIZE)
            assertEquals(ids[2], persisted.current?.episodeId, "A retry must retain its original decision revision")
            assertEquals(committed.generation, persisted.generation)
        } finally {
            release.complete(Unit)
            f.close()
        }
    }

    private enum class Event { END, ARRIVAL }

    private suspend fun TestScope.staleEvent(event: Event) {
        val completed = CompletableDeferred<Unit>()
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val projection = CoroutineName("held explicit start")
        lateinit var lookup: GatedLookup
        val f =
            AdapterFixture(
                this,
                sessionStore = { SignallingSessions(it, completed) },
                mediaLookup = { real -> GatedLookup(real, projection).also { lookup = it } },
            )
        var applying: Deferred<Unit>? = null
        try {
            val ids = f.seed(3)
            f.attach()
            f.start(if (event == Event.END) listOf(ids[0]) else ids)
            val title = "Projection ready"
            f.db.podcastDao().setCustomTitle(f.lookup.get(ids[0])!!.podcastId, title)
            awaitAdapter {
                f.player.currentMediaItem
                    ?.mediaMetadata
                    ?.albumTitle
                    ?.toString() == title
            }
            f.projector.beginOwnWrite()
            val generation =
                f.sessions.commitStart(SessionStart(ids[2], null, ContextRetention.KEEP, null, null), f.clock.now())
            val snapshot = f.sessions.virtualQueue(QueueWindowPlanner.CONTEXT_SIZE)
            lookup.barrier = {
                reached.complete(Unit)
                release.await()
            }
            applying = async(projection) { f.projector.applyStart(StartOutcome.Committed(snapshot, 0)) }
            awaitAdapter("New start did not reach the projection barrier") { reached.isCompleted }

            when (event) {
                Event.END -> f.projector.onPlaybackStateChanged(Player.STATE_ENDED)
                Event.ARRIVAL -> f.player.seekToNextMediaItem()
            }
            shadowOf(Looper.getMainLooper()).idle()
            awaitAdapter("The old event never reached storage") { completed.isCompleted }
            val persisted = f.sessions.virtualQueue(QueueWindowPlanner.CONTEXT_SIZE)
            assertEquals(ids[2], persisted.current?.episodeId, "Old player events must not replace the committed start")
            assertEquals(generation, persisted.generation)

            release.complete(Unit)
            applying.await()
            f.projector.endOwnWrite(generation)
            awaitAdapter { f.player.currentMediaItem?.mediaId == "episode:${ids[2]}" }
        } finally {
            release.complete(Unit)
            applying?.cancelAndJoin()
            f.close()
        }
    }
}

private class SignallingSessions(
    private val delegate: PlaySessionStore,
    private val completed: CompletableDeferred<Unit>,
    private val beforeEvent: suspend () -> Unit = {},
) : PlaySessionStore by delegate {
    override suspend fun endSession(
        departure: Departure?,
        expected: SessionRevision,
        now: Long,
    ): Long? {
        beforeEvent()
        return delegate.endSession(departure, expected, now).also { completed.complete(Unit) }
    }

    override suspend fun onTransition(
        departure: Departure?,
        arrivedEpisodeId: Long,
        arrivedOrigin: QueueOrigin,
        expected: SessionRevision,
        now: Long,
    ): Long? {
        beforeEvent()
        return delegate
            .onTransition(
                departure,
                arrivedEpisodeId,
                arrivedOrigin,
                expected,
                now,
            ).also { completed.complete(Unit) }
    }
}
