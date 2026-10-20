// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.importexport

import androidx.lifecycle.ViewModelStore
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.domain.DiscardPreviewResult
import ch.lkmc.neutrodyne.core.domain.ImportRepository
import ch.lkmc.neutrodyne.core.domain.ImportScreenVisibility
import ch.lkmc.neutrodyne.core.domain.ImportSessionOperations
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.ImportItemKind
import ch.lkmc.neutrodyne.core.model.ImportItemStatus
import ch.lkmc.neutrodyne.core.model.ImportItemView
import ch.lkmc.neutrodyne.core.model.ImportSource
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.testing.FakeGroupRepository
import ch.lkmc.neutrodyne.core.testing.FakeImportRepository
import ch.lkmc.neutrodyne.core.testing.FakePodcastRepository
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ImportSessionRegressionTest : MainDispatcherTest() {
    private val imports = FakeImportRepository()
    private val row =
        ImportItemView(
            0,
            "Private show",
            "feed.example",
            ImportItemKind.RSS,
            ImportItemStatus.AUTH_REQUIRED,
            false,
            false,
            emptyList(),
            true,
            "HTTP_AUTH",
            12,
        )

    private suspend fun seed(state: ImportState): Long {
        imports.createItems = listOf(row)
        val id = (imports.create(ImportSource("file:///one.opml", "one.opml")) as Outcome.Success).value
        imports.sessions.update { it + (id to it.getValue(id).copy(state = state)) }
        return id
    }

    private var operations: ImportSessionOperations? = null

    private fun vm(
        id: Long,
        repo: ImportRepository,
        scope: CoroutineScope,
    ): ImportViewModel {
        val owner = operations ?: ImportSessionOperations(scope).also { operations = it }
        return ImportViewModel(
            id,
            repo,
            FakeGroupRepository(),
            ImportScreenVisibility(),
            scope,
            FakePodcastRepository(),
            owner,
        )
    }

    @Test fun discardMustNotStopAConcurrentlyCommittedImport() =
        runTest {
            val id = seed(ImportState.PREVIEW)
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val atCancel = CompletableDeferred<Unit>()
            val proceed = CompletableDeferred<Unit>()
            val repo =
                object : ImportRepository by imports {
                    override suspend fun discardPreview(sessionId: Long): DiscardPreviewResult {
                        atCancel.complete(Unit)
                        proceed.await() // Confirm commits after the VM's last PREVIEW read.
                        return imports.discardPreview(sessionId)
                    }
                }
            val banner = ImportResumeViewModel(repo, scope)
            advanceUntilIdle()
            val discard = async { banner.discard() }
            runCurrent()
            atCancel.await()
            imports.confirm(id)
            assertEquals(
                ImportState.COMMITTED,
                imports.sessions.value
                    .getValue(id)
                    .state,
            )
            proceed.complete(Unit)
            discard.await()
            val actual =
                imports.sessions.value
                    .getValue(id)
                    .state
            scope.cancel()
            assertEquals(
                ImportState.COMMITTED,
                actual,
                "Discard is preview-only: a racing confirm must keep its active import",
            )
        }

    @Test fun reopenedRouteMustKeepBusyUntilAcceptedPasswordWriteCompletes() =
        runTest {
            val id = seed(ImportState.DONE)
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val gate = CompletableDeferred<Unit>()
            val repo =
                object : ImportRepository by imports {
                    override suspend fun enterPassword(
                        sessionId: Long,
                        ordinal: Int,
                        credentials: BasicCredentials,
                    ): Outcome<Unit, AddPodcastError> {
                        gate.await()
                        return Outcome.Success(Unit)
                    }
                }
            val first = vm(id, repo, scope)
            val store = ViewModelStore().apply { put("import", first) }
            advanceUntilIdle()
            val caller = launch { first.enterPassword(row, BasicCredentials("u", "old")) }
            runCurrent()
            assertTrue(first.uiState.value.busy)
            caller.cancel()
            store.clear()
            val reopened = vm(id, repo, scope)
            advanceUntilIdle()
            val busy = reopened.uiState.value.busy
            gate.complete(Unit)
            runCurrent()
            scope.cancel()
            assertTrue(busy, "Reopened route must remain busy while its previous accepted fix-up runs")
        }

    @Test fun reopenedRouteMustRejectOverlappingFixUps() =
        runTest {
            val id = seed(ImportState.DONE)
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val gate = CompletableDeferred<Unit>()
            var active = 0
            var maxActive = 0
            val repo =
                object : ImportRepository by imports {
                    override suspend fun enterPassword(
                        sessionId: Long,
                        ordinal: Int,
                        credentials: BasicCredentials,
                    ): Outcome<Unit, AddPodcastError> {
                        active++
                        maxActive = maxOf(maxActive, active)
                        gate.await()
                        active--
                        return Outcome.Success(Unit)
                    }
                }
            val first = vm(id, repo, scope)
            val store = ViewModelStore().apply { put("import", first) }
            advanceUntilIdle()
            val caller = launch { first.enterPassword(row, BasicCredentials("u", "old")) }
            runCurrent()
            caller.cancel()
            store.clear()
            val reopened = vm(id, repo, scope)
            advanceUntilIdle()
            launch { reopened.enterPassword(row, BasicCredentials("u", "new")) }
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            scope.cancel()
            assertEquals(1, maxActive, "One session must not run two accepted password fix-ups concurrently")
        }

    @Test fun discardMustReportWhenCancelKeepsThePreview() =
        runTest {
            val id = seed(ImportState.PREVIEW)
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            // A failed delete retains the preview and must be surfaced by the banner.
            val repo =
                object : ImportRepository by imports {
                    override suspend fun discardPreview(sessionId: Long) = DiscardPreviewResult.FAILED
                }
            val banner = ImportResumeViewModel(repo, scope)
            advanceUntilIdle()
            val result = banner.discard()
            assertNotNull(imports.sessions.value[id])
            assertTrue(banner.messages.value.isNotEmpty(), "A retained preview must surface the discard failure")
            scope.cancel()
            assertFalse(result, "Discard must not report success when cancel retained the preview")
        }

    @Test fun editUrlMustForwardTheSupportedNormalizedInputForms() =
        runTest {
            val id = seed(ImportState.DONE)
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val submitted = mutableListOf<String>()
            val repo =
                object : ImportRepository by imports {
                    override suspend fun editUrl(
                        sessionId: Long,
                        ordinal: Int,
                        input: String,
                    ): Outcome<Unit, AddPodcastError> {
                        submitted += input
                        return imports.editUrl(sessionId, ordinal, input)
                    }
                }
            val model = vm(id, repo, scope)
            advanceUntilIdle()
            val forms =
                listOf(
                    "feeds.example/show.xml",
                    "feed:https://feeds.example/show.xml",
                    "  feed:https://feeds.example/show.xml  ",
                )
            val rejected = forms.filter { model.editUrl(row, it) != null }
            scope.cancel()
            assertEquals(
                emptyList<String>(),
                rejected,
                "Edit URL must let the repository normalize supported feed input",
            )
            assertEquals(forms, submitted, "Normalization must receive the raw input unchanged")
        }
}
