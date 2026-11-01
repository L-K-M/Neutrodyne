// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.downloads

import androidx.lifecycle.ViewModelStore
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.testing.FakeDownloadController
import ch.lkmc.neutrodyne.core.testing.FakeDownloadProgressSource
import ch.lkmc.neutrodyne.core.testing.FakePlaybackController
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import ch.lkmc.neutrodyne.core.testing.testDownloadEntry
import ch.lkmc.neutrodyne.core.testing.testPlatformInfo
import ch.lkmc.neutrodyne.download.api.DownloadNotice
import ch.lkmc.neutrodyne.download.api.LiveProgress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModelTest : MainDispatcherTest() {
    private val network =
        ch.lkmc.neutrodyne.core.testing
            .FakeNetworkMonitor()
    private val downloads = FakeDownloadController()
    private val progress = FakeDownloadProgressSource()
    private val player = FakePlaybackController()
    private val settings = FakeSettingsRepository()
    private val store = ViewModelStore()

    private fun model() =
        DownloadsViewModel(downloads, progress, player, player.state, settings, testPlatformInfo, network).also {
            store.put("downloads", it)
        }

    private fun test(block: suspend TestScope.() -> Unit) =
        runTest {
            try {
                block()
            } finally {
                store.clear()
            }
        }

    @Test fun connectionChangesUpdatePresentationWithoutChangingStoredWaits() =
        test {
            downloads.overview.value = downloads.overview.value.copy(inProgress = listOf(testDownloadEntry(1)))
            val vm = model()
            backgroundScope.launch { vm.uiState.collect {} }
            runCurrent()
            assertTrue(vm.uiState.value.offline)
            assertEquals(
                ch.lkmc.neutrodyne.core.model.WaitReason.NONE,
                vm.uiState.value.overview
                    ?.inProgress
                    ?.single()
                    ?.status
                    ?.waitReason,
            )

            network.setStatus(ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor.ONLINE)
            runCurrent()
            assertFalse(vm.uiState.value.offline)
            assertEquals(downloads.overview.value, vm.uiState.value.overview)
            assertTrue(downloads.calls.isEmpty())
        }

    @Test fun loadingSettlesToEmptyAndStorageHasIndependentUnknownState() =
        test {
            val vm = model()
            assertNull(vm.uiState.value.overview)
            assertNull(vm.uiState.value.storage)
            backgroundScope.launch { vm.uiState.collect {} }
            runCurrent()
            assertNotNull(vm.uiState.value.overview)
            assertNull(
                vm.uiState.value.storage
                    ?.freeBytes,
            )
        }

    @Test fun selectionOnlyContainsCompletedAndBulkDeleteWaitsForConfirmation() =
        test {
            downloads.overview.value =
                downloads.overview.value.copy(
                    inProgress = listOf(testDownloadEntry(1)),
                    completed =
                        listOf(
                            testDownloadEntry(2, DownloadState.COMPLETED),
                            testDownloadEntry(3, DownloadState.COMPLETED).copy(played = true),
                        ),
                )
            val vm = model()
            backgroundScope.launch { vm.uiState.collect {} }
            runCurrent()
            vm.toggleSelection(1)
            assertTrue(
                vm.uiState.value.selected
                    .isEmpty(),
            )
            vm.selectPlayed()
            runCurrent()
            assertEquals(setOf(3L), vm.uiState.value.selected)
            vm.selectAllCompleted()
            runCurrent()
            assertEquals(setOf(2L, 3L), vm.uiState.value.selected)
            vm.deleteSelection()
            runCurrent()
            assertTrue(downloads.calls.isEmpty())
            vm.downloads.dismissDelete()
            assertTrue(downloads.calls.isEmpty())
            vm.deleteSelection()
            vm.downloads.deleteConfirmed()
            runCurrent()
            assertEquals(listOf(2L, 3L), downloads.calls.single().ids)
        }

    @Test fun removedFilesLeaveTheSelection() =
        test {
            downloads.overview.value =
                downloads.overview.value.copy(completed = listOf(testDownloadEntry(2, DownloadState.COMPLETED)))
            val vm = model()
            backgroundScope.launch { vm.uiState.collect {} }
            runCurrent()
            vm.selectAllCompleted()
            runCurrent()
            downloads.overview.value = downloads.overview.value.copy(completed = emptyList())
            runCurrent()
            assertTrue(
                vm.uiState.value.selected
                    .isEmpty(),
            )
        }

    @Test fun progressOnlyObservesVisibleActiveIdsAndNeverMutatesOverview() =
        test {
            downloads.overview.value =
                downloads.overview.value.copy(
                    inProgress =
                        listOf(
                            testDownloadEntry(1, DownloadState.DOWNLOADING),
                            testDownloadEntry(2, DownloadState.DOWNLOADING),
                        ),
                    completed = listOf(testDownloadEntry(3, DownloadState.COMPLETED)),
                )
            val vm = model()
            backgroundScope.launch { vm.live.collect {} }
            runCurrent()
            vm.onVisibleIds(setOf(1, 3))
            runCurrent()
            progress.progress.value = (1L..3L).associateWith { LiveProgress(DownloadState.DOWNLOADING, 50, 100, 5) }
            runCurrent()
            assertEquals(setOf(1L), vm.live.value.keys)
            assertEquals(setOf(1L), progress.observedIds.last())
            assertEquals(
                0L,
                downloads.overview.value.inProgress
                    .first()
                    .status.downloadedBytes,
            )
            vm.onVisibleIds(emptySet())
            runCurrent()
            assertTrue(vm.live.value.isEmpty())
            assertTrue(downloads.calls.isEmpty())
        }

    @Test fun allSectionCommandsAndDownloadsPlaybackUseThePublicController() =
        test {
            downloads.overview.value =
                downloads.overview.value.copy(failed = listOf(testDownloadEntry(7, DownloadState.FAILED)))
            val vm = model()
            runCurrent()
            vm.pauseAll()
            vm.resumeAll()
            vm.retryAll()
            vm.play()
            runCurrent()
            vm.play(3)
            runCurrent()
            assertEquals(
                listOf(
                    FakeDownloadController.Action.PAUSE_ALL,
                    FakeDownloadController.Action.RESUME_ALL,
                    FakeDownloadController.Action.RETRY,
                ),
                downloads.calls.map {
                    it.action
                },
            )
            assertEquals(listOf(7L), downloads.calls.last().ids)
            assertEquals(
                listOf<FakePlaybackController.Call>(
                    FakePlaybackController.Downloads(null),
                    FakePlaybackController.Downloads(3),
                ),
                player.calls,
            )
        }

    @Test fun orphanDeleteNeedsExplicitConfirmationAndRejectedOperationReportsFailure() =
        test {
            downloads.storage.value = downloads.storage.value.copy(orphanBytes = 500)
            val vm = model()
            backgroundScope.launch { vm.uiState.collect {} }
            runCurrent()
            vm.deleteOrphans()
            runCurrent()
            assertTrue(downloads.calls.isEmpty())
            vm.askDeleteOrphans()
            vm.dismissOrphans()
            vm.deleteOrphans()
            runCurrent()
            assertTrue(downloads.calls.isEmpty())
            vm.askDeleteOrphans()
            vm.deleteOrphans()
            runCurrent()
            assertEquals(FakeDownloadController.Action.ORPHANS, downloads.calls.single().action)
            assertEquals(1, vm.uiState.value.messages.size)
        }

    @Test fun noticesAreBoundedAndOrderedWithM6bControlsHidden() {
        assertEquals(
            listOf(DownloadNotice.DATA_SAVER, DownloadNotice.BACKGROUND_RESTRICTED),
            manualDownloadNotices(DownloadNotice.entries.reversed().toSet()),
        )
        assertEquals(
            listOf(DownloadNotice.STORAGE_LOW, DownloadNotice.ORPHAN_FILES),
            manualDownloadNotices(
                setOf(
                    DownloadNotice.ORPHAN_FILES,
                    DownloadNotice.MOVE_IN_PROGRESS,
                    DownloadNotice.STORAGE_LOW,
                    DownloadNotice.CAP_REACHED,
                ),
            ),
        )
    }
}
