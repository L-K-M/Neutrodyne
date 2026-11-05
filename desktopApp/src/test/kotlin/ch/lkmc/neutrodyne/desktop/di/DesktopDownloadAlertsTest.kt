// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.di

import ch.lkmc.neutrodyne.core.common.DesktopNotification
import ch.lkmc.neutrodyne.core.common.DesktopNotifier
import ch.lkmc.neutrodyne.core.common.NotificationKind
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.DownloadError
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.SourceKind
import ch.lkmc.neutrodyne.core.model.WaitReason
import ch.lkmc.neutrodyne.core.testing.FakeDownloadController
import ch.lkmc.neutrodyne.desktop.notifications.DesktopDownloadAlerts
import ch.lkmc.neutrodyne.download.api.DownloadEntry
import ch.lkmc.neutrodyne.download.api.DownloadStatus
import ch.lkmc.neutrodyne.download.api.DownloadsOverview
import ch.lkmc.neutrodyne.download.api.StorageUsage
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopDownloadAlertsTest {
    @Test fun manualFailuresAndStoragePostOnceThenClearThroughTheExistingNotifier() =
        runTest {
            val posted = mutableListOf<DesktopNotification>()
            val cancelled = mutableSetOf<String>()
            val notifier =
                object : DesktopNotifier {
                    override suspend fun post(n: DesktopNotification) {
                        posted += n
                    }

                    override fun cancel(id: String) {
                        cancelled += id
                    }
                }
            val alerts = DesktopDownloadAlerts(lazy { FakeDownloadController() }, notifier, backgroundScope)
            val status =
                DownloadStatus(
                    1,
                    DownloadLane.MANUAL,
                    DownloadState.FAILED,
                    WaitReason.NONE,
                    0,
                    null,
                    null,
                    null,
                    null,
                    DownloadError.NOT_MEDIA,
                    null,
                    false,
                    false,
                    SourceKind.RSS_ENCLOSURE,
                    null,
                )
            val failed = DownloadEntry(status, 1, "Podcast", "Episode", ArtworkRef("art", null, 0), 0, false, false)
            val empty =
                DownloadsOverview(
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    StorageUsage("dir:default", 0, 0, 0, null, null, false),
                    emptySet(),
                )
            val full =
                failed.copy(
                    status = status.copy(state = DownloadState.QUEUED, lastError = DownloadError.STORAGE_FULL),
                )
            val overview = empty.copy(failed = listOf(failed), inProgress = listOf(full))
            repeat(2) { alerts.update(overview) }
            val kinds = posted.map { it.kind }
            assertEquals(listOf(NotificationKind.DOWNLOAD_FAILED, NotificationKind.STORAGE_FULL), kinds)
            assertTrue(posted.all { it.route == "neutrodyne://open/downloads" && it.title.isNotBlank() })
            alerts.update(empty)
            assertTrue(cancelled.containsAll(posted.map { it.id }))
            val auto = failed.copy(status = status.copy(lane = DownloadLane.AUTO))
            alerts.update(overview.copy(failed = listOf(auto)))
            assertEquals(1, posted.count { it.kind == NotificationKind.DOWNLOAD_FAILED })
        }
}
