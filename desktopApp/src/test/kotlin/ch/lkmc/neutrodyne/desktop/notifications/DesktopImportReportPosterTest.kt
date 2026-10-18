// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.notifications

import ch.lkmc.neutrodyne.core.common.DesktopNotification
import ch.lkmc.neutrodyne.core.common.DesktopNotifier
import ch.lkmc.neutrodyne.core.common.NotificationKind
import ch.lkmc.neutrodyne.core.domain.BackupError
import ch.lkmc.neutrodyne.core.domain.ImportScreenVisibility
import ch.lkmc.neutrodyne.core.model.ImportItemStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * 05 §8's desktop report: one notification per finished session — the Android tag
 * (`import-{sessionId}`) so a repost replaces, the "N added" and "N need attention" buckets,
 * and the `neutrodyne://open/import/{id}` route for the banner's "Open". OS-centre delivery
 * is MD2 — this test only pins what [DesktopImportReportPoster] hands `DesktopNotifier`.
 */
class DesktopImportReportPosterTest {
    private val notifier = RecordingNotifier()
    private val visibility = ImportScreenVisibility()
    private val poster = DesktopImportReportPoster(notifier, visibility)
    private lateinit var previousLocale: Locale

    @Before
    fun setUp() {
        // The shared test-task config sets the JVM to de_DE; pin English for the copy.
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @After
    fun tearDown() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun aDoneReportPostsItsBucketsAndRoute() =
        runTest {
            poster.postDone(
                7,
                mapOf(
                    ImportItemStatus.SUBSCRIBED to 2,
                    ImportItemStatus.ALREADY_SUBSCRIBED to 1,
                    ImportItemStatus.NOT_A_FEED to 1,
                    ImportItemStatus.FETCH_FAILED to 2,
                    ImportItemStatus.QUEUED to 1,
                ),
            )

            val notification = notifier.posts.single()
            assertThat(notification.id).isEqualTo("import-7")
            assertThat(notification.kind).isEqualTo(NotificationKind.IMPORT_BACKUP)
            assertThat(notification.title).isEqualTo("Import finished")
            assertThat(notification.body).isEqualTo("2 added, 3 need attention")
            assertThat(notification.route).isEqualTo("neutrodyne://open/import/7")
        }

    @Test
    fun aCleanReportLeavesOutTheAttentionHalf() =
        runTest {
            poster.postDone(9, mapOf(ImportItemStatus.SUBSCRIBED to 1))

            val notification = notifier.posts.single()
            assertThat(notification.body).isEqualTo("1 added")
        }

    @Test
    fun matchingExistingSubscriptionsDoesNotCountThemAsNewlyAdded() =
        runTest {
            poster.postDone(9, mapOf(ImportItemStatus.ALREADY_SUBSCRIBED to 2, ImportItemStatus.MERGED to 1))

            assertThat(notifier.posts.single().body).isEqualTo("0 added")
        }

    @Test
    fun visibleSessionsSuppressOnlyTheirOwnReports() =
        runTest {
            visibility.enter(7L)
            poster.postDone(7L, mapOf(ImportItemStatus.SUBSCRIBED to 1))
            poster.postRestoreFailed(7L, auto = false, error = BackupError.NotABackup)
            poster.postDone(8L, mapOf(ImportItemStatus.SUBSCRIBED to 1))

            assertThat(notifier.posts.map { it.id }).containsExactly("import-8")
        }

    @Test
    fun aRestoreFailureAndPendingOfferProduceRealBannerRoutes() =
        runTest {
            poster.postRestoreFailed(9L, auto = false, error = BackupError.NotABackup)
            poster.postForeignSnapshotPending()

            val failure = notifier.posts.first()
            assertThat(failure.title).isEqualTo("Restore failed")
            assertThat(failure.body).isEqualTo("The backup could not be restored.")
            assertThat(failure.route).isEqualTo("neutrodyne://open/import/9")
            assertThat(notifier.posts.last().route).isEqualTo("neutrodyne://open/settings/backup")
        }

    private class RecordingNotifier : DesktopNotifier {
        val posts = mutableListOf<DesktopNotification>()
        val cancelled = mutableListOf<String>()

        override suspend fun post(n: DesktopNotification) {
            posts += n
        }

        override fun cancel(id: String) {
            cancelled += id
        }
    }
}
