// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.app.Application
import ch.lkmc.neutrodyne.core.domain.PlaybackBeforeUnsubscribe
import ch.lkmc.neutrodyne.core.domain.UnsubscribeUseCase
import ch.lkmc.neutrodyne.core.testing.FakeNewEpisodePoster
import ch.lkmc.neutrodyne.core.testing.FakePlaybackBeforeUnsubscribe
import ch.lkmc.neutrodyne.core.testing.FakePodcastRepository
import ch.lkmc.neutrodyne.playback.api.PlayResult
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PlaybackControllerTest {
    @Test fun invisibleUiStartReturnsUnavailableWithoutBuildingOrOpening() {
        LifecycleHarness(RuntimeEnvironment.getApplication()).use { h ->
            assertEquals(PlayResult.ServiceUnavailable, h.run { h.controller.playEpisode(1) })
            assertEquals(PlayResult.ServiceUnavailable, h.run { h.controller.play() })
            h.controller.pause()
            h.controller.seekTo(0)
            h.controller.skipToNext()
            assertEquals(0, h.dbProviderReads)
            assertNull(h.connection.controller)
        }
    }

    @Test fun queueEditsAndDisconnectedDismissDoNotCreateService() {
        LifecycleHarness(RuntimeEnvironment.getApplication()).use { h ->
            h.seedCurrent()
            h.run {
                h.controller.playNext(listOf(2))
                h.controller.playLast(listOf(3))
            }
            assertEquals(listOf(2L), h.queues.calls[0].ids)
            assertEquals(listOf(3L), h.queues.calls[1].ids)
            h.run { h.controller.dismiss() }
            assertNull(h.store.queue.current)
            assertNull(h.connection.controller)
        }
    }

    @Test fun unsubscribeWaitsForPauseBeforeCascading() {
        LifecycleHarness(RuntimeEnvironment.getApplication()).use { h ->
            val podcasts = FakePodcastRepository()
            podcasts.subscribedIds.value = setOf(10L)
            val playback = FakePlaybackBeforeUnsubscribe()
            playback.beforePause = { assertTrue(10L in podcasts.subscribedIds.value) }
            val useCase = UnsubscribeUseCase(podcasts, FakeNewEpisodePoster(), playback)
            assertEquals(listOf(10L), h.run { useCase(listOf(10)) })
            assertEquals(listOf(listOf(10L)), playback.calls)
            assertTrue(podcasts.subscribedIds.value.isEmpty())
        }
    }
}
