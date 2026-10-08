// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.RefreshController
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.model.settings.ShowNotesImages
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.UserMessage
import ch.lkmc.neutrodyne.core.ui.UserMessages
import ch.lkmc.neutrodyne.feature.settings.resources.Res
import ch.lkmc.neutrodyne.feature.settings.resources.settings_save_failed
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
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
    val messages: ImmutableList<UserMessage> = persistentListOf(),
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
    private val userMessages = UserMessages()

    public val uiState: StateFlow<FeedsSettingsUiState> =
        combine(
            settings.observe(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES),
            settings.observe(FeedsSettingKeys.REFRESH_WIFI_ONLY),
            settings.observe(FeedsSettingKeys.REFRESH_ON_APP_OPEN),
            settings.observe(FeedsSettingKeys.BACKFILL_PAGED_FEEDS),
            settings.observe(FeedsSettingKeys.SHOW_NOTES_IMAGES),
            ::FeedsSettingsUiState,
        ).combine(userMessages.flow) { state, messages ->
            state.copy(messages = messages)
        }.stateIn(viewModelScope, SHARING, FeedsSettingsUiState())

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

    /** The screen acks a shown snackbar so its [UserMessage] leaves the state. */
    public fun onMessageShown(id: Long) {
        userMessages.shown(id)
    }

    /** Writes the key, then rebases the periodic tick only when the write took. */
    private fun <T : Any> setRescheduling(
        key: SettingKey<T>,
        value: T,
    ) {
        viewModelScope.launch {
            val outcome = suspendRunCatching { settings.set(key, value) }.getOrNull()
            if (outcome !is Outcome.Success) {
                reportWriteFailed(null)
                return@launch
            }
            suspendRunCatching { refreshController.reschedulePeriodic() }.onFailure(::reportWriteFailed)
        }
    }

    private fun <T : Any> set(
        key: SettingKey<T>,
        value: T,
    ) {
        viewModelScope.launch {
            val outcome = suspendRunCatching { settings.set(key, value) }.getOrNull()
            if (outcome !is Outcome.Success) reportWriteFailed(null)
        }
    }

    /** A failed write is logged and surfaces as a snackbar — it never escapes the scope. */
    private fun reportWriteFailed(t: Throwable?) {
        Log.w(TAG, t) { "settings write failed" }
        userMessages.post(UiText.Res(Res.string.settings_save_failed))
    }

    private companion object {
        const val TAG = "FeedsSettingsViewModel"
        val SHARING = SharingStarted.WhileSubscribed(5_000)
    }
}
