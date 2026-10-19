// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.UserMessages
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.download_operation_failed
import ch.lkmc.neutrodyne.download.api.DownloadController
import ch.lkmc.neutrodyne.download.api.OrphanDeleteResult
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@ViewModelKey(DownloadsViewModel::class)
@ContributesIntoMap(AppScope::class)
@Inject
internal class DownloadsViewModel(
    internal val downloads: DownloadController,
) : ViewModel() {
    val overview =
        downloads.observeAll().stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            null,
        )
    val messages = UserMessages()
    val orphanConfirmation = MutableStateFlow<Long?>(null)
    val working = MutableStateFlow(false)
    val orphanError = MutableStateFlow<UiText?>(null)

    fun confirmOrphans() {
        orphanError.value = null
        overview.value
            ?.storage
            ?.orphanBytes
            ?.takeIf { it > 0 }
            ?.let { orphanConfirmation.value = it }
    }

    fun dismissOrphans() {
        if (!working.value) orphanConfirmation.value = null
    }

    fun deleteOrphans() {
        if (orphanConfirmation.value == null || working.value) return
        working.value = true
        orphanError.value = null
        viewModelScope.launch {
            try {
                if (suspendRunCatching { downloads.deleteOrphanFiles() }.getOrNull() is OrphanDeleteResult.Deleted) {
                    orphanConfirmation.value =
                        null
                } else {
                    failed()
                }
            } finally {
                working.value = false
            }
        }
    }

    fun command(block: suspend DownloadController.() -> Unit) {
        viewModelScope.launch { suspendRunCatching { downloads.block() }.onFailure { failed() } }
    }

    fun failed() {
        val error = UiText.Res(Res.string.download_operation_failed)
        if (orphanConfirmation.value != null) orphanError.value = error else messages.post(error)
    }
}
