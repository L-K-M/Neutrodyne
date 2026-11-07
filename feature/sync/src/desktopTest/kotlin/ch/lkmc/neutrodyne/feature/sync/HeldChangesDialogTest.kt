// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.sync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
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
import ch.lkmc.neutrodyne.sync.api.HeldResolution
import ch.lkmc.neutrodyne.sync.api.HeldResolveResult
import ch.lkmc.neutrodyne.sync.api.MassChangePrompt
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The held-removal review dialog (08 Held changes, 10 Mass-change guard step 3): the batch's
 * summary and names, the three stacked decisions and the failure/retry path. The route closes
 * itself when the prompt leaves the flow — never on its own.
 */
@OptIn(ExperimentalTestApi::class)
class HeldChangesDialogTest {
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
    fun theDialogShowsTheSummaryNamesAndChoices() =
        runComposeUiTest {
            val controller =
                FakeSyncController().apply {
                    heldChanges.value = listOf(PROMPT)
                }
            val navigator = RecordingAppNavigator()
            setPage(controller, navigator, PROMPT.id)

            onNodeWithText("Another device removed 2 podcasts and 1 group").assertIsDisplayed()
            onNodeWithText("Apply this on this device too?").assertIsDisplayed()
            onNodeWithText("Held Show A").assertIsDisplayed()
            onNodeWithText("Held Show B").assertIsDisplayed()
            onNodeWithText("Held Group").assertIsDisplayed()
            onNodeWithText("Apply").assertIsDisplayed()
            onNodeWithText("Keep mine").assertIsDisplayed()
            onNodeWithText("Keeps them here and adds them back on your other devices.")
                .assertIsDisplayed()
            onNodeWithText("Decide later").assertIsDisplayed()
        }

    /** 08: the groups part of the title drops when `g == 0`; names cap at ten, then "and k more". */
    @Test
    fun aLongSummaryTruncatesAfterTenNames() =
        runComposeUiTest {
            val controller =
                FakeSyncController().apply {
                    heldChanges.value =
                        listOf(PROMPT.copy(podcastTitles = (1..12).map { "Held Show $it" }, groupNames = emptyList()))
                }
            val navigator = RecordingAppNavigator()
            setPage(controller, navigator, PROMPT.id)

            onNodeWithText("Another device removed 12 podcasts").assertIsDisplayed()
            onNodeWithText("Held Show 10").assertIsDisplayed()
            onNodeWithText("Held Show 11").assertDoesNotExist()
            onNodeWithText("and 2 more").assertIsDisplayed()
        }

    @Test
    fun applyCallsResolveHeldAndCloses() =
        runComposeUiTest {
            val controller =
                FakeSyncController().apply {
                    heldChanges.value = listOf(PROMPT)
                }
            val navigator = RecordingAppNavigator()
            setPage(controller, navigator, PROMPT.id)

            onNodeWithText("Apply").performClick()
            waitForIdle()

            assertEquals(listOf(PROMPT.id to HeldResolution.APPLY), controller.heldResolutions)
            assertTrue(
                navigator.calls.contains(
                    RecordingAppNavigator.Call.PopEntry(SyncHeldChangesKey(PROMPT.id)),
                ),
            )
            onNodeWithText("Apply this on this device too?").assertDoesNotExist()
        }

    @Test
    fun keepMineCallsResolveHeldAndCloses() =
        runComposeUiTest {
            val controller =
                FakeSyncController().apply {
                    heldChanges.value = listOf(PROMPT)
                }
            val navigator = RecordingAppNavigator()
            setPage(controller, navigator, PROMPT.id)

            onNodeWithText("Keep mine").performClick()

            assertEquals(listOf(PROMPT.id to HeldResolution.KEEP_MINE), controller.heldResolutions)
            assertTrue(
                navigator.calls.contains(
                    RecordingAppNavigator.Call.PopEntry(SyncHeldChangesKey(PROMPT.id)),
                ),
            )
        }

    /** Decide later keeps the batch: the resolution is recorded and the prompt stays offered. */
    @Test
    fun decideLaterCallsResolveHeldAndKeepsTheBatch() =
        runComposeUiTest {
            val controller =
                FakeSyncController().apply {
                    heldChanges.value = listOf(PROMPT)
                }
            val navigator = RecordingAppNavigator()
            setPage(controller, navigator, PROMPT.id)

            onNodeWithText("Decide later").performClick()

            assertEquals(listOf(PROMPT.id to HeldResolution.DECIDE_LATER), controller.heldResolutions)
            assertTrue(
                navigator.calls.contains(
                    RecordingAppNavigator.Call.PopEntry(SyncHeldChangesKey(PROMPT.id)),
                ),
            )
            assertEquals(listOf(PROMPT), controller.heldChanges.value)
        }

    /** Apply reaches only the selected batch; the next prompt stays offered (10 step 3). */
    @Test
    fun applyLeavesTheOtherBatchOffered() =
        runComposeUiTest {
            val other = PROMPT.copy(id = 9, podcastTitles = listOf("Other Show"), groupNames = emptyList())
            val controller =
                FakeSyncController().apply {
                    heldChanges.value = listOf(PROMPT, other)
                }
            val navigator = RecordingAppNavigator()
            setPage(controller, navigator, PROMPT.id)

            onNodeWithText("Other Show").assertDoesNotExist()
            onNodeWithText("Apply").performClick()
            waitForIdle()

            assertEquals(listOf(PROMPT.id to HeldResolution.APPLY), controller.heldResolutions)
            assertEquals(listOf(other), controller.heldChanges.value)
        }

    /**
     * A Failed result never closes the prompt as though it succeeded: the error text shows and
     * the buttons stay actionable for the retry that then resolves.
     */
    @Test
    fun aFailedResolutionShowsTheErrorAndRetries() =
        runComposeUiTest {
            val controller =
                FakeSyncController().apply {
                    heldChanges.value = listOf(PROMPT)
                    resolveHeldResult = HeldResolveResult.Failed
                }
            val navigator = RecordingAppNavigator()
            setPage(controller, navigator, PROMPT.id)

            onNodeWithText("Apply").performClick()
            waitForIdle()
            onNodeWithText("That didn’t work. Nothing was changed; you can try again.")
                .assertIsDisplayed()
            onNodeWithText("Apply").assertIsDisplayed()
            assertTrue(navigator.calls.isEmpty())

            controller.resolveHeldResult = HeldResolveResult.Resolved
            onNodeWithText("Apply").performClick()
            waitForIdle()

            assertEquals(
                listOf(PROMPT.id to HeldResolution.APPLY, PROMPT.id to HeldResolution.APPLY),
                controller.heldResolutions,
            )
            onNodeWithText("Apply this on this device too?").assertDoesNotExist()
        }

    /** A decision from another surface removes the row; nothing left to review closes the route. */
    @Test
    fun aPromptResolvedElsewhereClosesTheDialog() =
        runComposeUiTest {
            val controller =
                FakeSyncController().apply {
                    heldChanges.value = listOf(PROMPT)
                }
            val navigator = RecordingAppNavigator()
            setPage(controller, navigator, PROMPT.id)
            onNodeWithText("Apply this on this device too?").assertIsDisplayed()

            runOnIdle { controller.heldChanges.value = emptyList() }

            onNodeWithText("Apply this on this device too?").assertDoesNotExist()
            assertTrue(
                navigator.calls.contains(
                    RecordingAppNavigator.Call.PopEntry(SyncHeldChangesKey(PROMPT.id)),
                ),
            )
            assertTrue(controller.heldResolutions.isEmpty())
        }

    private fun ComposeUiTest.setPage(
        controller: FakeSyncController,
        navigator: RecordingAppNavigator,
        id: Long,
    ) {
        val owner =
            object : LifecycleOwner {
                override val lifecycle =
                    LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
            }
        setContent {
            CompositionLocalProvider(
                LocalPlatformKind provides PlatformKind.DESKTOP,
                LocalLifecycleOwner provides owner,
                LocalAppNavigator provides navigator,
            ) {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    SyncHeldChangesRoute(controller, id)
                }
            }
        }
        waitForIdle()
    }

    private companion object {
        val PROMPT =
            MassChangePrompt(
                id = 7,
                deviceName = null,
                podcastTitles = listOf("Held Show A", "Held Show B"),
                groupNames = listOf("Held Group"),
            )
    }
}
