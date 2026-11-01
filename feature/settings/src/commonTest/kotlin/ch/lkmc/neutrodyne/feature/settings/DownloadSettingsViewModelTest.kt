// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.lifecycle.ViewModelStore
import ch.lkmc.neutrodyne.core.model.ManualMeteredPolicy
import ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeDownloadController
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import ch.lkmc.neutrodyne.download.api.RootChangeMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadSettingsViewModelTest : MainDispatcherTest() {
    @Test fun rootSelectionOnlyChangesNewDownloadsAndUnavailableIsNotSuccess() =
        runTest {
            val downloads = FakeDownloadController()
            val settings = FakeSettingsRepository()
            val store = ViewModelStore()
            try {
                val vm = DownloadSettingsViewModel(downloads, settings).also { store.put("settings", it) }
                vm.selectRoot("int")
                runCurrent()
                assertEquals(RootChangeMode.NEW_DOWNLOADS_ONLY, downloads.calls.single().rootMode)
                assertEquals(1, vm.userMessages.value.size)
                assertEquals(DownloadSettingKeys.ROOT_ID.default, settings.get(DownloadSettingKeys.ROOT_ID))
            } finally {
                store.clear()
            }
        }

    @Test fun manualMeteredWritesOnlyTheManualPolicy() =
        runTest {
            val settings = FakeSettingsRepository()
            val store = ViewModelStore()
            try {
                val vm =
                    DownloadSettingsViewModel(
                        FakeDownloadController(),
                        settings,
                    ).also { store.put("settings", it) }
                vm.setMetered(ManualMeteredPolicy.ALWAYS)
                runCurrent()
                assertEquals(ManualMeteredPolicy.ALWAYS, settings.get(DownloadSettingKeys.MANUAL_METERED))
                assertEquals(listOf("set(downloads.manual_metered)"), settings.calls.filter { it.startsWith("set(") })
            } finally {
                store.clear()
            }
        }
}
