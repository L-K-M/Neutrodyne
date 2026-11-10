// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.sync

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.testing.FakeSyncController
import ch.lkmc.neutrodyne.core.testing.FakeSyncServerAddress
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.platform.ExternalUrlOpener
import ch.lkmc.neutrodyne.core.ui.platform.FilePicker
import ch.lkmc.neutrodyne.core.ui.platform.FileSaver
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.OpenResult
import ch.lkmc.neutrodyne.core.ui.platform.PlatformActions
import ch.lkmc.neutrodyne.core.ui.root.LocalRootScope
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import ch.lkmc.neutrodyne.sync.api.SyncActivity
import ch.lkmc.neutrodyne.sync.api.SyncDevice
import ch.lkmc.neutrodyne.sync.api.SyncDisclosure
import ch.lkmc.neutrodyne.sync.api.SyncStatus
import kotlinx.coroutines.CompletableDeferred
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The "What the server stores" row on Settings › Sync (08 "Rows while linked"): the disclosure
 * the pre-link card showed, recomputed by `SyncController.disclosure` for the configured server.
 * Nothing is read until the row is tapped; the dialog shows the same text — private-feed count,
 * readable data without end-to-end encryption, conditional feed passwords, the
 * audio/downloads/artwork exclusion and the transport line — stays actionable on a failed read,
 * and dies with its (server, account, device) identity.
 */
@OptIn(ExperimentalTestApi::class)
class SyncServerStoresTest {
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
    fun theRowOpensTheSharedDisclosureAndDoneClosesIt() =
        runComposeUiTest {
            val harness = Harness()
            harness.controller.fake.disclosureResult =
                SyncDisclosure("sync.example.org", privateFeedLinks = 3, encrypted = true)
            setScreen(harness)

            onNodeWithText("What the server stores").assertIsDisplayed().performClick()
            waitForIdle()

            onNodeWithText("What sync.example.org stores").assertIsDisplayed()
            onNodeWithText("including 3 private feed links", substring = true).assertIsDisplayed()
            onNodeWithText("can read them", substring = true).assertIsDisplayed()
            onNodeWithText("encrypt them end to end", substring = true).assertIsDisplayed()
            onNodeWithText("only if you turn on Share feed passwords", substring = true).assertIsDisplayed()
            onNodeWithText("Audio, downloads and artwork are never sent", substring = true).assertIsDisplayed()
            onNodeWithText("The connection is encrypted (HTTPS)", substring = true).assertIsDisplayed()

            onNodeWithText("Done").performClick()
            waitForIdle()
            onNodeWithText("What sync.example.org stores").assertDoesNotExist()
        }

    @Test
    fun nothingIsReadUntilTheRowIsTapped() =
        runComposeUiTest {
            val harness = Harness()
            setScreen(harness)

            onNodeWithText("What the server stores").assertIsDisplayed()
            assertTrue(
                harness.controller.fake.disclosureReads
                    .isEmpty(),
            )

            onNodeWithText("What the server stores").performClick()
            waitForIdle()

            assertEquals(1, harness.controller.fake.disclosureReads.size)
        }

    @Test
    fun notConfiguredShowsNoRow() =
        runComposeUiTest {
            val harness = Harness(status = SyncStatus.NotConfigured)
            setScreen(harness)

            onNodeWithText("What the server stores").assertDoesNotExist()
        }

    @Test
    fun anUnencryptedServerSaysSo() =
        runComposeUiTest {
            val harness = Harness()
            harness.controller.fake.disclosureResult =
                SyncDisclosure("192.168.1.50", privateFeedLinks = 0, encrypted = false)
            setScreen(harness)

            onNodeWithText("What the server stores").performClick()
            waitForIdle()

            onNodeWithText("Your subscriptions, your groups", substring = true).assertIsDisplayed()
            onNodeWithText("encrypted (http)", substring = true).assertIsDisplayed()
        }

    @Test
    fun aFailedReadIsActionableAndRetries() =
        runComposeUiTest {
            val harness = Harness()
            harness.controller.fake.disclosureResult = null
            setScreen(harness)

            onNodeWithText("What the server stores").performClick()
            waitForIdle()

            onNodeWithText("Couldn't read what the server stores.").assertIsDisplayed()

            harness.controller.fake.disclosureResult =
                SyncDisclosure("sync.example.org", privateFeedLinks = 1, encrypted = true)
            onNodeWithText("Retry").performClick()
            waitForIdle()

            assertEquals(2, harness.controller.fake.disclosureReads.size)
            onNodeWithText("What sync.example.org stores").assertIsDisplayed()
            onNodeWithText("including 1 private feed links", substring = true).assertIsDisplayed()
        }

    @Test
    fun aThrownReadIsActionable() =
        runComposeUiTest {
            val harness = Harness()
            harness.controller.fake.disclosureError = IllegalStateException("db gone")
            setScreen(harness)

            onNodeWithText("What the server stores").performClick()
            waitForIdle()

            onNodeWithText("Couldn't read what the server stores.").assertIsDisplayed()
            onNodeWithText("Retry").assertIsDisplayed()
            onNodeWithText("Done").assertIsDisplayed()
        }

    @Test
    fun anIdentityChangeClosesTheDialogAndDropsTheLateAnswer() =
        runComposeUiTest {
            val harness = Harness()
            setScreen(harness)

            val gate = CompletableDeferred<Unit>()
            harness.controller.disclosureGate = gate
            onNodeWithText("What the server stores").performClick()
            waitForIdle()

            harness.controller.fake.status.value = OTHER_ACCOUNT
            gate.complete(Unit)
            waitForIdle()

            // The late answer is owned by the dead identity: no dialog, no disclosure text.
            onNodeWithText("What sync.example.org stores").assertDoesNotExist()
            onNodeWithText("can read them", substring = true).assertDoesNotExist()
            assertEquals(1, harness.controller.fake.disclosureReads.size)
        }

    private fun ComposeUiTest.setScreen(harness: Harness) {
        setContent {
            val rootScope = rememberCoroutineScope()
            CompositionLocalProvider(
                LocalLifecycleOwner provides ResumedLifecycleOwner(),
                LocalPlatformKind provides PlatformKind.DESKTOP,
                LocalAppNavigator provides harness.navigator,
                LocalSnackbarHost provides harness.snackbar,
                LocalRootScope provides rootScope,
                LocalPlatformActions provides harness.actions,
            ) {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    SyncSettingsScreen(harness.controller, harness.address, RUN_SERVER_URL)
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
    ) {
        val controller = GatedSyncController(FakeSyncController(status))
        val address =
            FakeSyncServerAddress(
                serverUrl = LINKED.serverUrl,
                deviceName = "Anna's Pixel",
            )
        val navigator = RecordingAppNavigator()
        val snackbar = SnackbarHostState()

        val actions =
            object : PlatformActions {
                override val urls = ExternalUrlOpener { OpenResult.OPENED }
                override val share = null
                override val files =
                    object : FilePicker {
                        override suspend fun pickFile(
                            mimeTypes: List<String>,
                            extensions: List<String>,
                        ): String? = null

                        override suspend fun pickFolder(title: String): String? = null
                    }
                override val saver = FileSaver { _, _ -> null }
                override val reveal = null
                override val notifications = null
            }

        init {
            controller.preferenceSink = address
            controller.fake.devicesResult = Outcome.Success(DEVICES)
        }
    }

    private companion object {
        const val RUN_SERVER_URL = "https://repo.example/neutrodyne#run-the-server"

        val LINKED =
            SyncStatus.Linked(
                serverUrl = "https://sync.example.org",
                accountId = "acc",
                deviceId = "dev-a",
                username = "anna",
                nodeId = "node",
                lastSyncAtMs = null,
                activity = SyncActivity.IDLE,
                problem = null,
            )

        val OTHER_ACCOUNT =
            LINKED.copy(serverUrl = "https://other.example.org", accountId = "other-acc", deviceId = "other-dev")

        val DEVICES =
            listOf(
                SyncDevice(
                    id = "dev-a",
                    name = "Anna's Pixel",
                    platform = "android",
                    appVersion = "0.9.3",
                    lastSeenAtMs = null,
                    current = true,
                    stale = false,
                ),
            )
    }
}
