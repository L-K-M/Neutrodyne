// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.PodcastKey
import ch.lkmc.neutrodyne.core.navigation.SyncAccountHelpKey
import ch.lkmc.neutrodyne.core.testing.FakePlaybackController
import ch.lkmc.neutrodyne.core.testing.FakeSyncController
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.UserMessage
import ch.lkmc.neutrodyne.core.ui.root.RemoteSessionCard
import ch.lkmc.neutrodyne.feature.settings.resources.Res
import ch.lkmc.neutrodyne.feature.settings.resources.sync_feed_password_needed
import ch.lkmc.neutrodyne.feature.settings.resources.sync_marked_played_elsewhere
import ch.lkmc.neutrodyne.feature.settings.resources.sync_not_you
import ch.lkmc.neutrodyne.feature.settings.resources.sync_password_set
import ch.lkmc.neutrodyne.feature.settings.resources.sync_resynced
import ch.lkmc.neutrodyne.feature.settings.resources.sync_resynced_added
import ch.lkmc.neutrodyne.feature.settings.resources.sync_show
import ch.lkmc.neutrodyne.sync.api.RemoteSessionOffer
import ch.lkmc.neutrodyne.sync.api.SyncNotice
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    private class Probe(
        val navigator: RecordingAppNavigator = RecordingAppNavigator(),
    ) {
        var syncResolutions = 0
        var presentation: SyncRootPresentation? = null
        val messages = mutableListOf<UserMessage>()
        var holding = true
        val holdFirst = CompletableDeferred<Unit>()
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
    fun everyNoticeLandsOnTheSnackbarFlowInOrder() =
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

            waitUntil("all four notices land", TIMEOUT_MS) { probe.messages.size == 4 }
            assertEquals(
                listOf(
                    UiText.Joined(
                        listOf(
                            UiText.Res(Res.string.sync_resynced),
                            UiText.Res(Res.string.sync_resynced_added, listOf(5)),
                        ),
                        separator = "",
                        suffix = "",
                    ),
                    UiText.Plural(Res.plurals.sync_feed_password_needed, 2, listOf(2)),
                    UiText.Res(Res.string.sync_password_set, listOf("2026-11-09")),
                    UiText.Res(Res.string.sync_marked_played_elsewhere, listOf("Pixel 8")),
                ),
                probe.messages.map { it.text },
            )
            assertEquals(
                listOf(
                    null,
                    UiText.Res(Res.string.sync_show),
                    UiText.Res(Res.string.sync_not_you),
                    null,
                ),
                probe.messages.map { it.action },
            )
            // Negative, monotonic ids: the desktop banner notifier owns the nonnegative ones.
            assertEquals(listOf(-1L, -2L, -3L, -4L), probe.messages.map { it.id })
        }

    /** 08: the resync suffix appears only when the re-asserted count is positive. */
    @Test
    fun aResyncWithoutAdditionsOmitsTheSuffix() =
        runComposeUiTest {
            val sync = FakeSyncController()
            val probe = setSyncContent(sync, FakePlaybackController())
            waitUntil("the notice collector resolved", TIMEOUT_MS) {
                sync.noticeSubscriptions.value > 0
            }

            runOnIdle { sync.post(SyncNotice.Resynced(0)) }
            waitUntil("the notice lands", TIMEOUT_MS) { probe.messages.size == 1 }
            assertEquals(UiText.Res(Res.string.sync_resynced), probe.messages.single().text)
            assertNull(probe.messages.single().action)
        }

    /** An empty id list has no first podcast to open, so the action is omitted entirely. */
    @Test
    fun anEmptyFeedPasswordNeedOmitsTheAction() =
        runComposeUiTest {
            val sync = FakeSyncController()
            val probe = setSyncContent(sync, FakePlaybackController())
            waitUntil("the notice collector resolved", TIMEOUT_MS) {
                sync.noticeSubscriptions.value > 0
            }

            runOnIdle { sync.post(SyncNotice.FeedPasswordNeeded(emptyList())) }
            waitUntil("the notice lands", TIMEOUT_MS) { probe.messages.size == 1 }
            assertEquals(
                UiText.Plural(Res.plurals.sync_feed_password_needed, 0, listOf(0)),
                probe.messages.single().text,
            )
            assertNull(probe.messages.single().action)
        }

    /**
     * The active notice's "Show" opens the first named podcast; the queued notice stays queued
     * until the snackbar is acknowledged, a stale id is inert, and a positive (desktop banner) id
     * is never consumed.
     */
    @Test
    fun theActiveShowActionOpensTheFirstPodcastAndStaleOnesAreInert() =
        runComposeUiTest {
            val sync = FakeSyncController()
            val probe =
                setSyncContent(
                    sync,
                    FakePlaybackController(),
                    holdFirstMessage = true,
                )
            waitUntil("the notice collector resolved", TIMEOUT_MS) {
                sync.noticeSubscriptions.value > 0
            }
            runOnIdle {
                sync.post(SyncNotice.FeedPasswordNeeded(listOf(7L, 9L)))
                sync.post(SyncNotice.MarkedPlayedElsewhere(42, "Episode 7", "Pixel 8"))
            }

            waitUntil("the first notice lands", TIMEOUT_MS) { probe.messages.size == 1 }
            val first = probe.messages.single()
            // The second notice is queued behind the visible snackbar.
            assertEquals(1, probe.messages.size)

            runOnIdle { assertTrue(probe.presentation!!.messageAction(first.id)) }
            waitUntil("Show opened the first podcast", TIMEOUT_MS) {
                probe.navigator.calls.any { it == RecordingAppNavigator.Call.Push(PodcastKey(7L)) }
            }
            // Idempotent: a second tap while the snackbar is still visible does not push again.
            runOnIdle { assertFalse(probe.presentation!!.messageAction(first.id)) }

            runOnIdle { probe.holdFirst.complete(Unit) }
            waitUntil("the queued notice lands", TIMEOUT_MS) { probe.messages.size == 2 }

            runOnIdle {
                assertFalse(probe.presentation!!.messageAction(first.id))
                // A positive id belongs to the desktop banner notifier, never to sync.
                assertFalse(probe.presentation!!.messageAction(0L))
            }
            waitForIdle()
            assertEquals(
                listOf<RecordingAppNavigator.Call>(
                    RecordingAppNavigator.Call.Push(PodcastKey(7L)),
                ),
                probe.navigator.calls,
            )
        }

    /** "Not you?" opens the static sync-account help page, and only while that notice is active. */
    @Test
    fun theNotYouActionOpensTheSyncAccountHelpPage() =
        runComposeUiTest {
            val sync = FakeSyncController()
            val probe =
                setSyncContent(
                    sync,
                    FakePlaybackController(),
                    holdFirstMessage = true,
                )
            waitUntil("the notice collector resolved", TIMEOUT_MS) {
                sync.noticeSubscriptions.value > 0
            }
            runOnIdle { sync.post(SyncNotice.PasswordSet("2026-11-09")) }

            waitUntil("the notice lands", TIMEOUT_MS) { probe.messages.size == 1 }
            val first = probe.messages.single()
            assertEquals(UiText.Res(Res.string.sync_not_you), first.action)

            runOnIdle { assertTrue(probe.presentation!!.messageAction(first.id)) }
            waitUntil("the help page opened", TIMEOUT_MS) {
                probe.navigator.calls.any {
                    it == RecordingAppNavigator.Call.Push(SyncAccountHelpKey)
                }
            }

            runOnIdle { probe.holdFirst.complete(Unit) }
            waitForIdle()
            runOnIdle { assertFalse(probe.presentation!!.messageAction(first.id)) }
            waitForIdle()
            assertEquals(
                listOf<RecordingAppNavigator.Call>(
                    RecordingAppNavigator.Call.Push(SyncAccountHelpKey),
                ),
                probe.navigator.calls,
            )
        }

    /** A closed gate emits no notices and dispatches no actions, even for an already-active one. */
    @Test
    fun aClosedGateStopsNoticesAndDispatchesNoActions() =
        runComposeUiTest {
            val sync = FakeSyncController()
            var enabled by mutableStateOf(true)
            val probe =
                setSyncContent(
                    sync,
                    FakePlaybackController(),
                    enabled = { enabled },
                    holdFirstMessage = true,
                )
            waitUntil("the notice collector resolved", TIMEOUT_MS) {
                sync.noticeSubscriptions.value > 0
            }
            runOnIdle { sync.post(SyncNotice.FeedPasswordNeeded(listOf(7L))) }
            waitUntil("the notice lands", TIMEOUT_MS) { probe.messages.size == 1 }
            val first = probe.messages.single()

            runOnIdle { enabled = false }
            waitForIdle()
            runOnIdle {
                assertFalse(probe.presentation!!.messageAction(first.id))
                sync.post(SyncNotice.PasswordSet("2026-11-09"))
            }
            runOnIdle { probe.holdFirst.complete(Unit) }
            waitForIdle()
            // The notice posted after the close is skipped, and no action navigated.
            assertEquals(1, probe.messages.size)
            assertTrue(probe.navigator.calls.isEmpty())
        }

    private fun ComposeUiTest.setSyncContent(
        sync: FakeSyncController,
        playback: FakePlaybackController,
        enabled: () -> Boolean = { true },
        isPlaying: () -> Boolean = { false },
        holdFirstMessage: Boolean = false,
    ): Probe {
        val probe = Probe()
        setContent {
            CompositionLocalProvider(LocalAppNavigator provides probe.navigator) {
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
                // The shells invoke this inside the player slot; here it is the only navigator
                // source, exactly as under NeutrodyneRoot.
                probe.presentation?.feedback()
                LaunchedEffect(probe.presentation?.userMessages) {
                    probe.presentation?.userMessages?.collect { message ->
                        probe.messages += message
                        if (holdFirstMessage && probe.holding) {
                            probe.holding = false
                            probe.holdFirst.await()
                        }
                    }
                }
            }
        }
        return probe
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
