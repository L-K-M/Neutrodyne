// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.Manifest
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.settings.YouTubeSettingKeys
import ch.lkmc.neutrodyne.core.testing.ReleaseSmoke
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.download.api.DeleteReason
import ch.lkmc.neutrodyne.download.api.RequestResult
import ch.lkmc.neutrodyne.youtube.api.AudioPref
import ch.lkmc.neutrodyne.youtube.api.EngineAvailability
import ch.lkmc.neutrodyne.youtube.api.ResolveResult
import ch.lkmc.neutrodyne.youtube.ytdlp.EnginePrepareWorker
import ch.lkmc.neutrodyne.youtube.ytdlp.YtxTestHooks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.Executor

/** Production graph, Binder, Python replay and chunked transfer on debug and minified release. */
@RunWith(AndroidJUnit4::class)
@ReleaseSmoke
class YouTubeReleaseSmokeTest {
    @Test fun resolvesAndDownloadsRecordedAudioThroughYtx() =
        runBlocking {
            assumeFalse(
                "CPython cannot load on x86_64 Android 8.x",
                Build.SUPPORTED_ABIS.first() == "x86_64" && Build.VERSION.SDK_INT < 28,
            )
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val app = instrumentation.targetContext.applicationContext as NeutrodyneApplication
            val graph = app.graph
            graph.databaseOpener.awaitOpen()
            val db = graph.databaseOpener.requireDatabase()
            val directory = app.noBackupFilesDir.resolve("ytx-test/${UUID.randomUUID()}").apply { mkdirs() }
            val downloads = graph.downloadController()
            val previousEnabled = graph.settingsRepository.get(YouTubeSettingKeys.ENGINE_ENABLED)
            val bytes = ByteArray(TOTAL_BYTES).also { "ftyp".encodeToByteArray().copyInto(it, 4) }
            val ranges = mutableListOf<String>()
            val server = MockWebServer()
            var episode: Long? = null
            var podcast: Long? = null
            var activity: ActivityScenario<MainActivity>? = null
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    instrumentation.uiAutomation.grantRuntimePermission(
                        app.packageName,
                        Manifest.permission.POST_NOTIFICATIONS,
                    )
                }
                graph.settingsRepository.set(YouTubeSettingKeys.ENGINE_ENABLED, false)
                withTimeout(10_000) {
                    graph.youtubeEngine.status.first { it.availability == EngineAvailability.DISABLED }
                }
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            val range = requireNotNull(request.headers["Range"])
                            synchronized(ranges) { ranges += range }
                            val (start, end) = range.removePrefix("bytes=").split('-').map(String::toInt)
                            return MockResponse
                                .Builder()
                                .code(206)
                                .addHeader("Content-Type", "audio/mp4")
                                .addHeader("Content-Range", "bytes $start-$end/${bytes.size}")
                                .body(Buffer().write(bytes, start, end - start + 1))
                                .build()
                        }
                    }
                server.start()
                val fixtureText =
                    instrumentation.context.assets
                        .open("recorded/resolve/http.json")
                        .bufferedReader()
                        .use { it.readText() }
                val fixture = JSONObject(fixtureText)
                val bodies = fixture.getJSONArray("bodies")
                for (index in 0 until bodies.length()) {
                    bodies.put(
                        index,
                        bodies
                            .getString(index)
                            .replace("https://media.example.invalid/audio", server.url("/audio").toString())
                            .replace("clen=1000", "clen=${bytes.size}")
                            .replace("\"contentLength\": \"1000\"", "\"contentLength\": \"${bytes.size}\""),
                    )
                }
                directory.resolve("http.json").writeText(fixture.toString())
                YtxTestHooks.arm(app, directory)
                graph.settingsRepository.set(YouTubeSettingKeys.ENGINE_ENABLED, true)
                withTimeout(10_000) { graph.youtubeCapabilities.capabilities.first { it.inAppPlayback } }
                prepare(app)
                graph.youtubeStreamResolver.invalidateAll()
                val result = graph.youtubeStreamResolver.resolveAudio(VIDEO, AudioPref())
                assertTrue(result is ResolveResult.Ok)
                assertEquals(bytes.size.toLong(), (result as ResolveResult.Ok).audio.contentLength)
                assertEquals(140, result.audio.itag)
                val podcastId =
                    db.podcastDao().insertPodcast(
                        podcastEntity(title = "Recorded YouTube") {
                            copy(
                                sourceType = SourceType.YOUTUBE_CHANNEL,
                                youtubeChannelId = "UCaaaaaaaaaaaaaaaaaaaaaA",
                                initialFetch = false,
                            )
                        },
                    )
                podcast = podcastId
                val episodeId =
                    db.episodeDao().insertStub(
                        episodeEntity(podcastId = podcastId, title = "Recorded audio") {
                            copy(externalMediaId = VIDEO)
                        },
                    )
                episode = episodeId
                activity = ActivityScenario.launch(MainActivity::class.java)
                val request =
                    withContext(Dispatchers.Main) {
                        downloads.request(listOf(episodeId), DownloadLane.MANUAL, allowMetered = true)
                    }
                assertTrue(request is RequestResult.Queued && request.queued == 1)
                withTimeout(60_000) {
                    while (db.downloadDao().byEpisode(episodeId)?.state != DownloadState.COMPLETED ||
                        graph.localMediaIndex.localUriOrNull(episodeId) == null
                    ) {
                        delay(100)
                    }
                }
                assertNotNull(graph.localMediaIndex.localUriOrNull(episodeId))
                val expectedRanges = listOf("bytes=0-10485759", "bytes=10485760-${bytes.lastIndex}")
                assertEquals(expectedRanges, synchronized(ranges) { ranges.toList() })
            } finally {
                episode?.let {
                    downloads.cancel(listOf(it))
                    downloads.delete(listOf(it), DeleteReason.LIFECYCLE)
                }
                podcast?.let { db.podcastDao().deleteCascade(it, System.currentTimeMillis()) }
                graph.settingsRepository.set(YouTubeSettingKeys.ENGINE_ENABLED, false)
                withTimeout(10_000) {
                    graph.youtubeEngine.status.first { it.availability == EngineAvailability.DISABLED }
                }
                YtxTestHooks.clear()
                graph.youtubeStreamResolver.invalidateAll()
                graph.settingsRepository.set(YouTubeSettingKeys.ENGINE_ENABLED, previousEnabled)
                activity?.close()
                server.close()
                directory.deleteRecursively()
            }
        }

    private suspend fun prepare(app: NeutrodyneApplication) {
        val factory = app.graph.workerFactory
        val executor = Executor { it.run() }
        val parameters =
            WorkerParameters(
                UUID.randomUUID(),
                Data.EMPTY,
                emptyList(),
                WorkerParameters.RuntimeExtras(),
                0,
                0,
                executor,
                Dispatchers.Default,
                WorkManagerTaskExecutor(executor),
                factory,
                { _, _, _ -> error("Unexpected progress") },
                { _, _, _ -> error("Unexpected foreground work") },
            )
        val worker =
            factory.createWorker(
                app,
                EnginePrepareWorker::class.java.name,
                parameters,
            ) as CoroutineWorker
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
    }

    private companion object {
        const val VIDEO = "aaaaaaaaaaA"
        const val TOTAL_BYTES = 10 * 1024 * 1024 + 32
    }
}
