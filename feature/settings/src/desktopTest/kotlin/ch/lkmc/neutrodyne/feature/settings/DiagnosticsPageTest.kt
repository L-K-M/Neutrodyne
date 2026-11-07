// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.domain.DiagnosticsError
import ch.lkmc.neutrodyne.core.domain.DiagnosticsRepository
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.testing.FakeDiagnosticsRepository
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import ch.lkmc.neutrodyne.core.ui.platform.ExternalUrlOpener
import ch.lkmc.neutrodyne.core.ui.platform.FilePicker
import ch.lkmc.neutrodyne.core.ui.platform.FileSaver
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.OpenResult
import ch.lkmc.neutrodyne.core.ui.platform.PlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.RevealInFolder
import ch.lkmc.neutrodyne.core.ui.platform.ShareSheet
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import kotlinx.coroutines.CompletableDeferred
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Settings › About › Diagnostics on the desktop JVM through the page's own view-model seam
 * (`DiagnosticsPage` + `DiagnosticsViewModel` over fakes, the way the shells inject the real
 * repository): 09's mandated confirmation must state what the database copy contains before any
 * share sheet, save dialog or repository export runs, and only the explicit Export may proceed.
 */
@OptIn(ExperimentalTestApi::class)
class DiagnosticsPageTest {
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
    fun exportTapExplainsTheCopyWithoutExporting() =
        runComposeUiTest {
            val diagnostics = FakeDiagnosticsRepository()
            val actions = RecordingActions(androidShare = false)
            setDiagnosticsPage(diagnostics, actions)

            onNodeWithText("Export database copy").performClick()

            onNodeWithText(
                "your subscriptions, episode titles and listening history",
                substring = true,
            ).assertIsDisplayed()
            onNodeWithText(
                "Addresses, show notes and descriptions are masked",
                substring = true,
            ).assertIsDisplayed()
            assertTrue(diagnostics.exportCalls.isEmpty(), "no repository export before a confirm")
            assertTrue(actions.saverCalls.isEmpty(), "no save dialog before a confirm")
            assertTrue(actions.shareFileCalls.isEmpty() && actions.shareTextCalls.isEmpty())
            assertTrue(actions.revealCalls.isEmpty())
        }

    @Test
    fun cancelDismissesTheConfirmationWithoutExport() =
        runComposeUiTest {
            val diagnostics = FakeDiagnosticsRepository()
            val actions = RecordingActions(androidShare = false)
            setDiagnosticsPage(diagnostics, actions)

            onNodeWithText("Export database copy").performClick()
            onNodeWithText("Cancel").performClick()

            onNodeWithText(CONFIRM_BODY, substring = true).assertDoesNotExist()
            assertTrue(diagnostics.exportCalls.isEmpty())
            assertTrue(actions.saverCalls.isEmpty() && actions.revealCalls.isEmpty())
        }

    /**
     * Dismissal must never export. Esc/Back and clicking outside the dialog all
     * funnel into `onDismissRequest`; the desktop dialog is window-backed, so
     * the scrim click (OverlaySceneDismissTest's pattern) is the dismissable
     * path the test harness can drive.
     */
    @Test
    fun dismissingTheConfirmationWithoutConfirmingExportsNothing() =
        runComposeUiTest {
            val diagnostics = FakeDiagnosticsRepository()
            val actions = RecordingActions(androidShare = false)
            setDiagnosticsPage(diagnostics, actions)

            onNodeWithText("Export database copy").performClick()
            onNodeWithText(CONFIRM_BODY, substring = true)
                .assertIsDisplayed()
                .performTouchInput {
                    // Land on the scrim in the window's corner, well outside the
                    // dialog card, to trigger dismissOnClickOutside.
                    click(topLeft + Offset(-240f, -360f))
                }
            waitForIdle()

            onNodeWithText(CONFIRM_BODY, substring = true).assertDoesNotExist()
            assertTrue(diagnostics.exportCalls.isEmpty())
            assertTrue(actions.saverCalls.isEmpty() && actions.revealCalls.isEmpty())
        }

    /** A keyboard Enter on the freshly opened dialog must never start the export. */
    @Test
    fun enterDoesNotConfirmTheExport() =
        runComposeUiTest {
            val diagnostics = FakeDiagnosticsRepository()
            val actions = RecordingActions(androidShare = false)
            setDiagnosticsPage(diagnostics, actions)

            onNodeWithText("Export database copy").performClick()
            onNodeWithText(CONFIRM_BODY, substring = true)
                .assertIsDisplayed()
                .performKeyInput {
                    keyDown(Key.Enter)
                    keyUp(Key.Enter)
                }
            waitForIdle()

            assertTrue(diagnostics.exportCalls.isEmpty(), "default Enter must not export")
            assertTrue(actions.saverCalls.isEmpty() && actions.shareFileCalls.isEmpty())
        }

    /** Android-like actions (`share` present): confirm shares the minted URI once. */
    @Test
    fun confirmExportsThroughTheShareSheetExactlyOnce() =
        runComposeUiTest {
            val diagnostics = FakeDiagnosticsRepository()
            val actions = RecordingActions(androidShare = true)
            setDiagnosticsPage(diagnostics, actions)

            onNodeWithText("Export database copy").performClick()
            onNodeWithText(CONFIRM_BODY, substring = true).assertIsDisplayed()
            onNodeWithText("Export").performClick()

            waitUntil("the share sheet hands out the export", TIMEOUT_MS) {
                actions.shareFileCalls.size == 1
            }
            assertEquals(listOf<String?>(null), diagnostics.exportCalls)
            assertEquals(
                listOf(Triple("file:///tmp/export.db", "application/octet-stream", "export.db")),
                actions.shareFileCalls,
            )
            assertTrue(actions.saverCalls.isEmpty(), "the share path never opens a save dialog")
            onNodeWithText("Database copy exported").assertIsDisplayed()
        }

    /** Desktop actions (`share` absent): confirm runs the saver then the repository once. */
    @Test
    fun confirmExportsThroughTheDesktopSaverExactlyOnce() =
        runComposeUiTest {
            val diagnostics = FakeDiagnosticsRepository()
            val actions =
                RecordingActions(androidShare = false, saverAnswer = "file:///chosen/export.db")
            setDiagnosticsPage(diagnostics, actions)

            onNodeWithText("Export database copy").performClick()
            onNodeWithText("Export").performClick()

            waitUntil("the save dialog ran", TIMEOUT_MS) { actions.saverCalls.size == 1 }
            assertEquals(
                listOf("neutrodyne-diagnostics.db" to "application/octet-stream"),
                actions.saverCalls,
            )
            waitUntil("the exported copy is revealed", TIMEOUT_MS) { actions.revealCalls.size == 1 }
            assertEquals(listOf<String?>("file:///chosen/export.db"), diagnostics.exportCalls)
            assertEquals(listOf("file:///tmp/export.db"), actions.revealCalls)
            assertTrue(actions.shareFileCalls.isEmpty() && actions.shareTextCalls.isEmpty())
        }

    /**
     * A cancelled save dialog answers CANCELLED through the repository contract: no copy is
     * handed to the file manager or share sheet and no success is reported.
     */
    @Test
    fun cancelledSaverProducesNoCopyAndNoFalseSuccess() =
        runComposeUiTest {
            val diagnostics =
                FakeDiagnosticsRepository().apply {
                    exportResult = Outcome.Failure(DiagnosticsError.CANCELLED)
                }
            val actions = RecordingActions(androidShare = false, saverAnswer = null)
            setDiagnosticsPage(diagnostics, actions)

            onNodeWithText("Export database copy").performClick()
            onNodeWithText("Export").performClick()

            waitUntil("the cancelled export resolves", TIMEOUT_MS) {
                diagnostics.exportCalls.size == 1
            }
            assertEquals(listOf<String?>(null), diagnostics.exportCalls)
            assertTrue(actions.revealCalls.isEmpty() && actions.shareFileCalls.isEmpty())
            onNodeWithText("Export cancelled").assertIsDisplayed()
            onNodeWithText("Database copy exported").assertDoesNotExist()
        }

    @Test
    fun failedExportReportsFailureAndSharesNothing() =
        runComposeUiTest {
            val diagnostics =
                FakeDiagnosticsRepository().apply {
                    exportResult = Outcome.Failure(DiagnosticsError.FAILED)
                }
            val actions = RecordingActions(androidShare = true)
            setDiagnosticsPage(diagnostics, actions)

            onNodeWithText("Export database copy").performClick()
            onNodeWithText("Export").performClick()

            waitUntil("the failed export resolves", TIMEOUT_MS) {
                diagnostics.exportCalls.size == 1
            }
            assertTrue(actions.shareFileCalls.isEmpty(), "a failure shares nothing")
            onNodeWithText("Database export failed").assertIsDisplayed()
            onNodeWithText("Database copy exported").assertDoesNotExist()
        }

    /**
     * While the save dialog or the export itself is still running the row stays disabled, so a
     * retap can neither stack a second confirmation nor repeat the export (09's busy guard).
     */
    @Test
    fun aRunningExportBlocksRetapsAndStackedDialogs() =
        runComposeUiTest {
            val diagnostics = GatedDiagnostics()
            val actions =
                RecordingActions(androidShare = false).apply {
                    saverGate = CompletableDeferred()
                }
            setDiagnosticsPage(diagnostics, actions)

            onNodeWithText("Export database copy").performClick()
            onNodeWithText("Export").performClick()

            // Save-dialog window: the row is disabled and a retap opens nothing.
            waitUntil("the save dialog opened", TIMEOUT_MS) { actions.saverEntered.isCompleted }
            onNodeWithText("Export database copy").assertIsNotEnabled()
            onNodeWithText("Export database copy").performClick()
            onNodeWithText(CONFIRM_BODY, substring = true).assertDoesNotExist()
            checkNotNull(actions.saverGate).complete(Unit)

            // Export window: still disabled, still no second dialog or call.
            waitUntil("the export started", TIMEOUT_MS) { diagnostics.enteredCount == 1 }
            onNodeWithText("Export database copy").assertIsNotEnabled()
            onNodeWithText("Export database copy").performClick()
            onNodeWithText(CONFIRM_BODY, substring = true).assertDoesNotExist()
            diagnostics.release.complete(Unit)

            waitUntil("the export resolves", TIMEOUT_MS) {
                diagnostics.delegate.exportCalls.size == 1
            }
            assertEquals(1, diagnostics.enteredCount, "the export ran exactly once")
            assertEquals(1, actions.saverCalls.size)
        }

    /** 08's dialog contract: both actions stay reachable at 200 % text in a small window. */
    @Test
    fun confirmationStaysReachableAtLargeTextInASmallWindow() =
        runDesktopComposeUiTest(width = 640, height = 480) {
            val diagnostics = FakeDiagnosticsRepository()
            val actions = RecordingActions(androidShare = false)
            setDiagnosticsPage(diagnostics, actions, density = Density(1f, 2f))

            onNodeWithText("Export database copy").performScrollTo().performClick()

            onNodeWithText("Cancel").assertIsDisplayed()
            onNodeWithText("Export").assertIsDisplayed().performClick()
            waitUntil("the save dialog ran", TIMEOUT_MS) { actions.saverCalls.size == 1 }
        }

    @Test
    fun germanConfirmationIsLocalized() =
        runComposeUiTest {
            Locale.setDefault(Locale.GERMAN)
            val diagnostics = FakeDiagnosticsRepository()
            val actions = RecordingActions(androidShare = false)
            setDiagnosticsPage(diagnostics, actions)

            onNodeWithText("Datenbankkopie exportieren").performClick()
            onNodeWithText("deine Abonnements", substring = true).assertIsDisplayed()
            onNodeWithText("Exportieren").assertIsDisplayed()
            onNodeWithText("Abbrechen").performClick()

            assertTrue(diagnostics.exportCalls.isEmpty() && actions.saverCalls.isEmpty())
        }

    private fun ComposeUiTest.setDiagnosticsPage(
        diagnostics: DiagnosticsRepository,
        actions: PlatformActions,
        density: Density? = null,
    ) {
        val viewModel = DiagnosticsViewModel(diagnostics, FakeSettingsRepository(), TestClock())
        setContent {
            val snackbar = remember { SnackbarHostState() }
            val providers = @Composable {
                CompositionLocalProvider(
                    LocalLifecycleOwner provides ResumedLifecycleOwner(),
                    LocalSaveableStateRegistry provides SaveableStateRegistry(null) { true },
                    LocalAppNavigator provides RecordingAppNavigator(),
                    LocalPlatformActions provides actions,
                    LocalSnackbarHost provides snackbar,
                ) {
                    NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                        Column {
                            Box(Modifier.weight(1f)) { DiagnosticsPage(viewModel) }
                            SnackbarHost(snackbar)
                        }
                    }
                }
            }
            if (density != null) {
                CompositionLocalProvider(LocalDensity provides density) { providers() }
            } else {
                providers()
            }
        }
        waitForIdle()
    }

    /** `createUnsafe` skips the main-thread checks so the test can resume it off the UI thread. */
    private class ResumedLifecycleOwner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)

        init {
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
    }

    private companion object {
        const val CONFIRM_BODY =
            "your subscriptions, episode titles and listening history"
        const val TIMEOUT_MS = 5_000L
    }
}

/** Records the platform seams the export flow drives: save dialog, share sheet, reveal. */
private class RecordingActions(
    androidShare: Boolean,
    private val saverAnswer: String? = "file:///tmp/export.db",
) : PlatformActions {
    val saverCalls = mutableListOf<Pair<String, String>>()
    val shareFileCalls = mutableListOf<Triple<String, String, String>>()
    val shareTextCalls = mutableListOf<String>()
    val revealCalls = mutableListOf<String>()
    val saverEntered = CompletableDeferred<Unit>()
    var saverGate: CompletableDeferred<Unit>? = null

    override val urls = ExternalUrlOpener { OpenResult.OPENED }
    override val notifications = null
    override val share: ShareSheet? =
        if (androidShare) {
            object : ShareSheet {
                override fun shareText(
                    text: String,
                    subject: String?,
                ) {
                    shareTextCalls += text
                }

                override fun shareFile(
                    uri: String,
                    mimeType: String,
                    title: String,
                ) {
                    shareFileCalls += Triple(uri, mimeType, title)
                }
            }
        } else {
            null
        }
    override val files =
        object : FilePicker {
            override suspend fun pickFile(
                mimeTypes: List<String>,
                extensions: List<String>,
            ): String? = null

            override suspend fun pickFolder(title: String): String? = null
        }
    override val saver =
        FileSaver { suggestedName, mimeType ->
            saverEntered.complete(Unit)
            saverGate?.await()
            saverCalls += suggestedName to mimeType
            saverAnswer
        }
    override val reveal =
        RevealInFolder {
            revealCalls += it
            true
        }
}

/** The fake behind a gate that holds the repository call until the test releases it. */
private class GatedDiagnostics(
    val delegate: FakeDiagnosticsRepository = FakeDiagnosticsRepository(),
) : DiagnosticsRepository by delegate {
    var enteredCount = 0
    val release = CompletableDeferred<Unit>()

    override suspend fun exportDatabaseCopy(destination: String?): Outcome<String, DiagnosticsError> {
        enteredCount++
        release.await()
        return delegate.exportDatabaseCopy(destination)
    }
}
