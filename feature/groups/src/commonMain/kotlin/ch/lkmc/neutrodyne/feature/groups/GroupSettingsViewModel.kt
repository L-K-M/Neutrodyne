// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.GroupRepository
import ch.lkmc.neutrodyne.core.domain.PodcastRepository
import ch.lkmc.neutrodyne.core.domain.ScopeSettingsRepository
import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.ExternalReason
import ch.lkmc.neutrodyne.core.model.Group
import ch.lkmc.neutrodyne.core.model.ScopedSettingsView
import ch.lkmc.neutrodyne.core.model.SettingField
import ch.lkmc.neutrodyne.core.model.SettingOverrides
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.ui.DownloadSettingEdit
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.UserMessage
import ch.lkmc.neutrodyne.core.ui.UserMessages
import ch.lkmc.neutrodyne.core.ui.platform.NotificationPermissionRequester
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.write_failed
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class GroupSettingsUiState(
    val group: Group? = null,
    val scoped: ScopedSettingsView? = null,
    val inherited: SettingOverrides = SettingOverrides(),
    val loaded: Boolean = false,
    val externalGroupReason: ExternalReason? = null,
    val invalidFields: Set<SettingField> = emptySet(),
    val messages: ImmutableList<UserMessage> = persistentListOf(),
) {
    val gone: Boolean get() = loaded && (group == null || scoped == null)
}

internal class GroupSettingsViewModel
    @AssistedInject
    constructor(
        @Assisted private val groupId: Long,
        groups: GroupRepository,
        private val scopes: ScopeSettingsRepository,
        settings: SettingsRepository,
        podcasts: PodcastRepository,
        capabilities: YouTubeCapabilitiesSource,
    ) : ViewModel() {
        private val messages = UserMessages()
        private val invalidFields = MutableStateFlow<Set<SettingField>>(emptySet())

        val uiState =
            combine(
                groups.observeGroup(groupId),
                scopes.observeGroup(groupId),
                settings.observe(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES),
                settings.observe(FeedsSettingKeys.NOTIFY_NEW_EPISODES),
            ) { group, scoped, minutes, notify ->
                // A group inherits only app defaults, so its chooser can preview them even with an override.
                GroupSettingsUiState(group, scoped, SettingOverrides(minutes, notify), loaded = true)
            }.combine(
                combine(podcasts.observeLibraryTiles(groupId), capabilities.capabilities) { members, caps ->
                    caps.externalReason.takeIf {
                        !caps.downloads &&
                            members.any { it.sourceType == SourceType.YOUTUBE_CHANNEL }
                    }
                },
            ) { state, reason ->
                state.copy(
                    externalGroupReason = reason,
                )
            }.combine(invalidFields) { state, fields -> state.copy(invalidFields = fields) }
                .combine(messages.flow) { state, list -> state.copy(messages = list) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupSettingsUiState())

        fun editDownload(edit: DownloadSettingEdit) {
            write(edit.field, edit::applyTo)
        }

        fun setRefreshIntervalMinutes(minutes: Int?) {
            write(SettingField.REFRESH_INTERVAL_MINUTES, { it.copy(refreshIntervalMinutes = minutes) })
        }

        fun setNotifyNewEpisodes(
            enabled: Boolean?,
            requester: NotificationPermissionRequester?,
        ) {
            write(SettingField.NOTIFY_NEW_EPISODES, { it.copy(notifyNewEpisodes = enabled) }) {
                // Ask only after a successful explicit opt-in; denial keeps the notification wish.
                if (enabled != true || uiState.value.gone) return@write
                val permission = requester ?: return@write
                if (!permission.granted) permission.request {}
            }
        }

        fun onMessageShown(id: Long) = messages.shown(id)

        private fun write(
            field: SettingField,
            change: (SettingOverrides) -> SettingOverrides,
            onSuccess: () -> Unit = {},
        ) {
            if (!uiState.value.loaded || uiState.value.gone) return

            viewModelScope.launch {
                invalidFields.update { it - field }
                val result =
                    suspendRunCatching { scopes.updateGroup(groupId, change) }
                        .onFailure(::reportFailure)
                        .getOrNull()
                when (result) {
                    is Outcome.Success -> {
                        onSuccess()
                    }

                    is Outcome.Failure -> {
                        when (result.error) {
                            is SettingsError.OutOfRange -> invalidFields.update { it + field }
                            SettingsError.WriteFailed -> reportFailure(null)
                        }
                    }

                    null -> {
                        Unit
                    }
                }
            }
        }

        private fun reportFailure(error: Throwable?) {
            Log.w("GroupSettings", error) { "Group settings write failed" }
            messages.post(UiText.Res(Res.string.write_failed))
        }

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey(Factory::class)
        @ContributesIntoMap(AppScope::class)
        fun interface Factory : ManualViewModelAssistedFactory {
            fun create(groupId: Long): GroupSettingsViewModel
        }
    }
