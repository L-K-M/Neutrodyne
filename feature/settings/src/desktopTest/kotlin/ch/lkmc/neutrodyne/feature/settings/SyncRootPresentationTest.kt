// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import ch.lkmc.neutrodyne.core.testing.FakePlaybackController
import ch.lkmc.neutrodyne.core.testing.FakeSyncController
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.UserMessage
import ch.lkmc.neutrodyne.core.ui.root.RemoteSessionCard
import ch.lkmc.neutrodyne.feature.settings.resources.Res
import ch.lkmc.neutrodyne.feature.settings.resources.sync_marked_played_elsewhere
import ch.lkmc.neutrodyne.sync.api.RemoteSessionOffer
import ch.lkmc.neutrodyne.sync.api.SyncNotice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The MS3 shell bridge (08 Continue on this device / Sync notices): the offer maps onto the
 * card the root renders, Continue reaches `PlaybackController.play()` and nothing else,
 * Dismiss reaches `SyncController.dismissRemoteSession()`, and `MarkedPlayedElsewhere` —
 * and only that notice — lands on the shell's snackbar flow. A closed gate resolves nothing:
 * `SyncController`'s impl dereferences the database at construction, so an early resolution
 * would both throw on the main thread and defeat the inert start-up frame.
 */
@OptIn(ExperimentalTestApi::class)
class SyncRootPresentationTest {
    private val offer =
        RemoteSessionOffer(
            episodeId = 42,
            title = "Episode 7",
            podcastTitle = "Show",
            positionMs = 1_394_000,
            deviceName = "Pixel 8",
        )

    private class Probe {
        var syncResolutions = 0
        var presentation: SyncRootPresentation? = null
        val messages = mutableListOf<UserMessage>()
    }

    @Test
    fun theOfferMapsToTheCardAndContinueOnlyResumes() =
        runComposeUiTest {
            val sync = FakeSyncController()
            val playback = FakePlaybackController()
            val probe = setSyncContent(sync, playback)

            runOnIdle { sync.remoteSession.value = offer }

            waitUntil("the offer becomes the card", TIMEOUT_MS) {
                probe.presentation?.remoteSession != null
            }
            assertEquals(
                RemoteSessionCard(
                    title = "Episode 7",
                    podcastTitle = "Show",
                    positionText = "23:14",
                    deviceName = "Pixel 8",
                ),
                probe.presentation?.remoteSession,
            )

            runOnIdle { probe.presentation?.continueHere?.invoke() }

            waitUntil("Continue reached play()", TIMEOUT_MS) { playback.calls.isNotEmpty() }
            // Deliberate resume only: a no-op, an episode pick or auto-play all fail this.
            assertEquals(
                listOf<FakePlaybackController.Call>(
                    FakePlaybackController.Call.Transport(
                        FakePlaybackController.Action.PLAY,
                    ),
                ),
                playback.calls,
            )
            assertTrue(sync.remoteSessionDismissals.isEmpty())
        }

    @Test
    fun dismissReachesTheControllerAndDropsTheCard() =
        runComposeUiTest {
            val sync = FakeSyncController()
            sync.remoteSession.value = offer
            val probe = setSyncContent(sync, FakePlaybackController())

            waitUntil("the card shows", TIMEOUT_MS) { probe.presentation?.remoteSession != null }
            runOnIdle { probe.presentation?.dismissRemoteSession?.invoke() }

            waitUntil("dismiss reached the service", TIMEOUT_MS) {
                sync.remoteSessionDismissals.size == 1
            }
            waitUntil("the cleared offer hides the card", TIMEOUT_MS) {
                probe.presentation?.remoteSession == null
            }
        }

    /**
     * The inert pre-open frame (01's start-up rule): a closed gate resolves no service and
     * publishes neither an offer nor a notice — the previous shell resolved nothing at all,
     * so resolving here would regress to eager service/database construction.
     */
    @Test
    fun aClosedGateResolvesNothingAndStaysInert() =
        runComposeUiTest {
            val sync = FakeSyncController()
            sync.remoteSession.value = offer
            var enabled by mutableStateOf(false)
            val probe = setSyncContent(sync, FakePlaybackController(), enabled = { enabled })

            waitUntil("the first frame settled", TIMEOUT_MS) { probe.presentation != null }
            waitForIdle()
            assertEquals(0, probe.syncResolutions)
            assertNull(probe.presentation?.remoteSession)

            runOnIdle {
                sync.post(SyncNotice.MarkedPlayedElsewhere(42, "Episode 7", "Pixel 8"))
                probe.presentation?.dismissRemoteSession?.invoke()
            }
            waitForIdle()
            assertTrue(probe.messages.isEmpty())
            assertTrue(sync.remoteSessionDismissals.isEmpty())
            assertEquals(0, probe.syncResolutions)

            runOnIdle { enabled = true }
            waitUntil("the opened gate resolves the service", TIMEOUT_MS) {
                probe.syncResolutions > 0
            }
            waitUntil("the already-waiting offer shows", TIMEOUT_MS) {
                probe.presentation?.remoteSession != null
            }
            // Notices are one-shot events on a replay-less flow: the one posted while closed is
            // gone; only a notice reaching the service with a live collector lands.
            waitUntil("the notices flow is subscribed", TIMEOUT_MS) {
                sync.noticeSubscriptions.value > 0
            }
            runOnIdle { sync.post(SyncNotice.MarkedPlayedElsewhere(42, "Episode 7", "Pixel 8")) }
            waitUntil("the post-open notice lands", TIMEOUT_MS) { probe.messages.size == 1 }
        }

    /**
     * 08: the card takes the mini player's slot and the paused adopted item stays underneath —
     * a loaded-but-paused `NowPlaying` must never suppress the offer. Only an actively playing
     * local session hides it; adopter eligibility stays service-side.
     */
    @Test
    fun aPausedLoadedItemKeepsTheCardAndOnlyPlayingHidesIt() =
        runComposeUiTest {
            val sync = FakeSyncController()
            sync.remoteSession.value = offer
            var playing by mutableStateOf(false)
            val probe =
                setSyncContent(
                    sync,
                    FakePlaybackController(),
                    isPlaying = { playing },
                )

            waitUntil("the card shows beside a paused player", TIMEOUT_MS) {
                probe.presentation?.remoteSession != null
            }

            runOnIdle { playing = true }
            waitUntil("actual playback hides the card", TIMEOUT_MS) {
                probe.presentation?.remoteSession == null
            }
            assertEquals(offer, sync.remoteSession.value)

            runOnIdle { playing = false }
            waitUntil("pausing re-shows the card", TIMEOUT_MS) {
                probe.presentation?.remoteSession != null
            }
        }

    /**
     * A callback surviving from a stale frame (queued tap, cleared or replaced offer, closed
     * gate) must not resume an unrelated session nor retract a newer offer. The guards compare
     * the last-rendered offer with the service's live `remoteSession` at invoke time.
     */
    @Test
    fun staleRenderedActionsCannotResumeOrHideAnotherOffer() =
        runComposeUiTest {
            val sync = FakeSyncController()
            val playback = FakePlaybackController()
            val probe = setSyncContent(sync, playback)

            runOnIdle { sync.remoteSession.value = offer }
            waitUntil("offer A renders", TIMEOUT_MS) {
                probe.presentation?.remoteSession?.title == "Episode 7"
            }
            var staleContinue: (() -> Unit)? = null
            var staleDismiss: (() -> Unit)? = null
            runOnIdle {
                staleContinue = probe.presentation?.continueHere
                staleDismiss = probe.presentation?.dismissRemoteSession
            }

            runOnIdle { sync.remoteSession.value = offer.copy(title = "Episode 8") }
            waitUntil("newer offer B renders", TIMEOUT_MS) {
                probe.presentation?.remoteSession?.title == "Episode 8"
            }

            runOnIdle {
                staleContinue?.invoke()
                staleDismiss?.invoke()
            }
            waitForIdle()
            assertTrue(playback.calls.isEmpty())
            assertTrue(sync.remoteSessionDismissals.isEmpty())
            assertEquals("Episode 8", probe.presentation?.remoteSession?.title)

            runOnIdle { probe.presentation?.continueHere?.invoke() }
            waitUntil("the current frame's Continue plays", TIMEOUT_MS) {
                playback.calls.contains(
                    FakePlaybackController.Call.Transport(FakePlaybackController.Action.PLAY),
                )
            }

            runOnIdle { sync.remoteSession.value = offer.copy(title = "Episode 8") }
            waitUntil("offer B renders again", TIMEOUT_MS) {
                probe.presentation?.remoteSession?.title == "Episode 8"
            }
            runOnIdle {
                staleContinue = probe.presentation?.continueHere
                sync.remoteSession.value = null
            }
            waitUntil("the cleared offer hides the card", TIMEOUT_MS) {
                probe.presentation?.remoteSession == null
            }
            runOnIdle { staleContinue?.invoke() }
            waitForIdle()
            assertEquals(
                1,
                playback.calls.size,
            )
        }

    @Test
    fun aClosedGateMakesRetainedActionsInert() =
        runComposeUiTest {
            val sync = FakeSyncController()
            val playback = FakePlaybackController()
            var enabled by mutableStateOf(true)
            val probe = setSyncContent(sync, playback, enabled = { enabled })

            runOnIdle { sync.remoteSession.value = offer }
            waitUntil("the card renders", TIMEOUT_MS) {
                probe.presentation?.remoteSession != null
            }
            var continueHere: (() -> Unit)? = null
            var dismiss: (() -> Unit)? = null
            runOnIdle {
                continueHere = probe.presentation?.continueHere
                dismiss = probe.presentation?.dismissRemoteSession
                enabled = false
            }
            waitForIdle()
            val resolutions = probe.syncResolutions

            runOnIdle {
                continueHere?.invoke()
                dismiss?.invoke()
            }
            waitForIdle()
            assertTrue(playback.calls.isEmpty())
            assertTrue(sync.remoteSessionDismissals.isEmpty())
            // The gate is checked before the thunk: no stale action may resolve the service.
            assertEquals(resolutions, probe.syncResolutions)
        }

    @Test
    fun clearedAndNewerOffersFollowTheServiceState() =
        runComposeUiTest {
            val sync = FakeSyncController()
            val probe = setSyncContent(sync, FakePlaybackController())

            runOnIdle { sync.remoteSession.value = offer }
            waitUntil("the first offer shows", TIMEOUT_MS) {
                probe.presentation?.remoteSession?.title == "Episode 7"
            }

            runOnIdle { sync.remoteSession.value = null }
            waitUntil("the cleared offer hides", TIMEOUT_MS) {
                probe.presentation?.remoteSession == null
            }

            runOnIdle { sync.remoteSession.value = offer.copy(title = "Episode 8") }
            waitUntil("the newer offer shows", TIMEOUT_MS) {
                probe.presentation?.remoteSession?.title == "Episode 8"
            }
        }

    @Test
    fun markedPlayedElsewhereIsTheOnlyNoticeOnTheSnackbarFlow() =
        runComposeUiTest {
            val sync = FakeSyncController()
            val probe = setSyncContent(sync, FakePlaybackController())
            waitUntil("the notice collector resolved", TIMEOUT_MS) {
                sync.noticeSubscriptions.value > 0
            }

            runOnIdle {
                sync.post(SyncNotice.Resynced(5))
                sync.post(SyncNotice.FeedPasswordNeeded(listOf(1L, 2L)))
                sync.post(SyncNotice.PasswordSet("2026-11-09"))
                sync.post(SyncNotice.MarkedPlayedElsewhere(42, "Episode 7", "Pixel 8"))
            }

            waitUntil("the one notice lands", TIMEOUT_MS) { probe.messages.size == 1 }
            assertEquals(
                UiText.Res(
                    Res.string.sync_marked_played_elsewhere,
                    listOf("Pixel 8"),
                ),
                probe.messages.single().text,
            )
        }

    private fun ComposeUiTest.setSyncContent(
        sync: FakeSyncController,
        playback: FakePlaybackController,
        enabled: () -> Boolean = { true },
        isPlaying: () -> Boolean = { false },
    ): Probe {
        val probe = Probe()
        setContent {
            probe.presentation =
                rememberSyncRootPresentation(
                    syncController = {
                        probe.syncResolutions++
                        sync
                    },
                    playbackController = { playback },
                    enabled = enabled(),
                    isPlaying = isPlaying(),
                )
            LaunchedEffect(probe.presentation?.userMessages) {
                probe.presentation?.userMessages?.collect { probe.messages += it }
            }
        }
        return probe
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
