// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.SearchRepository
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.ProviderId
import ch.lkmc.neutrodyne.core.model.ProviderInfo
import ch.lkmc.neutrodyne.core.model.ProviderStatus
import ch.lkmc.neutrodyne.core.model.settings.DiscoverSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal enum class DiscoverNotice { SAVED, REMOVED, INVALID_COUNTRY, SAVE_FAILED, KEY_FAILED, KEY_BUSY, KEY_INVALID }

internal data class DiscoverSettingsState(
    val country: String = "",
    val systemCountry: String = "US",
    val providers: ImmutableList<ProviderInfo> = persistentListOf(),
    val busy: Boolean = false,
    val notice: DiscoverNotice? = null,
)

@ViewModelKey(DiscoverSettingsViewModel::class)
@ContributesIntoMap(AppScope::class)
@Inject
internal class DiscoverSettingsViewModel(
    private val settings: SettingsRepository,
    private val search: SearchRepository,
    platform: PlatformInfo,
) : ViewModel() {
    private val systemCountry =
        platform.regionCode
            .uppercase()
            .takeIf { it.isNotEmpty() && DiscoverSettingKeys.acceptsCountry(it) } ?: "US"
    private val operation = MutableStateFlow(DiscoverSettingsState(systemCountry = systemCountry))
    private var keyJob: Job? = null

    val uiState =
        combine(settings.observe(DiscoverSettingKeys.COUNTRY), search.observeProviders(), operation) {
            country,
            providers,
            state,
            ->
            state.copy(country = country, providers = providers)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), operation.value)

    fun setCountry(input: String) {
        val country = input.trim().uppercase()
        if (!DiscoverSettingKeys.acceptsCountry(country)) {
            notice(DiscoverNotice.INVALID_COUNTRY)
            return
        }
        set(DiscoverSettingKeys.COUNTRY, country)
    }

    fun setProvider(
        id: ProviderId,
        enabled: Boolean,
    ) {
        val key =
            when (id) {
                ProviderId.APPLE -> DiscoverSettingKeys.APPLE_ENABLED
                ProviderId.FYYD -> DiscoverSettingKeys.FYYD_ENABLED
                ProviderId.PODCAST_INDEX -> DiscoverSettingKeys.PODCASTINDEX_ENABLED
            }
        set(key, enabled)
    }

    // Neither field enters StateFlow/SavedStateHandle or logs; only the repository stores a verified pair.
    fun saveKey(
        key: String,
        secret: CharArray,
    ) {
        if (operation.value.busy) {
            secret.fill('\u0000')
            return
        }
        if (key.isBlank() || secret.isEmpty()) {
            secret.fill('\u0000')
            notice(DiscoverNotice.KEY_INVALID)
            return
        }
        operation.update { it.copy(busy = true, notice = null) }
        keyJob =
            viewModelScope
                .launch {
                    try {
                        val result = suspendRunCatching { search.setPodcastIndexKey(key.trim(), secret) }.getOrNull()
                        notice(
                            when (result) {
                                is Outcome.Success -> {
                                    DiscoverNotice.SAVED
                                }

                                is Outcome.Failure -> {
                                    if (result.error ==
                                        ProviderStatus.RateLimited
                                    ) {
                                        DiscoverNotice.KEY_BUSY
                                    } else {
                                        DiscoverNotice.KEY_FAILED
                                    }
                                }

                                null -> {
                                    DiscoverNotice.SAVE_FAILED
                                }
                            },
                        )
                    } finally {
                        secret.fill('\u0000')
                        operation.update { it.copy(busy = false) }
                    }
                }.also { job ->
                    job.invokeOnCompletion {
                        secret.fill('\u0000')
                        operation.update { it.copy(busy = false) }
                    }
                }
    }

    fun cancelKeyEntry() {
        keyJob?.cancel()
    }

    fun clearKey() {
        if (operation.value.busy) return
        operation.update { it.copy(busy = true, notice = null) }
        keyJob =
            viewModelScope
                .launch {
                    try {
                        val result = suspendRunCatching { search.clearPodcastIndexKey() }
                        notice(if (result.isSuccess) DiscoverNotice.REMOVED else DiscoverNotice.SAVE_FAILED)
                    } finally {
                        operation.update { it.copy(busy = false) }
                    }
                }.also { job -> job.invokeOnCompletion { operation.update { it.copy(busy = false) } } }
    }

    private fun notice(value: DiscoverNotice) = operation.update { it.copy(notice = value) }

    private fun <T : Any> set(
        key: SettingKey<T>,
        value: T,
    ) {
        operation.update { it.copy(notice = null) }
        viewModelScope.launch {
            val result = suspendRunCatching { settings.set(key, value) }.getOrNull()
            if (result !is Outcome.Success) notice(DiscoverNotice.SAVE_FAILED)
        }
    }
}
