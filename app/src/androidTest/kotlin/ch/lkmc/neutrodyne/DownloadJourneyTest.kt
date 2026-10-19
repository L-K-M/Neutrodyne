// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.job.JobScheduler
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import ch.lkmc.neutrodyne.core.artwork.ArtworkKeys
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.database.ArtworkEntity
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.settings.BackupSettingKeys
import ch.lkmc.neutrodyne.core.testing.Nightly
import ch.lkmc.neutrodyne.core.testing.ReleaseSmoke
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.download.api.DeleteReason
import ch.lkmc.neutrodyne.download.api.RequestResult
import ch.lkmc.neutrodyne.download.api.RootChangeMode
import ch.lkmc.neutrodyne.download.api.RootChangeResult
import ch.lkmc.neutrodyne.playback.api.PlayResult
import ch.lkmc.neutrodyne.playback.api.StreamKind
import ch.lkmc.neutrodyne.playback.impl.NeutrodynePlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** E4 uses the production graph, HTTP family, disk, scheduler and Media3 service. */
@RunWith(AndroidJUnit4::class)
class DownloadJourneyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as NeutrodyneApplication
    private val graph get() = app.graph
    private val downloads get() = graph.downloadController()
    private val db get() = graph.databaseOpener.requireDatabase()
    private val device = UiDevice.getInstance(instrumentation)
    private val observation = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val server = MockWebServer()
    private val responses = ConcurrentHashMap<String, MockResponse>()
    private var serverClosed = false
    private var preserveBackupSeed = false
    private var scenario: ActivityScenario<MainActivity>? = null
    private var session: MediaController? = null
    private val ids = mutableListOf<Long>()
    private var cover: File? = null

    @Before fun start() =
        runBlocking {
            device.wakeUp()
            device.executeShellCommand("wm dismiss-keyguard")
            if (Build.VERSION.SDK_INT >= 33) {
                instrumentation.uiAutomation.grantRuntimePermission(
                    app.packageName,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
            }
            graph.databaseOpener.awaitOpen()
            observation.launch { graph.playbackStateSource.nowPlaying.collect {} }
            withContext(Dispatchers.Main) { graph.playbackController.dismiss() }
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        responses[request.url.encodedPath] ?: MockResponse.Builder().code(404).build()
                }
            server.start()
            scenario = ActivityScenario.launch(MainActivity::class.java)
        }

    @After fun stop() =
        runBlocking<Unit> {
            withContext(Dispatchers.Main) {
                graph.playbackController.dismiss()
                session?.release()
            }
            if (!preserveBackupSeed) {
                downloads.cancel(ids)
                downloads.delete(ids, DeleteReason.LIFECYCLE)
            }
            observation.cancel()
            scenario?.close()
            if (!serverClosed) server.close()
            cover?.delete()
            device.wakeUp()
        }

    @Test @ReleaseSmoke
    fun downloadServerOffPlaysLocalArtworkChaptersAndDefersDelete() =
        runBlocking {
            assertEquals(
                RootChangeResult.Applied,
                downloads.changeRoot("ext:primary", RootChangeMode.NEW_DOWNLOADS_ONLY),
            )
            val id = seed("chapters_id3.mp3", E4_REPEATS, rate = TransferRate.JOURNEY)
            request(id)
            await { db.downloadDao().byEpisode(id)?.state == DownloadState.DOWNLOADING }
            val root = checkNotNull(app.getExternalFilesDir(Environment.DIRECTORY_PODCASTS))
            val part = File(root, checkNotNull(db.downloadDao().byEpisode(id)?.tempPath))
            await { part.length() > 0 }
            val before = part.length()
            await { part.length() > before }
            await { db.downloadDao().byEpisode(id)?.state == DownloadState.COMPLETED }
            val row = checkNotNull(db.downloadDao().byEpisode(id))
            val media = File(checkNotNull(Uri.parse(row.finalUri).path))
            assertEquals("ext:primary", row.rootId)
            assertTrue(media.canonicalPath.startsWith(root.canonicalPath + File.separator))
            assertTrue(media.isFile && media.length() == row.downloadedBytes)
            assertTrue(media.length() >= E4_MIN_BYTES)
            assertNotNull(graph.localMediaIndex.localUriOrNull(id))
            // The publisher disappears before local chapter enrichment and the first playback start.
            server.close()
            serverClosed = true
            graph.chapterRepository().ensureLocalLoaded(id, checkNotNull(row.finalUri))
            assertTrue(
                graph
                    .chapterRepository()
                    .observe(id)
                    .first()
                    .isNotEmpty(),
            )
            play(id)
            assertEquals(
                StreamKind.LOCAL,
                graph.playbackStateSource.nowPlaying.value
                    ?.stream,
            )
            val artwork = withContext(Dispatchers.Main) { connect().mediaMetadata.artworkUri }
            assertNotNull(artwork)
            val bitmap = app.contentResolver.openInputStream(checkNotNull(artwork)).use(BitmapFactory::decodeStream)
            assertEquals(Color.BLUE, checkNotNull(bitmap).getPixel(0, 0))
            bitmap.recycle()
            downloads.delete(listOf(id), DeleteReason.USER)
            assertTrue("playing file survives row deletion", media.isFile)
            assertEquals(null, graph.localMediaIndex.localUriOrNull(id))
            withContext(Dispatchers.Main) { graph.playbackController.dismiss() }
            await { !media.exists() }
        }

    @Test @Nightly
    @SdkSuppress(minSdkVersion = 33, maxSdkVersion = 33)
    fun visibleApi33DownloadUsesDataSyncForegroundWorker() =
        runBlocking {
            val id = seed("cbr_128k_2s.mp3", 120, rate = TransferRate.THROTTLED)
            request(id)
            await {
                db
                    .downloadDao()
                    .byEpisode(id)
                    ?.runnerToken
                    ?.startsWith("work:") == true
            }
            await {
                app.getSystemService(NotificationManager::class.java).activeNotifications.any {
                    it.id == DOWNLOAD_NOTIFICATION &&
                        it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0
                }
            }
            val dump = device.executeShellCommand("dumpsys activity services ${app.packageName}")
            assertTrue(dump.contains("androidx.work.impl.foreground.SystemForegroundService"))
            // The service's ActivityManager record exposes the effective FGS type, not just a manifest bit.
            assertTrue(
                dump.contains("foregroundServiceType=0x00000001") ||
                    dump.contains("foregroundServiceType=1") || dump.contains("foregroundServiceType=0x1"),
            )
        }

    @Test @Nightly
    @SdkSuppress(minSdkVersion = 34, maxSdkVersion = 36)
    fun backgroundUidtBesidePlaybackForTwentyMinutes() =
        runBlocking {
            val playing = seed("cbr_128k_2s.mp3", 720)
            request(playing)
            await { db.downloadDao().byEpisode(playing)?.state == DownloadState.COMPLETED }
            play(playing)
            val downloading = seed("cbr_128k_2s.mp3", 900, rate = TransferRate.THROTTLED)
            request(downloading)
            await {
                db
                    .downloadDao()
                    .byEpisode(downloading)
                    ?.runnerToken
                    ?.startsWith("uidt:") == true
            }
            val job = app.getSystemService(JobScheduler::class.java).forNamespace("downloads").getPendingJob(1001)
            assertNotNull(job)
            assertTrue(checkNotNull(job).isUserInitiated)
            assertTrue(job.isPersisted)
            device.pressHome()
            device.sleep()
            val started = SystemClock.elapsedRealtime()
            var previous = position()
            val activeRow = checkNotNull(db.downloadDao().byEpisode(downloading))
            val root =
                if (activeRow.rootId == "int") {
                    File(app.filesDir, "downloads")
                } else {
                    checkNotNull(app.getExternalFilesDir(Environment.DIRECTORY_PODCASTS))
                }
            val part = File(root, checkNotNull(activeRow.tempPath))
            var previousBytes = part.length()
            while (SystemClock.elapsedRealtime() - started < TWENTY_MINUTES_MS) {
                delay(SAMPLE_MS)
                val row = checkNotNull(db.downloadDao().byEpisode(downloading))
                assertEquals(DownloadState.DOWNLOADING, row.state)
                assertTrue(checkNotNull(row.runnerToken).startsWith("uidt:"))
                assertTrue(
                    graph.playbackStateSource.nowPlaying.value
                        ?.isPlaying == true,
                )
                val next = position()
                assertTrue("playback must keep advancing", next > previous)
                previous = next
                val bytes = part.length()
                assertTrue("download must keep receiving bytes beside playback", bytes > previousBytes)
                previousBytes = bytes
                val notifications = app.getSystemService(NotificationManager::class.java).activeNotifications
                assertTrue(notifications.any { it.id == DOWNLOAD_NOTIFICATION })
                assertTrue(notifications.any { it.id == PLAYBACK_NOTIFICATION })
            }
        }

    /** Invoked directly, without Orchestrator data clearing, by bmgr-check.sh. */
    @Test @Nightly
    @SdkSuppress(minSdkVersion = 29)
    fun seedCompletedDownloadsForBackupTransport() =
        runBlocking {
            // Materialize the included portable PB, even on a fresh install with only default settings.
            assertEquals(
                Outcome.Success(Unit),
                graph.settingsRepository.set(BackupSettingKeys.AUTO_SNAPSHOT_ENABLED, true),
            )
            assertEquals(
                RootChangeResult.Applied,
                downloads.changeRoot("ext:primary", RootChangeMode.NEW_DOWNLOADS_ONLY),
            )
            val external = seed("cbr_128k_2s.mp3", 1)
            request(external)
            await { db.downloadDao().byEpisode(external)?.state == DownloadState.COMPLETED }
            assertEquals(RootChangeResult.Applied, downloads.changeRoot("int", RootChangeMode.NEW_DOWNLOADS_ONLY))
            val internal = seed("cbr_128k_2s.mp3", 1)
            request(internal)
            await { db.downloadDao().byEpisode(internal)?.state == DownloadState.COMPLETED }
            assertEquals("ext:primary", db.downloadDao().byEpisode(external)?.rootId)
            assertEquals("int", db.downloadDao().byEpisode(internal)?.rootId)
            assertEquals(
                RootChangeResult.Applied,
                downloads.changeRoot("ext:primary", RootChangeMode.NEW_DOWNLOADS_ONLY),
            )
            preserveBackupSeed = true
        }

    private suspend fun seed(
        asset: String,
        repeats: Int,
        rate: TransferRate = TransferRate.IMMEDIATE,
    ): Long {
        val frame =
            instrumentation.context.assets
                .open("media/$asset")
                .use { it.readBytes() }
        val body = Buffer().apply { repeat(repeats) { write(frame) } }
        responses["/episode-${ids.size}.mp3"] =
            MockResponse
                .Builder()
                .addHeader("Content-Type", "audio/mpeg")
                .addHeader("ETag", "\"m6a-fixture\"")
                .body(body)
                .apply { rate.bytesPerSecond?.let { throttleBody(it, 1, TimeUnit.SECONDS) } }
                .build()
        val artworkUrl = server.url("/cover.png").toString()
        val key = ArtworkKeys.forUrl(artworkUrl)
        val image = File(app.filesDir, "artwork/$key.png").also { cover = it }
        image.parentFile!!.mkdirs()
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        db.artworkDao().upsert(
            ArtworkEntity(
                key,
                url = artworkUrl,
                localPath = image.name,
                width = 32,
                height = 32,
                fetchedAt = System.currentTimeMillis(),
            ),
        )
        val podcast =
            db.podcastDao().insertPodcast(
                podcastEntity(title = "Download fixture", artworkKey = key) { copy(artworkUrl = artworkUrl) },
            )
        return db
            .episodeDao()
            .insertStub(
                episodeEntity(podcastId = podcast, title = "Offline fixture") {
                    copy(
                        enclosureUrl = server.url("/episode-${ids.size}.mp3").toString(),
                        enclosureType = "audio/mpeg",
                        durationMs = repeats * 2_000L,
                    )
                },
            ).also { ids += it }
    }

    private suspend fun request(id: Long) {
        val result =
            withContext(Dispatchers.Main) {
                downloads.request(listOf(id), DownloadLane.MANUAL, allowMetered = true)
            }
        assertTrue(result is RequestResult.Queued)
        assertEquals(1, (result as RequestResult.Queued).queued)
        assertTrue(result.rejected.isEmpty())
    }

    private suspend fun play(id: Long) {
        val result = withContext(Dispatchers.Main) { graph.playbackController.playDownloads(id) }
        assertEquals(PlayResult.Started, result)
        await {
            graph.playbackStateSource.nowPlaying.value
                ?.isPlaying == true
        }
        val before = position()
        await { position() > before + 200 }
    }

    private suspend fun connect(): MediaController =
        session ?: MediaController
            .Builder(
                app,
                SessionToken(app, ComponentName(app, NeutrodynePlaybackService::class.java)),
            ).buildAsync()
            .await()
            .also { session = it }

    private suspend fun position() = withContext(Dispatchers.Main) { connect().currentPosition }

    private suspend fun await(condition: suspend () -> Boolean) =
        withTimeout(WAIT_MS) {
            while (!condition()) delay(100)
        }

    private enum class TransferRate(
        val bytesPerSecond: Long?,
    ) {
        IMMEDIATE(null),
        JOURNEY(256 * 1024L),
        THROTTLED(THROTTLE_BYTES),
    }

    private companion object {
        // Repeat the 32,704-byte generated ID3 fixture past 5 MiB without a large APK asset.
        const val E4_REPEATS = 161
        const val E4_MIN_BYTES = 5 * 1024 * 1024L
        const val DOWNLOAD_NOTIFICATION = 2001
        const val PLAYBACK_NOTIFICATION = 4000
        const val WAIT_MS = 90_000L
        const val TWENTY_MINUTES_MS = 20 * 60_000L
        const val SAMPLE_MS = 15_000L
        const val THROTTLE_BYTES = 8_192L
    }
}
