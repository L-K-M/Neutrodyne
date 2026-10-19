// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.importexport

import androidx.lifecycle.ViewModelStore
import androidx.paging.testing.asSnapshot
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.domain.GroupError
import ch.lkmc.neutrodyne.core.domain.ImportError
import ch.lkmc.neutrodyne.core.domain.ImportRepository
import ch.lkmc.neutrodyne.core.domain.ImportScreenVisibility
import ch.lkmc.neutrodyne.core.domain.ImportSessionOperations
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.model.GroupProposal
import ch.lkmc.neutrodyne.core.model.ImportFormat
import ch.lkmc.neutrodyne.core.model.ImportItemFilter
import ch.lkmc.neutrodyne.core.model.ImportItemKind
import ch.lkmc.neutrodyne.core.model.ImportItemStatus
import ch.lkmc.neutrodyne.core.model.ImportItemView
import ch.lkmc.neutrodyne.core.model.ImportOptions
import ch.lkmc.neutrodyne.core.model.ImportSessionView
import ch.lkmc.neutrodyne.core.model.ImportSource
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.testing.FakeGroupRepository
import ch.lkmc.neutrodyne.core.testing.FakeImportRepository
import ch.lkmc.neutrodyne.core.testing.FakePodcastRepository
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.platform.NotificationPermissionRequester
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.import_err_confirm
import ch.lkmc.neutrodyne.core.ui.resources.import_err_gone
import ch.lkmc.neutrodyne.core.ui.resources.write_failed
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ImportViewModelTest : MainDispatcherTest() {
    private val imports = FakeImportRepository()
    private val podcasts = FakePodcastRepository()
    private val groups = FakeGroupRepository()
    private val visibility = ImportScreenVisibility()

    /**
     * The injected `@ApplicationScope` stand-in: a detached scope on the test's own scheduler,
     * so a cancelled route can never take a data job down with it (`backgroundScope` would fit
     * the lifetime but `advanceUntilIdle` can starve its jobs — the D3 export tests document
     * the same choice).
     */
    private fun TestScope.appScope(): CoroutineScope = CoroutineScope(coroutineContext + SupervisorJob())

    private fun TestScope.viewModel(sessionId: Long) =
        ImportViewModel(
            sessionId,
            imports,
            groups,
            visibility,
            appScope(),
            podcasts,
            ImportSessionOperations(appScope()),
        )

    private fun item(
        ordinal: Int,
        title: String = "Show $ordinal",
        host: String = "host$ordinal.example.com",
        status: ImportItemStatus = ImportItemStatus.PREVIEW,
        selected: Boolean = true,
        kind: ImportItemKind = ImportItemKind.RSS,
        isPrivate: Boolean = false,
        errorDetail: String? = null,
    ) = ImportItemView(
        ordinal = ordinal,
        title = title,
        host = host,
        kind = kind,
        status = status,
        selected = selected,
        selectable = status == ImportItemStatus.PREVIEW,
        groupNames = emptyList(),
        isPrivate = isPrivate,
        errorDetail = errorDetail,
        podcastId = null,
    )

    private fun proposal(
        nameKey: String,
        name: String = nameKey,
        memberCount: Int = 2,
        existingGroupId: Long? = null,
        included: Boolean = true,
        excludedReason: String? = null,
    ) = GroupProposal(
        nameKey = nameKey,
        name = name,
        memberCount = memberCount,
        existingGroupId = existingGroupId,
        included = included,
        excludedReason = excludedReason,
    )

    private suspend fun seed(
        sessionItems: List<ImportItemView> = listOf(item(0), item(1)),
        sessionProposals: List<GroupProposal> = emptyList(),
        format: ImportFormat = ImportFormat.OPML,
        options: ImportOptions = ImportOptions(),
        state: ImportState = ImportState.PREVIEW,
        sourceName: String? = "feeds.opml",
    ): Long {
        imports.createItems = sessionItems
        imports.createProposals = sessionProposals
        imports.createFormat = format
        imports.createOptions = { options.copy(targetGroupId = it.targetGroupId) }
        val id =
            (imports.create(ImportSource("file:///x", sourceName)) as Outcome.Success).value
        if (state != ImportState.PREVIEW) {
            imports.sessions.update {
                it + (id to it.getValue(id).copy(state = state))
            }
        }
        return id
    }

    private fun session(id: Long) = imports.sessions.value[id]

    @Test fun retryRequeuesFailedRowThroughRepository() =
        runTest {
            val row = item(0, status = ImportItemStatus.GONE).copy(podcastId = 12)
            val id = seed(listOf(row), state = ImportState.DONE)
            val vm = viewModel(id)
            advanceUntilIdle()
            assertTrue(vm.retry(row), "Retry must succeed through the repository")
            assertTrue(imports.calls.contains("retry($id, [0])"))
        }

    @Test fun removeUsesSessionOwnershipAndCompleteNoMediaSet() =
        runTest {
            val rows = (0 until 100).map { item(it, status = ImportItemStatus.NO_MEDIA).copy(podcastId = it + 1L) }
            val id = seed(rows, state = ImportState.DONE)
            val vm = viewModel(id)
            advanceUntilIdle()
            assertTrue(vm.removeAllNoMedia(), "Remove all must remove the whole session set")
            assertTrue(imports.items.value[id].isNullOrEmpty())
        }

    @Test fun urlAndPasswordFixUpsReturnRepositorySuccess() =
        runTest {
            val row = item(0, status = ImportItemStatus.AUTH_REQUIRED).copy(podcastId = 12)
            val id = seed(listOf(row), state = ImportState.DONE)
            val vm = viewModel(id)
            advanceUntilIdle()
            assertNull(vm.editUrl(row, "https://example.com/feed"), "URL success must clear the inline error")
            assertNull(
                vm.enterPassword(
                    row,
                    ch.lkmc.neutrodyne.core.model
                        .BasicCredentials("user", "secret"),
                ),
                "Password success must clear the inline error",
            )
            assertTrue(imports.calls.contains("editUrl($id, 0)"))
            assertTrue(imports.calls.contains("enterPassword($id, 0)"))
        }

    @Test fun fixUpExceptionsShowFailureWithoutLoggingSecrets() =
        runTest {
            val row = item(0, status = ImportItemStatus.AUTH_REQUIRED).copy(podcastId = 12)
            val id = seed(listOf(row), state = ImportState.DONE)
            val logged = mutableListOf<String>()
            Log.install(
                ch.lkmc.neutrodyne.core.common
                    .LogSink { _, _, text -> logged += text },
            )
            val repo =
                object : ImportRepository by imports {
                    override suspend fun enterPassword(
                        sessionId: Long,
                        ordinal: Int,
                        credentials: BasicCredentials,
                    ): Outcome<Unit, AddPodcastError> = error("opaque-password")

                    override suspend fun editUrl(
                        sessionId: Long,
                        ordinal: Int,
                        input: String,
                    ): Outcome<Unit, AddPodcastError> = error("opaque-url-token")

                    override suspend fun retry(
                        sessionId: Long,
                        ordinals: Set<Int>,
                    ) = error("write failed")

                    override suspend fun remove(
                        sessionId: Long,
                        ordinals: Set<Int>,
                    ) = error("write failed")
                }
            try {
                val vm =
                    ImportViewModel(
                        id,
                        repo,
                        groups,
                        visibility,
                        appScope(),
                        podcasts,
                        ImportSessionOperations(appScope()),
                    )
                advanceUntilIdle()
                assertEquals(
                    UiText.Res(Res.string.write_failed),
                    vm.enterPassword(row, BasicCredentials("u", "opaque-password")),
                )
                assertEquals(
                    UiText.Res(Res.string.write_failed),
                    vm.editUrl(row, "https://example.com/feed?token=opaque-url-token"),
                )
                assertFalse(vm.retry(row))
                assertFalse(vm.remove(row))
                assertTrue(vm.messages.value.isNotEmpty(), "Failed retry/remove must post a message")
                assertTrue(logged.none { "opaque-password" in it || "opaque-url-token" in it })
                assertFalse(vm.uiState.value.busy)
            } finally {
                Log.install()
            }
        }

    @Test fun invalidCredentialsAndIneligibleFixUpsNeverReachRepository() =
        runTest {
            val row = item(0, status = ImportItemStatus.AUTH_REQUIRED).copy(podcastId = 12)
            val id = seed(listOf(row), state = ImportState.DONE)
            val vm = viewModel(id)
            advanceUntilIdle()
            val before = imports.calls.toList()
            assertTrue(vm.enterPassword(row, BasicCredentials("", "")) != null)
            assertTrue(vm.enterPassword(row, BasicCredentials("u:injected", "password")) != null)
            assertFalse(vm.remove(row.copy(status = ImportItemStatus.MERGED)))
            assertFalse(vm.remove(row.copy(status = ImportItemStatus.ALREADY_SUBSCRIBED)))
            assertFalse(vm.retry(row.copy(status = ImportItemStatus.FETCH_FAILED, errorDetail = "DEFERRED")))
            assertEquals(before, imports.calls)
        }

    @Test fun fixUpsReturnTypedErrorsInline() =
        runTest {
            val row = item(0, status = ImportItemStatus.AUTH_REQUIRED).copy(podcastId = 12)
            val id = seed(listOf(row), state = ImportState.DONE)
            imports.editUrlOutcome = Outcome.Failure(AddPodcastError.NotAFeed)
            imports.enterPasswordOutcome = Outcome.Failure(AddPodcastError.AuthRequired("private realm"))
            val vm = viewModel(id)
            advanceUntilIdle()
            assertEquals(addPodcastErrorText(AddPodcastError.NotAFeed), vm.editUrl(row, "https://example.com/feed"))
            assertEquals(
                addPodcastErrorText(AddPodcastError.AuthRequired(null)),
                vm.enterPassword(row, BasicCredentials("user", "secret")),
            )
            assertFalse(vm.uiState.value.busy)
        }

    @Test fun acceptedPasswordFixUpSurvivesRouteAndViewModelTeardown() =
        runTest {
            val row = item(0, status = ImportItemStatus.AUTH_REQUIRED).copy(podcastId = 12)
            val id = seed(listOf(row), state = ImportState.DONE)
            val gate = CompletableDeferred<Unit>()
            var sent: BasicCredentials? = null
            val repo =
                object : ImportRepository by imports {
                    override suspend fun enterPassword(
                        sessionId: Long,
                        ordinal: Int,
                        credentials: BasicCredentials,
                    ): Outcome<Unit, AddPodcastError> {
                        gate.await()
                        sent = credentials
                        return imports.enterPassword(sessionId, ordinal, credentials)
                    }
                }
            val vm =
                ImportViewModel(id, repo, groups, visibility, appScope(), podcasts, ImportSessionOperations(appScope()))
            val store = ViewModelStore()
            store.put("import", vm)
            advanceUntilIdle()
            val owner = Job(coroutineContext.job)
            CoroutineScope(coroutineContext + owner).launch { vm.enterPassword(row, BasicCredentials("u", "secret")) }
            runCurrent()
            assertTrue(vm.uiState.value.busy, "Accepted fix-up must own busy until repository completion")
            assertFalse(vm.retry(row), "Concurrent fix-ups must be rejected")
            owner.cancel()
            store.clear()
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(BasicCredentials("u", "secret"), sent)
            assertFalse(vm.uiState.value.busy)
            assertFalse(
                vm.uiState.value
                    .toString()
                    .contains("secret"),
            )
        }

    @Test fun fixUpsClearBusyEvenWhenCancelledBeforeDispatch() =
        runTest {
            val row = item(0, status = ImportItemStatus.GONE).copy(podcastId = 12)
            val id = seed(listOf(row), state = ImportState.DONE)
            val appJob = SupervisorJob()
            val vm =
                ImportViewModel(
                    id,
                    imports,
                    groups,
                    visibility,
                    CoroutineScope(coroutineContext + appJob),
                    podcasts,
                    ImportSessionOperations(CoroutineScope(coroutineContext + appJob)),
                )
            advanceUntilIdle()
            val caller = launch(start = CoroutineStart.UNDISPATCHED) { vm.retry(row) }
            assertTrue(vm.uiState.value.busy, "Accepted retry must set busy before dispatch")
            appJob.cancel()
            runCurrent()
            assertTrue(caller.isCancelled)
            assertFalse(vm.uiState.value.busy)
            assertTrue(imports.calls.none { it.startsWith("retry") })
        }

    @Test fun previewCancelledAndMissingPodcastRowsCannotRunFixUps() =
        runTest {
            for (state in listOf(ImportState.PREVIEW, ImportState.CANCELLED)) {
                val row = item(0, status = ImportItemStatus.GONE).copy(podcastId = 12)
                val id = seed(listOf(row), state = state)
                val vm = viewModel(id)
                advanceUntilIdle()
                val before = imports.calls.toList()
                assertFalse(vm.retry(row))
                assertFalse(vm.remove(row))
                assertTrue(vm.editUrl(row, "https://example.com/feed") != null)
                assertEquals(before, imports.calls)
            }
        }

    @Test fun basicAuthAllowsAnEmptyUsernameOrPassword() =
        runTest {
            val row = item(0, status = ImportItemStatus.AUTH_REQUIRED).copy(podcastId = 12)
            val id = seed(listOf(row), state = ImportState.DONE)
            val vm = viewModel(id)
            advanceUntilIdle()
            for (credentials in listOf(BasicCredentials("", "secret"), BasicCredentials("alice", ""))) {
                assertNull(vm.enterPassword(row, credentials), "A nonempty Basic-auth pair must reach the repository")
            }
            assertEquals(2, imports.calls.count { it.startsWith("enterPassword") })
        }

    @Test fun artworkTracksTheResolvedPodcastAndVersionChanges() =
        runTest {
            val row = item(0, status = ImportItemStatus.SUBSCRIBED).copy(podcastId = 12)
            val id = seed(listOf(row), state = ImportState.DONE)
            val vm = viewModel(id)
            val seen = mutableListOf<ch.lkmc.neutrodyne.core.model.PodcastDetail?>()
            launch { vm.artworkFor(12).take(4).toList(seen) }
            runCurrent()
            val first =
                ch.lkmc.neutrodyne.core.testing
                    .testPodcastDetail(12)
                    .copy(artworkAvgArgb = 0xff123456.toInt())
            podcasts.detail.value = first
            runCurrent()
            val updated =
                first.copy(
                    artwork = first.artwork.copy(version = first.artwork.version + 1),
                    artworkAvgArgb = 0xff654321.toInt(),
                )
            podcasts.detail.value = updated
            runCurrent()
            podcasts.detail.value = null
            advanceUntilIdle()
            assertEquals(listOf(null, first, updated, null), seen)
        }

    @Test fun newTargetNameWaitsForStorageBeforeReturningSuccess() =
        runTest {
            val id = seed()
            val gate = CompletableDeferred<Unit>()
            val repo =
                object : ImportRepository by imports {
                    override suspend fun updateOptions(
                        sessionId: Long,
                        change: (ImportOptions) -> ImportOptions,
                    ) {
                        gate.await()
                        imports.updateOptions(sessionId, change)
                    }
                }
            val vm =
                ImportViewModel(id, repo, groups, visibility, appScope(), podcasts, ImportSessionOperations(appScope()))
            advanceUntilIdle()
            val rename = launch { assertNull(vm.setTargetGroupName("News")) }
            runCurrent()

            assertFalse(rename.isCompleted, "Target name must await its persisted write")
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals("News", session(id)!!.options.targetGroupName)
        }

    @Test fun acceptedRenameAndConfirmSurviveClearingTheViewModel() =
        runTest {
            val id = seed()
            val gate = CompletableDeferred<Unit>()
            val repo =
                object : ImportRepository by imports {
                    override suspend fun updateOptions(
                        sessionId: Long,
                        change: (ImportOptions) -> ImportOptions,
                    ) {
                        gate.await()
                        imports.updateOptions(sessionId, change)
                    }
                }
            val vm =
                ImportViewModel(id, repo, groups, visibility, appScope(), podcasts, ImportSessionOperations(appScope()))
            val store = ViewModelStore()
            store.put("import", vm)
            advanceUntilIdle()
            val owner = Job(coroutineContext.job)
            val routeScope = CoroutineScope(coroutineContext + owner)
            routeScope.launch { vm.renameGroup("tech", "News") }
            runCurrent()
            routeScope.launch { vm.confirm() }
            runCurrent()
            owner.cancel()
            store.clear()
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(ImportState.COMMITTED, session(id)!!.state)
            assertEquals(mapOf("tech" to "News"), session(id)!!.options.renamedGroups)
            assertFalse(vm.uiState.value.busy)
        }

    @Test fun terminalReportFiltersRemainAvailable() =
        runTest {
            val sessionItems =
                listOf(
                    item(0, status = ImportItemStatus.SUBSCRIBED),
                    item(1, status = ImportItemStatus.FETCH_FAILED),
                    item(2, status = ImportItemStatus.INVALID_URL, selected = false),
                    item(3, selected = false),
                )
            val id = seed(sessionItems = sessionItems, state = ImportState.DONE)
            val vm = viewModel(id)
            advanceUntilIdle()
            vm.setFilter(ImportItemFilter.NEEDS_ATTENTION)
            advanceUntilIdle()
            assertEquals(
                listOf(1),
                vm.items
                    .take(1)
                    .asSnapshot()
                    .map { it.ordinal },
            )
            vm.setFilter(ImportItemFilter.NOT_IMPORTED)
            advanceUntilIdle()
            assertEquals(
                listOf(2, 3),
                vm.items
                    .take(1)
                    .asSnapshot()
                    .map { it.ordinal },
            )
        }

    @Test fun renameWaitsForStorageBeforeReturningSuccess() =
        runTest {
            val id = seed(sessionProposals = listOf(proposal("tech")))
            val gate = CompletableDeferred<Unit>()
            val repo =
                object : ImportRepository by imports {
                    override suspend fun updateOptions(
                        sessionId: Long,
                        change: (ImportOptions) -> ImportOptions,
                    ) {
                        gate.await()
                        imports.updateOptions(sessionId, change)
                    }
                }
            val vm =
                ImportViewModel(id, repo, groups, visibility, appScope(), podcasts, ImportSessionOperations(appScope()))
            advanceUntilIdle()
            val rename = launch { assertNull(vm.renameGroup("tech", "News")) }
            runCurrent()

            assertFalse(rename.isCompleted, "Rename must await its persisted write")
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(mapOf("tech" to "News"), session(id)!!.options.renamedGroups)
        }

    @Test fun confirmWaitsForAcceptedPreviewEditsInOrder() =
        runTest {
            val id = seed(sessionProposals = listOf(proposal("tech")))
            val gate = CompletableDeferred<Unit>()
            val repo =
                object : ImportRepository by imports {
                    override suspend fun updateOptions(
                        sessionId: Long,
                        change: (ImportOptions) -> ImportOptions,
                    ) {
                        gate.await()
                        imports.updateOptions(sessionId, change)
                    }
                }
            val vm =
                ImportViewModel(id, repo, groups, visibility, appScope(), podcasts, ImportSessionOperations(appScope()))
            advanceUntilIdle()
            vm.setTreatExistingAsPlayed(true)
            launch { vm.renameGroup("tech", "News") }
            vm.toggleItem(1, false)
            runCurrent()
            launch { vm.confirm() }
            runCurrent()

            assertEquals(ImportState.PREVIEW, session(id)!!.state, "Confirm must wait for accepted edits")
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(ImportState.COMMITTED, session(id)!!.state)
            assertEquals(mapOf("tech" to "News"), session(id)!!.options.renamedGroups)
            assertTrue(session(id)!!.options.treatExistingAsPlayed)
            assertEquals(
                ImportItemStatus.PREVIEW,
                imports.items.value
                    .getValue(id)[1]
                    .status,
            )
            assertTrue(imports.calls.indexOf("updateOptions($id)") < imports.calls.indexOf("confirm($id)"))
        }

    @Test fun confirmClearsBusyWhenCancelledBeforeDispatch() =
        runTest {
            val id = seed()
            val appJob = SupervisorJob()
            val vm =
                ImportViewModel(
                    id,
                    imports,
                    groups,
                    visibility,
                    CoroutineScope(coroutineContext + appJob),
                    podcasts,
                    ImportSessionOperations(CoroutineScope(coroutineContext + appJob)),
                )
            advanceUntilIdle()
            val caller = launch(start = CoroutineStart.UNDISPATCHED) { vm.confirm() }
            assertTrue(vm.uiState.value.busy)
            appJob.cancel()
            runCurrent()

            assertTrue(caller.isCancelled)
            assertFalse(vm.uiState.value.busy, "Confirm completion must clear busy even before dispatch")
            assertTrue(imports.calls.none { it.startsWith("confirm") })
        }

    @Test fun cancelClearsBusyWhenCancelledBeforeDispatch() =
        runTest {
            val id = seed()
            val appJob = SupervisorJob()
            val vm =
                ImportViewModel(
                    id,
                    imports,
                    groups,
                    visibility,
                    CoroutineScope(coroutineContext + appJob),
                    podcasts,
                    ImportSessionOperations(CoroutineScope(coroutineContext + appJob)),
                )
            advanceUntilIdle()
            val caller = launch(start = CoroutineStart.UNDISPATCHED) { vm.cancelSession() }
            assertTrue(vm.uiState.value.busy)
            appJob.cancel()
            runCurrent()

            assertTrue(caller.isCancelled)
            assertFalse(vm.uiState.value.busy, "Cancel completion must clear busy even before dispatch")
            assertTrue(imports.calls.none { it.startsWith("cancel") })
        }

    @Test fun committedAndFetchingIgnoreEveryPreviewFilter() =
        runTest {
            val sessionItems = listOf(item(0), item(1, status = ImportItemStatus.INVALID_URL, selected = false))
            val id = seed(sessionItems = sessionItems)
            val vm = viewModel(id)
            advanceUntilIdle()
            vm.setFilter(ImportItemFilter.NOT_IMPORTED)
            vm.confirm()
            advanceUntilIdle()

            assertEquals(
                listOf(0, 1),
                vm.items
                    .take(1)
                    .asSnapshot()
                    .map { it.ordinal },
            )
            for (state in listOf(ImportState.COMMITTED, ImportState.FETCHING)) {
                imports.sessions.update { it + (id to it.getValue(id).copy(state = state)) }
                for (filter in ImportItemFilter.entries) {
                    // Use a fresh paging cache for each filter instead of replaying the prior page.
                    val progress = viewModel(id)
                    advanceUntilIdle()
                    progress.setFilter(filter)
                    val rows = progress.items.take(1).asSnapshot()
                    assertEquals(listOf(0, 1), rows.map { it.ordinal }, "$state / $filter")
                }
            }
        }

    @Test fun theSessionProposalsAndSelectionsFlowFromTheRepository() =
        runTest {
            val id =
                seed(
                    sessionItems = listOf(item(0), item(1, selected = false)),
                    sessionProposals = listOf(proposal("tech", memberCount = 2)),
                )
            val vm = viewModel(id)
            advanceUntilIdle()

            val state = vm.uiState.value
            assertTrue(state.loaded)
            assertEquals("feeds.opml", state.session?.sourceName)
            assertEquals(1, state.session?.selectedCount)
            assertEquals(listOf("tech"), state.proposals.map { it.nameKey })
        }

    @Test fun aMissingSessionIsUnavailableAfterLoading() =
        runTest {
            val vm = viewModel(99L)
            advanceUntilIdle()

            assertTrue(vm.uiState.value.loaded)
            assertNull(vm.uiState.value.session)
        }

    @Test fun selectionEditsReachThePersistedRows() =
        runTest {
            val id = seed(sessionItems = listOf(item(0), item(1)))
            val vm = viewModel(id)
            advanceUntilIdle()

            vm.toggleItem(1, false)
            vm.selectAll()
            vm.selectNone()
            advanceUntilIdle()

            assertEquals(
                listOf(
                    "setSelected($id, [1], false)",
                    "setSelected($id, all, true)",
                    "setSelected($id, all, false)",
                ),
                imports.calls.filter { it.startsWith("setSelected") },
            )
            assertEquals(
                0,
                vm.uiState.value.session
                    ?.selectedCount,
            )
        }

    @Test fun nonSelectableRowsStayUntouchedBySelectAll() =
        runTest {
            val id =
                seed(
                    sessionItems =
                        listOf(
                            item(0),
                            item(1, status = ImportItemStatus.ALREADY_SUBSCRIBED, selected = false),
                            item(2, status = ImportItemStatus.DUPLICATE_IN_FILE, selected = false),
                        ),
                )
            val vm = viewModel(id)
            advanceUntilIdle()

            vm.selectAll()
            advanceUntilIdle()

            val rows = imports.items.value.getValue(id)
            assertTrue(rows.single { it.ordinal == 0 }.selected)
            assertFalse(rows.single { it.ordinal == 1 }.selected)
            assertFalse(rows.single { it.ordinal == 2 }.selected)
        }

    @Test fun optionEditsPreserveParserFields() =
        runTest {
            val sourceGroups =
                listOf(
                    ch.lkmc.neutrodyne.core.model
                        .SourceGroup("tech", "tech", 0xFF0000, null, null),
                )
            val id =
                seed(
                    options =
                        ImportOptions(
                            importGroups = true,
                            sourceGroups = sourceGroups,
                            itemCustomTitles = mapOf(0 to "Custom"),
                        ),
                )
            val vm = viewModel(id)
            advanceUntilIdle()

            vm.setImportGroups(false)
            vm.setGroupIncluded("tech", false)
            vm.setTreatExistingAsPlayed(true)
            advanceUntilIdle()

            val options = session(id)?.options
            assertEquals(false, options?.importGroups)
            assertEquals(setOf("tech"), options?.excludedGroupKeys)
            assertEquals(true, options?.treatExistingAsPlayed)
            assertEquals(sourceGroups, options?.sourceGroups)
            assertEquals(mapOf(0 to "Custom"), options?.itemCustomTitles)
        }

    @Test fun aRenameStoresTheValidatedName() =
        runTest {
            val id = seed(sessionProposals = listOf(proposal("tech")))
            val vm = viewModel(id)
            advanceUntilIdle()

            assertNull(vm.renameGroup("tech", "  Tech News  "))
            advanceUntilIdle()

            assertEquals(mapOf("tech" to "Tech News"), session(id)?.options?.renamedGroups)
        }

    @Test fun aRenameMatchingAnExistingGroupIsReuseNotAnError() =
        runTest {
            val groupId = (groups.create(GroupDraft("Tech News")) as Outcome.Success).value
            val id = seed(sessionProposals = listOf(proposal("tech")))
            val vm = viewModel(id)
            advanceUntilIdle()

            assertNull(vm.renameGroup("tech", "Tech News"))
            advanceUntilIdle()

            assertEquals(mapOf("tech" to "Tech News"), session(id)?.options?.renamedGroups)
        }

    @Test fun invalidRenameNamesReportTheirError() =
        runTest {
            val id = seed(sessionProposals = listOf(proposal("tech")))
            val vm = viewModel(id)
            advanceUntilIdle()

            assertEquals(GroupError.NameEmpty, vm.renameGroup("tech", "   "))
            assertTrue(vm.renameGroup("tech", "x".repeat(50)) is GroupError.NameTooLong)
            assertEquals(emptyMap(), session(id)?.options?.renamedGroups)
        }

    @Test fun theTargetGroupResolvesIdsAndNewNames() =
        runTest {
            val groupId = (groups.create(GroupDraft("Existing")) as Outcome.Success).value
            val id = seed()
            val vm = viewModel(id)
            advanceUntilIdle()

            assertNull(vm.setTargetGroupName("Imported"))
            vm.setTargetGroup(groupId)
            advanceUntilIdle()

            val options = session(id)?.options
            assertEquals(groupId, options?.targetGroupId)
            assertNull(options?.targetGroupName)
        }

    @Test fun aNewTargetNameKeepsTheName() =
        runTest {
            val id = seed()
            val vm = viewModel(id)
            advanceUntilIdle()

            assertNull(vm.setTargetGroupName("Imported"))
            advanceUntilIdle()

            val options = session(id)?.options
            assertNull(options?.targetGroupId)
            assertEquals("Imported", options?.targetGroupName)
        }

    @Test fun anExistingTargetNameIsReuseNotAnError() =
        runTest {
            val groupId = (groups.create(GroupDraft("Existing")) as Outcome.Success).value
            val id = seed()
            val vm = viewModel(id)
            advanceUntilIdle()

            assertNull(vm.setTargetGroupName("Existing"))
            advanceUntilIdle()

            // The typed name resolves to the same group at commit — a stored name, no error.
            assertEquals("Existing", session(id)?.options?.targetGroupName)
        }

    @Test fun notifyRequestsPermissionOnlyWhenEnabled() =
        runTest {
            var requested = 0
            val requester =
                object : NotificationPermissionRequester {
                    override var granted = false

                    override fun request(onResult: (Boolean) -> Unit) {
                        requested++
                        granted = true
                        onResult(true)
                    }

                    override fun openSettings() {}
                }
            val id = seed()
            val vm = viewModel(id)
            advanceUntilIdle()

            vm.setNotifyNewEpisodes(true, requester)
            advanceUntilIdle()

            assertEquals(true, session(id)?.options?.notifyNewEpisodes)
            assertEquals(1, requested)
            assertFalse(vm.uiState.value.notificationsBlocked)
        }

    @Test fun confirmRunsOnTheApplicationScopeAndSurvivesTheCaller() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val gated =
                object : ImportRepository by imports {
                    override suspend fun confirm(sessionId: Long): Outcome<Unit, ImportError> {
                        gate.await()
                        return imports.confirm(sessionId)
                    }
                }
            val id = seed()
            val vm =
                ImportViewModel(
                    id,
                    gated,
                    groups,
                    visibility,
                    appScope(),
                    podcasts,
                    ImportSessionOperations(appScope()),
                )
            advanceUntilIdle()
            val owner = Job(coroutineContext.job)
            val routeScope = CoroutineScope(coroutineContext + owner)

            routeScope.launch { vm.confirm() }
            runCurrent()
            assertTrue(vm.uiState.value.busy)

            owner.cancel()
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("confirm($id)"), imports.calls.filter { it.startsWith("confirm") })
            assertEquals(ImportState.COMMITTED, session(id)?.state)
            assertFalse(vm.uiState.value.busy)
        }

    @Test fun aSecondConfirmWhileBusyIsIgnored() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val gated =
                object : ImportRepository by imports {
                    override suspend fun confirm(sessionId: Long): Outcome<Unit, ImportError> {
                        gate.await()
                        return imports.confirm(sessionId)
                    }
                }
            val id = seed()
            val vm =
                ImportViewModel(
                    id,
                    gated,
                    groups,
                    visibility,
                    appScope(),
                    podcasts,
                    ImportSessionOperations(appScope()),
                )
            advanceUntilIdle()

            launch { vm.confirm() }
            runCurrent()
            assertTrue(vm.uiState.value.busy)

            launch { vm.confirm() }
            runCurrent()
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, imports.calls.count { it.startsWith("confirm") })
        }

    @Test fun aConfirmFailurePostsTheErrorAndClearsBusy() =
        runTest {
            imports.confirmOutcome = Outcome.Failure(ImportError.SessionGone)
            val id = seed()
            val vm = viewModel(id)
            advanceUntilIdle()

            vm.confirm()
            advanceUntilIdle()

            assertFalse(vm.uiState.value.busy)
            assertEquals(
                UiText.Res(Res.string.import_err_gone),
                vm.messages.value
                    .single()
                    .text,
            )
        }

    @Test fun confirmOnADeadApplicationScopeDoesNothing() =
        runTest {
            val appJob = SupervisorJob()
            val id = seed()
            val vm =
                ImportViewModel(
                    id,
                    imports,
                    groups,
                    visibility,
                    CoroutineScope(coroutineContext + appJob),
                    podcasts,
                    ImportSessionOperations(CoroutineScope(coroutineContext + appJob)),
                )
            advanceUntilIdle()

            appJob.cancel()
            vm.confirm()
            advanceUntilIdle()

            assertFalse(vm.uiState.value.busy)
            assertTrue(imports.calls.none { it.startsWith("confirm") })
            assertEquals(
                UiText.Res(Res.string.import_err_confirm),
                vm.messages.value
                    .single()
                    .text,
            )
        }

    @Test fun aBackupPreviewSessionNeverConfirms() =
        runTest {
            val id = seed(format = ImportFormat.NEUTRODYNE_BACKUP)
            val vm = viewModel(id)
            advanceUntilIdle()

            vm.confirm()
            advanceUntilIdle()

            assertTrue(imports.calls.none { it.startsWith("confirm") })
            assertEquals(ImportState.PREVIEW, session(id)?.state)
        }

    @Test fun aPreviewCancelDeletesTheSession() =
        runTest {
            val id = seed()
            val vm = viewModel(id)
            advanceUntilIdle()

            vm.cancelSession()
            advanceUntilIdle()

            assertNull(session(id))
            assertEquals(listOf("cancel($id)"), imports.calls.filter { it.startsWith("cancel") })
        }

    @Test fun aFetchingCancelMarksTheSessionCancelled() =
        runTest {
            val id = seed(state = ImportState.FETCHING)
            val vm = viewModel(id)
            advanceUntilIdle()

            vm.cancelSession()
            advanceUntilIdle()

            assertEquals(ImportState.CANCELLED, session(id)?.state)
        }

    @Test fun editsStopOnceTheSessionLeftPreview() =
        runTest {
            val id = seed(state = ImportState.COMMITTED)
            val vm = viewModel(id)
            advanceUntilIdle()

            vm.selectAll()
            vm.setImportGroups(false)
            advanceUntilIdle()

            assertTrue(imports.calls.none { it.startsWith("setSelected") || it.startsWith("updateOptions") })
        }

    @Test fun theSearchNarrowsThePagedRows() =
        runTest {
            val id =
                seed(
                    sessionItems =
                        listOf(
                            item(0, title = "BBC Tech", host = "feeds.bbc.co.uk"),
                            item(1, title = "Other Show", host = "other.example.com"),
                        ),
                )
            val vm = viewModel(id)
            advanceUntilIdle()

            vm.setQuery("bbc")
            val rows = vm.items.take(1).asSnapshot()

            assertEquals(listOf(0), rows.map { it.ordinal })
        }

    @Test fun theReportPagesAllRowsAfterAnOnlyNewPreview() =
        runTest {
            val id =
                seed(
                    sessionItems =
                        listOf(
                            item(0),
                            item(1, status = ImportItemStatus.ALREADY_SUBSCRIBED, selected = false),
                        ),
                )
            val vm = viewModel(id)
            advanceUntilIdle()

            vm.setFilter(ImportItemFilter.NEW_ONLY)
            vm.confirm()
            advanceUntilIdle()

            // Past PREVIEW the stale preview-only chip cannot hide the committed rows.
            val rows = vm.items.take(1).asSnapshot()
            assertEquals(listOf(0, 1), rows.map { it.ordinal })
        }

    @Test fun screenVisibilityFollowsTheViewModel() =
        runTest {
            val id = seed()
            val store = ViewModelStore()
            val vm = viewModel(id)
            store.put("import", vm)
            advanceUntilIdle()

            assertEquals(id, visibility.visibleSessionId.value)

            store.clear()
            assertNull(visibility.visibleSessionId.value)
        }

    @Test fun anOptionWriteFailurePostsWriteFailed() =
        runTest {
            val broken =
                object : ImportRepository by imports {
                    override suspend fun updateOptions(
                        sessionId: Long,
                        change: (ImportOptions) -> ImportOptions,
                    ): Unit = throw IllegalStateException("boom")
                }
            val id = seed()
            val vm =
                ImportViewModel(
                    id,
                    broken,
                    groups,
                    visibility,
                    appScope(),
                    podcasts,
                    ImportSessionOperations(appScope()),
                )
            advanceUntilIdle()

            vm.setImportGroups(false)
            advanceUntilIdle()

            assertEquals(
                UiText.Res(Res.string.write_failed),
                vm.messages.value
                    .single()
                    .text,
            )
        }
}
