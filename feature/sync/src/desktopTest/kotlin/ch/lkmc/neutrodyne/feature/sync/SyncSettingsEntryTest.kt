// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.sync

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.SyncSetupKey
import ch.lkmc.neutrodyne.core.testing.FakeLinkFlow
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
import ch.lkmc.neutrodyne.sync.api.LinkPhase
import ch.lkmc.neutrodyne.sync.api.LinkState
import ch.lkmc.neutrodyne.sync.api.SyncActivity
import ch.lkmc.neutrodyne.sync.api.SyncStatus
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Settings › Sync entry affordances (08 Settings › Sync): every status body either pushes
 * `SyncSetupKey` — the address typed or kept for `NotConfigured`, the known `serverUrl` for
 * `Reconnect`, `LinkUnfinished` and `Revoked` — or stays read-only (`Linked` can `syncNow` but
 * never starts a replacement link). `LinkUnfinished`'s Continue resumes the existing flow:
 * it must not cancel an in-progress merge or start a second authorisation.
 */
@OptIn(ExperimentalTestApi::class)
class SyncSettingsEntryTest {
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
    fun unconfiguredContinuePushesTheTypedAddress() =
        runComposeUiTest {
            val harness = Harness()
            setSyncPage(harness)

            onNode(hasSetTextAction()).performTextInput("https://sync.example.org")
            onNodeWithText("Continue").performClick()
            waitForIdle()

            assertEquals(
                listOf<RecordingAppNavigator.Call>(
                    RecordingAppNavigator.Call.Push(SyncSetupKey("https://sync.example.org")),
                ),
                harness.navigator.calls,
            )
            assertUntouched(harness)
        }

    @Test
    fun unconfiguredContinuePushesTheKeptAddress() =
        runComposeUiTest {
            val harness = Harness(keptAddress = "https://kept.example")
            setSyncPage(harness)

            onNode(hasSetTextAction())
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.EditableText,
                        AnnotatedString("https://kept.example"),
                    ),
                )
            onNodeWithText("Continue").performClick()
            waitForIdle()

            assertEquals(
                listOf<RecordingAppNavigator.Call>(
                    RecordingAppNavigator.Call.Push(SyncSetupKey("https://kept.example")),
                ),
                harness.navigator.calls,
            )
        }

    @Test
    fun unconfiguredContinueWaitsForAnAddress() =
        runComposeUiTest {
            val harness = Harness()
            setSyncPage(harness)

            onNodeWithText("Continue").assertIsNotEnabled()
        }

    @Test
    fun howToRunAServerOpensTheReadmeSection() =
        runComposeUiTest {
            val harness = Harness()
            setSyncPage(harness)

            onNodeWithText("How to run a server").performClick()

            assertEquals(listOf(harness.runServerUrl), harness.openedUrls)
        }

    @Test
    fun reconnectOpensSetupAtTheKnownServer() =
        runComposeUiTest {
            val harness =
                Harness(SyncStatus.Reconnect("https://sync.example.org", username = "anna"))
            setSyncPage(harness)

            onNodeWithText("Reconnect").performClick()
            waitForIdle()

            assertEquals(
                listOf<RecordingAppNavigator.Call>(
                    RecordingAppNavigator.Call.Push(SyncSetupKey("https://sync.example.org")),
                ),
                harness.navigator.calls,
            )
            assertUntouched(harness)
        }

    @Test
    fun revokedLinkAgainOpensSetupAtTheKnownServer() =
        runComposeUiTest {
            val harness = Harness(SyncStatus.Revoked("https://sync.example.org"))
            setSyncPage(harness)

            onNodeWithText("Link again").performClick()
            waitForIdle()

            assertEquals(
                listOf<RecordingAppNavigator.Call>(
                    RecordingAppNavigator.Call.Push(SyncSetupKey("https://sync.example.org")),
                ),
                harness.navigator.calls,
            )
            assertUntouched(harness)
        }

    @Test
    fun unfinishedContinueResumesWithoutCancellingTheFlow() =
        runComposeUiTest {
            val link = FakeLinkFlow(LinkState.Merging(LinkPhase.DOWNLOADING, done = 1, total = 4))
            val harness = Harness(SyncStatus.LinkUnfinished("https://sync.example.org"), link)
            setSyncPage(harness)

            onNodeWithText("Continue").performClick()
            waitForIdle()

            assertEquals(
                listOf<RecordingAppNavigator.Call>(
                    RecordingAppNavigator.Call.Push(SyncSetupKey("https://sync.example.org")),
                ),
                harness.navigator.calls,
            )
            assertEquals(0, link.cancelCount)
            assertUntouched(harness)
        }

    @Test
    fun linkedShowsSyncNowAndOffersNoReplacementLink() =
        runComposeUiTest {
            val harness =
                Harness(
                    SyncStatus.Linked(
                        serverUrl = "https://sync.example.org",
                        accountId = "acc",
                        deviceId = "dev",
                        username = "anna",
                        nodeId = "node",
                        lastSyncAtMs = null,
                        activity = SyncActivity.IDLE,
                        problem = null,
                    ),
                )
            setSyncPage(harness)

            onNodeWithText("Sync now").assertIsDisplayed()
            onNodeWithText("Continue").assertDoesNotExist()
            onNodeWithText("Reconnect").assertDoesNotExist()
            onNodeWithText("Link again").assertDoesNotExist()

            onNodeWithText("Sync now").performClick()
            waitForIdle()

            assertEquals(1, harness.controller.syncNows.size)
            assertTrue(harness.navigator.calls.isEmpty())
        }

    private fun assertUntouched(harness: Harness) {
        assertTrue(harness.controller.startLinks.isEmpty(), "no second authorisation")
        assertTrue(harness.controller.discoveries.isEmpty(), "no discovery restart")
        assertTrue(harness.controller.syncNows.isEmpty(), "no syncNow placeholder")
        assertTrue(harness.controller.firstLinkChoices.isEmpty())
    }

    private fun ComposeUiTest.setSyncPage(harness: Harness) {
        setContent {
            CompositionLocalProvider(
                LocalLifecycleOwner provides ResumedLifecycleOwner(),
                LocalPlatformKind provides PlatformKind.DESKTOP,
                LocalPlatformActions provides harness.actions,
                LocalAppNavigator provides harness.navigator,
            ) {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    SyncSettingsScreen(harness.controller, harness.address, harness.runServerUrl)
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
        status: SyncStatus = SyncStatus.NotConfigured,
        val link: FakeLinkFlow = FakeLinkFlow(),
        keptAddress: String? = null,
    ) {
        val controller = FakeSyncController(status, link)
        val address = FakeSyncServerAddress(keptAddress)
        val navigator = RecordingAppNavigator()
        val runServerUrl = "https://repo.example/neutrodyne#run-the-server"
        val openedUrls = mutableListOf<String>()

        val actions =
            object : PlatformActions {
                override val urls =
                    ExternalUrlOpener { url ->
                        openedUrls += url
                        OpenResult.OPENED
                    }
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
    }
}
