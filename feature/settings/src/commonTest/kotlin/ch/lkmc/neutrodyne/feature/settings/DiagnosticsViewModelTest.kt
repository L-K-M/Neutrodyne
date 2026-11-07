// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.DiagnosticsError
import ch.lkmc.neutrodyne.core.testing.FakeDiagnosticsRepository
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import ch.lkmc.neutrodyne.core.testing.TestClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The diagnostics screen's export bookkeeping — the busy flag must survive a crash. */
@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModelTest : MainDispatcherTest() {
    @Test
    fun anExportCrashResetsTheBusyFlagAndReportsFailure() =
        runTest(dispatcher) {
            val diagnostics = FakeDiagnosticsRepository()
            diagnostics.exportThrows = IllegalStateException("disk gone")
            val viewModel = DiagnosticsViewModel(diagnostics, FakeSettingsRepository(), TestClock())
            runCurrent()

            viewModel.exportDatabaseCopy("file:///tmp/copy.db") {}
            runCurrent()

            assertFalse(viewModel.uiState.value.exporting, "exporting must not stay set")
            assertEquals(listOf<String?>("file:///tmp/copy.db"), diagnostics.exportCalls)
        }

    @Test
    fun aRepositoryFailureResetsTheBusyFlag() =
        runTest(dispatcher) {
            val diagnostics = FakeDiagnosticsRepository()
            diagnostics.exportResult = Outcome.Failure(DiagnosticsError.NOT_ENOUGH_SPACE)
            val viewModel = DiagnosticsViewModel(diagnostics, FakeSettingsRepository(), TestClock())
            runCurrent()

            viewModel.exportDatabaseCopy(null) {}
            runCurrent()

            assertFalse(viewModel.uiState.value.exporting)
        }
}
