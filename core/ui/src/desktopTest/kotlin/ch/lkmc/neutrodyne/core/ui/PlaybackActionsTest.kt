// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.testing.FakePlaybackController
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import ch.lkmc.neutrodyne.core.testing.testNowPlaying
import ch.lkmc.neutrodyne.playback.api.PlayResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackActionsTest : MainDispatcherTest() {
    @Test fun replacementStartDiscardsLateConsentResult() =
        runTest {
            val controller = FakePlaybackController()
            val actions =
                PlaybackActions(
                    backgroundScope,
                    controller,
                    controller.state,
                    UserMessages(),
                    PlatformKind.ANDROID,
                    {},
                    { throw it },
                )
            val oldResult = CompletableDeferred<PlayResult>()
            var oldAttempted = false
            var staleDispatched = false
            actions.play(null) {
                oldAttempted = true
                oldResult.await().also { staleDispatched = true }
            }
            runCurrent()
            assertTrue(oldAttempted)
            actions.play(null) { PlayResult.Started }
            runCurrent()
            oldResult.complete(PlayResult.NeedsMeteredConsent(2))
            runCurrent()
            assertFalse(staleDispatched, "A superseded preparation must not dispatch playback")
            assertNull(actions.meteredConsent.value)
            actions.consentOnce()
            assertTrue(controller.calls.isEmpty())
        }

    @Test fun pauseCancelsAnOlderStartStillPreparing() =
        runTest {
            val controller = FakePlaybackController()
            controller.state.nowPlaying.value = testNowPlaying(1)
            val actions =
                PlaybackActions(
                    backgroundScope,
                    controller,
                    controller.state,
                    UserMessages(),
                    PlatformKind.ANDROID,
                    {},
                    { throw it },
                )
            val preparation = CompletableDeferred<Unit>()
            var staleDispatched = false
            actions.play(null) {
                preparation.await()
                staleDispatched = true
                PlayResult.Started
            }
            runCurrent()
            actions.playToggle(1, null) { error("Current must pause") }
            preparation.complete(Unit)
            runCurrent()
            assertFalse(staleDispatched)
            assertEquals(
                listOf<FakePlaybackController.Call>(
                    FakePlaybackController.Call.Transport(FakePlaybackController.Action.PAUSE),
                ),
                controller.calls,
            )
        }

    @Test fun clearingPublishedConsentDiscardsTheRetry() =
        runTest {
            val controller = FakePlaybackController()
            val actions =
                PlaybackActions(
                    backgroundScope,
                    controller,
                    controller.state,
                    UserMessages(),
                    PlatformKind.ANDROID,
                    {},
                    { throw it },
                )
            controller.state.pendingMeteredConsent.value = PlayResult.NeedsMeteredConsent(2)
            actions.play(null) { PlayResult.NeedsMeteredConsent(2) }
            runCurrent()
            assertEquals(2L, actions.meteredConsent.value?.episodeId)
            controller.state.pendingMeteredConsent.value = null
            runCurrent()
            assertNull(actions.meteredConsent.value)
            actions.consentOnce()
            assertTrue(controller.calls.isEmpty())
        }
}
