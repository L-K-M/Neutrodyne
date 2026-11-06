// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsSection
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsSectionId
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsSeverity
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.root.ShowUserMessages
import ch.lkmc.neutrodyne.feature.settings.resources.Res
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_copy_report
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_export_db
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_refresh
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_section_app
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_section_background
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_section_database
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_section_desktop
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_section_downloads
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_section_jobs
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_section_log
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_section_notifications
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_section_parse_warnings
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_section_refresh
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_section_sync
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_section_youtube
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_title
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_verbose_log
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_verbose_log_summary
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Settings › About › Diagnostics (08): the section cards from `DiagnosticsRepository`'s
 * snapshot, "Copy report" to the clipboard, "Export database copy" through the platform
 * share/save seam and the 24-hour detailed-log switch.
 */
@Composable
internal fun DiagnosticsPage(vm: DiagnosticsViewModel = metroViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    ShowUserMessages(messages, vm::onMessageShown)

    val clipboard = LocalClipboardManager.current
    val actions = LocalPlatformActions.current
    val scope = rememberCoroutineScope()
    val diagnosticsTitle = stringResource(Res.string.diagnostics_title)

    Column(Modifier.fillMaxSize()) {
        NdTopAppBar(
            title = stringResource(Res.string.diagnostics_title),
            navigation = { SettingsBackButton() },
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsSwitchRow(
                title = stringResource(Res.string.diagnostics_verbose_log),
                summary = stringResource(Res.string.diagnostics_verbose_log_summary),
                checked = state.verboseLogging,
                onCheckedChange = vm::setVerboseLogging,
            )
            SettingsRow(
                icon = NdIcons.Refresh,
                title = stringResource(Res.string.diagnostics_refresh),
                summary = null,
                onClick = vm::refresh,
            )
            SettingsRow(
                icon = NdIcons.Article,
                title = stringResource(Res.string.diagnostics_copy_report),
                summary = null,
                enabled = state.report != null,
                onClick = {
                    val text = vm.plainTextReport()
                    if (text.isEmpty()) return@SettingsRow
                    val share = actions.share
                    if (share != null) {
                        share.shareText(text, diagnosticsTitle)
                    } else {
                        clipboard.setText(AnnotatedString(text))
                    }
                    vm.copied()
                },
            )
            SettingsRow(
                icon = NdIcons.Download,
                title = stringResource(Res.string.diagnostics_export_db),
                summary = null,
                enabled = !state.exporting,
                onClick = {
                    val share = actions.share
                    if (share != null) {
                        // Android: the repository mints the FileProvider URI; the sheet
                        // hands it out (09 Destinations).
                        vm.exportDatabaseCopy(null) { uri ->
                            share.shareFile(
                                uri,
                                "application/octet-stream",
                                uri.substringAfterLast('/'),
                            )
                        }
                    } else {
                        scope.launch {
                            val uri =
                                actions.saver.create(EXPORT_SUGGESTED_NAME, "application/octet-stream")
                            vm.exportDatabaseCopy(uri) { saved ->
                                actions.reveal?.reveal(saved)
                            }
                        }
                    }
                },
            )
            val report = state.report ?: return@Column
            for (section in report.sections) {
                DiagnosticsSectionCard(section)
            }
        }
    }
}

@Composable
private fun DiagnosticsSectionCard(section: DiagnosticsSection) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(sectionTitle(section.id)),
                style = MaterialTheme.typography.titleSmall,
            )
            for (line in section.lines) {
                val text = if (line.key.isEmpty()) line.value else "${line.key}: ${line.value}"
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        when (line.severity) {
                            DiagnosticsSeverity.PROBLEM -> MaterialTheme.colorScheme.error
                            DiagnosticsSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
                            DiagnosticsSeverity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
        }
    }
}

private fun sectionTitle(id: DiagnosticsSectionId) =
    when (id) {
        DiagnosticsSectionId.APP -> Res.string.diagnostics_section_app
        DiagnosticsSectionId.REFRESH -> Res.string.diagnostics_section_refresh
        DiagnosticsSectionId.BACKGROUND -> Res.string.diagnostics_section_background
        DiagnosticsSectionId.JOBS -> Res.string.diagnostics_section_jobs
        DiagnosticsSectionId.DOWNLOADS -> Res.string.diagnostics_section_downloads
        DiagnosticsSectionId.YOUTUBE -> Res.string.diagnostics_section_youtube
        DiagnosticsSectionId.SYNC -> Res.string.diagnostics_section_sync
        DiagnosticsSectionId.DESKTOP -> Res.string.diagnostics_section_desktop
        DiagnosticsSectionId.DATABASE -> Res.string.diagnostics_section_database
        DiagnosticsSectionId.NOTIFICATIONS -> Res.string.diagnostics_section_notifications
        DiagnosticsSectionId.PARSE_WARNINGS -> Res.string.diagnostics_section_parse_warnings
        DiagnosticsSectionId.LOG -> Res.string.diagnostics_section_log
    }

/** The desktop save dialog's suggestion; the exporter stamps the real name itself (02). */
private const val EXPORT_SUGGESTED_NAME = "neutrodyne-diagnostics.db"
