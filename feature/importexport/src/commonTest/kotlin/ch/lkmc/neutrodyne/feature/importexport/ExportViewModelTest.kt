// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.importexport

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.ExportError
import ch.lkmc.neutrodyne.core.domain.ExportRepository
import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.ExportLayout
import ch.lkmc.neutrodyne.core.model.ExportRequest
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.model.PreparedExport
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.testing.FakeExportRepository
import ch.lkmc.neutrodyne.core.testing.FakeGroupRepository
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.export_err_destination
import ch.lkmc.neutrodyne.core.ui.resources.export_err_io
import ch.lkmc.neutrodyne.core.ui.resources.export_err_unavailable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ExportViewModelTest : MainDispatcherTest() {
    private val exports = FakeExportRepository()
    private val settings = FakeSettingsRepository()
    private val groups = FakeGroupRepository()

    /** The route's `LocalPlatformActions` seams, recorded per call. */
    private class Destinations(
        canShare: Boolean = true,
        var saveResult: String? = PICKED_URI,
        /** Overrides the save lambda's result (hang or throw); the call itself is still recorded. */
        private val saveBody: (suspend (PreparedExport) -> String?)? = null,
        private val shareBody: ((PreparedExport) -> Unit)? = null,
    ) {
        val api =
            ExportDestinations(
                canShare = canShare,
                save = { export ->
                    saved += export
                    if (saveBody != null) saveBody.invoke(export) else saveResult
                },
                share = { export ->
                    shared += export
                    shareBody?.invoke(export)
                },
            )
        val saved = mutableListOf<PreparedExport>()
        val shared = mutableListOf<PreparedExport>()
    }

    private fun viewModel(groupId: Long? = null) = ExportViewModel(groupId, exports, settings, groups)

    private fun prepared(
        privateLinks: Int = 0,
        passwords: Int = 0,
        shareUri: String? = "content://provider/export/file.opml",
    ) = PreparedExport("neutrodyne-subscriptions.opml", "text/x-opml", shareUri, 3, privateLinks, passwords)

    @Test fun exportPreparesOnceWithTheChosenOptionsAndWritesThemBack() =
        runTest {
            val vm = viewModel()
            val destinations = Destinations()
            advanceUntilIdle()
            vm.setLayout(ExportLayout.FLAT)
            vm.setYouTube(false)
            vm.setPasswords(true)

            vm.export(ExportAction.SAVE, destinations.api)
            advanceUntilIdle()

            assertEquals(
                listOf(
                    ExportRequest(
                        groupId = null,
                        layout = ExportLayout.FLAT,
                        includeYouTube = false,
                        includePasswords = true,
                    ),
                ),
                exports.prepareRequests,
            )
            assertEquals(
                listOf("set(backup.opml_layout)", "set(backup.opml_include_youtube)"),
                settings.calls.filter { it.startsWith("set(") },
            )
        }

    @Test fun zeroWarningCountsDeliverWithoutAWarning() =
        runTest {
            exports.prepareOutcome = Outcome.Success(prepared())
            val vm = viewModel()
            val destinations = Destinations()
            advanceUntilIdle()

            vm.export(ExportAction.SAVE, destinations.api)
            advanceUntilIdle()

            assertNull(vm.uiState.value.warning)
            assertEquals(listOf("neutrodyne-subscriptions.opml" to PICKED_URI), exports.savedTo)
            assertEquals(FinishedExport(prepared(), PICKED_URI, shared = false), vm.uiState.value.finished)
        }

    @Test fun privateLinkCountsRaiseTheWarningFromThePreparedArtifact() =
        runTest {
            val export = prepared(privateLinks = 2)
            exports.prepareOutcome = Outcome.Success(export)
            val vm = viewModel()
            val destinations = Destinations()
            advanceUntilIdle()

            vm.export(ExportAction.SAVE, destinations.api)
            advanceUntilIdle()

            assertSame(export, vm.uiState.value.warning)
            assertTrue(exports.savedTo.isEmpty())
            assertTrue(destinations.saved.isEmpty())
            assertTrue(destinations.shared.isEmpty())
        }

    @Test fun passwordsAloneRaiseTheWarning() =
        runTest {
            exports.prepareOutcome = Outcome.Success(prepared(passwords = 1))
            val vm = viewModel()
            val destinations = Destinations()
            advanceUntilIdle()

            vm.export(ExportAction.SHARE, destinations.api)
            advanceUntilIdle()

            assertEquals(
                1,
                vm.uiState.value.warning
                    ?.passwords,
            )
            assertTrue(destinations.shared.isEmpty())
        }

    @Test fun cancellingTheWarningWritesAndSharesNothing() =
        runTest {
            exports.prepareOutcome = Outcome.Success(prepared(privateLinks = 2, passwords = 1))
            val vm = viewModel()
            val destinations = Destinations()
            advanceUntilIdle()

            vm.export(ExportAction.SAVE, destinations.api)
            advanceUntilIdle()
            vm.cancelWarning()
            advanceUntilIdle()

            assertNull(vm.uiState.value.warning)
            assertNull(vm.uiState.value.finished)
            assertTrue(exports.savedTo.isEmpty())
            assertTrue(destinations.saved.isEmpty())
            assertTrue(destinations.shared.isEmpty())
        }

    @Test fun continuingTheWarningDeliversTheSamePreparedArtifact() =
        runTest {
            val export = prepared(privateLinks = 2)
            exports.prepareOutcome = Outcome.Success(export)
            val vm = viewModel()
            val destinations = Destinations()
            advanceUntilIdle()

            vm.export(ExportAction.SAVE, destinations.api)
            advanceUntilIdle()
            vm.confirmWarning(destinations.api)
            advanceUntilIdle()

            assertEquals(1, exports.prepareRequests.size)
            assertSame(export, destinations.saved.single())
            assertEquals(listOf("neutrodyne-subscriptions.opml" to PICKED_URI), exports.savedTo)
            assertEquals(
                export,
                vm.uiState.value.finished
                    ?.export,
            )
        }

    @Test fun aDismissedDestinationPickerSavesAndReportsNothing() =
        runTest {
            exports.prepareOutcome = Outcome.Success(prepared())
            val vm = viewModel()
            val destinations = Destinations(saveResult = null)
            advanceUntilIdle()

            vm.export(ExportAction.SAVE, destinations.api)
            advanceUntilIdle()

            assertEquals(1, destinations.saved.size)
            assertTrue(exports.savedTo.isEmpty())
            assertNull(vm.uiState.value.finished)
            assertTrue(vm.messages.value.isEmpty())
        }

    @Test fun aPrepareFailurePostsTheErrorAndFinishesNothing() =
        runTest {
            exports.prepareOutcome = Outcome.Failure(ExportError.Io)
            val vm = viewModel()
            val destinations = Destinations()
            advanceUntilIdle()

            vm.export(ExportAction.SAVE, destinations.api)
            advanceUntilIdle()

            assertTrue(exports.savedTo.isEmpty())
            assertTrue(destinations.saved.isEmpty())
            assertNull(vm.uiState.value.finished)
            assertEquals(
                UiText.Res(Res.string.export_err_io),
                vm.messages.value
                    .single()
                    .text,
            )
        }

    @Test fun aSaveFailurePostsTheErrorAndReportsNoSuccess() =
        runTest {
            exports.prepareOutcome = Outcome.Success(prepared())
            exports.saveOutcome = Outcome.Failure(ExportError.DestinationUnavailable)
            val vm = viewModel()
            val destinations = Destinations()
            advanceUntilIdle()

            vm.export(ExportAction.SAVE, destinations.api)
            advanceUntilIdle()

            assertNull(vm.uiState.value.finished)
            assertEquals(
                UiText.Res(Res.string.export_err_destination),
                vm.messages.value
                    .single()
                    .text,
            )
        }

    @Test fun sharingHandsTheArtifactNameMimeAndUriToTheSheet() =
        runTest {
            val export = prepared()
            exports.prepareOutcome = Outcome.Success(export)
            val vm = viewModel()
            val destinations = Destinations()
            advanceUntilIdle()

            vm.export(ExportAction.SHARE, destinations.api)
            advanceUntilIdle()

            assertSame(export, destinations.shared.single())
            assertEquals("content://provider/export/file.opml", export.shareUri)
            assertEquals("text/x-opml", export.mimeType)
            assertEquals("neutrodyne-subscriptions.opml", export.fileName)
            assertEquals(FinishedExport(export, null, shared = true), vm.uiState.value.finished)
            assertTrue(exports.savedTo.isEmpty())
        }

    @Test fun sharingWithoutASheetOrUriPostsUnavailableInsteadOfSucceeding() =
        runTest {
            exports.prepareOutcome = Outcome.Success(prepared(shareUri = null))
            val vm = viewModel()
            val destinations = Destinations()
            advanceUntilIdle()

            vm.export(ExportAction.SHARE, destinations.api)
            advanceUntilIdle()

            assertTrue(destinations.shared.isEmpty())
            assertNull(vm.uiState.value.finished)
            assertEquals(
                UiText.Res(Res.string.export_err_unavailable),
                vm.messages.value
                    .single()
                    .text,
            )
        }

    @Test fun aSecondActionWhileBusyIsIgnored() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val gated =
                object : ExportRepository by exports {
                    override suspend fun prepare(request: ExportRequest): Outcome<PreparedExport, ExportError> {
                        gate.await()
                        return exports.prepare(request)
                    }
                }
            exports.prepareOutcome = Outcome.Success(prepared())
            val vm = ExportViewModel(null, gated, settings, groups)
            val destinations = Destinations()
            advanceUntilIdle()

            launch { vm.export(ExportAction.SAVE, destinations.api) }
            runCurrent()
            assertTrue(vm.uiState.value.busy)

            launch { vm.export(ExportAction.SAVE, destinations.api) }
            runCurrent()
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, exports.prepareRequests.size)
        }

    @Test fun consumeFinishedReportsOnce() =
        runTest {
            exports.prepareOutcome = Outcome.Success(prepared())
            val vm = viewModel()
            val destinations = Destinations()
            advanceUntilIdle()

            vm.export(ExportAction.SAVE, destinations.api)
            advanceUntilIdle()

            assertTrue(vm.consumeFinished() != null)
            assertNull(vm.consumeFinished())
            assertNull(vm.uiState.value.finished)
        }

    @Test fun aGroupExportCarriesTheGroupIdAndName() =
        runTest {
            val groupId = (groups.create(GroupDraft("tech")) as Outcome.Success).value
            exports.prepareOutcome = Outcome.Success(prepared())
            val vm = viewModel(groupId)
            val destinations = Destinations()
            advanceUntilIdle()

            assertEquals("tech", vm.uiState.value.groupName)

            vm.export(ExportAction.SAVE, destinations.api)
            advanceUntilIdle()

            assertEquals(groupId, exports.prepareRequests.single().groupId)
        }

    @Test fun aDeletedGroupKeepsTheFlowOnPrepare() =
        runTest {
            exports.prepareOutcome = Outcome.Failure(ExportError.Io)
            val vm = viewModel(groupId = 42L)
            val destinations = Destinations()
            advanceUntilIdle()

            vm.export(ExportAction.SAVE, destinations.api)
            advanceUntilIdle()

            assertEquals(42L, exports.prepareRequests.single().groupId)
            assertEquals(
                UiText.Res(Res.string.export_err_io),
                vm.messages.value
                    .single()
                    .text,
            )
        }

    @Test fun finishedIsNullForCancelledAndSkippedDeliveriesOnly() =
        runTest {
            exports.prepareOutcome = Outcome.Success(prepared())
            val vm = viewModel()
            val cancelled = Destinations(saveResult = null)
            advanceUntilIdle()

            vm.export(ExportAction.SAVE, cancelled.api)
            advanceUntilIdle()
            assertNull(vm.consumeFinished())

            val saved = Destinations()
            vm.export(ExportAction.SAVE, saved.api)
            advanceUntilIdle()
            assertEquals(PICKED_URI, vm.consumeFinished()?.destinationUri)
        }

    @Test fun confirmingTheWarningDeliversThroughTheLiveDestinations() =
        runTest {
            // The route was recreated while the warning sat pending: Continue must deliver the
            // retained artifact through the LIVE seams, never the disposed route's.
            val export = prepared(privateLinks = 1)
            exports.prepareOutcome = Outcome.Success(export)
            val vm = viewModel()
            val dead = Destinations()
            val live = Destinations()
            advanceUntilIdle()

            vm.export(ExportAction.SAVE, dead.api)
            advanceUntilIdle()
            assertSame(export, vm.uiState.value.warning)

            vm.confirmWarning(live.api)
            advanceUntilIdle()

            assertTrue(dead.saved.isEmpty(), "the stale route's destinations were invoked")
            assertSame(export, live.saved.single())
            assertEquals(listOf("neutrodyne-subscriptions.opml" to PICKED_URI), exports.savedTo)
        }

    @Test fun aRouteLeavingMidPrepareCancelsTheFlowAndStaysRetryable() =
        runTest {
            // The caller's scope is the route's: disposing it mid-prepare must cancel cleanly —
            // no late delivery, busy cleared, a fresh tap allowed.
            val gate = CompletableDeferred<Unit>()
            val gated =
                object : ExportRepository by exports {
                    override suspend fun prepare(request: ExportRequest): Outcome<PreparedExport, ExportError> {
                        gate.await()
                        return exports.prepare(request)
                    }
                }
            exports.prepareOutcome = Outcome.Success(prepared())
            val vm = ExportViewModel(null, gated, settings, groups)
            val destinations = Destinations()
            val owner = Job(coroutineContext.job)
            val routeScope = CoroutineScope(coroutineContext + owner)
            advanceUntilIdle()

            routeScope.launch { vm.export(ExportAction.SAVE, destinations.api) }
            runCurrent()
            assertTrue(vm.uiState.value.busy)

            owner.cancel()
            gate.complete(Unit)
            advanceUntilIdle()

            assertFalse(vm.uiState.value.busy)
            assertTrue(destinations.saved.isEmpty())
            assertTrue(exports.savedTo.isEmpty())
            assertNull(vm.uiState.value.finished)

            vm.export(ExportAction.SAVE, destinations.api)
            advanceUntilIdle()
            assertEquals(listOf("neutrodyne-subscriptions.opml" to PICKED_URI), exports.savedTo)
        }

    @Test fun aRouteLeavingWhileThePickerIsOpenDropsTheLateResult() =
        runTest {
            val picker = CompletableDeferred<String?>()
            exports.prepareOutcome = Outcome.Success(prepared())
            val vm = viewModel()
            val destinations = Destinations(saveBody = { picker.await() })
            val owner = Job(coroutineContext.job)
            val routeScope = CoroutineScope(coroutineContext + owner)
            advanceUntilIdle()

            routeScope.launch { vm.export(ExportAction.SAVE, destinations.api) }
            runCurrent()
            assertEquals(1, destinations.saved.size)
            assertTrue(vm.uiState.value.busy)

            owner.cancel()
            picker.complete(PICKED_URI)
            advanceUntilIdle()

            assertTrue(exports.savedTo.isEmpty(), "a late picker result was written after the owner died")
            assertNull(vm.uiState.value.finished)
            assertFalse(vm.uiState.value.busy)
        }

    @Test fun aRouteLeavingMidConfirmRestoresThePendingWarning() =
        runTest {
            // The dialog died while Continue's picker was open: the prepared artifact stays
            // pending so a recreated route can finish the exact same export.
            val export = prepared(privateLinks = 1)
            exports.prepareOutcome = Outcome.Success(export)
            val vm = viewModel()
            val picker = CompletableDeferred<String?>()
            val first = Destinations(saveBody = { picker.await() })
            val second = Destinations()
            val owner = Job(coroutineContext.job)
            val routeScope = CoroutineScope(coroutineContext + owner)
            advanceUntilIdle()

            vm.export(ExportAction.SAVE, first.api)
            advanceUntilIdle()
            assertSame(export, vm.uiState.value.warning)

            routeScope.launch { vm.confirmWarning(first.api) }
            runCurrent()
            assertTrue(vm.uiState.value.busy)

            owner.cancel()
            picker.complete(null)
            advanceUntilIdle()

            assertSame(export, vm.uiState.value.warning, "a cancelled confirm lost the pending export")
            assertFalse(vm.uiState.value.busy)

            vm.confirmWarning(second.api)
            advanceUntilIdle()
            assertSame(export, second.saved.single())
            assertEquals(listOf("neutrodyne-subscriptions.opml" to PICKED_URI), exports.savedTo)
        }

    @Test fun aPrepareThrowPostsAnErrorAndClearsBusy() =
        runTest {
            var failPrepare = true
            val throwing =
                object : ExportRepository by exports {
                    override suspend fun prepare(request: ExportRequest): Outcome<PreparedExport, ExportError> {
                        if (failPrepare) throw IOException("database gone")
                        return exports.prepare(request)
                    }
                }
            exports.prepareOutcome = Outcome.Success(prepared())
            val vm = ExportViewModel(null, throwing, settings, groups)
            val destinations = Destinations()
            advanceUntilIdle()

            vm.export(ExportAction.SAVE, destinations.api)
            advanceUntilIdle()

            assertFalse(vm.uiState.value.busy)
            assertNull(vm.uiState.value.finished)
            assertEquals(
                UiText.Res(Res.string.export_err_io),
                vm.messages.value
                    .single()
                    .text,
            )

            failPrepare = false
            vm.export(ExportAction.SAVE, destinations.api)
            advanceUntilIdle()
            assertEquals(listOf("neutrodyne-subscriptions.opml" to PICKED_URI), exports.savedTo)
        }

    @Test fun aPickerThrowPostsAnErrorInsteadOfSilentCancel() =
        runTest {
            // A platform seam that throws is a real failure, not a picker dismissal: it must
            // surface an error and release busy.
            exports.prepareOutcome = Outcome.Success(prepared())
            val vm = viewModel()
            val destinations = Destinations(saveBody = { throw IOException("picker exploded") })
            advanceUntilIdle()

            vm.export(ExportAction.SAVE, destinations.api)
            advanceUntilIdle()

            assertFalse(vm.uiState.value.busy)
            assertNull(vm.uiState.value.finished)
            assertTrue(exports.savedTo.isEmpty())
            assertEquals(
                UiText.Res(Res.string.export_err_io),
                vm.messages.value
                    .single()
                    .text,
            )
        }

    @Test fun aShareThrowPostsUnavailableAndClearsBusy() =
        runTest {
            exports.prepareOutcome = Outcome.Success(prepared())
            val vm = viewModel()
            val destinations = Destinations(shareBody = { throw IOException("no activity found") })
            advanceUntilIdle()

            vm.export(ExportAction.SHARE, destinations.api)
            advanceUntilIdle()

            assertEquals(1, destinations.shared.size)
            assertFalse(vm.uiState.value.busy)
            assertNull(vm.uiState.value.finished)
            assertEquals(
                UiText.Res(Res.string.export_err_unavailable),
                vm.messages.value
                    .single()
                    .text,
            )
        }

    @Test fun aSaveToThrowPostsAnErrorAndClearsBusy() =
        runTest {
            var failSave = true
            val throwing =
                object : ExportRepository by exports {
                    override suspend fun saveTo(
                        export: PreparedExport,
                        destinationUri: String,
                    ): Outcome<Unit, ExportError> {
                        if (failSave) throw IOException("stream closed")
                        return exports.saveTo(export, destinationUri)
                    }
                }
            exports.prepareOutcome = Outcome.Success(prepared())
            val vm = ExportViewModel(null, throwing, settings, groups)
            val destinations = Destinations()
            advanceUntilIdle()

            vm.export(ExportAction.SAVE, destinations.api)
            advanceUntilIdle()

            assertFalse(vm.uiState.value.busy)
            assertNull(vm.uiState.value.finished)
            assertEquals(
                UiText.Res(Res.string.export_err_io),
                vm.messages.value
                    .single()
                    .text,
            )
        }

    @Test fun aPreferenceMemoryWriteThrowDoesNotFailTheExport() =
        runTest {
            // Layout/YouTube write-back is best-effort: a thrown settings write must not crash
            // or wedge a real export.
            val flakySettings =
                object : SettingsRepository by settings {
                    override suspend fun <T : Any> set(
                        key: SettingKey<T>,
                        value: T,
                    ): Outcome<Unit, SettingsError> = throw IOException("datastore gone")
                }
            exports.prepareOutcome = Outcome.Success(prepared())
            val vm = ExportViewModel(null, exports, flakySettings, groups)
            val destinations = Destinations()
            advanceUntilIdle()

            vm.export(ExportAction.SAVE, destinations.api)
            advanceUntilIdle()

            assertEquals(listOf("neutrodyne-subscriptions.opml" to PICKED_URI), exports.savedTo)
            assertEquals(FinishedExport(prepared(), PICKED_URI, shared = false), vm.uiState.value.finished)
            assertTrue(vm.messages.value.isEmpty())
        }

    private companion object {
        const val PICKED_URI = "file:///picked/neutrodyne-subscriptions.opml"
    }
}
