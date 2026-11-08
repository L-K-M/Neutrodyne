// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialogAction
import ch.lkmc.neutrodyne.core.designsystem.components.NdTextButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsSection
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsSectionId
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsSeverity
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.SystemSettingsTarget
import ch.lkmc.neutrodyne.core.ui.resources.action_cancel
import ch.lkmc.neutrodyne.core.ui.root.ShowUserMessages
import ch.lkmc.neutrodyne.feature.settings.resources.Res
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_battery_settings
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_copy_report
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_export_confirm
import ch.lkmc.neutrodyne.feature.settings.resources.diagnostics_export_confirm_body
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
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import ch.lkmc.neutrodyne.core.ui.resources.Res as UiRes

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
    // Set while the desktop save dialog is open: the ViewModel's `exporting` flag only
    // starts once `exportDatabaseCopy` runs, so the row needs this earlier guard.
    var saverInFlight by remember { mutableStateOf(false) }

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
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            // SpaceEvenly misbehaves under a scrollable's unbounded width; spacing + center
            // keeps the same spread when the buttons fit and a scroll when they do not.
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
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
                enabled = !state.exporting && !saverInFlight,
                onClick = { confirmExport = true },
            ) {
                Text(stringResource(Res.string.diagnostics_export_db))
            }
        }
    }

    // 09's pre-export statement, both platforms: only an explicit Export runs the
    // share sheet, the save dialog or the repository call.
    if (confirmExport) {
        DiagnosticsExportDialog(
            onDismissRequest = { confirmExport = false },
            icon = NdIcons.Download,
            title = stringResource(Res.string.diagnostics_export_db),
            text = stringResource(Res.string.diagnostics_export_confirm_body),
            confirm =
                NdDialogAction(stringResource(Res.string.diagnostics_export_confirm)) {
                    confirmExport = false
                    val share = actions.share
                    if (share != null) {
                        // Android: the repository mints the FileProvider URI; the sheet
                        // hands it out (09 Destinations).
                        vm.exportDatabaseCopy(null) { uri ->
                            share.shareFile(uri, EXPORT_MIME, uri.substringAfterLast('/'))
                        }
                    } else if (!saverInFlight) {
                        // A repeat confirm cannot stack a second save dialog.
                        saverInFlight = true
                        scope.launch {
                            try {
                                val uri = actions.saver.create(EXPORT_SUGGESTED_NAME, EXPORT_MIME)
                                if (uri.isNullOrBlank()) {
                                    vm.exportCancelled()
                                } else {
                                    vm.exportDatabaseCopy(uri) { saved ->
                                        actions.reveal?.reveal(saved)
                                    }
                                }
                            } finally {
                                saverInFlight = false
                            }
                        }
                    }
                },
            dismiss =
                NdDialogAction(stringResource(UiRes.string.action_cancel)) {
                    confirmExport = false
                },
        )
    }
}

/** The explanation and actions can scroll; the first keyboard action stays Cancel. */
@Composable
private fun DiagnosticsExportDialog(
    onDismissRequest: () -> Unit,
    icon: ImageVector,
    title: String,
    text: String,
    confirm: NdDialogAction,
    dismiss: NdDialogAction,
) {
    NdDialog(onDismissRequest = onDismissRequest, icon = icon) {
        val cancelFocus = remember { FocusRequester() }
        val windowFocused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(windowFocused) {
            if (windowFocused) {
                withFrameNanos { }
                cancelFocus.requestFocus()
            }
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(16.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(24.dp))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                NdTextButton(
                    dismiss.label,
                    dismiss.onClick,
                    modifier = Modifier.focusRequester(cancelFocus),
                )
                NdTextButton(confirm.label, confirm.onClick)
            }
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

private const val EXPORT_MIME = "application/octet-stream"
