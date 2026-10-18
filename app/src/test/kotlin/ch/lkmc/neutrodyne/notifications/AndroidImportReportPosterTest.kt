// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.notifications

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.core.net.toUri
import ch.lkmc.neutrodyne.MainActivity
import ch.lkmc.neutrodyne.core.domain.BackupError
import ch.lkmc.neutrodyne.core.domain.ImportScreenVisibility
import ch.lkmc.neutrodyne.core.model.ImportItemStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.setResourceReaderAndroidContext
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * `AndroidImportReportPoster` on the `import_backup` channel (05 §8, Restore): the DONE report
 * lands at ID 3001 under the session's `import-{id}` tag with a `neutrodyne://open/import/{id}`
 * deep link; the restore failure and foreign-snapshot offer sit at their own stable IDs.
 * `POST_NOTIFICATIONS` is self-checked — never requested — a revoked or disabled notice is
 * skipped, and a session's notice is suppressed while its own screen is the visible one.
 */
@OptIn(ExperimentalResourceApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AndroidImportReportPosterTest {
    private val application = RuntimeEnvironment.getApplication()
    private val manager = application.getSystemService(NotificationManager::class.java)
    private val shadowManager = shadowOf(manager)
    private val visibility = ImportScreenVisibility()
    private val poster = AndroidImportReportPoster(application, visibility, Dispatchers.Unconfined)

    @Before
    fun setUp() {
        Locale.setDefault(Locale.ENGLISH)
        setResourceReaderAndroidContext(application)
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowManager.setNotificationsEnabled(true)
    }

    @Test
    fun theDoneReportLandsAt3001WithTheSessionRoute() =
        runBlocking<Unit> {
            poster.postDone(
                5L,
                mapOf(
                    ImportItemStatus.SUBSCRIBED to 3,
                    ImportItemStatus.GONE to 1,
                ),
            )

            val posted = manager.activeNotifications.single()
            assertThat(posted.tag).isEqualTo("import-5")
            assertThat(posted.id).isEqualTo(ImportBackupChannels.ID_IMPORT_DONE)
            val notification = posted.notification
            assertThat(notification.channelId).isEqualTo(ImportBackupChannels.CHANNEL)
            assertThat(notification.flags and Notification.FLAG_AUTO_CANCEL).isNotEqualTo(0)
            assertThat(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE).isNotEqualTo(0)
            val intent = shadowOf(notification.contentIntent).savedIntent
            assertThat(intent.action).isEqualTo(Intent.ACTION_VIEW)
            assertThat(intent.data).isEqualTo("neutrodyne://open/import/5".toUri())
            assertThat(intent.component?.className).isEqualTo(MainActivity::class.qualifiedName)
        }

    @Test
    fun aVisibleSessionSuppressesOnlyItsOwnNotice() =
        runBlocking<Unit> {
            visibility.enter(5L)
            poster.postDone(5L, mapOf(ImportItemStatus.SUBSCRIBED to 1))
            poster.postDone(6L, mapOf(ImportItemStatus.SUBSCRIBED to 2))
            assertThat(manager.activeNotifications.map { it.tag }).containsExactly("import-6")

            visibility.leave(5L)
            poster.postDone(5L, mapOf(ImportItemStatus.SUBSCRIBED to 1))
            assertThat(manager.activeNotifications.map { it.tag })
                .containsExactly("import-5", "import-6")
        }

    @Test
    fun aRevokedOrDisabledNoticeIsSkippedNeverRequested() =
        runBlocking<Unit> {
            shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            poster.postDone(5L, mapOf(ImportItemStatus.SUBSCRIBED to 1))
            assertThat(manager.activeNotifications).isEmpty()

            shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            shadowManager.setNotificationsEnabled(false)
            poster.postDone(5L, mapOf(ImportItemStatus.SUBSCRIBED to 1))
            assertThat(manager.activeNotifications).isEmpty()
        }

    @Test
    fun theRestoreFailureAndForeignOfferUseTheirOwnIds() =
        runBlocking<Unit> {
            poster.postRestoreFailed(9L, auto = false, error = BackupError.NotABackup)
            poster.postForeignSnapshotPending()

            val byId = manager.activeNotifications.associateBy { it.id }
            val failed = byId.getValue(ImportBackupChannels.ID_RESTORE_FAILED)
            assertThat(failed.tag).isEqualTo("import-9")
            assertThat(shadowOf(failed.notification.contentIntent).savedIntent.data)
                .isEqualTo("neutrodyne://open/import/9".toUri())
            val foreign = byId.getValue(ImportBackupChannels.ID_FOREIGN_SNAPSHOT)
            assertThat(shadowOf(foreign.notification.contentIntent).savedIntent.data)
                .isEqualTo("neutrodyne://open/settings/backup".toUri())
        }

    @Test
    fun postingCreatesTheImportBackupChannelOnce() =
        runBlocking<Unit> {
            poster.postDone(5L, mapOf(ImportItemStatus.SUBSCRIBED to 1))
            val channel = manager.getNotificationChannel(ImportBackupChannels.CHANNEL)
            assertThat(channel).isNotNull()
            assertThat(channel.name.toString()).isEqualTo("Import and backup")
            assertThat(channel.importance).isEqualTo(NotificationManager.IMPORTANCE_DEFAULT)
        }

    @Test
    fun newerSnapshotsUseThePendingOfferIdAndKeepManualFailuresOnTheirReport() =
        runBlocking<Unit> {
            poster.postRestoreFailed(10L, auto = true, error = BackupError.NewerFormat(2))
            poster.postRestoreFailed(11L, auto = false, error = BackupError.NewerFormat(2))

            val byId = manager.activeNotifications.associateBy { it.id }
            val auto = byId.getValue(ImportBackupChannels.ID_FOREIGN_SNAPSHOT).notification
            assertThat(auto.extras.getString(Notification.EXTRA_TEXT)).contains("newer version")
            assertThat(shadowOf(auto.contentIntent).savedIntent.data)
                .isEqualTo("neutrodyne://open/settings/backup".toUri())
            val manual = byId.getValue(ImportBackupChannels.ID_RESTORE_FAILED).notification
            assertThat(manual.extras.getString(Notification.EXTRA_TEXT)).contains("newer version")
            assertThat(shadowOf(manual.contentIntent).savedIntent.data)
                .isEqualTo("neutrodyne://open/import/11".toUri())
        }
}
