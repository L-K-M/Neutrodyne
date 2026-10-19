// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import ch.lkmc.neutrodyne.core.ui.resources.download_desktop_settings
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
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DownloadsSettingsPage(
    platform: PlatformKind,
    page: SettingsPage = SettingsPage.DOWNLOADS,
    viewModel: DownloadsSettingsViewModel = metroViewModel(),
) {
    val desktopFolder = page == SettingsPage.DESKTOP
    val state by viewModel.state.collectAsStateWithLifecycle()
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    val roots by viewModel.roots.collectAsStateWithLifecycle()
    val folder by viewModel.folderDecision.collectAsStateWithLifecycle()
    val orphans by viewModel.orphanConfirmation.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    var capDialog by rememberSaveable { mutableStateOf(false) }
    val actions = LocalPlatformActions.current
    val scope = rememberCoroutineScope()
    val navigator = LocalAppNavigator.current
    val folderTitle = stringResource(Res.string.download_change_folder)
    Column(Modifier.fillMaxSize()) {
        NdTopAppBar(
            title =
                stringResource(
                    if (desktopFolder) Res.string.download_desktop_settings else Res.string.downloads_settings,
                ),
            navigation = {
                SettingsBackButton()
            },
        )
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (!desktopFolder) {
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
            }
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
                if (!desktopFolder) {
                    SettingsRow(
                        NdIcons.Close,
                        stringResource(Res.string.download_cleanup),
                        downloadSizeText(usage.orphanBytes).asString(),
                        viewModel::confirmOrphans,
                        enabled =
                            usage.orphanBytes > 0 && !working,
                    )
                }
                if (platform == PlatformKind.DESKTOP) {
                    if (!desktopFolder) {
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
                    } else {
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
    folder?.let { decision ->
        NdDialog(
            onDismissRequest = viewModel::dismissFolder,
            title = stringResource(Res.string.download_folder),
            confirm =
                if (working) {
                    null
                } else {
                    NdDialogAction(
                        stringResource(Res.string.download_move),
                        onClick = {
                            viewModel.changeFolder(RootChangeMode.MOVE_EXISTING)
                        },
                    )
                },
            dismiss =
                if (working) {
                    null
                } else {
                    NdDialogAction(
                        stringResource(Res.string.download_leave),
                        onClick = {
                            viewModel.changeFolder(RootChangeMode.NEW_DOWNLOADS_ONLY)
                        },
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

private const val MIB = 1_048_576L
