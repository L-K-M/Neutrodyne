// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.DiagnosticsError
import ch.lkmc.neutrodyne.core.domain.DiagnosticsRepository
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsReport
import ch.lkmc.neutrodyne.core.model.settings.DiagnosticsSettingKeys
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.UserMessages
import ch.lkmc.neutrodyne.feature.settings.resources.Res
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_copy_done
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_export_cancelled
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_export_done
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_export_failed
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class DiagnosticsUiState(
    val report: DiagnosticsReport? = null,
    val verboseLogging: Boolean = false,
    val exporting: Boolean = false,
)

/** Settings › About › Diagnostics (08): snapshot, copy/share, DB export and verbose log. */
@ViewModelKey(DiagnosticsViewModel::class)
@ContributesIntoMap(AppScope::class)
@Inject
internal class DiagnosticsViewModel(
    private val diagnostics: DiagnosticsRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
) : ViewModel() {
    private val mutableState = MutableStateFlow(DiagnosticsUiState())
    val uiState = mutableState.asStateFlow()
    private val userMessages = UserMessages()
    val messages = userMessages.flow

    init {
        refresh()
        viewModelScope.launch {
            settings.observe(DiagnosticsSettingKeys.VERBOSE_LOG_UNTIL).collect { until ->
                mutableState.value = mutableState.value.copy(verboseLogging = until > clock.now())
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val report = suspendRunCatching { diagnostics.snapshot() }.getOrNull() ?: return@launch
            mutableState.value = mutableState.value.copy(report = report)
        }
    }

    /** The plain-text report for the caller's clipboard/share sheet (09's 64 KB cap). */
    fun plainTextReport(): String {
        val report = mutableState.value.report ?: return ""
        return diagnostics.toPlainText(report)
    }

    /** "Detailed log for 24 hours" (09): the sink's DEBUG window, not a log-level write. */
    fun setVerboseLogging(enabled: Boolean) {
        viewModelScope.launch {
            val until = if (enabled) clock.now() + VERBOSE_WINDOW_MS else 0L
            suspendRunCatching { settings.set(DiagnosticsSettingKeys.VERBOSE_LOG_UNTIL, until) }
        }
    }

    /**
     * [destination]: `null` on Android (the repository hands back the FileProvider share URI
     * the screen shares); the `file:` URI the desktop save dialog returned.
     */
    fun exportDatabaseCopy(
        destination: String?,
        onShared: (String) -> Unit,
    ) {
        if (mutableState.value.exporting) return
        mutableState.value = mutableState.value.copy(exporting = true)
        viewModelScope.launch {
            val outcome = diagnostics.exportDatabaseCopy(destination)
            mutableState.value = mutableState.value.copy(exporting = false)
            when (outcome) {
                is Outcome.Success -> {
                    onShared(outcome.value)
                    userMessages.post(UiText.Res(Res.string.diagnostics_export_done))
                }

                is Outcome.Failure ->
                    userMessages.post(
                        UiText.Res(
                            if (outcome.error == DiagnosticsError.CANCELLED) {
                                Res.string.diagnostics_export_cancelled
                            } else {
                                Res.string.diagnostics_export_failed
                            },
                        ),
                    )
            }
        }
    }

    fun copied() {
        viewModelScope.launch { userMessages.post(UiText.Res(Res.string.diagnostics_copy_done)) }
    }

    fun onMessageShown(id: Long) = userMessages.shown(id)

    private companion object {
        const val VERBOSE_WINDOW_MS = 24L * 3_600_000
    }
}
