// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.importexport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.BackupError
import ch.lkmc.neutrodyne.core.domain.BackupRepository
import ch.lkmc.neutrodyne.core.domain.ImportRepository
import ch.lkmc.neutrodyne.core.domain.RestoreProgress
import ch.lkmc.neutrodyne.core.model.BackupPreview
import ch.lkmc.neutrodyne.core.model.RestoreCategory
import ch.lkmc.neutrodyne.core.model.RestoreMode
import ch.lkmc.neutrodyne.core.model.RestoreRequest
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.playback.api.PlaybackController
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class RestoreUiState(
    val loading: Boolean = true,
    val preview: BackupPreview? = null,
    val error: BackupError? = null,
    val acquisitionError: UiText? = null,
    val busy: Boolean = false,
    val mode: RestoreMode = RestoreMode.MERGE,
    val categories: Set<RestoreCategory> = setOf(RestoreCategory.HISTORY, RestoreCategory.UP_NEXT),
    val confirmingReplace: Boolean = false,
    val requested: Boolean = false,
    val progress: RestoreProgress? = null,
)

/** Backup-only controller; normal import confirmation is never part of this path. */
internal class RestoreViewModel
    @AssistedInject
    constructor(
        @Assisted private val sessionId: Long,
        private val backups: BackupRepository,
        private val imports: ImportRepository,
        private val playback: PlaybackController,
        @ApplicationScope private val appScope: CoroutineScope,
    ) : ViewModel() {
        private val state = MutableStateFlow(RestoreUiState())
        val uiState = state.asStateFlow()

        init {
            // Inspection can scan the archive, so it shares the restore's application lifetime.
            appScope
                .async {
                    val result =
                        suspendRunCatching { backups.inspect(sessionId) }
                            .getOrElse { Outcome.Failure(BackupError.Io) }
                    state.update {
                        when (result) {
                            is Outcome.Success -> it.copy(preview = result.value)
                            is Outcome.Failure -> it.copy(error = result.error)
                        }
                    }
                }.invokeOnCompletion {
                    state.update { it.copy(loading = false) }
                }
            viewModelScope.launch {
                imports.observeSession(sessionId).collect { session ->
                    if (session?.options?.restoreRequestedAt == null) return@collect
                    state.update {
                        it.copy(
                            requested = true,
                            error = (it.progress as? RestoreProgress.Failed)?.error ?: it.error,
                        )
                    }
                }
            }
            viewModelScope.launch {
                backups.observeRestore().collect { progress ->
                    if (progress is RestoreProgress.Finished && progress.sessionId != sessionId) return@collect
                    state.update {
                        it.copy(
                            progress = progress,
                            error =
                                if (it.requested &&
                                    progress is RestoreProgress.Failed
                                ) {
                                    progress.error
                                } else {
                                    it.error
                                },
                        )
                    }
                }
            }
        }

        fun selectMode(mode: RestoreMode) {
            if (!editable() || mode == state.value.mode) return
            state.update {
                it.copy(
                    mode = mode,
                    categories =
                        if (mode == RestoreMode.REPLACE) {
                            RestoreCategory.entries.toSet()
                        } else {
                            setOf(RestoreCategory.HISTORY, RestoreCategory.UP_NEXT)
                        },
                )
            }
        }

        fun setCategory(
            category: RestoreCategory,
            selected: Boolean,
        ) {
            if (!editable()) return
            state.update { it.copy(categories = if (selected) it.categories + category else it.categories - category) }
        }

        suspend fun requestRestore() {
            if (!editable()) return
            if (state.value.mode == RestoreMode.REPLACE) {
                state.update { it.copy(confirmingReplace = true) }
                return
            }
            restore()
        }

        fun cancelReplace() {
            state.update { it.copy(confirmingReplace = false) }
        }

        suspend fun confirmReplace() {
            if (!state.value.confirmingReplace || state.value.busy) return
            state.update { it.copy(confirmingReplace = false) }
            restore()
        }

        suspend fun chooseAnother(pick: suspend () -> String?): Long? {
            if (state.value.busy) return null
            state.update { it.copy(busy = true) }
            return acquireBackup(imports, appScope, pick)
                .also { job ->
                    job.invokeOnCompletion { state.update { it.copy(busy = false) } }
                }.await()
                .let { outcome ->
                    when (outcome) {
                        is Outcome.Success -> {
                            outcome.value
                        }

                        is Outcome.Failure -> {
                            state.update { it.copy(acquisitionError = acquisitionErrorText(outcome.error)) }
                            null
                        }
                    }
                }
        }

        fun dismissAcquisitionError() {
            state.update { it.copy(acquisitionError = null) }
        }

        private fun editable(): Boolean =
            !state.value.loading && !state.value.busy &&
                !state.value.requested && !state.value.confirmingReplace && state.value.preview != null &&
                state.value.error == null

        private suspend fun restore() {
            val request = RestoreRequest(state.value.mode, state.value.categories.toSet())
            state.update { it.copy(busy = true, error = null, progress = null) }
            if (!appScope.coroutineContext.isActive) {
                state.update { it.copy(busy = false, error = BackupError.Io) }
                return
            }
            appScope
                .async {
                    // Playback is main-thread-only; enqueue stays in this application-owned job.
                    val outcome =
                        suspendRunCatching {
                            if (request.mode == RestoreMode.REPLACE) {
                                withContext(Dispatchers.Main.immediate) { playback.pause() }
                            }
                            backups.restore(sessionId, request)
                        }.getOrElse { Outcome.Failure(BackupError.Io) }
                    state.update {
                        when (outcome) {
                            is Outcome.Success -> {
                                it.copy(
                                    requested = true,
                                    error = (it.progress as? RestoreProgress.Failed)?.error,
                                )
                            }

                            is Outcome.Failure -> {
                                it.copy(error = outcome.error)
                            }
                        }
                    }
                }.also { job ->
                    // Runs even if cancellation prevents dispatching the body (D1).
                    job.invokeOnCompletion { state.update { it.copy(busy = false) } }
                }.await()
        }

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey(Factory::class)
        @ContributesIntoMap(AppScope::class)
        fun interface Factory : ManualViewModelAssistedFactory {
            fun create(sessionId: Long): RestoreViewModel
        }
    }
