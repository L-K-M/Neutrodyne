// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.domain.update.AppUpdateChecker
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.settings.UpdateSettingKeys
import ch.lkmc.neutrodyne.core.model.update.UpdateCheckState
import ch.lkmc.neutrodyne.core.model.update.UpdateDisabledReason
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.UserMessages
import ch.lkmc.neutrodyne.core.ui.platform.NotificationPermissionRequester
import ch.lkmc.neutrodyne.feature.settings.resources.Res
import ch.lkmc.neutrodyne.feature.settings.resources.updates_skipped
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal data class UpdatesSettingsUiState(
    val check: UpdateCheckState = UpdateCheckState.Idle(null),
    val enabled: Boolean = true,
    val lastCheckAtMs: Long? = null,
)

@ViewModelKey(UpdatesSettingsViewModel::class)
@ContributesIntoMap(AppScope::class)
@Inject
internal class UpdatesSettingsViewModel(
    private val settings: SettingsRepository,
    private val checker: AppUpdateChecker,
    private val build: BuildInfo,
) : ViewModel() {
    private val mutableSaveFailed = kotlinx.coroutines.flow.MutableStateFlow(false)
    val saveFailed = mutableSaveFailed.asStateFlow()
    private val userMessages = UserMessages()
    val messages = userMessages.flow
    val writeError = checker.writeError

    val uiState =
        combine(
            checker.state,
            settings.observe(UpdateSettingKeys.CHECK_ENABLED),
            settings.observe(UpdateSettingKeys.LAST_CHECK_AT),
        ) { state, enabled, last ->
            UpdatesSettingsUiState(
                state,
                enabled && !build.debug,
                lastCheckTime(state) ?: UpdateSettingKeys.nullableValue(last),
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            UpdatesSettingsUiState(check = checker.state.value),
        )

    fun setEnabled(
        enabled: Boolean,
        requester: NotificationPermissionRequester?,
    ) {
        if (build.debug) return

        viewModelScope.launch {
            val result = suspendRunCatching { settings.set(UpdateSettingKeys.CHECK_ENABLED, enabled) }.getOrNull()
            mutableSaveFailed.value = result !is Outcome.Success
            if (result is Outcome.Success && enabled && requester?.granted == false) requester.request {}
        }
    }

    fun checkNow() {
        if (build.debug) return

        viewModelScope.launch { checker.checkNow() }
    }

    fun onMessageShown(id: Long) = userMessages.shown(id)

    fun skip(versionCode: Long) {
        val info = (checker.state.value as? UpdateCheckState.Available)?.info ?: return
        if (info.versionCode != versionCode) return

        checker.skip(versionCode)
        viewModelScope.launch {
            val result =
                combine(checker.state, checker.writeError) { state, error -> state to error }
                    .first { (state, error) -> state is UpdateCheckState.Idle || error != null }
            if (result.first is UpdateCheckState.Idle) {
                userMessages.post(UiText.Res(Res.string.updates_skipped, listOf(info.versionName)))
            }
        }
    }
}

internal fun lastCheckTime(state: UpdateCheckState): Long? =
    when (state) {
        is UpdateCheckState.Available -> state.lastCheckAtMs
        is UpdateCheckState.Idle -> state.lastCheckAtMs
        is UpdateCheckState.Failed -> state.lastCheckAtMs
        UpdateCheckState.Checking, is UpdateCheckState.Disabled -> null
    }

internal fun checksAreDevelopmentOnly(state: UpdateCheckState): Boolean =
    state == UpdateCheckState.Disabled(UpdateDisabledReason.DEV_BUILD)
