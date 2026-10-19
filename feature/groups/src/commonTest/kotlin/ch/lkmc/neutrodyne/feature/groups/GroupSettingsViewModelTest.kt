// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.groups

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.ScopeSettingsRepository
import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.model.DeleteAfter
import ch.lkmc.neutrodyne.core.model.ExternalReason
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.model.NetworkPolicy
import ch.lkmc.neutrodyne.core.model.ScopedSettingsView
import ch.lkmc.neutrodyne.core.model.SettingField
import ch.lkmc.neutrodyne.core.model.SettingOverrides
import ch.lkmc.neutrodyne.core.model.SettingSource
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeGroupRepository
import ch.lkmc.neutrodyne.core.testing.FakePodcastRepository
import ch.lkmc.neutrodyne.core.testing.FakeScopeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import ch.lkmc.neutrodyne.core.testing.testLibraryTile
import ch.lkmc.neutrodyne.core.ui.DownloadSettingEdit
import ch.lkmc.neutrodyne.core.ui.platform.NotificationPermissionRequester
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GroupSettingsViewModelTest : MainDispatcherTest() {
    private val groups = FakeGroupRepository()
    private val scopes = FakeScopeSettingsRepository()
    private val settings = FakeSettingsRepository()
    private val podcasts = FakePodcastRepository()
    private val capabilities = FakeYouTubeCapabilitiesSource()
    private var groupId = 0L
    private lateinit var ownSource: SettingSource.Group

    private suspend fun TestScope.start(repository: ScopeSettingsRepository = scopes): GroupSettingsViewModel {
        groupId = assertIs<Outcome.Success<Long>>(groups.create(GroupDraft("News"))).value
        val group = groups.groups.value.single()
        ownSource = SettingSource.Group(group.uuid, group.name)
        scopes.seedGroup(groupId, group.uuid, group.name)
        val vm =
            GroupSettingsViewModel(
                groupId,
                groups,
                repository,
                settings,
                podcasts,
                capabilities,
            )
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        return vm
    }

    @Test fun ownValuesAndAppDefaultsHaveTheRightAttribution() =
        runTest {
            val vm = start()
            assertTrue(
                vm.uiState.value.scoped!!
                    .sources.values
                    .all { it == SettingSource.AppDefault },
            )
            vm.setRefreshIntervalMinutes(120)
            vm.setNotifyNewEpisodes(true, null)
            advanceUntilIdle()
            val scoped = vm.uiState.value.scoped!!
            assertEquals(SettingOverrides(120, true), scoped.own)
            assertEquals(
                scoped.own.copy(
                    playbackSpeed = 1f,
                    skipSilence = false,
                    autoDownload = false,
                    autoDownloadKeepLatest = 3,
                    autoDownloadNetwork = NetworkPolicy.UNMETERED,
                    autoDownloadRequireCharging = false,
                    includeVideoInAutoDownload = false,
                    deleteAfterPlayed = DeleteAfter.AFTER_24H,
                ),
                scoped.effective,
            )
            assertEquals(ownSource, scoped.sources[SettingField.REFRESH_INTERVAL_MINUTES])
            assertEquals(ownSource, scoped.sources[SettingField.NOTIFY_NEW_EPISODES])
            assertTrue(settings.calls.isEmpty(), "Group overrides must not write global settings")
        }

    @Test fun manualOnlyStaysDistinctFromInherit() =
        runTest {
            val vm = start()
            vm.setRefreshIntervalMinutes(FeedsSettingKeys.MANUAL_ONLY_MINUTES)
            advanceUntilIdle()
            assertEquals(
                0,
                vm.uiState.value.scoped!!
                    .own.refreshIntervalMinutes,
            )
            assertNull(
                vm.uiState.value.scoped!!
                    .effective.refreshIntervalMinutes,
            )
            assertEquals(
                ownSource,
                vm.uiState.value.scoped!!
                    .sources[SettingField.REFRESH_INTERVAL_MINUTES],
            )
        }

    @Test fun inheritClearsOnlyTheChosenField() =
        runTest {
            val vm = start()
            vm.setRefreshIntervalMinutes(0)
            vm.setNotifyNewEpisodes(false, null)
            advanceUntilIdle()
            vm.setRefreshIntervalMinutes(null)
            advanceUntilIdle()
            assertEquals(
                SettingOverrides(null, false),
                vm.uiState.value.scoped!!
                    .own,
            )
            assertEquals(
                FeedsSettingKeys.DEFAULT_REFRESH_INTERVAL_MINUTES,
                vm.uiState.value.scoped!!
                    .effective.refreshIntervalMinutes,
            )
            assertEquals(
                SettingSource.AppDefault,
                vm.uiState.value.scoped!!
                    .sources[SettingField.REFRESH_INTERVAL_MINUTES],
            )
            vm.setNotifyNewEpisodes(null, null)
            advanceUntilIdle()
            assertEquals(
                SettingOverrides(),
                vm.uiState.value.scoped!!
                    .own,
            )
            assertTrue(
                vm.uiState.value.scoped!!
                    .sources.values
                    .all { it == SettingSource.AppDefault },
            )
        }

    @Test fun inheritedManualOnlyKeepsAppDefaultSource() =
        runTest {
            val vm = start()
            scopes.seedGroup(
                groupId,
                ownSource.uuid,
                ownSource.name,
                ScopedSettingsView(
                    SettingOverrides(),
                    SettingOverrides(null, false),
                    SettingField.entries.associateWith { SettingSource.AppDefault },
                ),
            )
            advanceUntilIdle()
            assertNull(
                vm.uiState.value.scoped!!
                    .own.refreshIntervalMinutes,
            )
            assertNull(
                vm.uiState.value.scoped!!
                    .effective.refreshIntervalMinutes,
            )
            assertEquals(
                SettingSource.AppDefault,
                vm.uiState.value.scoped!!
                    .sources[SettingField.REFRESH_INTERVAL_MINUTES],
            )
        }

    @Test fun chooserPreviewObservesAppDefaultsEvenWithAnOwnValue() =
        runTest {
            val vm = start()
            vm.setRefreshIntervalMinutes(120)
            advanceUntilIdle()
            settings.set(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES, 0)
            settings.set(FeedsSettingKeys.NOTIFY_NEW_EPISODES, true)
            advanceUntilIdle()
            assertEquals(SettingOverrides(0, true), vm.uiState.value.inherited)
            assertEquals(
                120,
                vm.uiState.value.scoped!!
                    .effective.refreshIntervalMinutes,
            )
        }

    @Test fun permissionIsRequestedOnlyForExplicitOnWithoutPermission() =
        runTest {
            val vm = start()
            val requester = PermissionRequester()
            assertEquals(0, requester.requests)
            vm.setNotifyNewEpisodes(false, requester)
            vm.setNotifyNewEpisodes(null, requester)
            advanceUntilIdle()
            assertEquals(0, requester.requests)
            vm.setNotifyNewEpisodes(true, requester)
            advanceUntilIdle()
            assertEquals(1, requester.requests)
            assertEquals(
                true,
                vm.uiState.value.scoped!!
                    .own.notifyNewEpisodes,
                "Denial preserves the wish",
            )
            requester.granted = true
            vm.setNotifyNewEpisodes(true, requester)
            advanceUntilIdle()
            assertEquals(1, requester.requests)
        }

    @Test fun inheritedNotificationsDoNotPromptAndDesktopNeedsNoRequester() =
        runTest {
            val vm = start()
            val requester = PermissionRequester()
            scopes.seedGroup(
                groupId,
                ownSource.uuid,
                ownSource.name,
                ScopedSettingsView(
                    SettingOverrides(),
                    SettingOverrides(60, true),
                    SettingField.entries.associateWith { SettingSource.AppDefault },
                ),
            )
            advanceUntilIdle()
            assertEquals(
                true,
                vm.uiState.value.scoped!!
                    .effective.notifyNewEpisodes,
            )
            assertEquals(0, requester.requests)
            vm.setNotifyNewEpisodes(null, requester)
            vm.setNotifyNewEpisodes(true, null)
            advanceUntilIdle()
            assertEquals(0, requester.requests)
            assertEquals(
                true,
                vm.uiState.value.scoped!!
                    .own.notifyNewEpisodes,
            )
        }

    @Test fun failedWriteDoesNotPromptAndReportsAnError() =
        runTest {
            val repository =
                object : ScopeSettingsRepository by scopes {
                    override suspend fun updateGroup(
                        groupId: Long,
                        change: (SettingOverrides) -> SettingOverrides,
                    ): Outcome<Unit, SettingsError> = Outcome.Failure(SettingsError.WriteFailed)
                }
            val vm = start(repository)
            val requester = PermissionRequester()
            vm.setNotifyNewEpisodes(true, requester)
            advanceUntilIdle()
            assertEquals(0, requester.requests)
            assertNull(
                vm.uiState.value.scoped!!
                    .own.notifyNewEpisodes,
            )
            assertEquals(1, vm.uiState.value.messages.size)
            vm.onMessageShown(
                vm.uiState.value.messages
                    .single()
                    .id,
            )
            advanceUntilIdle()
            assertTrue(
                vm.uiState.value.messages
                    .isEmpty(),
            )
        }

    @Test fun invalidRefreshShowsInlineErrorAndCanBeRetried() =
        runTest {
            val vm = start()
            vm.setRefreshIntervalMinutes(15)
            advanceUntilIdle()
            assertEquals(setOf(SettingField.REFRESH_INTERVAL_MINUTES), vm.uiState.value.invalidFields)
            assertNull(
                vm.uiState.value.scoped!!
                    .own.refreshIntervalMinutes,
            )
            vm.setRefreshIntervalMinutes(60)
            advanceUntilIdle()
            assertTrue(
                vm.uiState.value.invalidFields
                    .isEmpty(),
            )
            assertEquals(
                60,
                vm.uiState.value.scoped!!
                    .own.refreshIntervalMinutes,
            )
        }

    @Test fun deletedGroupAndDeletedScopeBothCloseTheScreen() =
        runTest {
            val vm = start()
            assertFalse(vm.uiState.value.gone)
            scopes.removeGroup(groupId)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.gone)
            scopes.seedGroup(groupId, ownSource.uuid, ownSource.name)
            advanceUntilIdle()
            assertFalse(vm.uiState.value.gone)
            groups.groups.value = emptyList()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.gone)
            val requester = PermissionRequester()
            vm.setNotifyNewEpisodes(true, requester)
            advanceUntilIdle()
            assertEquals(0, requester.requests)
        }

    private class PermissionRequester : NotificationPermissionRequester {
        override var granted = false
        var requests = 0

        override fun request(onResult: (Boolean) -> Unit) {
            requests++
            onResult(granted)
        }

        override fun openSettings() = Unit
    }

    @Test fun automaticDownloadWritesClearDependentsWithoutTouchingOtherFields() =
        runTest {
            val vm = start()
            vm.setRefreshIntervalMinutes(120)
            vm.editDownload(
                DownloadSettingEdit
                    .Enabled(true),
            )
            vm.editDownload(
                DownloadSettingEdit
                    .Keep(7),
            )
            vm.editDownload(
                DownloadSettingEdit
                    .Charging(true),
            )
            vm.editDownload(
                DownloadSettingEdit
                    .Delete(DeleteAfter.NEVER),
            )
            advanceUntilIdle()
            assertEquals(
                7,
                vm.uiState.value.scoped
                    ?.own
                    ?.autoDownloadKeepLatest,
            )
            vm.editDownload(
                DownloadSettingEdit
                    .Enabled(false),
            )
            advanceUntilIdle()
            val own =
                vm.uiState.value.scoped!!
                    .own
            assertEquals(false, own.autoDownload)
            assertNull(own.autoDownloadKeepLatest)
            assertNull(own.autoDownloadRequireCharging)
            assertEquals(120, own.refreshIntervalMinutes)
            assertEquals(DeleteAfter.NEVER, own.deleteAfterPlayed)
        }

    @Test fun externalGroupLineTracksMembersAndCapabilityChanges() =
        runTest {
            val vm = start()
            assertNull(vm.uiState.value.externalGroupReason)
            podcasts.tilesByGroup.value =
                mapOf(
                    groupId to listOf(testLibraryTile(1).copy(sourceType = SourceType.YOUTUBE_CHANNEL)),
                )
            advanceUntilIdle()
            assertEquals(ExternalReason.NOT_YET_AVAILABLE, vm.uiState.value.externalGroupReason)
            capabilities.capabilities.value =
                capabilities.capabilities.value.copy(
                    downloads = true,
                    externalReason = null,
                )
            advanceUntilIdle()
            assertNull(vm.uiState.value.externalGroupReason)
        }
}
