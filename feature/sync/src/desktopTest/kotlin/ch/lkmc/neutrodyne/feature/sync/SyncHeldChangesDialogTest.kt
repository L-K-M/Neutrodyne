// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.sync

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.SyncHeldChangesKey
import ch.lkmc.neutrodyne.core.testing.FakeSyncController
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import ch.lkmc.neutrodyne.sync.api.HeldResolution
import ch.lkmc.neutrodyne.sync.api.MassChangePrompt
import ch.lkmc.neutrodyne.sync.api.SyncActivity
import ch.lkmc.neutrodyne.sync.api.SyncStatus
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The held-changes dialog on `SyncHeldChangesKey` (08 Held changes, R7.7): it names the staging
 * device ("Another device" without one), counts podcasts and groups (the groups part only when
 * groups are staged), lists at most ten titles/names and counts the rest, and maps its three
 * stacked buttons to `resolveHeld(APPLY/KEEP_MINE/DECIDE_LATER)`. The command API is fire and
 * forget: a click only requests the resolution and pops — the HELD row and its banner leave when
 * the backend commits, not when the click lands. The dialog belongs to its (server, account,
 * device) identity and its row: either going away pops it without resolving a different row or
 * account, while a content update to the same row repaints in place.
 */
@OptIn(ExperimentalTestApi::class)
class SyncHeldChangesDialogTest {
    private lateinit var previousLocale: Locale

    @BeforeTest
    fun setUp() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest
    fun tearDown() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun theTitleNamesTheDeviceAndBothCounts() =
        runComposeUiTest {
            val harness = Harness(prompt = PROMPT)
            setDialog(harness)

            onNodeWithText("Pixel removed 11 podcasts and 2 groups").assertIsDisplayed()
            onNodeWithText("Apply this on this device too?").assertIsDisplayed()
        }

    @Test
    fun theGroupsPartIsLeftOutWhenNoGroupsAreStaged() =
        runComposeUiTest {
            val harness = Harness(prompt = PROMPT.copy(groupNames = emptyList()))
            setDialog(harness)

            onNodeWithText("Pixel removed 11 podcasts").assertIsDisplayed()
        }

    @Test
    fun anUnknownDeviceReadsAsAnotherDevice() =
        runComposeUiTest {
            val harness = Harness(prompt = PROMPT.copy(deviceName = null, groupNames = emptyList()))
            setDialog(harness)

            onNodeWithText("Another device removed 11 podcasts").assertIsDisplayed()
        }

    @Test
    fun theListShowsTheFirstTenAndCountsTheRest() =
        runComposeUiTest {
            val titles = (1..14).map { "Podcast $it" }
            val harness = Harness(prompt = PROMPT.copy(podcastTitles = titles, groupNames = listOf("Friends")))
            setDialog(harness)

            // The combined list keeps the prompt's order: podcast titles, then group names —
            // here the first ten are exactly the first ten podcasts.
            onNodeWithText("Podcast 1").assertIsDisplayed()
            onNodeWithText("Podcast 10").assertIsDisplayed()
            onNodeWithText("Podcast 11").assertDoesNotExist()
            onNodeWithText("Friends").assertDoesNotExist()
            onNodeWithText("and 5 more").assertIsDisplayed()
        }

    @Test
    fun applyRequestsTheResolutionAndPops() =
        runComposeUiTest {
            val harness = Harness()
            setDialog(harness)

            onNodeWithText("Apply").performClick()
            waitForIdle()

            assertEquals(listOf(ID to HeldResolution.APPLY), harness.controller.heldResolutions)
            assertEquals(
                listOf<RecordingAppNavigator.Call>(RecordingAppNavigator.Call.PopEntry(SyncHeldChangesKey(ID))),
                harness.navigator.calls,
            )
        }

    @Test
    fun keepMineExplainsItselfAndResolvesKeepMine() =
        runComposeUiTest {
            val harness = Harness()
            setDialog(harness)

            onNodeWithText("Keeps them here and adds them back on your other devices").assertIsDisplayed()
            onNodeWithText("Keep mine").performClick()
            waitForIdle()

            assertEquals(listOf(ID to HeldResolution.KEEP_MINE), harness.controller.heldResolutions)
            assertEquals(
                listOf<RecordingAppNavigator.Call>(RecordingAppNavigator.Call.PopEntry(SyncHeldChangesKey(ID))),
                harness.navigator.calls,
            )
        }

    @Test
    fun decideLaterResolvesDecideLaterAndPops() =
        runComposeUiTest {
            val harness = Harness()
            setDialog(harness)

            onNodeWithText("Decide later").performClick()
            waitForIdle()

            // The banner stays: DECIDE_LATER is the prompt's keep-it-held answer, not an apply.
            assertEquals(listOf(ID to HeldResolution.DECIDE_LATER), harness.controller.heldResolutions)
            assertEquals(
                listOf<RecordingAppNavigator.Call>(RecordingAppNavigator.Call.PopEntry(SyncHeldChangesKey(ID))),
                harness.navigator.calls,
            )
        }

    @Test
    fun anEmptyBatchStillOffersTheThreeChoices() =
        runComposeUiTest {
            val harness =
                Harness(prompt = PROMPT.copy(podcastTitles = emptyList(), groupNames = emptyList()))
            setDialog(harness)

            onNodeWithText("Apply this on this device too?").assertIsDisplayed()
            onNodeWithText("Apply").assertIsDisplayed()
            onNodeWithText("Keep mine").assertIsDisplayed()
            onNodeWithText("Decide later").assertIsDisplayed()
        }

    @Test
    fun aRowThatWasNeverTherePopsWithoutResolving() =
        runComposeUiTest {
            val harness = Harness()
            harness.controller.heldChanges.value = listOf(PROMPT.copy(id = ID + 40))
            setDialog(harness)

            assertTrue(harness.controller.heldResolutions.isEmpty())
            assertEquals(
                listOf<RecordingAppNavigator.Call>(RecordingAppNavigator.Call.PopEntry(SyncHeldChangesKey(ID))),
                harness.navigator.calls,
            )
        }

    @Test
    fun aRowRemovedWhileOpenPopsWithoutResolving() =
        runComposeUiTest {
            val harness = Harness()
            setDialog(harness)

            onNodeWithText("Apply").assertIsDisplayed()
            harness.controller.heldChanges.value = emptyList()
            waitForIdle()

            assertTrue(harness.controller.heldResolutions.isEmpty())
            assertEquals(
                listOf<RecordingAppNavigator.Call>(RecordingAppNavigator.Call.PopEntry(SyncHeldChangesKey(ID))),
                harness.navigator.calls,
            )
        }

    @Test
    fun anIdentityChangePopsWithoutResolving() =
        runComposeUiTest {
            val harness = Harness()
            setDialog(harness)

            onNodeWithText("Apply").assertIsDisplayed()
            harness.controller.status.value = SyncStatus.Revoked(LINKED.serverUrl)
            waitForIdle()

            assertTrue(harness.controller.heldResolutions.isEmpty())
            assertEquals(
                listOf<RecordingAppNavigator.Call>(RecordingAppNavigator.Call.PopEntry(SyncHeldChangesKey(ID))),
                harness.navigator.calls,
            )
        }

    @Test
    fun aDirectLinkedSwapToAnotherAccountPopsWithoutResolving() =
        runComposeUiTest {
            val harness = Harness()
            setDialog(harness)

            onNodeWithText("Apply").assertIsDisplayed()
            // Linked A -> Linked B with the same held id still present: the dialog belongs to
            // the identity it opened under — it must close, never rebind its actions to B.
            harness.controller.status.value = OTHER_ACCOUNT
            waitForIdle()

            assertTrue(harness.controller.heldResolutions.isEmpty())
            assertEquals(
                listOf<RecordingAppNavigator.Call>(RecordingAppNavigator.Call.PopEntry(SyncHeldChangesKey(ID))),
                harness.navigator.calls,
            )
        }

    @Test
    fun aSwapKeepingServerAndDevicePopsWithoutResolving() =
        runComposeUiTest {
            val harness = Harness()
            setDialog(harness)

            onNodeWithText("Apply").assertIsDisplayed()
            // Same server and device, a different account: still a different identity.
            harness.controller.status.value = LINKED.copy(accountId = "other-acc")
            waitForIdle()

            assertTrue(harness.controller.heldResolutions.isEmpty())
            assertEquals(
                listOf<RecordingAppNavigator.Call>(RecordingAppNavigator.Call.PopEntry(SyncHeldChangesKey(ID))),
                harness.navigator.calls,
            )
        }

    @Test
    fun aNodeChangeAloneKeepsTheDialogOpen() =
        runComposeUiTest {
            val harness = Harness()
            setDialog(harness)

            // The (server, account, device) identity is unchanged — a new nodeId is a session
            // detail, not an account change, so the dialog must not pop.
            harness.controller.status.value = LINKED.copy(nodeId = "other-node", lastSyncAtMs = 5L)
            waitForIdle()

            onNodeWithText("Apply").assertIsDisplayed()
            assertTrue(harness.navigator.calls.isEmpty())
            onNodeWithText("Apply").performClick()
            waitForIdle()
            assertEquals(listOf(ID to HeldResolution.APPLY), harness.controller.heldResolutions)
        }

    @Test
    fun notLinkedExposesNoPrompt() =
        runComposeUiTest {
            val harness = Harness(status = SyncStatus.NotConfigured)
            setDialog(harness)

            assertTrue(harness.controller.heldResolutions.isEmpty())
            assertEquals(
                listOf<RecordingAppNavigator.Call>(RecordingAppNavigator.Call.PopEntry(SyncHeldChangesKey(ID))),
                harness.navigator.calls,
            )
        }

    @Test
    fun anUpdateToTheSameRowRepaintsInPlace() =
        runComposeUiTest {
            val harness = Harness()
            setDialog(harness)

            harness.controller.heldChanges.value =
                listOf(PROMPT.copy(podcastTitles = listOf("Renamed show", "Another show"), groupNames = emptyList()))
            waitForIdle()

            onNodeWithText("Pixel removed 2 podcasts").assertIsDisplayed()
            onNodeWithText("Renamed show").assertIsDisplayed()
            assertTrue(harness.navigator.calls.isEmpty(), "a content update keeps the dialog open")
        }

    @Test
    fun everythingStaysReachableAt360x300() =
        runComposeUiTest {
            val longTitle = "A podcast whose title keeps going and going and going and going"
            val harness =
                Harness(
                    prompt =
                        PROMPT.copy(
                            deviceName = "A device with a particularly long name for a phone",
                            podcastTitles = (1..20).map { "$longTitle $it" },
                            groupNames = listOf("A group with a long name too"),
                        ),
                )
            setDialog(harness, constrained = true)

            onNodeWithText("Decide later").performScrollTo().assertIsDisplayed()
            onNodeWithText("Apply").performScrollTo().assertIsDisplayed()
            onNodeWithText("Keep mine").performScrollTo().assertIsDisplayed()
            onNodeWithText("and 11 more").assertIsDisplayed()

            onNodeWithText("Apply").performClick()
            waitForIdle()
            assertEquals(listOf(ID to HeldResolution.APPLY), harness.controller.heldResolutions)
        }

    private fun ComposeUiTest.setDialog(
        harness: Harness,
        constrained: Boolean = false,
    ) {
        setContent {
            CompositionLocalProvider(
                LocalLifecycleOwner provides ResumedLifecycleOwner(),
                LocalPlatformKind provides PlatformKind.DESKTOP,
                LocalAppNavigator provides harness.navigator,
                LocalSnackbarHost provides harness.snackbar,
            ) {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    if (constrained) {
                        Box(Modifier.size(360.dp, 300.dp)) {
                            SyncHeldChangesDialog(harness.controller, SyncHeldChangesKey(ID))
                        }
                    } else {
                        SyncHeldChangesDialog(harness.controller, SyncHeldChangesKey(ID))
                    }
                }
            }
        }
        waitForIdle()
    }

    private class ResumedLifecycleOwner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)

        init {
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
    }

    private class Harness(
        status: SyncStatus = LINKED,
        prompt: MassChangePrompt = PROMPT,
    ) {
        val controller = FakeSyncController(status)
        val navigator = RecordingAppNavigator()
        val snackbar = SnackbarHostState()

        init {
            controller.heldChanges.value = listOf(prompt)
        }
    }

    private companion object {
        const val ID = 7L

        val LINKED =
            SyncStatus.Linked(
                serverUrl = "https://sync.example.org",
                accountId = "acc",
                deviceId = "dev",
                username = "anna",
                nodeId = "node",
                lastSyncAtMs = null,
                activity = SyncActivity.IDLE,
                problem = null,
            )

        val OTHER_ACCOUNT =
            LINKED.copy(serverUrl = "https://other.example.org", accountId = "other-acc", deviceId = "other-dev")

        val PROMPT =
            MassChangePrompt(
                id = ID,
                deviceName = "Pixel",
                podcastTitles = (1..11).map { "Podcast $it" },
                groupNames = listOf("News", "Friends"),
            )
    }
}
