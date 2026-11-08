// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.DiagnosticsError
import ch.lkmc.neutrodyne.core.domain.DiagnosticsRepository
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.diagnostics.DESKTOP_LOG_DIR_KEY
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsReport
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsSection
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsSectionId
import ch.lkmc.neutrodyne.core.model.settings.DiagnosticsSettingKeys
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.UserMessages
import ch.lkmc.neutrodyne.feature.settings.resources.Res
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_copy_done
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_export_cancelled
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_export_done
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_export_failed
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_export_no_space
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
    private val buildInfo: BuildInfo,
    private val platformInfo: PlatformInfo,
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
            // The busy flag must come down even when the repository throws: reset it in
            // finally and report an unexpected throw as FAILED rather than crashing.
            val outcome =
                suspendRunCatching { diagnostics.exportDatabaseCopy(destination) }
                    .getOrElse { Outcome.Failure(DiagnosticsError.FAILED) }
            try {
                when (outcome) {
                    is Outcome.Success -> {
                        onShared(outcome.value)
                        userMessages.post(UiText.Res(Res.string.diagnostics_export_done))
                    }

                    is Outcome.Failure -> {
                        userMessages.post(
                            UiText.Res(
                                when (outcome.error) {
                                    DiagnosticsError.CANCELLED -> {
                                        Res.string.diagnostics_export_cancelled
                                    }

                                    DiagnosticsError.NOT_ENOUGH_SPACE -> {
                                        Res.string.diagnostics_export_no_space
                                    }

                                    else -> {
                                        Res.string.diagnostics_export_failed
                                    }
                                },
                            ),
                        )
                    }
                }
            } finally {
                mutableState.value = mutableState.value.copy(exporting = false)
            }
        }
    }

    /** The desktop save dialog can cancel before any database copy exists. */
    fun exportCancelled() {
        viewModelScope.launch { userMessages.post(UiText.Res(Res.string.diagnostics_export_cancelled)) }
    }

    /**
     * 09's "Report a problem": the screen copies the full `toPlainText` to the clipboard
     * itself (the caller does that), then opens the issue URL whose `diagnostics` field
     * carries the summary — the report without `LOG` and `PARSE_WARNINGS` and without the
     * DESKTOP log-directory row (an absolute home path), cut from the end until the
     * percent-encoded URL fits GitHub's unpublished limit. `null` without a report.
     */
    fun issueReportUrl(): String? {
        val report = mutableState.value.report ?: return null
        val summary =
            diagnostics.toPlainText(
                report.copy(
                    sections =
                        report.sections
                            .filter {
                                it.id != DiagnosticsSectionId.LOG && it.id != DiagnosticsSectionId.PARSE_WARNINGS
                            }.map(::withoutPrivateDesktopRows),
                ),
            )
        val base = "${buildInfo.repoUrl}$ISSUE_URL_BASE"
        var text = summary
        var encoded = percentEncode(text)
        while (base.length + encoded.length > ISSUE_URL_MAX_CHARS && text.isNotEmpty()) {
            text = text.dropLast((text.length / 8).coerceAtLeast(1))
            encoded = percentEncode(text + TRUNCATION_NOTE)
        }
        return base + encoded
    }

    /**
     * The issue pre-fill is a public surface: the DESKTOP log-directory row carries an absolute
     * path under the user's home, so it is dropped here while the local report keeps it.
     */
    private fun withoutPrivateDesktopRows(section: DiagnosticsSection): DiagnosticsSection =
        if (section.id != DiagnosticsSectionId.DESKTOP) {
            section
        } else {
            section.copy(lines = section.lines.filterNot { it.key == DESKTOP_LOG_DIR_KEY })
        }

    fun copied() {
        // 08's clipboard rule: Android 13+ shows its own copy confirmation; the desktop
        // and API <= 32 get ours.
        val sdk = platformInfo.androidSdkInt
        if (sdk != null && sdk > 32) return
        viewModelScope.launch { userMessages.post(UiText.Res(Res.string.diagnostics_copy_done)) }
    }

    fun onMessageShown(id: Long) = userMessages.shown(id)

    private companion object {
        const val VERBOSE_WINDOW_MS = 24L * 3_600_000
        const val ISSUE_URL_BASE = "/issues/new?template=bug.yml&diagnostics="
        const val ISSUE_URL_MAX_CHARS = 7_000
        const val TRUNCATION_NOTE =
            "(truncated — the full diagnostics are on your clipboard; paste them below)"
    }
}

/** RFC 3986 percent-encoding for the issue query value: unreserved stays, the rest is `%XX`. */
private fun percentEncode(value: String): String {
    val out = StringBuilder(value.length)
    for (byte in value.encodeToByteArray()) {
        val c = byte.toInt() and 0xFF
        if (c.toChar().isUnreserved()) {
            out.append(c.toChar())
        } else {
            out.append('%').append(c.toString(16).uppercase().padStart(2, '0'))
        }
    }
    return out.toString()
}

private fun Char.isUnreserved(): Boolean =
    this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' ||
        this == '-' || this == '_' || this == '.' || this == '~'
