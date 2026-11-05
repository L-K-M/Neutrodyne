// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.ManualMeteredPolicy
import ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.UserMessages
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.downloads_root_unavailable
import ch.lkmc.neutrodyne.core.ui.resources.write_failed
import ch.lkmc.neutrodyne.download.api.DownloadController
import ch.lkmc.neutrodyne.download.api.RootChangeMode
import ch.lkmc.neutrodyne.download.api.RootChangeResult
import ch.lkmc.neutrodyne.download.api.StorageRootInfo
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@ViewModelKey(DownloadSettingsViewModel::class)
@ContributesIntoMap(AppScope::class)
@Inject
public class DownloadSettingsViewModel(
    private val downloads: DownloadController,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val messages = UserMessages()
    public val userMessages = messages.flow
    private val rootsState = MutableStateFlow<List<StorageRootInfo>>(emptyList())
    public val roots = rootsState.stateIn(viewModelScope, SHARING, emptyList())
    public val rootId =
        settings
            .observe(
                DownloadSettingKeys.ROOT_ID,
            ).stateIn(viewModelScope, SHARING, DownloadSettingKeys.ROOT_ID.default)
    public val metered =
        settings
            .observe(
                DownloadSettingKeys.MANUAL_METERED,
            ).stateIn(viewModelScope, SHARING, ManualMeteredPolicy.ASK)

    init {
        refreshRoots()
    }

    public fun refreshRoots() {
        run { rootsState.value = downloads.storageRoots() }
    }

    public fun selectRoot(id: String) {
        run {
            if (downloads.changeRoot(id, RootChangeMode.NEW_DOWNLOADS_ONLY) != RootChangeResult.Applied) {
                messages.post(UiText.Res(Res.string.downloads_root_unavailable))
            }
            rootsState.value = downloads.storageRoots()
        }
    }

    public fun setMetered(value: ManualMeteredPolicy) {
        run { check(settings.set(DownloadSettingKeys.MANUAL_METERED, value) is Outcome.Success) }
    }

    public fun onMessageShown(id: Long) {
        messages.shown(id)
    }

    private fun run(block: suspend () -> Unit) {
        viewModelScope.launch {
            suspendRunCatching { block() }.onFailure { messages.post(UiText.Res(Res.string.write_failed)) }
        }
    }

    private companion object {
        val SHARING = SharingStarted.WhileSubscribed(5_000)
    }
}
