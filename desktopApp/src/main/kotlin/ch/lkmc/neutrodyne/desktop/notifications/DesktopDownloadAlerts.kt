// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.notifications

import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.DesktopNotification
import ch.lkmc.neutrodyne.core.common.DesktopNotifier
import ch.lkmc.neutrodyne.core.common.NotificationKind
import ch.lkmc.neutrodyne.core.model.DownloadError
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.ui.DownloadStatusText
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.resolve
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.dl_err_failed
import ch.lkmc.neutrodyne.core.ui.resources.dl_err_storage
import ch.lkmc.neutrodyne.download.api.DownloadController
import ch.lkmc.neutrodyne.download.api.DownloadsOverview
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** In-app banners through the existing notifier; native delivery and tray policy belong to MD2. */
@ContributesIntoSet(AppScope::class)
@Inject
internal class DesktopDownloadAlerts(
    private val controller: Lazy<DownloadController>,
    private val notifier: DesktopNotifier,
    @param:ApplicationScope private val scope: CoroutineScope,
) : AppInitializer {
    override val order = 310
    private var failures = emptySet<Long>()
    private var storageFull = false

    override suspend fun run() {
        scope.launch { controller.value.observeAll().collect(::update) }
    }

    internal suspend fun update(overview: DownloadsOverview) {
        val failed =
            overview.failed.filter {
                it.status.lane == DownloadLane.MANUAL && it.status.state == DownloadState.FAILED
            }
        val ids = failed.map { it.status.episodeId }.toSet()
        if ((ids - failures).isNotEmpty()) {
            val entry = failed.first { it.status.episodeId !in failures }
            notifier.post(
                DesktopNotification(
                    FAILED_ID,
                    NotificationKind.DOWNLOAD_FAILED,
                    UiText.Res(Res.string.dl_err_failed).resolve(),
                    "${entry.episodeTitle}: ${DownloadStatusText.errorText(entry.status.lastError).resolve()}",
                    DOWNLOADS_ROUTE,
                ),
            )
        }
        if (ids.isEmpty()) notifier.cancel(FAILED_ID)
        failures = ids
        val full =
            overview.inProgress.firstOrNull {
                it.status.lane == DownloadLane.MANUAL && it.status.lastError == DownloadError.STORAGE_FULL
            }
        if (full != null && !storageFull) {
            notifier.post(
                DesktopNotification(
                    STORAGE_ID,
                    NotificationKind.STORAGE_FULL,
                    UiText.Res(Res.string.dl_err_storage).resolve(),
                    full.episodeTitle,
                    DOWNLOADS_ROUTE,
                ),
            )
        }
        if (full == null) notifier.cancel(STORAGE_ID)
        storageFull = full != null
    }

    private companion object {
        const val FAILED_ID = "download-failed"
        const val STORAGE_ID = "download-storage"
        const val DOWNLOADS_ROUTE = "neutrodyne://open/downloads"
    }
}
