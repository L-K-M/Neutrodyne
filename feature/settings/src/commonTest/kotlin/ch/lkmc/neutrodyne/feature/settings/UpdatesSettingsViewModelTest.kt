// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.DesktopArch
import ch.lkmc.neutrodyne.core.model.DesktopOs
import ch.lkmc.neutrodyne.core.model.InstallKind
import ch.lkmc.neutrodyne.core.model.settings.UpdateSettingKeys
import ch.lkmc.neutrodyne.core.model.update.DesktopAssetSelection
import ch.lkmc.neutrodyne.core.model.update.UpdateApk
import ch.lkmc.neutrodyne.core.model.update.UpdateCheckError
import ch.lkmc.neutrodyne.core.model.update.UpdateCheckState
import ch.lkmc.neutrodyne.core.model.update.UpdateNotice
import ch.lkmc.neutrodyne.core.model.update.UpdateRuntimeTarget
import ch.lkmc.neutrodyne.core.model.update.VerificationPhase
import ch.lkmc.neutrodyne.core.testing.FakeAppUpdateChecker
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.FakeUpdateNotices
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import ch.lkmc.neutrodyne.core.testing.updateContractInfo
import ch.lkmc.neutrodyne.core.ui.platform.NotificationPermissionRequester
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal val UpdateTestBuild =
    BuildInfo(
        versionName = "0.1.0",
        versionCode = 10095,
        debug = false,
        platform = BuildInfo.Platform.ANDROID,
        repoUrl = "https://github.com/L-K-M/Neutrodyne",
        updateManifestUrl = "https://github.com/L-K-M/Neutrodyne/releases/latest/download/neutrodyne-update.json",
        engineManifestUrl = "https://example.invalid/engine",
        youTubeEngineBundled = false,
        apkAbi = "armeabi-v7a",
        shippedLocales = persistentListOf("en", "de"),
        podcastIndexKey = "",
        podcastIndexSecret = "",
    )

internal val UpdateTestDesktop =
    UpdateTestBuild.copy(
        platform = BuildInfo.Platform.DESKTOP,
        apkAbi = null,
        desktop = BuildInfo.Desktop(DesktopOs.MACOS, DesktopArch.ARM64, InstallKind.MAC_ZIP, "bundled"),
    )

@OptIn(ExperimentalCoroutinesApi::class)
class UpdatesSettingsViewModelTest : MainDispatcherTest() {
    @Test fun manualCheckWorksWithAutomaticChecksOffAndSkipUsesCurrentVersion() =
        runTest {
            val settings = FakeSettingsRepository()
            settings.set(UpdateSettingKeys.CHECK_ENABLED, false)
            val checker =
                FakeAppUpdateChecker().apply {
                    nextCheckState =
                        UpdateCheckState.Available(updateContractInfo(), 42)
                }
            val vm = UpdatesSettingsViewModel(settings, checker, UpdateTestBuild)
            backgroundScope.launch { vm.uiState.collect {} }
            runCurrent()
            assertFalse(vm.uiState.value.enabled)
            vm.checkNow()
            runCurrent()
            assertEquals(42L, vm.uiState.value.lastCheckAtMs)
            vm.skip(1000095)
            runCurrent()
            assertEquals(UpdateCheckState.Idle(42), vm.uiState.value.check)
            assertEquals(listOf("checkNow", "skip(1000095)"), checker.calls)
        }

    @Test fun failedWriteKeepsPreferenceAndDoesNotRequestPermission() =
        runTest {
            val settings = FakeSettingsRepository()
            settings.set(UpdateSettingKeys.CHECK_ENABLED, false)
            settings.failNextSet = SettingsError.WriteFailed
            val requester = TestUpdatePermission()
            val vm = UpdatesSettingsViewModel(settings, FakeAppUpdateChecker(), UpdateTestBuild)
            vm.setEnabled(true, requester)
            runCurrent()
            assertTrue(vm.saveFailed.value)
            assertFalse(settings.get(UpdateSettingKeys.CHECK_ENABLED))
            assertEquals(0, requester.requests)
            vm.setEnabled(true, requester)
            runCurrent()
            assertFalse(vm.saveFailed.value)
            assertTrue(settings.get(UpdateSettingKeys.CHECK_ENABLED))
            assertEquals(1, requester.requests)
        }

    @Test fun debugCannotEnableOrCheckEvenAfterRestore() =
        runTest {
            val settings = FakeSettingsRepository()
            val checker = FakeAppUpdateChecker()
            val vm = UpdatesSettingsViewModel(settings, checker, UpdateTestBuild.copy(debug = true))
            backgroundScope.launch { vm.uiState.collect {} }
            runCurrent()
            vm.setEnabled(true, null)
            vm.checkNow()
            runCurrent()
            assertFalse(vm.uiState.value.enabled)
            assertTrue(checker.calls.isEmpty())
            assertTrue(settings.calls.isEmpty())
        }

    @Test fun persistedLastCheckSurvivesCheckingDisabledAndFailedState() =
        runTest {
            val settings = FakeSettingsRepository()
            settings.set(UpdateSettingKeys.LAST_CHECK_AT, 123)
            val checker = FakeAppUpdateChecker(UpdateCheckState.Checking)
            val vm = UpdatesSettingsViewModel(settings, checker, UpdateTestBuild)
            backgroundScope.launch { vm.uiState.collect {} }
            runCurrent()
            assertEquals(123L, vm.uiState.value.lastCheckAtMs)
            checker.state.value = UpdateCheckState.Failed(UpdateCheckError.NETWORK, 456)
            runCurrent()
            assertEquals(456L, vm.uiState.value.lastCheckAtMs)
        }

    @Test fun turnOffPersistsThenDismissesAndUndoRestoresChoice() =
        runTest {
            val settings = FakeSettingsRepository()
            val notices = FakeUpdateNotices(UpdateNotice.entries)
            val actions = UpdateNoticeActions(settings, notices, backgroundScope)
            actions.disable()
            runCurrent()
            assertFalse(settings.get(UpdateSettingKeys.CHECK_ENABLED))
            assertEquals(UpdateNotice.VERIFICATION_ENFORCEMENT, notices.pending.value)
            actions.undo(
                actions.messages.flow.value
                    .single()
                    .id,
            )
            runCurrent()
            assertTrue(settings.get(UpdateSettingKeys.CHECK_ENABLED))
        }

    @Test fun failedTurnOffKeepsDisclosureAndReportsFailure() =
        runTest {
            val settings = FakeSettingsRepository().apply { failNextSet = SettingsError.WriteFailed }
            val notices = FakeUpdateNotices(listOf(UpdateNotice.FIRST_RUN_CHOICE))
            val actions = UpdateNoticeActions(settings, notices, backgroundScope)
            actions.disable()
            runCurrent()
            assertEquals(UpdateNotice.FIRST_RUN_CHOICE, notices.pending.value)
            assertTrue(settings.get(UpdateSettingKeys.CHECK_ENABLED))
            assertEquals(1, actions.messages.flow.value.size)
        }

    @Test fun acknowledgementRequestsPermissionOnlyAfterDurableSuccess() =
        runTest {
            val notices =
                FakeUpdateNotices(listOf(UpdateNotice.FIRST_RUN_CHOICE)).apply {
                    failNextWrite =
                        SettingsError.WriteFailed
                }
            val requester = TestUpdatePermission()
            val actions = UpdateNoticeActions(FakeSettingsRepository(), notices, backgroundScope)
            actions.acknowledge(requester)
            runCurrent()
            assertEquals(0, requester.requests)
            actions.acknowledge(requester)
            runCurrent()
            assertNull(notices.pending.value)
            assertEquals(1, requester.requests)
        }

    @Test fun incompatibleAndroidAndDesktopNeverExposeAssetLink() {
        val apk = UpdateApk("arm64-v8a", "test.apk", "https://github.com/test.apk", 10, "a".repeat(64))
        val info = updateContractInfo().copy(minSdk = 37, apk = apk)
        assertNull(updateDownload(info, UpdateRuntimeTarget.Android("arm64-v8a", 26)))
        assertEquals(apk.url, updateDownload(info, UpdateRuntimeTarget.Android("arm64-v8a", 37))?.url)
        assertNull(
            updateDownload(
                info.copy(desktopSelection = DesktopAssetSelection.RequiresNewerOs(DesktopOs.MACOS, "99")),
                UpdateRuntimeTarget.Desktop("13"),
            ),
        )
    }

    @Test fun helpSectionsArePlatformSpecificAndPostEnforcementMovesGoogleFirst() {
        val android = helpSections(UpdateTestBuild, VerificationPhase.PRE_ENFORCEMENT)
        assertFalse(InstallHelpSection.MACOS in android)
        assertTrue(InstallHelpSection.ANDROID_AUTO in android)
        assertEquals(
            InstallHelpSection.GOOGLE_PLAY,
            helpSections(UpdateTestBuild, VerificationPhase.POST_ENFORCEMENT).first(),
        )
        val desktop = helpSections(UpdateTestDesktop, VerificationPhase.PRE_ENFORCEMENT)
        assertFalse(InstallHelpSection.GOOGLE_PLAY in desktop)
        assertEquals(InstallHelpSection.MACOS, desktop[4])
        assertEquals("MACOS", helpSection(UpdateTestDesktop))
        assertEquals("ALLOW", helpSection(UpdateTestBuild))
    }
}

private class TestUpdatePermission : NotificationPermissionRequester {
    var requests = 0
    override val granted = false

    override fun request(onResult: (Boolean) -> Unit) {
        requests++
        onResult(false)
    }

    override fun openSettings() = Unit
}
