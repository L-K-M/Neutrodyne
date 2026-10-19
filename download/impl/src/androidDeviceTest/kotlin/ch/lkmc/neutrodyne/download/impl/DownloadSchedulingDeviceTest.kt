// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import android.app.job.JobScheduler
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** AC3/AC10 API smoke only. The 20-minute/playback/reboot journeys need an external device
 * harness and an integrated W4/W5 engine; compilation is not execution evidence. */
@RunWith(AndroidJUnit4::class)
class DownloadSchedulingDeviceTest {
    @Test fun ac3PersistedUidtBuilderOnReal34Or36() {
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val job = JobSchedulerFacade(context).info(listOf(deviceRow()))
        assertTrue(job.isUserInitiated)
        assertTrue(job.isPersisted)
        assertEquals(JobSchedulerFacade.JOB_ID, job.id)
        val scheduler = context.getSystemService(JobScheduler::class.java).forNamespace(JobSchedulerFacade.NAMESPACE)
        assertEquals(JobSchedulerFacade.NAMESPACE, scheduler.namespace)
    }

    @Test fun ac10WorkerNotificationAndValidatedConstraintsOnRealPlatform() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val notification =
            DownloadNotifications(
                context,
                object : ch.lkmc.neutrodyne.core.common.Clock {
                    override fun now() = System.currentTimeMillis()

                    override fun elapsedRealtime() = android.os.SystemClock.elapsedRealtime()
                },
            ).progress()
        assertEquals(DownloadNotifications.CHANNEL, notification.channelId)
        val request = AndroidDownloadWork.lane(visible = false)
        assertEquals(Build.VERSION.SDK_INT >= 31, request.workSpec.expedited)
        if (Build.VERSION.SDK_INT >= 34) {
            assertThrows(IllegalStateException::class.java) {
                DownloadNotifications(
                    context,
                    object : ch.lkmc.neutrodyne.core.common.Clock {
                        override fun now() = 0L

                        override fun elapsedRealtime() = 0L
                    },
                ).foregroundInfo()
            }
        }
    }

    private fun deviceRow() =
        ch.lkmc.neutrodyne.core.database.DownloadEntity(
            1,
            ch.lkmc.neutrodyne.core.model.DownloadLane.MANUAL,
            ch.lkmc.neutrodyne.core.model.DownloadState.QUEUED,
            priority = 100,
            requestedAt = 0,
            sourceKind = ch.lkmc.neutrodyne.core.model.SourceKind.RSS_ENCLOSURE,
            sourceRef = "https://example.test/audio.mp3",
            rootId = "int",
            allowMetered = false,
        )
}
