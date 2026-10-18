// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.importexport

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
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
import ch.lkmc.neutrodyne.core.navigation.ExportKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.testing.FakeExportRepository
import ch.lkmc.neutrodyne.core.testing.FakeGroupRepository
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.platform.ExternalUrlOpener
import ch.lkmc.neutrodyne.core.ui.platform.FilePicker
import ch.lkmc.neutrodyne.core.ui.platform.FileSaver
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.OpenResult
import ch.lkmc.neutrodyne.core.ui.platform.PlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.RevealInFolder
import ch.lkmc.neutrodyne.core.ui.root.LocalRootScope
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The `ExportKey` route's terminal effect (05 Destinations): a completed save pops the dialog
 * and the "Saved" snackbar lives on the root-owned scope — it survives the entry's disposal but
 * is cancelled with the root, not parked `NonCancellable` on the dead route's job. The action
 * targets the saved destination URI (desktop "Show in folder").
 */
@OptIn(ExperimentalTestApi::class)
class ExportRouteTest {
    private lateinit var previousLocale: Locale
    private val exports = FakeExportRepository()
    private val settings = FakeSettingsRepository()
    private val groups = FakeGroupRepository()
    private val navigator = RecordingAppNavigator()
    private val host = SnackbarHostState()
    private val revealed = mutableListOf<String>()

    private val actions =
        object : PlatformActions {
            override val urls: ExternalUrlOpener = ExternalUrlOpener { OpenResult.OPENED }
            override val share = null
            override val files: FilePicker =
                object : FilePicker {
                    override suspend fun pickFile(
                        mimeTypes: List<String>,
                        extensions: List<String>,
                    ): String? = null

                    override suspend fun pickFolder(title: String): String? = null
                }
            override val saver: FileSaver = FileSaver { _, _ -> SAVED_URI }
            override val reveal: RevealInFolder =
                RevealInFolder {
                    revealed += it
                    true
                }
            override val notifications = null
        }

    @BeforeTest
    fun setUp() {
        // The shared test-task config sets the JVM to de_DE; these assertions name English labels.
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest
    fun tearDown() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun aSavePopsTheDialogAndShowsInFolderTargetsTheSavedUri() =
        runComposeUiTest {
            setRouteContent()

            onNodeWithText("Save to file").performClick()

            waitUntil(timeoutMillis = 5_000) { host.currentSnackbarData != null }
            assertEquals(
                listOf<RecordingAppNavigator.Call>(RecordingAppNavigator.Call.PopEntry(ExportKey(null))),
                navigator.calls,
            )
            // An action snackbar on the serial root host must be bounded (08's duration rule).
            assertEquals(SnackbarDuration.Long, host.currentSnackbarData?.visuals?.duration)

            onNodeWithText("Show in folder").performClick()
            waitUntil(timeoutMillis = 5_000) { revealed.isNotEmpty() }
            assertEquals(listOf(SAVED_URI), revealed)
        }

    @Test
    fun theSavedSnackbarEndsWithTheRootOwner() =
        runComposeUiTest {
            setRouteContent()

            onNodeWithText("Save to file").performClick()
            waitUntil(timeoutMillis = 5_000) { host.currentSnackbarData != null }

            runOnIdle { ownerAlive = false }
            waitUntil(timeoutMillis = 5_000) { host.currentSnackbarData == null }
            assertNull(host.currentSnackbarData)
        }

    private var ownerAlive by mutableStateOf(true)

    /** The route under a fake root-owned scope that dies with its provider's composition. */
    private fun androidx.compose.ui.test.ComposeUiTest.setRouteContent() {
        ownerAlive = true
        val viewModel = ExportViewModel(null, exports, settings, groups)
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides ResumedLifecycleOwner()) {
                CompositionLocalProvider(
                    LocalPlatformKind provides PlatformKind.DESKTOP,
                    LocalAppNavigator provides navigator,
                    LocalSnackbarHost provides host,
                    LocalPlatformActions provides actions,
                ) {
                    NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                        if (ownerAlive) {
                            val rootScope = rememberCoroutineScope()
                            CompositionLocalProvider(LocalRootScope provides rootScope) {
                                ExportRoute(ExportKey(null), viewModel)
                            }
                        }
                        SnackbarHost(host)
                    }
                }
            }
        }
        waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.ready }
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
        const val SAVED_URI = "file:///saved/neutrodyne-subscriptions.opml"
    }
}
