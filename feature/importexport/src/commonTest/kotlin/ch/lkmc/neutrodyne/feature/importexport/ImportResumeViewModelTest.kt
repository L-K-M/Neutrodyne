// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.importexport

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.model.ImportFormat
import ch.lkmc.neutrodyne.core.model.ImportSource
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.testing.FakeImportRepository
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ImportResumeViewModelTest : MainDispatcherTest() {
    @Test fun oldestOpenImportWaitsForExplicitNavigation() =
        runTest {
            val imports = FakeImportRepository()
            val first = (imports.create(ImportSource("file:///one", "one")) as Outcome.Success).value
            val second = (imports.create(ImportSource("file:///two", "two")) as Outcome.Success).value
            val vm = ImportResumeViewModel(imports, CoroutineScope(coroutineContext + SupervisorJob()))
            advanceUntilIdle()
            assertEquals(
                first,
                vm.uiState.value.session
                    ?.id,
                "Oldest open session must appear in the resume banner",
            )
            assertTrue(vm.discard(), "Discard must cancel the preview")
            advanceUntilIdle()
            assertEquals(
                second,
                vm.uiState.value.session
                    ?.id,
            )
        }

    @Test fun backupPreviewWaitsForD4AndClosedImportsDisappear() =
        runTest {
            val imports = FakeImportRepository()
            imports.createFormat = ImportFormat.NEUTRODYNE_BACKUP
            imports.create(ImportSource("file:///backup", "backup"))
            imports.createFormat = ImportFormat.OPML
            val id = (imports.create(ImportSource("file:///one", "one")) as Outcome.Success).value
            imports.sessions.update { it + (id to it.getValue(id).copy(state = ImportState.FETCHING)) }
            val vm = ImportResumeViewModel(imports, CoroutineScope(coroutineContext + SupervisorJob()))
            advanceUntilIdle()
            assertEquals(
                id,
                vm.uiState.value.session
                    ?.id,
                "Fetching sessions must remain resumable",
            )
            imports.sessions.update { it + (id to it.getValue(id).copy(state = ImportState.DONE)) }
            advanceUntilIdle()
            assertEquals(null, vm.uiState.value.session)
        }

    @Test fun discardFailureKeepsThePreviewAndReportsFailure() =
        runTest {
            val imports = FakeImportRepository()
            val id = (imports.create(ImportSource("file:///one", "one")) as Outcome.Success).value
            val repo =
                object : ch.lkmc.neutrodyne.core.domain.ImportRepository by imports {
                    override suspend fun discardPreview(
                        sessionId: Long,
                    ): ch.lkmc.neutrodyne.core.domain.DiscardPreviewResult = error("storage failed")
                }
            val vm = ImportResumeViewModel(repo, CoroutineScope(coroutineContext + SupervisorJob()))
            advanceUntilIdle()
            kotlin.test.assertFalse(vm.discard())
            assertEquals(
                id,
                vm.uiState.value.session
                    ?.id,
            )
            assertTrue(vm.messages.value.isNotEmpty())
            kotlin.test.assertFalse(vm.uiState.value.busy)
        }

    @Test fun failedObservationOffersAnExplicitResubscription() =
        runTest {
            val imports = FakeImportRepository()
            val id = (imports.create(ImportSource("file:///one", "one")) as Outcome.Success).value
            var fail = true
            val repo =
                object : ch.lkmc.neutrodyne.core.domain.ImportRepository by imports {
                    override fun observeOpenSessions() =
                        if (fail) {
                            kotlinx.coroutines.flow.flow {
                                error("storage failed")
                            }
                        } else {
                            imports.observeOpenSessions()
                        }
                }
            val vm = ImportResumeViewModel(repo, CoroutineScope(coroutineContext + SupervisorJob()))
            advanceUntilIdle()
            assertTrue(vm.uiState.value.failed)
            fail = false
            vm.retryObservation()
            advanceUntilIdle()
            assertEquals(
                id,
                vm.uiState.value.session
                    ?.id,
            )
            kotlin.test.assertFalse(vm.uiState.value.failed)
        }

    @Test fun acceptedDiscardSurvivesRouteTeardown() =
        runTest {
            val imports = FakeImportRepository()
            val id = (imports.create(ImportSource("file:///one", "one")) as Outcome.Success).value
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val repo =
                object : ch.lkmc.neutrodyne.core.domain.ImportRepository by imports {
                    override suspend fun discardPreview(
                        sessionId: Long,
                    ): ch.lkmc.neutrodyne.core.domain.DiscardPreviewResult {
                        gate.await()
                        return imports.discardPreview(sessionId)
                    }
                }
            val vm = ImportResumeViewModel(repo, CoroutineScope(coroutineContext + SupervisorJob()))
            val store = androidx.lifecycle.ViewModelStore()
            store.put("resume", vm)
            advanceUntilIdle()
            val caller = launch { vm.discard() }
            runCurrent()
            assertTrue(vm.uiState.value.busy)
            caller.cancel()
            store.clear()
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(null, imports.sessions.value[id])
            kotlin.test.assertFalse(vm.uiState.value.busy)
        }

    @Test fun discardNeverCancelsFetchingSessions() =
        runTest {
            val imports = FakeImportRepository()
            val id = (imports.create(ImportSource("file:///one", "one")) as Outcome.Success).value
            imports.sessions.update { it + (id to it.getValue(id).copy(state = ImportState.COMMITTED)) }
            val vm = ImportResumeViewModel(imports, CoroutineScope(coroutineContext + SupervisorJob()))
            advanceUntilIdle()
            kotlin.test.assertFalse(vm.discard())
            assertTrue(imports.calls.none { it.startsWith("cancel") })
        }
}
