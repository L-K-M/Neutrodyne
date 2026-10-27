// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.app.Application
import android.os.Looper
import androidx.media3.common.Player
import ch.lkmc.neutrodyne.core.domain.ContextRetention
import ch.lkmc.neutrodyne.core.domain.Departure
import ch.lkmc.neutrodyne.core.domain.PlaySessionStore
import ch.lkmc.neutrodyne.core.domain.SessionStart
import ch.lkmc.neutrodyne.core.model.QueueOrigin
import ch.lkmc.neutrodyne.playback.core.StartOutcome
import ch.lkmc.neutrodyne.playback.core.QueueWindowPlanner
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class QueueProjectorEventFenceTest {
    @Test fun oldEndCannotClearANewerStartDuringProjection() = runTest { staleEvent(Event.END) }

    @Test fun oldArrivalCannotPromoteOverANewerStartDuringProjection() = runTest { staleEvent(Event.ARRIVAL) }

    private enum class Event { END, ARRIVAL }

    private suspend fun TestScope.staleEvent(event: Event) {
        val completed = CompletableDeferred<Unit>()
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        lateinit var lookup: GatedLookup
        val f =
            AdapterFixture(
                this,
                sessionStore = { real ->
                    object : PlaySessionStore by real {
                        override suspend fun endSession(departure: Departure?, now: Long): Long =
                            real.endSession(departure, now).also { completed.complete(Unit) }

                        override suspend fun onTransition(
                            departure: Departure?,
                            arrivedEpisodeId: Long,
                            arrivedOrigin: QueueOrigin,
                            now: Long,
                        ): Long = real.onTransition(departure, arrivedEpisodeId, arrivedOrigin, now).also { completed.complete(Unit) }
                    }
                },
                mediaLookup = { real -> GatedLookup(real).also { lookup = it } },
            )
        var applying: Deferred<Unit>? = null
        try {
            val ids = f.seed(3)
            f.attach()
            f.start(ids)
            val title = "Projection ready"
            f.db.podcastDao().setCustomTitle(f.lookup.get(ids[0])!!.podcastId, title)
            awaitAdapter { f.player.currentMediaItem?.mediaMetadata?.albumTitle?.toString() == title }
            f.projector.beginOwnWrite()
            val generation = f.sessions.commitStart(SessionStart(ids[2], null, ContextRetention.KEEP, null, null), f.clock.now())
            val snapshot = f.sessions.virtualQueue(QueueWindowPlanner.CONTEXT_SIZE)
            lookup.barrier = {
                reached.complete(Unit)
                release.await()
            }
            applying = async { f.projector.applyStart(StartOutcome.Committed(snapshot, 0)) }
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
