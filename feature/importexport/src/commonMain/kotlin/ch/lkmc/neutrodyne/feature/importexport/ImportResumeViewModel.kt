// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.importexport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.DiscardPreviewResult
import ch.lkmc.neutrodyne.core.domain.ImportRepository
import ch.lkmc.neutrodyne.core.model.ImportFormat
import ch.lkmc.neutrodyne.core.model.ImportSessionView
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.UserMessages
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.write_failed
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class ImportResumeUiState(
    val session: ImportSessionView? = null,
    val busy: Boolean = false,
    val failed: Boolean = false,
)

@ViewModelKey(ImportResumeViewModel::class)
@ContributesIntoMap(AppScope::class)
@Inject
internal class ImportResumeViewModel(
    private val imports: ImportRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {
    private val state = MutableStateFlow(ImportResumeUiState())
    val uiState = state.asStateFlow()
    private val userMessages = UserMessages()
    val messages = userMessages.flow

    private var observation: kotlinx.coroutines.Job? = null

    init {
        retryObservation()
    }

    fun retryObservation() {
        observation?.cancel()
        observation =
            viewModelScope.launch {
                state.update { it.copy(failed = false) }
                suspendRunCatching {
                    imports.observeOpenSessions().collect { sessions ->
                        val next =
                            sessions
                                .filter {
                                    val backupPreview =
                                        it.state == ImportState.PREVIEW &&
                                            it.format == ImportFormat.NEUTRODYNE_BACKUP
                                    it.state in OPEN_STATES && !backupPreview
                                }.minWithOrNull(compareBy(ImportSessionView::createdAt, ImportSessionView::id))
                        state.update { it.copy(session = next, failed = false) }
                    }
                }.onFailure { state.update { it.copy(session = null, failed = true) } }
            }
    }

    suspend fun discard(): Boolean {
        val session = state.value.session ?: return false
        if (session.state != ImportState.PREVIEW || state.value.busy) return false
        if (!appScope.coroutineContext.isActive) {
            userMessages.post(UiText.Res(Res.string.write_failed))
            return false
        }
        state.update { it.copy(busy = true) }
        return appScope
            .async {
                val result =
                    suspendRunCatching {
                        imports.discardPreview(session.id) == DiscardPreviewResult.DISCARDED
                    }.getOrDefault(false)
                if (!result) userMessages.post(UiText.Res(Res.string.write_failed))
                result
            }.also { job ->
                job.invokeOnCompletion { state.update { it.copy(busy = false) } }
            }.await()
    }

    fun acknowledgeMessage(id: Long) = userMessages.shown(id)

    private companion object {
        val OPEN_STATES = setOf(ImportState.PREVIEW, ImportState.COMMITTED, ImportState.FETCHING)
    }
}
