// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.SettingOverrides
import ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.ui.DownloadSettingEdit
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.download_operation_failed
import ch.lkmc.neutrodyne.download.api.DownloadController
import ch.lkmc.neutrodyne.download.api.FolderRootResult
import ch.lkmc.neutrodyne.download.api.OrphanDeleteResult
import ch.lkmc.neutrodyne.download.api.RootChangeMode
import ch.lkmc.neutrodyne.download.api.RootChangeResult
import ch.lkmc.neutrodyne.download.api.RootKind
import ch.lkmc.neutrodyne.download.api.StorageRootInfo
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal data class DownloadsSettingsState(
    val values: SettingOverrides = SettingOverrides(),
    val capMb: Int = 0,
)

internal data class FolderDecision(
    val root: StorageRootInfo,
    val count: Int,
    val bytes: Long,
)

@ViewModelKey(DownloadsSettingsViewModel::class)
@ContributesIntoMap(AppScope::class)
@Inject
internal class DownloadsSettingsViewModel(
    private val settings: SettingsRepository,
    private val downloads: DownloadController,
) : ViewModel() {
    val error = MutableStateFlow<UiText?>(null)
    val folderDecision = MutableStateFlow<FolderDecision?>(null)
    val orphanConfirmation = MutableStateFlow<Long?>(null)
    val roots = MutableStateFlow<List<StorageRootInfo>>(emptyList())
    val working = MutableStateFlow(false)
    val storage =
        downloads.observeStorage().stateIn(
            viewModelScope,
            SHARING,
            null,
        )
    private val overview =
        downloads.observeAll().stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            null,
        )

    val state =
        combine(
            combine(
                settings.observe(DownloadSettingKeys.AUTO_DOWNLOAD),
                settings.observe(DownloadSettingKeys.AUTO_DOWNLOAD_KEEP_LATEST),
                settings.observe(DownloadSettingKeys.AUTO_DOWNLOAD_NETWORK),
                settings.observe(DownloadSettingKeys.AUTO_DOWNLOAD_REQUIRE_CHARGING),
                settings.observe(DownloadSettingKeys.AUTO_DOWNLOAD_INCLUDE_VIDEO),
            ) {
                enabled,
                keep,
                network,
                charging,
                video,
                ->
                SettingOverrides(
                    autoDownload = enabled,
                    autoDownloadKeepLatest = keep,
                    autoDownloadNetwork = network,
                    autoDownloadRequireCharging = charging,
                    includeVideoInAutoDownload = video,
                )
            },
            settings.observe(DownloadSettingKeys.DELETE_AFTER_PLAYED),
            settings.observe(DownloadSettingKeys.STORAGE_CAP_MB),
        ) {
            auto,
            delete,
            cap,
            ->
            DownloadsSettingsState(
                auto.copy(deleteAfterPlayed = delete),
                cap,
            )
        }.stateIn(
            viewModelScope,
            SHARING,
            DownloadsSettingsState(),
        )

    init {
        viewModelScope.launch {
            suspendRunCatching { downloads.storageRoots() }.onSuccess { roots.value = it }.onFailure { fail() }
        }
    }

    fun edit(edit: DownloadSettingEdit) {
        when (edit) {
            is DownloadSettingEdit.Enabled -> {
                edit.value?.let {
                    set(
                        DownloadSettingKeys.AUTO_DOWNLOAD,
                        it,
                    )
                }
            }

            is DownloadSettingEdit.Keep -> {
                edit.value?.let {
                    set(
                        DownloadSettingKeys.AUTO_DOWNLOAD_KEEP_LATEST,
                        it,
                    )
                }
            }

            is DownloadSettingEdit.Network -> {
                edit.value?.let {
                    set(
                        DownloadSettingKeys.AUTO_DOWNLOAD_NETWORK,
                        it,
                    )
                }
            }

            is DownloadSettingEdit.Charging -> {
                edit.value?.let {
                    set(
                        DownloadSettingKeys.AUTO_DOWNLOAD_REQUIRE_CHARGING,
                        it,
                    )
                }
            }

            is DownloadSettingEdit.Video -> {
                edit.value?.let {
                    set(
                        DownloadSettingKeys.AUTO_DOWNLOAD_INCLUDE_VIDEO,
                        it,
                    )
                }
            }

            is DownloadSettingEdit.Delete -> {
                edit.value?.let {
                    set(
                        DownloadSettingKeys.DELETE_AFTER_PLAYED,
                        it,
                    )
                }
            }
        }
    }

    fun setCap(mb: Int) =
        set(
            DownloadSettingKeys.STORAGE_CAP_MB,
            mb,
        )

    private fun <T : Any> set(
        key: SettingKey<T>,
        value: T,
    ) {
        viewModelScope.launch {
            if (suspendRunCatching {
                    settings.set(
                        key,
                        value,
                    )
                }.getOrNull() !is Outcome.Success
            ) {
                fail()
            }
        }
    }

    fun chooseFolder(path: String) =
        operation {
            when (val result = downloads.registerFolderRoot(path)) {
                is FolderRootResult.Rejected -> {
                    error.value =
                        ch.lkmc.neutrodyne.core.ui
                            .folderRejectText(result.reason)
                }

                is FolderRootResult.Ok -> {
                    offerRoot(result.root)
                }
            }
        }

    fun useDefaultFolder() {
        roots.value
            .firstOrNull {
                it.kind ==
                    RootKind.DESKTOP_DEFAULT
            }?.let(::offerRoot)
    }

    fun selectRoot(rootId: String) {
        roots.value.firstOrNull { it.rootId == rootId && it.available }?.let(::offerRoot)
    }

    private fun offerRoot(root: StorageRootInfo) {
        if (root.rootId == storage.value?.rootId) return
        val completed =
            overview.value?.completed ?: run {
                fail()
                return
            }
        folderDecision.value =
            FolderDecision(
                root,
                completed.size,
                completed.sumOf { it.status.totalBytes ?: 0L },
            )
    }

    fun dismissFolder() {
        if (!working.value) folderDecision.value = null
    }

    fun changeFolder(mode: RootChangeMode) {
        val decision = folderDecision.value ?: return
        operation {
            when (
                downloads.changeRoot(
                    decision.root.rootId,
                    mode,
                )
            ) {
                RootChangeResult.Applied -> {
                    folderDecision.value = null
                    roots.value = downloads.storageRoots()
                }

                RootChangeResult.Unavailable,
                -> {
                    fail()
                }
            }
        }
    }

    fun confirmOrphans() {
        storage.value
            ?.orphanBytes
            ?.takeIf { it > 0L }
            ?.let { orphanConfirmation.value = it }
    }

    fun dismissOrphans() {
        if (!working.value) orphanConfirmation.value = null
    }

    fun deleteOrphans() {
        if (orphanConfirmation.value == null) return
        operation {
            when (downloads.deleteOrphanFiles()) {
                is OrphanDeleteResult.Deleted -> orphanConfirmation.value = null
                OrphanDeleteResult.Unavailable -> fail()
            }
        }
    }

    private fun operation(block: suspend () -> Unit) {
        if (working.value) return
        working.value = true
        error.value = null
        viewModelScope.launch {
            try {
                suspendRunCatching { block() }.onFailure { fail() }
            } finally {
                working.value = false
            }
        }
    }

    private fun fail() {
        error.value =
            UiText
                .Res(Res.string.download_operation_failed)
    }

    private companion object {
        val SHARING = SharingStarted.WhileSubscribed(5_000)
    }
}
