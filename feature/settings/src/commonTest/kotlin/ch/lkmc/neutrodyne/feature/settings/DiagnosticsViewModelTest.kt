// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.domain.DiagnosticsError
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsLine
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsReport
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsSection
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsSectionId
import ch.lkmc.neutrodyne.core.model.settings.DiagnosticsSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeDiagnosticsRepository
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.feature.settings.resources.Res
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_copy_done
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_export_cancelled
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_export_done
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_export_failed
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_export_no_space
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class TestPlatformInfo(
    override val androidSdkInt: Int? = null,
) : PlatformInfo {
    override val kind: PlatformKind =
        if (androidSdkInt == null) PlatformKind.DESKTOP else PlatformKind.ANDROID
    override val userAgentPlatform: String = "Test"
    override val regionCode: String = "DE"
}

private fun section(
    id: DiagnosticsSectionId,
    vararg keys: String,
) = DiagnosticsSection(id, keys.map { DiagnosticsLine(it, "v-$it") })

private fun reportOf(vararg sections: DiagnosticsSection) =
    DiagnosticsReport(generatedAt = 1_000, sections = sections.toList())

/** 08/09 Diagnostics screen state: issue URL, clipboard rule, export errors, verbose switch. */
@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModelTest : MainDispatcherTest() {
    private fun viewModel(
        diagnostics: FakeDiagnosticsRepository = FakeDiagnosticsRepository(),
        settings: FakeSettingsRepository = FakeSettingsRepository(),
        clock: Clock = TestClock(),
        sdk: Int? = null,
    ) = DiagnosticsViewModel(diagnostics, settings, clock, UpdateTestBuild, TestPlatformInfo(sdk))

    private fun TestScope.awaitReport(vm: DiagnosticsViewModel) {
        runCurrent()
        assertTrue(vm.uiState.value.report != null)
    }

    @Test
    fun issueUrlOmitsLogAndParseWarningsAndIsEncoded() =
        runTest {
            val diagnostics =
                FakeDiagnosticsRepository(
                    reportOf(
                        section(DiagnosticsSectionId.APP, "appLine"),
                        section(DiagnosticsSectionId.SYNC, "syncLine"),
                        section(DiagnosticsSectionId.LOG, "logLine"),
                        section(DiagnosticsSectionId.PARSE_WARNINGS, "warnLine"),
                    ),
                )
            val vm = viewModel(diagnostics)
            awaitReport(vm)

            val url = vm.issueReportUrl()!!
            assertTrue(
                url.startsWith(
                    "https://github.com/L-K-M/Neutrodyne/issues/new?template=bug.yml&diagnostics=",
                ),
            )
            assertTrue("appLine" in url)
            assertTrue("syncLine" in url)
            assertFalse("logLine" in url)
            assertFalse("warnLine" in url)
            // Spaces and newlines never reach the URL raw — everything reserved is %XX.
            assertFalse(" " in url)
            assertFalse("\n" in url)
            assertTrue("appLine%3A%20v-appLine" in url)
        }

    @Test
    fun issueUrlOmitsTheDesktopLogDirectoryButKeepsSafeDesktopRows() =
        runTest {
            val diagnostics =
                FakeDiagnosticsRepository(
                    reportOf(
                        DiagnosticsSection(
                            DiagnosticsSectionId.DESKTOP,
                            listOf(
                                DiagnosticsLine("logDir", "/Users/alice/.local/state/neutrodyne/logs"),
                                DiagnosticsLine("lastWakeAt", "1234"),
                                DiagnosticsLine("engineChild", "not running"),
                            ),
                        ),
                    ),
                )
            val vm = viewModel(diagnostics)
            awaitReport(vm)

            val url = vm.issueReportUrl()!!
            // The absolute home path and the OS username never reach the public issue URL.
            assertFalse("alice" in url)
            assertFalse("%2FUsers%2Falice" in url)
            // The safe desktop rows stay in the pre-fill.
            assertTrue("lastWakeAt" in url)
            assertTrue("engineChild" in url)
            // The local report keeps the log directory for the user's own diagnostics.
            assertTrue("/Users/alice/.local/state/neutrodyne/logs" in vm.plainTextReport())
        }

    @Test
    fun issueUrlTruncatesToTheGitHubBudget() =
        runTest {
            val huge = "x".repeat(60_000)
            val diagnostics =
                FakeDiagnosticsRepository(
                    reportOf(
                        DiagnosticsSection(
                            DiagnosticsSectionId.APP,
                            listOf(DiagnosticsLine("blob", huge)),
                        ),
                    ),
                )
            val vm = viewModel(diagnostics)
            awaitReport(vm)

            val url = vm.issueReportUrl()!!
            assertTrue(url.length <= 7_000, "issue URL must stay under 7000 chars, was ${url.length}")
            assertTrue("truncated" in url)
        }

    @Test
    fun issueUrlIsNullBeforeTheFirstReport() =
        runTest {
            val diagnostics = FakeDiagnosticsRepository()
            diagnostics.snapshotError = IllegalStateException("db down")
            val vm = viewModel(diagnostics)
            runCurrent()
            assertNull(vm.uiState.value.report)
            assertNull(vm.issueReportUrl())
            assertEquals("", vm.plainTextReport())
        }

    @Test
    fun copiedPostsSnackbarOnDesktopAndApi32ButNotApi33() =
        runTest {
            // 08's rule: Android 13+ shows the system confirmation, ours would double it.
            val desktop = viewModel()
            desktop.copied()
            runCurrent()
            assertEquals(
                listOf(UiText.Res(Res.string.diagnostics_copy_done)),
                desktop.messages.value.map { it.text },
            )

            val api33 = viewModel(sdk = 33)
            api33.copied()
            runCurrent()
            assertTrue(api33.messages.value.isEmpty())

            val api32 = viewModel(sdk = 32)
            api32.copied()
            runCurrent()
            assertEquals(1, api32.messages.value.size)
        }

    @Test
    fun verboseSwitchWritesThe24HourDeadline() =
        runTest {
            val settings = FakeSettingsRepository()
            val clock = TestClock()
            val vm = viewModel(settings = settings, clock = clock)
            backgroundScope.launch { vm.uiState.collect {} }
            runCurrent()

            vm.setVerboseLogging(true)
            runCurrent()
            assertEquals(clock.now() + 86_400_000, settings.get(DiagnosticsSettingKeys.VERBOSE_LOG_UNTIL))
            assertTrue(vm.uiState.value.verboseLogging)

            vm.setVerboseLogging(false)
            runCurrent()
            assertEquals(0L, settings.get(DiagnosticsSettingKeys.VERBOSE_LOG_UNTIL))
            assertFalse(vm.uiState.value.verboseLogging)
        }

    @Test
    fun exportOutcomesMapToTheirOwnMessage() =
        runTest {
            val diagnostics = FakeDiagnosticsRepository()
            val vm = viewModel(diagnostics)
            var shared: String? = null

            diagnostics.exportResult = Outcome.Success("file:///tmp/a.db")
            vm.exportDatabaseCopy(null) { shared = it }
            runCurrent()
            assertEquals("file:///tmp/a.db", shared)
            assertEquals(
                UiText.Res(Res.string.diagnostics_export_done),
                vm.messages.value
                    .last()
                    .text,
            )

            diagnostics.exportResult = Outcome.Failure(DiagnosticsError.CANCELLED)
            vm.exportDatabaseCopy("file:///tmp/b.db") {}
            runCurrent()
            assertEquals(
                UiText.Res(Res.string.diagnostics_export_cancelled),
                vm.messages.value
                    .last()
                    .text,
            )

            diagnostics.exportResult = Outcome.Failure(DiagnosticsError.NOT_ENOUGH_SPACE)
            vm.exportDatabaseCopy(null) {}
            runCurrent()
            assertEquals(
                UiText.Res(Res.string.diagnostics_export_no_space),
                vm.messages.value
                    .last()
                    .text,
            )

            diagnostics.exportResult = Outcome.Failure(DiagnosticsError.DATABASE_BUSY)
            vm.exportDatabaseCopy(null) {}
            runCurrent()
            assertEquals(
                UiText.Res(Res.string.diagnostics_export_failed),
                vm.messages.value
                    .last()
                    .text,
            )
            assertFalse(vm.uiState.value.exporting)
        }

    @Test
    fun anExportCrashResetsTheBusyFlagAndReportsFailure() =
        runTest {
            val diagnostics = FakeDiagnosticsRepository()
            diagnostics.exportThrows = IllegalStateException("disk gone")
            val vm = viewModel(diagnostics)
            runCurrent()

            vm.exportDatabaseCopy("file:///tmp/copy.db") {}
            runCurrent()

            assertFalse(vm.uiState.value.exporting, "exporting must not stay set")
            assertEquals(listOf<String?>("file:///tmp/copy.db"), diagnostics.exportCalls)
            assertEquals(
                UiText.Res(Res.string.diagnostics_export_failed),
                vm.messages.value
                    .last()
                    .text,
            )
        }
}
