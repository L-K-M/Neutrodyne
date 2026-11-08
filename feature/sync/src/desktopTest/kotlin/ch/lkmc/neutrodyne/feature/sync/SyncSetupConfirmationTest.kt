// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.sync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.domain.BackupError
import ch.lkmc.neutrodyne.core.testing.FakeBackupRepository
import ch.lkmc.neutrodyne.core.testing.FakeLinkFlow
import ch.lkmc.neutrodyne.core.testing.FakeSyncController
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.platform.ExternalUrlOpener
import ch.lkmc.neutrodyne.core.ui.platform.FilePicker
import ch.lkmc.neutrodyne.core.ui.platform.FileSaver
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.OpenResult
import ch.lkmc.neutrodyne.core.ui.platform.PlatformActions
import ch.lkmc.neutrodyne.sync.api.FirstLinkChoice
import ch.lkmc.neutrodyne.sync.api.LinkState
import kotlinx.coroutines.CompletableDeferred
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 08 Sync screens, the first-link confirmations (10 First-link choices): Continue on a destructive
 * choice only asks; the destructive service is called by the explicit final action alone. The
 * USE_SERVER backup path runs 05's manual backup flow and stops there, so a cancelled or failed
 * backup never merges and a later success still lets Replace continue.
 */
@OptIn(ExperimentalTestApi::class)
class SyncSetupConfirmationTest {
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
    fun mergeContinuesWithoutAConfirmation() =
        runComposeUiTest {
            val harness = Harness()
            setPage(harness)

            onNodeWithText("Continue").performClick()
            waitForIdle()

            assertEquals(listOf(FirstLinkChoice.MERGE), harness.controller.firstLinkChoices)
            onNodeWithText("Replace this device’s library?").assertDoesNotExist()
        }

    @Test
    fun useServerContinueOnlyAsksForConfirmation() =
        runComposeUiTest {
            val harness = Harness()
            setPage(harness)

            openServerDialog()

            onNodeWithText("Replace this device’s library?").assertIsDisplayed()
            onNodeWithText("Save a backup first").assertIsDisplayed()
            onNodeWithText("Replace").assertIsDisplayed()
            onNodeWithText("Cancel").assertIsDisplayed()
            assertTrue(harness.controller.firstLinkChoices.isEmpty())
        }

    @Test
    fun useServerCancelIsInertAndTheDialogCanBeReopened() =
        runComposeUiTest {
            val harness = Harness()
            setPage(harness)

            openServerDialog()
            onNodeWithText("Cancel").performClick()
            waitForIdle()

            onNodeWithText("Replace this device’s library?").assertDoesNotExist()
            assertTrue(harness.controller.firstLinkChoices.isEmpty())
            assertEquals(0, harness.link.cancelCount)

            onNodeWithText("Continue").performClick()
            waitForIdle()
            onNodeWithText("Replace this device’s library?").assertIsDisplayed()
        }

    @Test
    fun useServerReplaceIsTheExplicitDestructiveConfirmation() =
        runComposeUiTest {
            val harness = Harness()
            setPage(harness)

            openServerDialog()
            onNodeWithText("Replace").performClick()
            waitForIdle()

            assertEquals(listOf(FirstLinkChoice.USE_SERVER), harness.controller.firstLinkChoices)
        }

    @Test
    fun cancelledBackupNeverMerges() =
        runComposeUiTest {
            val harness = Harness()
            harness.target = null
            setPage(harness)

            openServerDialog()
            onNodeWithText("Save a backup first").performClick()
            waitUntil(timeoutMillis = 5_000) { harness.backup.calls.any { it.startsWith("prepareBackup") } }
            waitForIdle()

            assertTrue(harness.controller.firstLinkChoices.isEmpty())
            assertTrue(harness.backup.savePreparedCalls.isEmpty())
            onNodeWithText("Replace this device’s library?").assertIsDisplayed()
            onNodeWithText("Save a backup first").assertIsDisplayed()
        }

    @Test
    fun failedBackupNeverMergesAndLaterSuccessLetsReplaceContinue() =
        runComposeUiTest {
            val harness = Harness()
            harness.backup.savePreparedOutcome = Outcome.Failure(BackupError.Io)
            setPage(harness)

            openServerDialog()
            onNodeWithText("Save a backup first").performClick()
            waitUntil(timeoutMillis = 5_000) { harness.backup.savePreparedCalls.size == 1 }
            waitForIdle()

            assertTrue(harness.controller.firstLinkChoices.isEmpty())
            onNodeWithText("Couldn’t read or write the backup file.").assertIsDisplayed()

            harness.backup.savePreparedOutcome = Outcome.Success(Unit)
            onNodeWithText("Save a backup first").performClick()
            waitUntil(timeoutMillis = 5_000) { harness.backup.savePreparedCalls.size == 2 }
            waitForIdle()

            onNodeWithText("Backup saved.").assertIsDisplayed()
            assertTrue(harness.controller.firstLinkChoices.isEmpty())

            onNodeWithText("Replace").performClick()
            waitForIdle()
            assertEquals(listOf(FirstLinkChoice.USE_SERVER), harness.controller.firstLinkChoices)
        }

    @Test
    fun cancellingWhileTheBackupRunsNeverMerges() =
        runComposeUiTest {
            val harness = Harness()
            val gate = CompletableDeferred<String?>()
            harness.suspendSaver = gate
            setPage(harness)

            openServerDialog()
            onNodeWithText("Save a backup first").performClick()
            waitUntil(timeoutMillis = 5_000) { harness.saverPicks == 1 }

            onNodeWithText("Cancel").performClick()
            gate.complete("file:///backup.zip")
            waitForIdle()

            assertTrue(harness.controller.firstLinkChoices.isEmpty())
            assertTrue(harness.backup.savePreparedCalls.isEmpty())
        }

    @Test
    fun useDeviceContinueOnlyAsksForConfirmation() =
        runComposeUiTest {
            val harness = Harness()
            setPage(harness)

            onNodeWithText("library everywhere", substring = true).performClick()
            onNodeWithText("Continue").performClick()
            waitForIdle()

            onNodeWithText("Use this device’s library everywhere?").assertIsDisplayed()
            onNodeWithText("Replace everywhere").assertIsDisplayed()
            onNodeWithText("Cancel").assertIsDisplayed()
            assertTrue(harness.controller.firstLinkChoices.isEmpty())
        }

    @Test
    fun useDeviceCancelIsInert() =
        runComposeUiTest {
            val harness = Harness()
            setPage(harness)

            openDeviceDialog()
            onNodeWithText("Cancel").performClick()
            waitForIdle()

            onNodeWithText("Use this device’s library everywhere?").assertDoesNotExist()
            assertTrue(harness.controller.firstLinkChoices.isEmpty())
            assertEquals(0, harness.link.cancelCount)
        }

    @Test
    fun useDeviceReplaceEverywhereIsTheExplicitDestructiveConfirmation() =
        runComposeUiTest {
            val harness = Harness()
            setPage(harness)

            openDeviceDialog()
            onNodeWithText("Replace everywhere").performClick()
            waitForIdle()

            assertEquals(listOf(FirstLinkChoice.USE_DEVICE), harness.controller.firstLinkChoices)
        }

    @Test
    fun destructiveActionsAreNotTheDefaultFocus() =
        runComposeUiTest {
            val harness = Harness()
            setPage(harness)

            openDeviceDialog()
            onNodeWithText("Replace everywhere").assertIsNotFocused()

            onNodeWithText("Cancel").performClick()
            waitForIdle()

            openServerDialog()
            onNodeWithText("Replace").assertIsNotFocused()
        }

    @Test
    fun germanLabelsStayReachableAtDoubleFontInASmallWindow() =
        runDesktopComposeUiTest(width = 480, height = 640) {
            Locale.setDefault(Locale.GERMAN)
            val harness = Harness()
            setPage(harness, fontScale = 2f)

            onNodeWithText("Bibliothek des Servers auf diesem Gerät verwenden").performScrollTo().performClick()
            onNodeWithText("Bibliothek des Servers auf diesem Gerät verwenden").assertIsSelected()
            onNodeWithText("Weiter").performScrollTo().performClick()
            waitForIdle()

            onNodeWithText("Bibliothek dieses Geräts ersetzen?").assertIsDisplayed()
            onNodeWithText("Zuerst eine Sicherung speichern").assertIsDisplayed()
            onNodeWithText("Ersetzen").assertIsDisplayed()
            onNodeWithText("Abbrechen").assertIsDisplayed()
            onNodeWithText("Abbrechen").performClick()
            waitForIdle()

            onNodeWithText("Bibliothek dieses Geräts überall verwenden").performScrollTo().performClick()
            onNodeWithText("Weiter").performScrollTo().performClick()
            waitForIdle()

            onNodeWithText("Bibliothek dieses Geräts überall verwenden?").assertIsDisplayed()
            onNodeWithText("Überall ersetzen").assertIsDisplayed()
            onNodeWithText("Abbrechen").assertIsDisplayed()
            assertTrue(harness.controller.firstLinkChoices.isEmpty())
        }

    private fun ComposeUiTest.openServerDialog() {
        onNodeWithText("library on this device", substring = true).performClick()
        onNodeWithText("Continue").performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.openDeviceDialog() {
        onNodeWithText("library everywhere", substring = true).performClick()
        onNodeWithText("Continue").performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.setPage(
        harness: Harness,
        fontScale: Float = 1f,
        content: @Composable () -> Unit = {
            SyncSetupScreen(harness.controller, "https://sync.example.org", harness.backup)
        },
    ) {
        setContent {
            val original = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(
                LocalLifecycleOwner provides ResumedLifecycleOwner(),
                LocalPlatformKind provides PlatformKind.DESKTOP,
                LocalPlatformActions provides harness.actions,
                androidx.compose.ui.platform.LocalDensity provides
                    androidx.compose.ui.unit
                        .Density(original.density, fontScale),
            ) {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) { content() }
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

    private class Harness {
        val link =
            FakeLinkFlow(
                LinkState.ChooseFirstLink(
                    localPodcasts = 3,
                    localGroups = 1,
                    serverPodcasts = 5,
                    serverGroups = 2,
                ),
            )
        val controller = FakeSyncController(link = link)
        val backup = FakeBackupRepository()
        var target: String? = "file:///backup.zip"
        var suspendSaver: CompletableDeferred<String?>? = null
        var saverPicks: Int = 0
            private set

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

                override val saver =
                    FileSaver { _, _ ->
                        saverPicks++
                        suspendSaver?.await() ?: target
                    }

                override val reveal = null
                override val notifications = null
            }
    }
}
