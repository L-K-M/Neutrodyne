// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.model.ManualMeteredPolicy
import ch.lkmc.neutrodyne.core.ui.downloadSize
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.downloads_manual_metered
import ch.lkmc.neutrodyne.core.ui.resources.downloads_metered_always
import ch.lkmc.neutrodyne.core.ui.resources.downloads_metered_ask
import ch.lkmc.neutrodyne.core.ui.resources.downloads_metered_never
import ch.lkmc.neutrodyne.core.ui.resources.downloads_root
import ch.lkmc.neutrodyne.core.ui.resources.downloads_root_desktop
import ch.lkmc.neutrodyne.core.ui.resources.downloads_root_external
import ch.lkmc.neutrodyne.core.ui.resources.downloads_root_internal
import ch.lkmc.neutrodyne.core.ui.resources.downloads_root_new
import ch.lkmc.neutrodyne.core.ui.resources.downloads_root_removable
import ch.lkmc.neutrodyne.core.ui.resources.downloads_root_unavailable
import ch.lkmc.neutrodyne.core.ui.resources.downloads_settings
import ch.lkmc.neutrodyne.core.ui.root.ShowUserMessages
import ch.lkmc.neutrodyne.download.api.RootKind
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ManualDownloadSettings(
    platform: PlatformKind,
    onRoot: (String) -> Unit,
    viewModel: DownloadSettingsViewModel = metroViewModel(),
) {
    val roots by viewModel.roots.collectAsStateWithLifecycle()
    val rootId by viewModel.rootId.collectAsStateWithLifecycle()
    val metered by viewModel.metered.collectAsStateWithLifecycle()
    val messages by viewModel.userMessages.collectAsStateWithLifecycle()
    Column {
        if (platform == PlatformKind.ANDROID) {
            Text(stringResource(Res.string.downloads_root))
            roots.filter { platform == PlatformKind.ANDROID || it.kind == RootKind.DESKTOP_DEFAULT }.forEach { root ->
                val label =
                    root.label ?: stringResource(
                        when (root.kind) {
                            RootKind.PRIMARY_EXTERNAL -> Res.string.downloads_root_external
                            RootKind.INTERNAL -> Res.string.downloads_root_internal
                            RootKind.REMOVABLE -> Res.string.downloads_root_removable
                            RootKind.DESKTOP_DEFAULT, RootKind.DESKTOP_FOLDER -> Res.string.downloads_root_desktop
                        },
                    )
                SettingsRow(
                    icon =
                        if (rootId == root.rootId ||
                            platform == PlatformKind.DESKTOP
                        ) {
                            NdIcons.DownloadDone
                        } else {
                            NdIcons.Download
                        },
                    title = label,
                    summary =
                        if (!root.available) {
                            stringResource(Res.string.downloads_root_unavailable)
                        } else {
                            root.freeBytes?.let(::downloadSize)
                                ?: root.path
                        },
                    enabled = root.available && platform == PlatformKind.ANDROID,
                    onClick = { onRoot(root.rootId) },
                    modifier =
                        Modifier.semantics {
                            selected =
                                rootId == root.rootId || platform == PlatformKind.DESKTOP
                        },
                )
            }
            if (platform == PlatformKind.ANDROID) {
                Text(stringResource(Res.string.downloads_manual_metered))
                ManualMeteredPolicy.entries.forEach { policy ->
                    SettingsRow(
                        modifier = Modifier.semantics { selected = policy == metered },
                        icon = if (policy == metered) NdIcons.DownloadDone else NdIcons.Download,
                        title =
                            stringResource(
                                when (policy) {
                                    ManualMeteredPolicy.ASK -> Res.string.downloads_metered_ask
                                    ManualMeteredPolicy.ALWAYS -> Res.string.downloads_metered_always
                                    ManualMeteredPolicy.NEVER -> Res.string.downloads_metered_never
                                },
                            ),
                        summary = null,
                        onClick = { viewModel.setMetered(policy) },
                    )
                }
            }
        }
    }
    ShowUserMessages(messages, viewModel::onMessageShown)
}
