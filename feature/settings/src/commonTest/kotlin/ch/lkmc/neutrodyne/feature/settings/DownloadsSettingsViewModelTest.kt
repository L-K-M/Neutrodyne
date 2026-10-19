// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.model.DeleteAfter
import ch.lkmc.neutrodyne.core.model.NetworkPolicy
import ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeDownloadController
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import ch.lkmc.neutrodyne.core.ui.DownloadSettingEdit
import ch.lkmc.neutrodyne.download.api.FolderRootResult
import ch.lkmc.neutrodyne.download.api.OrphanDeleteResult
import ch.lkmc.neutrodyne.download.api.RootChangeMode
import ch.lkmc.neutrodyne.download.api.RootChangeResult
import ch.lkmc.neutrodyne.download.api.RootKind
import ch.lkmc.neutrodyne.download.api.StorageRootInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsSettingsViewModelTest : MainDispatcherTest() {
    private val settings = FakeSettingsRepository()
    private val downloads = FakeDownloadController()
    private val root =
        StorageRootInfo(
            "dir:test",
            RootKind.DESKTOP_FOLDER,
            "Test",
            999,
            true,
            "/podcasts",
        )

    @Test fun controlsWriteTypedPoliciesAndReflectDefaults() =
        runTest {
            val vm =
                DownloadsSettingsViewModel(
                    settings,
                    downloads,
                )
            backgroundScope.launch { vm.state.collect {} }
            advanceUntilIdle()
            assertEquals(
                3,
                vm.state.value.values.autoDownloadKeepLatest,
            )
            assertEquals(
                false,
                vm.state.value.values.autoDownload,
            )
            vm.edit(DownloadSettingEdit.Enabled(true))
            vm.edit(DownloadSettingEdit.Keep(7))
            vm.edit(DownloadSettingEdit.Network(NetworkPolicy.ANY))
            vm.edit(DownloadSettingEdit.Charging(true))
            vm.edit(DownloadSettingEdit.Video(true))
            vm.edit(DownloadSettingEdit.Delete(DeleteAfter.NEVER))
            vm.setCap(5120)
            advanceUntilIdle()
            val state = vm.state.value
            assertEquals(
                true,
                state.values.autoDownload,
            )
            assertEquals(
                7,
                state.values.autoDownloadKeepLatest,
            )
            assertEquals(
                NetworkPolicy.ANY,
                state.values.autoDownloadNetwork,
            )
            assertEquals(
                true,
                state.values.autoDownloadRequireCharging,
            )
            assertEquals(
                true,
                state.values.includeVideoInAutoDownload,
            )
            assertEquals(
                DeleteAfter.NEVER,
                state.values.deleteAfterPlayed,
            )
            assertEquals(
                5120,
                state.capMb,
            )
            settings.failNextSet = SettingsError.WriteFailed
            vm.setCap(1024)
            advanceUntilIdle()
            assertNotNull(vm.error.value)
            assertEquals(
                5120,
                settings.get(DownloadSettingKeys.STORAGE_CAP_MB),
            )
        }

    @Test fun rejectedFolderKeepsOldRootAndDismissalNeverChangesIt() =
        runTest {
            val vm =
                DownloadsSettingsViewModel(
                    settings,
                    downloads,
                )
            backgroundScope.launch { vm.storage.collect {} }
            advanceUntilIdle()
            vm.chooseFolder("/bad")
            advanceUntilIdle()
            assertNotNull(vm.error.value)
            assertNull(vm.folderDecision.value)
            assertTrue(downloads.calls.none { it.action == FakeDownloadController.Action.ROOT })
            downloads.folderResult = FolderRootResult.Ok(root)
            vm.chooseFolder("/podcasts")
            advanceUntilIdle()
            assertEquals(
                root,
                vm.folderDecision.value?.root,
            )
            vm.dismissFolder()
            vm.changeFolder(RootChangeMode.MOVE_EXISTING)
            advanceUntilIdle()
            assertTrue(downloads.calls.none { it.action == FakeDownloadController.Action.ROOT })
        }

    @Test fun leaveAndDefaultFolderRequireAConfirmedDecision() =
        runTest {
            downloads.roots =
                listOf(
                    root,
                    StorageRootInfo(
                        "dir:default",
                        RootKind.DESKTOP_DEFAULT,
                        null,
                        999,
                        true,
                    ),
                )
            downloads.folderResult = FolderRootResult.Ok(root)
            downloads.rootResult = RootChangeResult.Applied
            val vm =
                DownloadsSettingsViewModel(
                    settings,
                    downloads,
                )
            backgroundScope.launch { vm.storage.collect {} }
            advanceUntilIdle()
            vm.chooseFolder("/podcasts")
            advanceUntilIdle()
            vm.changeFolder(RootChangeMode.NEW_DOWNLOADS_ONLY)
            advanceUntilIdle()
            assertNull(vm.folderDecision.value)
            assertEquals(
                RootChangeMode.NEW_DOWNLOADS_ONLY,
                downloads.calls.last { it.action == FakeDownloadController.Action.ROOT }.rootMode,
            )
            downloads.storage.value = downloads.storage.value.copy(rootId = root.rootId)
            advanceUntilIdle()
            vm.useDefaultFolder()
            assertEquals(
                "dir:default",
                vm.folderDecision.value
                    ?.root
                    ?.rootId,
            )
            downloads.moveResult = RootChangeResult.Applied
            vm.changeFolder(RootChangeMode.MOVE_EXISTING)
            advanceUntilIdle()
            assertEquals(
                "dir:default",
                downloads.calls.last { it.action == FakeDownloadController.Action.ROOT }.rootId,
            )
            assertEquals(
                RootChangeMode.MOVE_EXISTING,
                downloads.calls.last { it.action == FakeDownloadController.Action.ROOT }.rootMode,
            )
        }

    @Test fun orphanCleanupNeedsConfirmationAndDoesNotHideFailure() =
        runTest {
            downloads.storage.value = downloads.storage.value.copy(orphanBytes = 100)
            val vm =
                DownloadsSettingsViewModel(
                    settings,
                    downloads,
                )
            backgroundScope.launch { vm.storage.collect {} }
            advanceUntilIdle()
            vm.deleteOrphans()
            advanceUntilIdle()
            assertTrue(downloads.calls.none { it.action == FakeDownloadController.Action.ORPHANS })
            vm.confirmOrphans()
            vm.dismissOrphans()
            vm.deleteOrphans()
            advanceUntilIdle()
            assertTrue(downloads.calls.none { it.action == FakeDownloadController.Action.ORPHANS })
            vm.confirmOrphans()
            vm.deleteOrphans()
            advanceUntilIdle()
            assertNotNull(vm.error.value)
            assertEquals(
                100,
                vm.orphanConfirmation.value,
            )
            downloads.orphanResult = OrphanDeleteResult.Deleted(100)
            vm.deleteOrphans()
            advanceUntilIdle()
            assertNull(vm.orphanConfirmation.value)
        }
}
