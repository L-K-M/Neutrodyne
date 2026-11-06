// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialogAction
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsSection
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsSectionId
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsSeverity
import ch.lkmc.neutrodyne.core.ui.platform.FileSaver
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.RevealInFolder
import ch.lkmc.neutrodyne.core.ui.platform.ShareSheet
import ch.lkmc.neutrodyne.core.ui.platform.SystemSettingsTarget
import ch.lkmc.neutrodyne.core.ui.resources.action_cancel
import ch.lkmc.neutrodyne.core.ui.root.ShowUserMessages
import ch.lkmc.neutrodyne.feature.settings.resources.Res
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_battery_settings
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_copy_report
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_export_confirm_body
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_export_confirm_go
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_export_confirm_title
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_export_db
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_loading
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_open_log_folder
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_refresh
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_report_problem
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import ch.lkmc.neutrodyne.core.ui.resources.Res as CoreUiRes

/**
 * Settings › About › Diagnostics (08): the section cards from `DiagnosticsRepository`'s
 * snapshot, refresh in the top bar, and the bottom action row — "Copy diagnostics",
 * "Report a problem" (09's issue pre-fill), "Export database copy" behind its
 * confirmation — plus the 24-hour detailed-log switch.
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
    var confirmExport by rememberSaveable { mutableStateOf(false) }

    if (confirmExport) {
        NdDialog(
            onDismissRequest = { confirmExport = false },
            title = stringResource(Res.string.diagnostics_export_confirm_title),
            text = stringResource(Res.string.diagnostics_export_confirm_body),
            confirm =
                NdDialogAction(stringResource(Res.string.diagnostics_export_confirm_go)) {
                    confirmExport = false
                    exportDatabaseCopy(vm, actions.share, actions.saver, actions.reveal, scope)
                },
            dismiss =
                NdDialogAction(stringResource(CoreUiRes.string.action_cancel)) { confirmExport = false },
        )
    }

    Column(Modifier.fillMaxSize()) {
        NdTopAppBar(
            title = stringResource(Res.string.diagnostics_title),
            navigation = { SettingsBackButton() },
            actions = {
                IconButton(onClick = vm::refresh) {
                    Icon(
                        NdIcons.Refresh,
                        contentDescription = stringResource(Res.string.diagnostics_refresh),
                    )
                }
            },
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SettingsSwitchRow(
                title = stringResource(Res.string.diagnostics_verbose_log),
                summary = stringResource(Res.string.diagnostics_verbose_log_summary),
                checked = state.verboseLogging,
                onCheckedChange = vm::setVerboseLogging,
            )
            val report = state.report
            if (report == null) {
                Text(
                    stringResource(Res.string.diagnostics_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                for (section in report.sections) {
                    DiagnosticsSectionCard(
                        section,
                        onOpenBatterySettings =
                            actions.settings?.let { opener ->
                                { opener.open(SystemSettingsTarget.BATTERY_OPTIMIZATION) }
                            },
                        onOpenLogFolder = actions.reveal?.let { reveal -> { dir: String -> reveal.reveal(dir) } },
                    )
                }
            }
        }
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                enabled = state.report != null,
                onClick = {
                    val text = vm.plainTextReport()
                    if (text.isEmpty()) return@TextButton
                    val share = actions.share
                    if (share != null) {
                        share.shareText(text, diagnosticsTitle)
                    } else {
                        clipboard.setText(AnnotatedString(text))
                        vm.copied()
                    }
                },
            ) {
                Text(stringResource(Res.string.diagnostics_copy_report))
            }
            TextButton(
                enabled = state.report != null,
                onClick = {
                    // 09's flow: the full report goes to the clipboard, the encoded
                    // summary into the issue form — it stays on the clipboard for paste.
                    val url = vm.issueReportUrl() ?: return@TextButton
                    clipboard.setText(AnnotatedString(vm.plainTextReport()))
                    actions.urls.open(url)
                },
            ) {
                Text(stringResource(Res.string.diagnostics_report_problem))
            }
            TextButton(
                enabled = !state.exporting,
                onClick = { confirmExport = true },
            ) {
                Text(stringResource(Res.string.diagnostics_export_db))
            }
        }
    }
}

private fun exportDatabaseCopy(
    vm: DiagnosticsViewModel,
    share: ShareSheet?,
    saver: FileSaver,
    reveal: RevealInFolder?,
    scope: CoroutineScope,
) {
    if (share != null) {
        // Android: the repository mints the FileProvider URI; the sheet hands it out
        // (09 Destinations).
        vm.exportDatabaseCopy(null) { uri ->
            share.shareFile(uri, "application/octet-stream", uri.substringAfterLast('/'))
        }
    } else {
        scope.launch {
            val uri = saver.create(EXPORT_SUGGESTED_NAME, "application/octet-stream")
            vm.exportDatabaseCopy(uri) { saved -> reveal?.reveal(saved) }
        }
    }
}

@Composable
private fun DiagnosticsSectionCard(
    section: DiagnosticsSection,
    onOpenBatterySettings: (() -> Unit)?,
    onOpenLogFolder: ((String) -> Unit)?,
) {
    // 08: PARSE_WARNINGS and LOG stay collapsed until tapped; the rest is open.
    var expanded by
        rememberSaveable(section.id) {
            mutableStateOf(
                section.id != DiagnosticsSectionId.PARSE_WARNINGS &&
                    section.id != DiagnosticsSectionId.LOG,
            )
        }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(sectionTitle(section.id)),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(24.dp)) {
                    Icon(
                        NdIcons.ArrowForwardIos,
                        contentDescription = null,
                        // Right-pointing when collapsed, rotated to down when open.
                        modifier = Modifier.size(14.dp).rotate(if (expanded) 90f else 0f),
                    )
                }
            }
            if (expanded) {
                SelectionContainer {
                    Column {
                        for (line in section.lines) {
                            val text = if (line.key.isEmpty()) line.value else "${line.key}: ${line.value}"
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (line.severity == DiagnosticsSeverity.PROBLEM) {
                                    // 08: PROBLEM is an icon plus the error colour —
                                    // never colour alone.
                                    Icon(
                                        NdIcons.ErrorFilled,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                                Text(
                                    text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color =
                                        when (line.severity) {
                                            DiagnosticsSeverity.PROBLEM -> MaterialTheme.colorScheme.error
                                            DiagnosticsSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
                                            DiagnosticsSeverity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    modifier =
                                        Modifier.padding(
                                            start =
                                                if (line.severity ==
                                                    DiagnosticsSeverity.PROBLEM
                                                ) {
                                                    4.dp
                                                } else {
                                                    0.dp
                                                },
                                        ),
                                )
                            }
                        }
                    }
                }
                if (section.id == DiagnosticsSectionId.BACKGROUND && onOpenBatterySettings != null) {
                    TextButton(onClick = onOpenBatterySettings) {
                        Text(stringResource(Res.string.diagnostics_battery_settings))
                    }
                }
                if (section.id == DiagnosticsSectionId.DESKTOP && onOpenLogFolder != null) {
                    val logDir = section.lines.firstOrNull { it.key == "logDir" }?.value
                    if (logDir != null) {
                        TextButton(onClick = { onOpenLogFolder(logDir) }) {
                            Text(stringResource(Res.string.diagnostics_open_log_folder))
                        }
                    }
                }
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
