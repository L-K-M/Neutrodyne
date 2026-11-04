// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialogAction
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.model.ScopedSettingsView
import ch.lkmc.neutrodyne.core.model.SettingField
import ch.lkmc.neutrodyne.core.model.SettingSource
import ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import ch.lkmc.neutrodyne.core.ui.DownloadSettingsRows
import ch.lkmc.neutrodyne.core.ui.DownloadSettingsScope
import ch.lkmc.neutrodyne.core.ui.OrphanFilesDialog
import ch.lkmc.neutrodyne.core.ui.SettingsChoiceDialog
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.asString
import ch.lkmc.neutrodyne.core.ui.downloadSizeText
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.download_cap
import ch.lkmc.neutrodyne.core.ui.resources.download_change_folder
import ch.lkmc.neutrodyne.core.ui.resources.download_cleanup
import ch.lkmc.neutrodyne.core.ui.resources.download_default_folder
import ch.lkmc.neutrodyne.core.ui.resources.download_folder
import ch.lkmc.neutrodyne.core.ui.resources.download_leave
import ch.lkmc.neutrodyne.core.ui.resources.download_move
import ch.lkmc.neutrodyne.core.ui.resources.download_move_confirm
import ch.lkmc.neutrodyne.core.ui.resources.download_operation_failed
import ch.lkmc.neutrodyne.core.ui.resources.download_show_folder
import ch.lkmc.neutrodyne.core.ui.resources.download_unknown_space
import ch.lkmc.neutrodyne.core.ui.resources.download_unlimited
import ch.lkmc.neutrodyne.core.ui.resources.download_usage
import ch.lkmc.neutrodyne.core.ui.resources.downloads_settings
import ch.lkmc.neutrodyne.download.api.RootChangeMode
import ch.lkmc.neutrodyne.download.api.RootKind
import ch.lkmc.neutrodyne.feature.settings.resources.desktop_section_downloads
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import ch.lkmc.neutrodyne.feature.settings.resources.Res as SettingsRes

/**
 * Settings › Downloads (07 and 08 Downloads settings). On the desktop the page's "Downloads
 * folder" row only links to [SettingsPage.DESKTOP], where [DesktopDownloadFolderSection] renders
 * the folder itself; Android's storage-root selection stays here and shares 07's move dialog.
 */
@Composable
internal fun DownloadsSettingsPage(
    platform: PlatformKind,
    viewModel: DownloadsSettingsViewModel = metroViewModel(),
    youtubeSettings: @Composable () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    val roots by viewModel.roots.collectAsStateWithLifecycle()
    val folder by viewModel.folderDecision.collectAsStateWithLifecycle()
    val orphans by viewModel.orphanConfirmation.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    var capDialog by rememberSaveable { mutableStateOf(false) }
    val navigator = LocalAppNavigator.current
    Column(Modifier.fillMaxSize()) {
        NdTopAppBar(
            title = stringResource(Res.string.downloads_settings),
            navigation = {
                SettingsBackButton()
            },
        )
        Column(Modifier.verticalScroll(rememberScrollState())) {
            youtubeSettings()
            ManualDownloadSettings(platform, viewModel::selectRoot)
            DownloadSettingsRows(
                ScopedSettingsView(
                    state.values,
                    state.values,
                    SettingField.entries.associateWith {
                        SettingSource.AppDefault
                    },
                ),
                DownloadSettingsScope.APP,
                platform,
                viewModel::edit,
            )
            SettingsRow(
                NdIcons.Download,
                stringResource(Res.string.download_cap),
                if (state.capMb ==
                    0
                ) {
                    stringResource(Res.string.download_unlimited)
                } else {
                    downloadSizeText(state.capMb.toLong() * MIB).asString()
                },
                {
                    capDialog =
                        true
                },
            )
            storage?.let { usage ->
                Text(
                    stringResource(
                        Res.string.download_usage,
                        downloadSizeText(usage.usedBytes).asString(),
                        downloadSizeText(usage.partialBytes).asString(),
                        usage.freeBytes?.let { downloadSizeText(it).asString() }
                            ?: stringResource(Res.string.download_unknown_space),
                    ),
                )
                SettingsRow(
                    NdIcons.Close,
                    stringResource(Res.string.download_cleanup),
                    downloadSizeText(usage.orphanBytes).asString(),
                    viewModel::confirmOrphans,
                    enabled =
                        usage.orphanBytes > 0 && !working,
                )
                if (platform == PlatformKind.DESKTOP) {
                    SettingsRow(
                        NdIcons.Download,
                        stringResource(Res.string.download_folder),
                        roots
                            .firstOrNull {
                                it.rootId ==
                                    usage.rootId
                            }?.path,
                        { navigator.pushDetail(SettingsKey(SettingsPage.DESKTOP)) },
                    )
                }
            }
            error?.let {
                Text(
                    it.asString(),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
    if (capDialog) {
        SettingsChoiceDialog(
            NdIcons.Download,
            stringResource(Res.string.download_cap),
            DownloadSettingKeys.STORAGE_CAP_MB_VALUES.toList(),
            state.capMb,
            label = {
                if (it ==
                    0
                ) {
                    stringResource(Res.string.download_unlimited)
                } else {
                    downloadSizeText(it.toLong() * MIB).asString()
                }
            },
            onSelect = {
                capDialog = false
                viewModel.setCap(it)
            },
            onDismissRequest = { capDialog = false },
        )
    }
    FolderMoveDialog(folder, error, working, viewModel::changeFolder, viewModel::dismissFolder)
    orphans?.let {
        OrphanFilesDialog(
            it,
            working,
            viewModel::deleteOrphans,
            viewModel::dismissOrphans,
            error = error,
        )
    }
}

/**
 * The downloads-folder section of Settings › Desktop (11 Change folder; 08's Desktop page owns the
 * layout): the usage line of the current root, "Change folder…", "Use default folder" and "Show in
 * folder", the inline error line and 07's move confirmation dialog. [DesktopPage] renders it
 * through a slot so the page itself composes without a download graph; the view model binds
 * through `metroViewModel()` like the Downloads page's, scoped to the Desktop entry's store.
 */
@Composable
internal fun DesktopDownloadFolderSection(viewModel: DownloadsSettingsViewModel = metroViewModel()) {
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    val roots by viewModel.roots.collectAsStateWithLifecycle()
    val folder by viewModel.folderDecision.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    val actions = LocalPlatformActions.current
    val scope = rememberCoroutineScope()
    val folderTitle = stringResource(Res.string.download_change_folder)
    Column {
        Text(
            stringResource(SettingsRes.string.desktop_section_downloads),
            Modifier.padding(SECTION_HEADING_PADDING),
            style = MaterialTheme.typography.titleSmall,
        )
        storage?.let { usage ->
            Text(
                stringResource(
                    Res.string.download_usage,
                    downloadSizeText(usage.usedBytes).asString(),
                    downloadSizeText(usage.partialBytes).asString(),
                    usage.freeBytes?.let { downloadSizeText(it).asString() }
                        ?: stringResource(Res.string.download_unknown_space),
                ),
            )
            val current = roots.firstOrNull { it.rootId == usage.rootId }
            SettingsRow(
                NdIcons.Download,
                folderTitle,
                current?.path,
                {
                    scope.launch {
                        suspendRunCatching { actions.files.pickFolder(folderTitle) }
                            .onSuccess { it?.let(viewModel::chooseFolder) }
                            .onFailure {
                                viewModel.error.value =
                                    UiText.Res(Res.string.download_operation_failed)
                            }
                    }
                },
                enabled = !working,
            )
            if (current?.kind == RootKind.DESKTOP_FOLDER) {
                SettingsRow(
                    NdIcons.Download,
                    stringResource(Res.string.download_default_folder),
                    null,
                    viewModel::useDefaultFolder,
                    enabled = !working,
                )
            }
            current?.path?.let { path ->
                actions.reveal?.let { reveal ->
                    SettingsRow(
                        NdIcons.Download,
                        stringResource(Res.string.download_show_folder),
                        null,
                        {
                            if (!reveal.reveal(path)) {
                                viewModel.error.value =
                                    UiText
                                        .Res(Res.string.download_operation_failed)
                            }
                        },
                    )
                }
            }
        }
        error?.let {
            Text(
                it.asString(),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    FolderMoveDialog(folder, error, working, viewModel::changeFolder, viewModel::dismissFolder)
}

/** 07's "Move {n} downloads ({size}) to {folder}?" confirmation, shared by both entry points. */
@Composable
private fun FolderMoveDialog(
    decision: FolderDecision?,
    error: UiText?,
    working: Boolean,
    onConfirm: (RootChangeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    decision?.let { decision ->
        NdDialog(
            onDismissRequest = onDismiss,
            title = stringResource(Res.string.download_folder),
            confirm =
                if (working) {
                    null
                } else {
                    NdDialogAction(
                        stringResource(Res.string.download_move),
                        onClick = { onConfirm(RootChangeMode.MOVE_EXISTING) },
                    )
                },
            dismiss =
                if (working) {
                    null
                } else {
                    NdDialogAction(
                        stringResource(Res.string.download_leave),
                        onClick = { onConfirm(RootChangeMode.NEW_DOWNLOADS_ONLY) },
                    )
                },
        ) {
            Text(
                stringResource(
                    Res.string.download_move_confirm,
                    decision.count,
                    downloadSizeText(decision.bytes).asString(),
                    decision.root.path ?: decision.root.label.orEmpty(),
                ),
            )
            error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
        }
    }
}

private const val MIB = 1_048_576L

private val SECTION_HEADING_PADDING = 16.dp
