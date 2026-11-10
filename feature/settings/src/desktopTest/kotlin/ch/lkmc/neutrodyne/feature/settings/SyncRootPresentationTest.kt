// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import ch.lkmc.neutrodyne.core.ui.root.HeldChangesBanner
import ch.lkmc.neutrodyne.core.ui.root.ReconnectProfile
import ch.lkmc.neutrodyne.core.ui.root.RemoteSessionCard
import ch.lkmc.neutrodyne.feature.settings.resources.Res
import ch.lkmc.neutrodyne.feature.settings.resources.sync_feed_password_needed
import ch.lkmc.neutrodyne.feature.settings.resources.sync_marked_played_elsewhere
import ch.lkmc.neutrodyne.feature.settings.resources.sync_not_you
import ch.lkmc.neutrodyne.feature.settings.resources.sync_password_set
import ch.lkmc.neutrodyne.feature.settings.resources.sync_resynced
import ch.lkmc.neutrodyne.feature.settings.resources.sync_resynced_added
import ch.lkmc.neutrodyne.feature.settings.resources.sync_show
import ch.lkmc.neutrodyne.sync.api.MassChangePrompt
import ch.lkmc.neutrodyne.sync.api.RemoteSessionOffer
import ch.lkmc.neutrodyne.sync.api.SyncActivity
import ch.lkmc.neutrodyne.sync.api.SyncNotice
import ch.lkmc.neutrodyne.sync.api.SyncStatus
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

    /**
     * R7.7 + 08 Banners: the presentation maps the oldest published `MassChangePrompt` onto
     * `RootUiState.heldChanges` — the DAO already publishes `ORDER BY heldAt, id`, so the banner
     * is the flow's first element — and follows the list as held rows resolve or arrive.
     */
    @Test
    fun theOldestHeldPromptMapsToTheBannerAndFollowsTheList() =
        runComposeUiTest {
            val sync = FakeSyncController(status = linked)
            val probe = setSyncContent(sync, FakePlaybackController())

            runOnIdle {
                sync.heldChanges.value =
                    listOf(
                        held(7, "Pixel 8", podcasts = 2, groups = 1),
                        held(9, "Desktop", podcasts = 1, groups = 0),
                    )
            }
            waitUntil("the oldest prompt maps to the banner", TIMEOUT_MS) {
                probe.presentation?.heldChanges != null
            }
            assertEquals(
                HeldChangesBanner(7, "Pixel 8", 2, 1),
                probe.presentation?.heldChanges,
            )

            runOnIdle { sync.heldChanges.value = listOf(held(9, "Desktop", 1, 0)) }
            waitUntil("the resolved row leaves the next-oldest", TIMEOUT_MS) {
                probe.presentation?.heldChanges?.id == 9L
            }
            assertEquals(
                HeldChangesBanner(9, "Desktop", 1, 0),
                probe.presentation?.heldChanges,
            )

            runOnIdle { sync.heldChanges.value = emptyList() }
            waitUntil("the emptied list hides the banner", TIMEOUT_MS) {
                probe.presentation?.heldChanges == null
            }
        }

    /**
     * `MassChangePrompt.deviceName == null` passes through as `HeldChangesBanner.deviceName ==
     * null` — `RootBanners` renders the generic "Another device" wording — and a prompt with no
     * group removals reports a zero group count.
     */
    @Test
    fun aPromptWithoutADeviceOrGroupsMapsTheFallbacks() =
        runComposeUiTest {
            val sync = FakeSyncController(status = linked)
            val probe = setSyncContent(sync, FakePlaybackController())

            runOnIdle { sync.heldChanges.value = listOf(held(3, null, podcasts = 4, groups = 0)) }
            waitUntil("the prompt maps", TIMEOUT_MS) { probe.presentation?.heldChanges != null }
            assertEquals(
                HeldChangesBanner(3, null, 4, 0),
                probe.presentation?.heldChanges,
            )
        }

    /**
     * R7.1: only a Linked profile shows sync UI. NotConfigured hides the banner even while the
     * service still lists prompts, unlink drops it immediately (the Review target is bound
     * per-frame, so a cleared banner can no longer navigate), and a relinked profile shows only
     * what its own publication carries.
     */
    @Test
    fun onlyALinkedProfileShowsTheBanner() =
        runComposeUiTest {
            val sync = FakeSyncController()
            sync.heldChanges.value = listOf(held(7, "Pixel 8", 2, 1))
            val probe = setSyncContent(sync, FakePlaybackController())

            waitUntil("the presentation exists", TIMEOUT_MS) { probe.presentation != null }
            waitForIdle()
            assertNull(probe.presentation?.heldChanges)

            runOnIdle { sync.status.value = linked }
            waitUntil("linking shows the held prompt", TIMEOUT_MS) {
                probe.presentation?.heldChanges?.id == 7L
            }

            runOnIdle { sync.status.value = SyncStatus.NotConfigured }
            waitUntil("unlink clears the banner", TIMEOUT_MS) {
                probe.presentation?.heldChanges == null
            }

            runOnIdle {
                sync.heldChanges.value = listOf(held(11, "Desktop", 1, 1))
                sync.status.value = linked.copy(accountId = "acct-2", deviceId = "dev-2")
            }
            waitUntil("the relinked profile shows its own prompt", TIMEOUT_MS) {
                probe.presentation?.heldChanges?.id == 11L
            }
            assertEquals(
                HeldChangesBanner(11, "Desktop", 1, 1),
                probe.presentation?.heldChanges,
            )
        }

    /**
     * A closed database gate resolves nothing and publishes no banner (the inert pre-open frame
     * covers NotConfigured — the service thunk is never dereferenced), and dropping the gate
     * afterwards clears the projected banner immediately even though the service's list is
     * unchanged.
     */
    @Test
    fun aClosedGatePublishesNoBannerAndADroppedGateClearsIt() =
        runComposeUiTest {
            val sync = FakeSyncController(status = linked)
            sync.heldChanges.value = listOf(held(7, "Pixel 8", 2, 1))
            var enabled by mutableStateOf(false)
            val probe = setSyncContent(sync, FakePlaybackController(), enabled = { enabled })

            waitUntil("the first frame settled", TIMEOUT_MS) { probe.presentation != null }
            waitForIdle()
            assertEquals(0, probe.syncResolutions)
            assertNull(probe.presentation?.heldChanges)

            runOnIdle { enabled = true }
            waitUntil("the opened gate publishes the banner", TIMEOUT_MS) {
                probe.presentation?.heldChanges?.id == 7L
            }

            runOnIdle { enabled = false }
            waitUntil("the dropped gate clears the banner", TIMEOUT_MS) {
                probe.presentation?.heldChanges == null
            }
        }

    @Test
    fun onlyReconnectPublishesTheReconnectBanner() =
        runComposeUiTest {
            val status = SyncStatus.Reconnect("https://sync.example/private", "anna")
            val sync = FakeSyncController(status = status)
            val probe = setSyncContent(sync, FakePlaybackController())
            for (other in listOf(
                SyncStatus.NotConfigured,
                SyncStatus.Revoked(status.serverUrl),
                SyncStatus.LinkUnfinished(status.serverUrl),
                linked,
            )) {
                runOnIdle { sync.status.value = status }
                waitUntil("reconnect is published", TIMEOUT_MS) { probe.presentation?.reconnect != null }
                assertEquals(status.serverUrl, probe.presentation?.reconnect?.serverUrl)
                runOnIdle { sync.status.value = other }
                waitUntil("other states withdraw reconnect", TIMEOUT_MS) { probe.presentation?.reconnect == null }
            }
        }

    @Test
    fun notNowSurvivesCompositionRecreationButANewProcessStartsFresh() =
        runComposeUiTest {
            val status = SyncStatus.Reconnect("https://sync.example", "anna")
            val sync = FakeSyncController(status = status)
            var holder = ReconnectDismissals()
            var generation by mutableIntStateOf(0)
            val probe =
                setSyncContent(
                    sync,
                    FakePlaybackController(),
                    dismissals = { holder },
                    generation = { generation },
                )
            waitUntil("reconnect is shown", TIMEOUT_MS) { probe.presentation?.reconnect != null }
            val banner = probe.presentation!!.reconnect!!
            runOnIdle { probe.presentation!!.dismissReconnect(banner) }
            waitUntil("Not now hides this process profile", TIMEOUT_MS) {
                holder.isDismissed(banner.profile) && probe.presentation?.reconnect == null
            }
            val before = probe.syncResolutions
            runOnIdle { generation++ }
            waitUntil("a recreated composition resolves its controller", TIMEOUT_MS) {
                probe.syncResolutions > before
            }
            waitForIdle()
            assertNull(probe.presentation?.reconnect)
            runOnIdle {
                holder = ReconnectDismissals()
                generation++
            }
            waitUntil("a new process holder shows reconnect again", TIMEOUT_MS) {
                probe.presentation?.reconnect?.profile == banner.profile
            }
        }

    @Test
    fun staleProfileActionsCannotDismissOrOpenAnotherProfile() =
        runComposeUiTest {
            val first = SyncStatus.Reconnect("https://sync.example/path", "anna")
            val second = first.copy(username = "bob")
            val sync = FakeSyncController(status = first)
            val holder = ReconnectDismissals()
            val probe = setSyncContent(sync, FakePlaybackController(), dismissals = { holder })
            waitUntil("the first banner is shown", TIMEOUT_MS) { probe.presentation?.reconnect != null }
            val old = probe.presentation!!
            val banner = old.reconnect!!
            runOnIdle { sync.status.value = second }
            waitUntil("the second profile is shown", TIMEOUT_MS) {
                probe.presentation?.reconnect?.username == "bob"
            }
            assertFalse(old.isReconnectTargetLive(banner))
            runOnIdle { old.dismissReconnect(banner) }
            waitForIdle()
            assertFalse(holder.isDismissed(banner.profile))
            assertFalse(holder.isDismissed(ReconnectProfile(second.serverUrl, second.username)))
            assertEquals("bob", probe.presentation?.reconnect?.username)
        }

    @Test
    fun aDismissedBannerCannotRunItsQueuedReconnectAction() =
        runComposeUiTest {
            val sync = FakeSyncController(status = SyncStatus.Reconnect("https://sync.example", "anna"))
            val holder = ReconnectDismissals()
            val probe = setSyncContent(sync, FakePlaybackController(), dismissals = { holder })
            waitUntil("reconnect is shown", TIMEOUT_MS) { probe.presentation?.reconnect != null }
            val old = probe.presentation!!
            val banner = old.reconnect!!
            assertTrue(old.isReconnectTargetLive(banner))
            runOnIdle { old.dismissReconnect(banner) }
            waitUntil("dismissal is recorded", TIMEOUT_MS) { holder.isDismissed(banner.profile) }
            assertFalse(old.isReconnectTargetLive(banner))
        }

    @Test
    fun reconnectWaitsForTheDatabaseAndClosedGateActionsResolveNothing() =
        runComposeUiTest {
            val sync = FakeSyncController(status = SyncStatus.Reconnect("https://sync.example", "anna"))
            val holder = ReconnectDismissals()
            var open by mutableStateOf(false)
            val probe = setSyncContent(sync, FakePlaybackController(), enabled = { open }, dismissals = { holder })
            waitForIdle()
            assertEquals(0, probe.syncResolutions)
            assertNull(probe.presentation?.reconnect)
            runOnIdle { open = true }
            waitUntil("opening the gate shows reconnect", TIMEOUT_MS) { probe.presentation?.reconnect != null }
            val old = probe.presentation!!
            val banner = old.reconnect!!
            runOnIdle { open = false }
            waitUntil("closing the gate removes reconnect", TIMEOUT_MS) { probe.presentation?.reconnect == null }
            val before = probe.syncResolutions
            assertFalse(old.isReconnectTargetLive(banner))
            runOnIdle { old.dismissReconnect(banner) }
            waitForIdle()
            assertEquals(before, probe.syncResolutions)
            assertFalse(holder.isDismissed(banner.profile))
        }

    @Test
    fun displayHostsNeverIncludeCredentialsPathsQueriesOrFragments() =
        runComposeUiTest {
            val serverUrl = "https://person:secret@sync.example:8443/private?q=hidden#fragment"
            val sync = FakeSyncController(status = SyncStatus.Reconnect(serverUrl, "anna"))
            val probe = setSyncContent(sync, FakePlaybackController())
            waitUntil("the safe host is shown", TIMEOUT_MS) { probe.presentation?.reconnect != null }
            assertEquals("sync.example:8443", probe.presentation?.reconnect?.host)
            runOnIdle {
                sync.status.value = SyncStatus.Reconnect("https://person:secret@/private", "anna")
            }
            waitUntil("the malformed host uses a safe fallback", TIMEOUT_MS) {
                probe.presentation?.reconnect?.host == "?"
            }
        }

    private fun ComposeUiTest.setSyncContent(
        sync: FakeSyncController,
        playback: FakePlaybackController,
        enabled: () -> Boolean = { true },
        isPlaying: () -> Boolean = { false },
        holdFirstMessage: Boolean = false,
        dismissals: () -> ReconnectDismissals = { ReconnectDismissals() },
        generation: () -> Int = { 0 },
    ): Probe {
        val probe = Probe()
        setContent {
            key(generation()) {
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
                            reconnectDismissals = dismissals,
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
        }
        return probe
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L

        val linked =
            SyncStatus.Linked(
                serverUrl = "https://sync.example",
                accountId = "acct",
                deviceId = "dev-1",
                username = "me",
                nodeId = "node",
                lastSyncAtMs = null,
                activity = SyncActivity.IDLE,
                problem = null,
            )

        fun held(
            id: Long,
            deviceName: String?,
            podcasts: Int,
            groups: Int,
        ): MassChangePrompt =
            MassChangePrompt(
                id = id,
                deviceName = deviceName,
                podcastTitles = List(podcasts) { "podcast-$it" },
                groupNames = List(groups) { "group-$it" },
            )
    }
}
