// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.importexport

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onAllNodesWithText
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
import ch.lkmc.neutrodyne.core.model.ImportFormat
import ch.lkmc.neutrodyne.core.model.PrivacyCounts
import ch.lkmc.neutrodyne.core.navigation.ExportKey
import ch.lkmc.neutrodyne.core.navigation.ImportKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.testing.FakeBackupRepository
import ch.lkmc.neutrodyne.core.testing.FakeImportRepository
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.platform.ExternalUrlOpener
import ch.lkmc.neutrodyne.core.ui.platform.FilePicker
import ch.lkmc.neutrodyne.core.ui.platform.FileSaver
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.OpenResult
import ch.lkmc.neutrodyne.core.ui.platform.PlatformActions
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class BackupRouteTest {
    private lateinit var locale: Locale
    private val backups =
        FakeBackupRepository().apply {
            snapshotStatus.value =
                snapshotStatus.value.copy(supported = false)
        }
    private val imports = FakeImportRepository()
    private val navigator = RecordingAppNavigator()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val vm = BackupViewModel(backups, imports, FakeSettingsRepository(), appScope)
    private val host = SnackbarHostState()
    private var target: String? = "file:///backup.zip"
    private var source: String? = null
    private val saves = mutableListOf<Pair<String, String>>()
    private val picks = mutableListOf<Pair<List<String>, List<String>>>()
    private val visible = mutableStateOf(true)
    private val actions =
        object : PlatformActions {
            override val urls = ExternalUrlOpener { OpenResult.OPENED }
            override val share = null
            override val files =
                object : FilePicker {
                    override suspend fun pickFile(
                        mimeTypes: List<String>,
                        extensions: List<String>,
                    ): String? {
                        picks += mimeTypes to extensions
                        return source
                    }

                    override suspend fun pickFolder(title: String): String? = null
                }
            override val saver =
                FileSaver { name, mime ->
                    saves += name to mime
                    target
                }
            override val reveal = null
            override val notifications = null
        }

    @BeforeTest fun setUp() {
        locale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest fun tearDown() {
        appScope.cancel()
        Locale.setDefault(locale)
    }

    private fun androidx.compose.ui.test.ComposeUiTest.content() {
        setContent {
            CompositionLocalProvider(
                LocalLifecycleOwner provides Owner(),
                LocalPlatformKind provides PlatformKind.DESKTOP,
                LocalAppNavigator provides navigator,
                LocalPlatformActions provides actions,
                LocalSnackbarHost provides host,
            ) {
                NeutrodyneTheme(
                    AppearancePrefs(),
                    SystemUiState.DEFAULT,
                ) { if (visible.value) BackupRoute(viewModel = vm) }
            }
        }
        waitForIdle()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.awaitText(text: String) {
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun warningUsesPreparedCountsAndCancelNeverOpensTheSaveLauncher() =
        runComposeUiTest {
            backups.prepareOutcome = Outcome.Success(backupReceipt(PrivacyCounts(7, 3)))
            backups.preflightCounts = PrivacyCounts(99, 88)
            content()
            onNodeWithText("Create backup").performClick()
            awaitText("This file contains private access links for 7 feeds. Anyone with the file can listen to them.")
            onNodeWithText("It also contains 3 passwords in plain text.").assertExists()
            assertTrue(saves.isEmpty())
            onNodeWithText("Cancel").performClick()
            waitForIdle()
            assertTrue(backups.savePreparedCalls.isEmpty())
            onNodeWithText("Create backup").performClick()
            awaitText("Continue")
            onNodeWithText("Continue").performClick()
            waitUntil(timeoutMillis = 5_000) { backups.savePreparedCalls.size == 1 }
            assertEquals(listOf("neutrodyne-backup-2026-10-18-1200.zip" to "application/zip"), saves)
            waitUntil(timeoutMillis = 5_000) { host.currentSnackbarData?.visuals?.message == "Backup saved (2.4 MB)" }
        }

    @Test fun passwordConsentResetsWhenTheSameRouteIsOpenedAgain() =
        runComposeUiTest {
            content()
            onNodeWithText("Include passwords for private feeds").assertIsOff().performClick().assertIsOn()
            runOnIdle { visible.value = false }
            waitForIdle()
            runOnIdle { visible.value = true }
            waitForIdle()
            onNodeWithText("Include passwords for private feeds").assertIsOff()
        }

    @Test fun cancelledSavePickerWritesNothingAndPostsNoSnackbar() =
        runComposeUiTest {
            target = null
            content()
            onNodeWithText("Create backup").performClick()
            waitUntil(timeoutMillis = 5_000) { saves.size == 1 && !vm.uiState.value.busy }
            assertTrue(backups.savePreparedCalls.isEmpty())
            assertTrue(vm.messages.value.isEmpty())
        }

    @Test fun exportAndRestoreUseExistingNavigationAndAcquisitionSeams() =
        runComposeUiTest {
            source = "file:///restore.zip"
            imports.createFormat = ImportFormat.NEUTRODYNE_BACKUP
            content()
            onNodeWithText("Export subscriptions (OPML)").performClick()
            assertEquals(
                ExportKey(null),
                navigator.calls
                    .filterIsInstance<RecordingAppNavigator.Call.Push>()
                    .map {
                        it.key
                    }.single(),
            )
            onNodeWithText("Restore from backup").performClick()
            waitUntil(timeoutMillis = 5_000) {
                navigator.calls
                    .filterIsInstance<RecordingAppNavigator.Call.Push>()
                    .map { it.key }
                    .size ==
                    2
            }
            assertTrue(
                navigator.calls
                    .filterIsInstance<RecordingAppNavigator.Call.Push>()
                    .map { it.key }
                    .last() is ImportKey,
            )
            assertEquals(listOf(listOf("*/*") to listOf("zip")), picks)
            assertTrue(imports.calls.any { it.startsWith("create(") })
        }

    @Test fun germanScreenAndWarningAreLocalized() =
        runComposeUiTest {
            Locale.setDefault(Locale.GERMAN)
            backups.prepareOutcome = Outcome.Success(backupReceipt(PrivacyCounts(0, 3)))
            content()
            onNodeWithText("Sichern und wiederherstellen").assertExists()
            onNodeWithText("Sicherung erstellen").performClick()
            awaitText("Fortfahren")
            // The shared export warning supplies its existing German translation.
            assertTrue(saves.isEmpty())
        }

    private class Owner : LifecycleOwner {
        override val lifecycle =
            LifecycleRegistry
                .createUnsafe(
                    this,
                ).apply { handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
    }
}
