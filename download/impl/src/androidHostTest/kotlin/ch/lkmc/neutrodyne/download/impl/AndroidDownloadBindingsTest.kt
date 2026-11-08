// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import ch.lkmc.neutrodyne.core.database.AndroidDatabaseFactory
import ch.lkmc.neutrodyne.core.database.DatabaseOpener
import ch.lkmc.neutrodyne.core.database.DownloadEntity
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.SourceKind
import ch.lkmc.neutrodyne.core.testing.FakeDownloadController
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The production [AndroidDownloadBindings] scheduler provider must feed queued() the live
 * capability flag: an eligible YouTube row schedules only while capabilities allow downloads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidDownloadBindingsTest {
    private val app = RuntimeEnvironment.getApplication()
    private val clock = TestClock()

    @Test fun boundSchedulerSchedulesYouTubeRowWhenCapabilitiesAllow() =
        runBlocking {
            val scheduler = boundScheduler()
            scheduler.ensureScheduled(DownloadLane.MANUAL)
            val work =
                WorkManager
                    .getInstance(app)
                    .getWorkInfosForUniqueWork(AndroidDownloadWork.LANE)
                    .get()
            assertEquals(listOf(WorkInfo.State.ENQUEUED), work.map { it.state })
        }

    @Test fun boundSchedulerLeavesYouTubeRowIdleWhenCapabilitiesDeny() =
        runBlocking {
            val scheduler = boundScheduler(allowDownloads = false)
            scheduler.ensureScheduled(DownloadLane.MANUAL)
            assertTrue(
                WorkManager
                    .getInstance(app)
                    .getWorkInfosForUniqueWork(AndroidDownloadWork.LANE)
                    .get()
                    .isEmpty(),
            )
        }

    private suspend fun boundScheduler(allowDownloads: Boolean = true): AndroidDownloadScheduler {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration
                .Builder()
                .setExecutor { it.run() }
                .setTaskExecutor { it.run() }
                .build(),
        )
        val opener =
            DatabaseOpener(
                AndroidDatabaseFactory(app),
                AndroidSQLiteDriver(),
                Dispatchers.IO,
                clock,
                false,
            )
        opener.awaitOpen()
        val db = opener.requireDatabase()
        val podcast = db.podcastDao().insertPodcast(podcastEntity())
        val episode = db.episodeDao().insertStub(episodeEntity(podcastId = podcast))
        db.downloadDao().insert(
            DownloadEntity(
                episode,
                DownloadLane.MANUAL,
                DownloadState.QUEUED,
                priority = 100,
                requestedAt = 0,
                sourceKind = SourceKind.YOUTUBE,
                sourceRef = "ytvideo0001",
                rootId = "int",
                allowMetered = true,
            ),
        )
        val capabilities = FakeYouTubeCapabilitiesSource()
        if (allowDownloads) {
            capabilities.capabilities.value =
                YouTubeCapabilities(
                    inAppPlayback = false,
                    downloads = true,
                    channelSearch = false,
                    enrichment = false,
                    backCatalogue = false,
                    externalReason = null,
                )
        }
        return AndroidDownloadBindings.scheduler(
            application = app,
            db = lazy { db },
            opener = opener,
            network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE),
            charging = DownloadChargingMonitor(app),
            capabilities = capabilities,
            visibility = DownloadAppVisibility(),
            queue = DownloadWorkQueue(app),
            runtime =
                lazy {
                    AndroidDownloadRuntime(
                        drain = { DrainOutcome.Empty },
                        reconcile = {},
                        stop = { _, _ -> },
                        wake = {},
                        controller = { FakeDownloadController() },
                    )
                },
            notifications = DownloadNotifications(app, clock),
            clock = clock,
            registry = LaneRegistry(),
            jobs = JobSchedulerFacade(app),
        )
    }
}
