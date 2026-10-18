// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.importexport

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.ExportError
import ch.lkmc.neutrodyne.core.domain.ExportRepository
import ch.lkmc.neutrodyne.core.model.ExportLayout
import ch.lkmc.neutrodyne.core.model.ExportRequest
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.model.PreparedExport
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
    ) {
        val api =
            ExportDestinations(
                canShare = canShare,
                save = { export ->
                    saved += export
                    saveResult
                },
                share = { export -> shared += export },
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
            vm.confirmWarning()
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

            vm.export(ExportAction.SAVE, destinations.api)
            vm.export(ExportAction.SAVE, destinations.api)
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

    private companion object {
        const val PICKED_URI = "file:///picked/neutrodyne-subscriptions.opml"
    }
}
