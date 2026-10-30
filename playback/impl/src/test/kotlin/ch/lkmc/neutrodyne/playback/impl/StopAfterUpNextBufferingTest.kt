// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.app.Application
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.model.PlayContextSpec
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.memberEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression coverage for the M4 review finding: "Stop after Up next" (`queue.clearContext()`)
 * bumps `play_session.generation`, which the planner reads as a foreign update. While the accepted
 * start is still buffering (`playWhenReady = true`, `isPlaying = false`) that foreign read must
 * preserve the play intent and the live position; it may only drop the context tail.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class StopAfterUpNextBufferingTest {
    @Test fun clearContextDuringBufferingKeepsPlayIntentPositionAndUpNext() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val ids = f.seed(4)
                val podcast = f.lookup.get(ids[0])!!.podcastId
                val group = f.db.groupDao().insert(groupEntity(orderKey = "a0"))
                f.db.groupDao().insertMember(memberEntity(group, podcast, "a0"))
                val context =
                    PlayContextSpec(
                        ContextType.GROUP,
                        group,
                        FeedOrder.NEWEST_FIRST,
                        0,
                        MediaFilter.ALL,
                        null,
                        false,
                    )
                f.attach()
                f.start(ids.take(2), 63_000, context)
                awaitAdapter { f.player.mediaItemCount == 4 }
                // Accepted start still buffering: play intent on, isPlaying() reads false.
                f.player.play()
                f.player.advancing = false
                f.player.calls.clear()

                f.queue.clearContext()
                awaitAdapter { f.player.ids() == ids.take(2) }

                assertTrue("the buffering start lost its play intent", f.player.playWhenReady)
                assertEquals(63_000L, f.player.currentPosition)
                assertEquals(ids.take(2), f.player.ids())
                assertFalse("the unchanged current was reloaded", f.player.calls.contains("set"))
                assertFalse("the unchanged current was re-prepared", f.player.calls.contains("prepare"))
                val snapshot = f.sessions.virtualQueue(20)
                assertEquals(ids[0], snapshot.current!!.episodeId)
                assertEquals(listOf(ids[1]), snapshot.upNext.map { it.episodeId })
                assertTrue(snapshot.contextTail.isEmpty())
                assertTrue(f.failures.toString(), f.failures.isEmpty())
            } finally {
                f.player.advancing = null
                f.close()
            }
        }

    /**
     * The guard that must remain: an explicitly paused player (`playWhenReady = false`) still loads
     * paused on a foreign generation, never autoplays.
     */
    @Test fun clearContextWhileExplicitlyPausedStaysPaused() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val ids = f.seed(4)
                val podcast = f.lookup.get(ids[0])!!.podcastId
                val group = f.db.groupDao().insert(groupEntity(orderKey = "a0"))
                f.db.groupDao().insertMember(memberEntity(group, podcast, "a0"))
                val context =
                    PlayContextSpec(
                        ContextType.GROUP,
                        group,
                        FeedOrder.NEWEST_FIRST,
                        0,
                        MediaFilter.ALL,
                        null,
                        false,
                    )
                f.attach()
                f.start(ids.take(2), 63_000, context)
                awaitAdapter { f.player.mediaItemCount == 4 }
                f.player.play()
                awaitAdapter { f.player.playWhenReady }
                f.player.pause()
                awaitAdapter { !f.player.playWhenReady }
                f.awaitSaved(ids[0], 63_000)
                f.player.calls.clear()

                f.queue.clearContext()
                awaitAdapter { f.player.ids() == ids.take(2) }

                assertFalse("a foreign update autoplays an explicitly paused player", f.player.playWhenReady)
                assertFalse(f.player.isPlaying)
                assertEquals("the paused reload restores the stored position", 63_000L, f.player.currentPosition)
                assertTrue("the paused foreign reload was skipped", f.player.calls.contains("set"))
                assertFalse("a paused foreign load prepared", f.player.calls.contains("prepare"))
                assertNull(f.player.playerError)
                assertTrue(f.failures.toString(), f.failures.isEmpty())
            } finally {
                f.player.advancing = null
                f.close()
            }
        }
}
