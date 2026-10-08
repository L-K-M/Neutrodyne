// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.RefreshController
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.model.settings.ShowNotesImages
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The five values Settings › Feeds renders (08 Settings › Feeds; defaults until first written). */
public data class FeedsSettingsUiState(
    val intervalMinutes: Int = FeedsSettingKeys.REFRESH_INTERVAL_MINUTES.default,
    val wifiOnly: Boolean = FeedsSettingKeys.REFRESH_WIFI_ONLY.default,
    val refreshOnAppOpen: Boolean = FeedsSettingKeys.REFRESH_ON_APP_OPEN.default,
    val backfillPagedFeeds: Boolean = FeedsSettingKeys.BACKFILL_PAGED_FEEDS.default,
    val showNotesImages: ShowNotesImages = FeedsSettingKeys.SHOW_NOTES_IMAGES.default,
)

/**
 * Settings › Feeds' writes (08 Settings › Feeds). The interval and Wi-Fi-only choices shape the
 * periodic tick, so a successful write is followed by `RefreshController.reschedulePeriodic()`
 * (03 Refresh scheduling: the scheduler re-reads the keys and rebases `nextRefreshAt`); the other
 * rows are plain writes the scheduler never reads.
 */
@ViewModelKey(FeedsSettingsViewModel::class)
@ContributesIntoMap(AppScope::class)
@Inject
public class FeedsSettingsViewModel(
    private val settings: SettingsRepository,
    private val refreshController: RefreshController,
) : ViewModel() {
    public val uiState: StateFlow<FeedsSettingsUiState> =
        combine(
            settings.observe(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES),
            settings.observe(FeedsSettingKeys.REFRESH_WIFI_ONLY),
            settings.observe(FeedsSettingKeys.REFRESH_ON_APP_OPEN),
            settings.observe(FeedsSettingKeys.BACKFILL_PAGED_FEEDS),
            settings.observe(FeedsSettingKeys.SHOW_NOTES_IMAGES),
            ::FeedsSettingsUiState,
        ).stateIn(viewModelScope, SHARING, FeedsSettingsUiState())

    /** The interval chooser's write; 03 rebases the periodic tick on success. */
    public fun setRefreshIntervalMinutes(minutes: Int) {
        setRescheduling(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES, minutes)
    }

    /** The Wi-Fi-only switch's write; 03 rebases the periodic tick's network constraint on success. */
    public fun setRefreshWifiOnly(enabled: Boolean) {
        setRescheduling(FeedsSettingKeys.REFRESH_WIFI_ONLY, enabled)
    }

    /** The refresh-on-open switch's write (Android foreground trigger; no tick rebase). */
    public fun setRefreshOnAppOpen(enabled: Boolean) {
        set(FeedsSettingKeys.REFRESH_ON_APP_OPEN, enabled)
    }

    /** The backfill switch's write (03 RFC 5005 paging). */
    public fun setBackfillPagedFeeds(enabled: Boolean) {
        set(FeedsSettingKeys.BACKFILL_PAGED_FEEDS, enabled)
    }

    /** The show-notes images chooser's write (03 Images and links). */
    public fun setShowNotesImages(choice: ShowNotesImages) {
        set(FeedsSettingKeys.SHOW_NOTES_IMAGES, choice)
    }

    /** Writes the key, then rebases the periodic tick only when the write took. */
    private fun <T : Any> setRescheduling(
        key: SettingKey<T>,
        value: T,
    ) {
        viewModelScope.launch {
            if (settings.set(key, value) is Outcome.Success) {
                refreshController.reschedulePeriodic()
            }
        }
    }

    private fun <T : Any> set(
        key: SettingKey<T>,
        value: T,
    ) {
        viewModelScope.launch { settings.set(key, value) }
    }

    private companion object {
        val SHARING = SharingStarted.WhileSubscribed(5_000)
    }
}
