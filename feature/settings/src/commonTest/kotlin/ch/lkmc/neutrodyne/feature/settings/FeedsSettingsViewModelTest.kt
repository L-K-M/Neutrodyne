// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.ShowNotesImages
import ch.lkmc.neutrodyne.core.testing.FakeRefreshController
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Settings › Feeds' writes (03 Refresh scheduling): the interval and Wi-Fi-only rows rebase the
 * periodic tick through `RefreshController.reschedulePeriodic()` once the setting is persisted;
 * every other row is a plain write that must not touch the scheduler.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedsSettingsViewModelTest : MainDispatcherTest() {
    private val settings = FakeSettingsRepository()
    private val refreshController = FakeRefreshController()

    private fun viewModel() = FeedsSettingsViewModel(settings, refreshController)

    @Test
    fun intervalChangePersistsThenReschedules() =
        runTest {
            val viewModel = viewModel()
            viewModel.setRefreshIntervalMinutes(MINUTES_PER_HOUR)
            advanceUntilIdle()

            assertEquals(MINUTES_PER_HOUR, settings.get(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES))
            assertEquals(listOf("reschedulePeriodic()"), refreshController.calls)
        }

    @Test
    fun wifiOnlyChangePersistsThenReschedules() =
        runTest {
            val viewModel = viewModel()
            viewModel.setRefreshWifiOnly(true)
            advanceUntilIdle()

            assertEquals(true, settings.get(FeedsSettingKeys.REFRESH_WIFI_ONLY))
            assertEquals(listOf("reschedulePeriodic()"), refreshController.calls)
        }

    @Test
    fun failedWriteDoesNotReschedule() =
        runTest {
            val viewModel = viewModel()
            settings.failNextSet = SettingsError.WriteFailed
            viewModel.setRefreshIntervalMinutes(MINUTES_PER_HOUR)
            advanceUntilIdle()

            assertTrue(refreshController.calls.isEmpty())
        }

    @Test
    fun otherRowsDoNotReschedule() =
        runTest {
            val viewModel = viewModel()
            viewModel.setRefreshOnAppOpen(false)
            viewModel.setBackfillPagedFeeds(false)
            viewModel.setShowNotesImages(ShowNotesImages.ALWAYS)
            advanceUntilIdle()

            assertTrue(refreshController.calls.isEmpty())
            assertEquals(false, settings.get(FeedsSettingKeys.REFRESH_ON_APP_OPEN))
            assertEquals(false, settings.get(FeedsSettingKeys.BACKFILL_PAGED_FEEDS))
            assertEquals(ShowNotesImages.ALWAYS, settings.get(FeedsSettingKeys.SHOW_NOTES_IMAGES))
        }

    private companion object {
        const val MINUTES_PER_HOUR = 60
    }
}
