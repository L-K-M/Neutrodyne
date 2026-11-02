// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.importexport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.common.DateFormatter
import ch.lkmc.neutrodyne.core.designsystem.components.NdActionRole
import ch.lkmc.neutrodyne.core.designsystem.components.NdBanner
import ch.lkmc.neutrodyne.core.designsystem.components.NdButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialogAction
import ch.lkmc.neutrodyne.core.designsystem.components.NdTextButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.domain.BackupError
import ch.lkmc.neutrodyne.core.domain.RestoreProgress
import ch.lkmc.neutrodyne.core.model.BackupPreview
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.model.RestoreCategory
import ch.lkmc.neutrodyne.core.model.RestoreMode
import ch.lkmc.neutrodyne.core.navigation.ImportKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import ch.lkmc.neutrodyne.core.ui.NavBackButton
import ch.lkmc.neutrodyne.core.ui.asString
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_cancel
import ch.lkmc.neutrodyne.core.ui.resources.back
import ch.lkmc.neutrodyne.core.ui.resources.restore_action
import ch.lkmc.neutrodyne.core.ui.resources.restore_choose_another
import ch.lkmc.neutrodyne.core.ui.resources.restore_credentials
import ch.lkmc.neutrodyne.core.ui.resources.restore_download_allow
import ch.lkmc.neutrodyne.core.ui.resources.restore_download_cancel
import ch.lkmc.neutrodyne.core.ui.resources.restore_download_failed
import ch.lkmc.neutrodyne.core.ui.resources.restore_download_metered
import ch.lkmc.neutrodyne.core.ui.resources.restore_download_wifi
import ch.lkmc.neutrodyne.core.ui.resources.restore_history
import ch.lkmc.neutrodyne.core.ui.resources.restore_linked
import ch.lkmc.neutrodyne.core.ui.resources.restore_merge
import ch.lkmc.neutrodyne.core.ui.resources.restore_phase_episodes
import ch.lkmc.neutrodyne.core.ui.resources.restore_phase_library
import ch.lkmc.neutrodyne.core.ui.resources.restore_phase_other
import ch.lkmc.neutrodyne.core.ui.resources.restore_phase_validate
import ch.lkmc.neutrodyne.core.ui.resources.restore_preview
import ch.lkmc.neutrodyne.core.ui.resources.restore_progress
import ch.lkmc.neutrodyne.core.ui.resources.restore_queue
import ch.lkmc.neutrodyne.core.ui.resources.restore_redownload
import ch.lkmc.neutrodyne.core.ui.resources.restore_replace
import ch.lkmc.neutrodyne.core.ui.resources.restore_replace_effect
import ch.lkmc.neutrodyne.core.ui.resources.restore_replace_title
import ch.lkmc.neutrodyne.core.ui.resources.restore_settings
import ch.lkmc.neutrodyne.core.ui.resources.restore_title
import ch.lkmc.neutrodyne.core.ui.resources.restore_updates
import ch.lkmc.neutrodyne.core.ui.resources.restore_waiting
import ch.lkmc.neutrodyne.core.ui.resources.restore_warn_ignored_entries
import ch.lkmc.neutrodyne.core.ui.resources.restore_warn_skipped_lines
import ch.lkmc.neutrodyne.core.ui.resources.restore_written_android
import ch.lkmc.neutrodyne.core.ui.resources.restore_written_desktop
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun RestoreSessionRoute(
    sessionId: Long,
    importState: ImportUiState,
    onCancel: () -> Unit,
    report: @Composable () -> Unit,
    providedViewModel: RestoreViewModel? = null,
) {
    val viewModel =
        providedViewModel ?: assistedMetroViewModel<RestoreViewModel, RestoreViewModel.Factory> { create(sessionId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val actions = LocalPlatformActions.current
    val scope = rememberCoroutineScope()
    val requested = state.requested || importState.session?.options?.restoreRequestedAt != null
    val progress = state.progress
    val preview = importState.session?.state == ImportState.PREVIEW
    val running = requested && preview && progress !is RestoreProgress.Finished && state.error == null
    val failed = requested && progress is RestoreProgress.Failed && importState.session?.state == ImportState.CANCELLED
    // Import persistence can arrive before the restore VM projects the same error.
    val restoreError = state.error ?: (progress as? RestoreProgress.Failed)?.error?.takeIf { failed }
    // Persisted committed work already belongs to the report, even after progress-flow loss.
    if (!preview && !failed) {
        Column {
            if (state.redownloadIds.isNotEmpty()) {
                NdButton(
                    label = stringResource(Res.string.restore_redownload, state.redownloadIds.size),
                    onClick = { scope.launch { viewModel.redownload(requester = actions.notifications) } },
                    enabled = !state.busy,
                )
            }
            if (state.downloadFailed) Text(stringResource(Res.string.restore_download_failed))
            report()
            if (state.meteredDownload) {
                NdDialog(
                    title = stringResource(Res.string.restore_download_metered),
                    onDismissRequest = viewModel::dismissMeteredDownload,
                    confirm =
                        NdDialogAction(stringResource(Res.string.restore_download_allow)) {
                            scope.launch { viewModel.redownload(true, actions.notifications) }
                        },
                    dismiss =
                        NdDialogAction(
                            stringResource(Res.string.restore_download_cancel),
                            onClick = viewModel::dismissMeteredDownload,
                        ),
                ) {
                    NdTextButton(
                        label = stringResource(Res.string.restore_download_wifi),
                        onClick = { scope.launch { viewModel.redownload(false, actions.notifications) } },
                    )
                }
            }
        }
        return
    }
    Scaffold(topBar = {
        NdTopAppBar(title = stringResource(Res.string.restore_title), navigation = { NavBackButton() })
    }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                running -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        if (progress is RestoreProgress.Running) {
                            stringResource(
                                Res.string.restore_progress,
                                phaseText(progress.phase),
                                progress.done,
                                progress.total,
                            )
                        } else {
                            stringResource(Res.string.restore_waiting)
                        },
                    )
                }

                state.loading -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                restoreError != null || state.preview == null -> {
                    Text(backupErrorText(restoreError ?: BackupError.Io).asString())
                    NdButton(stringResource(Res.string.restore_choose_another), {
                        scope.launch {
                            viewModel
                                .chooseAnother { actions.files.pickFile(listOf("*/*"), listOf("zip")) }
                                ?.let { navigator.pushDetail(ImportKey(it.toString())) }
                        }
                    }, enabled = !state.busy)
                    if (restoreError is BackupError.NewerFormat) {
                        NdTextButton(
                            stringResource(Res.string.restore_updates),
                            { navigator.push(SettingsKey(SettingsPage.UPDATES)) },
                        )
                    }
                }

                !requested -> {
                    RestorePreview(state, viewModel::selectMode, viewModel::setCategory)
                    NdButton(stringResource(Res.string.restore_action), {
                        scope.launch { viewModel.requestRestore() }
                    }, enabled = !state.busy)
                    NdTextButton(stringResource(Res.string.action_cancel), onCancel, enabled = !state.busy)
                }
            }
        }
    }
    if (state.confirmingReplace) {
        val preview = state.preview ?: return
        NdDialog(
            onDismissRequest = viewModel::cancelReplace,
            title = stringResource(Res.string.restore_replace_title),
            text =
                stringResource(
                    Res.string.restore_replace_effect,
                    preview.localPodcastsNotInBackup,
                    preview.localGroupsNotInBackup,
                ),
            confirm =
                NdDialogAction(stringResource(Res.string.restore_replace), NdActionRole.DESTRUCTIVE) {
                    scope.launch { viewModel.confirmReplace() }
                },
            dismiss =
                NdDialogAction(
                    stringResource(Res.string.action_cancel),
                    onClick = viewModel::cancelReplace,
                ),
        )
    }
    state.acquisitionError?.let { error ->
        NdDialog(
            onDismissRequest = viewModel::dismissAcquisitionError,
            text = error.asString(),
            confirm = NdDialogAction(stringResource(Res.string.back), onClick = viewModel::dismissAcquisitionError),
        )
    }
}

@Composable
private fun RestorePreview(
    state: RestoreUiState,
    onMode: (RestoreMode) -> Unit,
    onCategory: (RestoreCategory, Boolean) -> Unit,
) {
    val preview = state.preview ?: return
    Column {
        PreviewSummary(preview)
        Column(Modifier.selectableGroup()) {
            RestoreMode.entries.forEach { mode ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(state.mode == mode, enabled = !state.busy, role = Role.RadioButton, onClick = {
                            onMode(mode)
                        })
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(state.mode == mode, onClick = null, enabled = !state.busy)
                    Text(
                        stringResource(
                            if (mode ==
                                RestoreMode.MERGE
                            ) {
                                Res.string.restore_merge
                            } else {
                                Res.string.restore_replace
                            },
                        ),
                    )
                }
            }
        }
        RestoreCategory.entries.forEach { category ->
            val selected = category in state.categories
            val label =
                when (category) {
                    RestoreCategory.HISTORY -> Res.string.restore_history
                    RestoreCategory.UP_NEXT -> Res.string.restore_queue
                    RestoreCategory.SETTINGS -> Res.string.restore_settings
                }
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(selected, enabled = !state.busy, role = Role.Checkbox, onValueChange = {
                        onCategory(category, it)
                    })
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(selected, onCheckedChange = null, enabled = !state.busy)
                Text(stringResource(label))
            }
        }
    }
}

@Composable
private fun PreviewSummary(preview: BackupPreview) {
    Column {
        Text(
            stringResource(
                Res.string.restore_preview,
                DateFormatter.date(preview.createdAt),
                preview.appVersionName,
                preview.podcasts,
                preview.groups,
                preview.played,
                preview.upNext,
                preview.settings,
            ),
        )
        when (preview.writtenOn?.lowercase()) {
            "android" -> Text(stringResource(Res.string.restore_written_android))
            "desktop", "windows", "macos", "linux" -> Text(stringResource(Res.string.restore_written_desktop))
        }
        preview.linkedServerHost?.let { Text(stringResource(Res.string.restore_linked, it)) }
        if (preview.includesCredentials) Text(stringResource(Res.string.restore_credentials))

        for (warning in preview.warnings) {
            val text =
                when (warning.code) {
                    "SKIPPED_LINES" -> {
                        pluralStringResource(
                            Res.plurals.restore_warn_skipped_lines,
                            warning.count,
                            warning.count,
                        )
                    }

                    "IGNORED_ENTRIES" -> {
                        pluralStringResource(
                            Res.plurals.restore_warn_ignored_entries,
                            warning.count,
                            warning.count,
                        )
                    }

                    else -> {
                        continue
                    }
                }
            NdBanner(message = text, icon = NdIcons.Info)
        }
    }
}

@Composable
private fun phaseText(phase: String): String =
    stringResource(
        when (phase) {
            "validate" -> Res.string.restore_phase_validate
            "library" -> Res.string.restore_phase_library
            "episodes" -> Res.string.restore_phase_episodes
            else -> Res.string.restore_phase_other
        },
    )
