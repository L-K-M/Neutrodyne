// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.testing.FakePlaybackController
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import ch.lkmc.neutrodyne.core.testing.testNowPlaying
import ch.lkmc.neutrodyne.playback.api.PlayResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackActionsTest : MainDispatcherTest() {
    private fun TestScope.actions(
        controller: FakePlaybackController,
        allowMeteredStreaming: suspend () -> Unit = {},
        onFailure: (Throwable) -> Unit = { throw it },
    ) = PlaybackActions(
        backgroundScope,
        controller,
        controller.state,
        UserMessages(),
        PlatformKind.ANDROID,
        allowMeteredStreaming,
        onFailure,
    )

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

    @Test fun alwaysCannotReplaceAnotherScreensNewerPrompt() =
        runTest {
            val controller = FakePlaybackController()
            val policyWrite = CompletableDeferred<Unit>()
            val attempts = mutableListOf<Long>()
            val first = actions(controller, { policyWrite.await() })
            val second = actions(controller)
            val original = PlayResult.NeedsMeteredConsent(1)
            controller.state.pendingMeteredConsent.value = original
            first.play(null) {
                attempts += 1
                original
            }
            runCurrent()
            first.consentAlways()
            runCurrent()

            val replacement = PlayResult.NeedsMeteredConsent(2)
            second.play(null) {
                attempts += 2
                controller.state.pendingMeteredConsent.value = null
                controller.state.pendingMeteredConsent.value = replacement
                replacement
            }
            runCurrent()
            policyWrite.complete(Unit)
            runCurrent()

            assertEquals(listOf(1L, 2L), attempts)
            assertEquals(2L, second.meteredConsent.value?.episodeId)
            assertTrue(controller.calls.isEmpty(), "An obsolete policy decision must not grant consent")
        }

    @Test fun sameEpisodeReplacementInvalidatesConsentBeforeTheCollectorRuns() =
        runTest {
            val controller = FakePlaybackController()
            val actions = actions(controller)
            val original = PlayResult.NeedsMeteredConsent(1)
            controller.state.pendingMeteredConsent.value = original
            actions.play(null) { original }
            runCurrent()

            // A new start can target the same episode with a different context.
            controller.state.pendingMeteredConsent.value = null
            controller.state.pendingMeteredConsent.value = PlayResult.NeedsMeteredConsent(1)
            actions.consentOnce()
            runCurrent()

            assertTrue(controller.calls.isEmpty())
            assertNull(actions.meteredConsent.value)
        }

    @Test fun aRefusalWithoutItsPublishedPromptDoesNotOpenConsent() =
        runTest {
            val controller = FakePlaybackController()
            val actions = actions(controller)
            actions.play(null) { PlayResult.NeedsMeteredConsent(1) }
            runCurrent()
            assertNull(actions.meteredConsent.value)
        }

    @Test fun ownedConsentRetriesOnlyAfterThePolicyWriteCompletes() =
        runTest {
            val controller = FakePlaybackController()
            val policyWrite = CompletableDeferred<Unit>()
            val actions = actions(controller, { policyWrite.await() })
            val original = PlayResult.NeedsMeteredConsent(1)
            controller.state.pendingMeteredConsent.value = original
            var attempts = 0
            actions.play(null) { if (++attempts == 1) original else PlayResult.Started }
            runCurrent()
            actions.consentAlways()
            runCurrent()
            assertEquals(1, attempts)
            assertTrue(controller.calls.isEmpty())
            policyWrite.complete(Unit)
            runCurrent()
            assertEquals(2, attempts)
            assertEquals(
                listOf<FakePlaybackController.Call>(
                    FakePlaybackController.Call.Transport(FakePlaybackController.Action.CONSENT),
                ),
                controller.calls,
            )
        }

    @Test fun failedPolicyWriteNeitherGrantsNorRetries() =
        runTest {
            val controller = FakePlaybackController()
            val failures = mutableListOf<Throwable>()
            val failure = IllegalStateException("Policy write failed")
            val actions = actions(controller, { throw failure }, failures::add)
            val original = PlayResult.NeedsMeteredConsent(1)
            controller.state.pendingMeteredConsent.value = original
            var attempts = 0
            actions.play(null) {
                attempts++
                original
            }
            runCurrent()
            actions.consentAlways()
            runCurrent()
            assertEquals(1, attempts)
            assertTrue(controller.calls.isEmpty())
            assertEquals(listOf<Throwable>(failure), failures)
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
