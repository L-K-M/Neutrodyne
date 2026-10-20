// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.runtime.Composable
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.components.NdBanner
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialogAction
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.download_allow
import ch.lkmc.neutrodyne.core.ui.resources.download_change_folder
import ch.lkmc.neutrodyne.core.ui.resources.download_delete
import ch.lkmc.neutrodyne.core.ui.resources.download_manage_storage
import ch.lkmc.neutrodyne.core.ui.resources.download_open_settings
import ch.lkmc.neutrodyne.core.ui.resources.download_show_folder
import ch.lkmc.neutrodyne.core.ui.resources.downloads_settings
import ch.lkmc.neutrodyne.download.api.DownloadNotice
import ch.lkmc.neutrodyne.download.api.DownloadsOverview
import org.jetbrains.compose.resources.stringResource

@Composable
public fun DownloadNotices(
    overview: DownloadsOverview,
    platform: PlatformKind,
    onAction: (DownloadNotice) -> Unit,
) {
    visibleDownloadNotices(
        overview.notices,
        platform,
    ).forEach { notice ->
        val action =
            when (notice) {
                DownloadNotice.DATA_SAVER,
                DownloadNotice.BACKGROUND_RESTRICTED,
                -> {
                    Res.string.download_open_settings
                }

                DownloadNotice.NOTIFICATIONS_OFF -> {
                    Res.string.download_allow
                }

                DownloadNotice.CAP_REACHED -> {
                    Res.string.downloads_settings
                }

                DownloadNotice.STORAGE_LOW -> {
                    if (platform ==
                        PlatformKind.DESKTOP
                    ) {
                        Res.string.download_show_folder
                    } else {
                        Res.string.download_manage_storage
                    }
                }

                DownloadNotice.ROOT_UNAVAILABLE -> {
                    if (platform ==
                        PlatformKind.DESKTOP
                    ) {
                        Res.string.download_change_folder
                    } else {
                        null
                    }
                }

                DownloadNotice.ORPHAN_FILES -> {
                    Res.string.download_delete
                }

                DownloadNotice.MOVE_IN_PROGRESS,
                DownloadNotice.YOUTUBE_PAUSED,
                -> {
                    null
                }
            }
        NdBanner(
            downloadNoticeText(
                notice,
                overview,
                platform,
            ).asString(),
            primary =
                action?.let {
                    NdDialogAction(
                        stringResource(it),
                        onClick = { onAction(notice) },
                    )
                },
        )
    }
}
