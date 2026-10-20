// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.content.ComponentName
import android.content.Intent
import android.media.AudioManager
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import ch.lkmc.neutrodyne.MainActivity
import ch.lkmc.neutrodyne.NeutrodyneApplication
import ch.lkmc.neutrodyne.core.database.EpisodeAltEnclosureEntity
import ch.lkmc.neutrodyne.core.testing.ReleaseSmoke
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.playback.api.PlayResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Real app graph/player/HTTP boundary. Audible listening requires a separate device report. */
@RunWith(AndroidJUnit4::class)
class VideoAsAudioTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as NeutrodyneApplication
    private val graph get() = app.graph
    private val device = UiDevice.getInstance(instrumentation)
    private val server = MockWebServer()
    private val paths = Collections.synchronizedList(mutableListOf<String>())
    private val audioDecoders = AtomicInteger()
    private val videoDecoders = AtomicInteger()

    @Volatile private var audioCounters: DecoderCounters? = null
    private var scenario: ActivityScenario<MainActivity>? = null
    private var controller: MediaController? = null
    private var podcast: Long? = null

    @Before fun prepare() =
        runBlocking {
            device.wakeUp()
            device.executeShellCommand("wm dismiss-keyguard")
            graph.databaseOpener.awaitOpen()
            graph.playbackController.dismiss()
            app.stopService(Intent(app, NeutrodynePlaybackService::class.java))
            // The hook is inert in production and attaches before any decoder can be initialized.
            PlaybackTestHooks.playerCreated = { player ->
                player.addAnalyticsListener(
                    object : AnalyticsListener {
                        override fun onAudioEnabled(
                            eventTime: AnalyticsListener.EventTime,
                            counters: DecoderCounters,
                        ) {
                            audioCounters =
                                counters
                        }

                        override fun onAudioDecoderInitialized(
                            eventTime: AnalyticsListener.EventTime,
                            decoderName: String,
                            initializedTimestampMs: Long,
                            initializationDurationMs: Long,
                        ) {
                            audioDecoders.incrementAndGet()
                        }

                        override fun onVideoDecoderInitialized(
                            eventTime: AnalyticsListener.EventTime,
                            decoderName: String,
                            initializedTimestampMs: Long,
                            initializationDurationMs: Long,
                        ) {
                            videoDecoders.incrementAndGet()
                        }
                    },
                )
            }
            server.start()
            scenario = ActivityScenario.launch(MainActivity::class.java)
        }

    @After fun close() =
        runBlocking {
            withContext(Dispatchers.Main) { controller?.release() }
            graph.playbackController.dismiss()
            app.stopService(Intent(app, NeutrodynePlaybackService::class.java))
            PlaybackTestHooks.playerCreated = null
            scenario?.close()
            device.wakeUp()
            server.close()
            podcast?.let {
                graph.databaseOpener.requireDatabase().podcastDao().deleteCascade(
                    it,
                    System.currentTimeMillis(),
                )
            }
        }

    @ReleaseSmoke
    @Test fun muxedH264AacAdvancesAudioWithScreenOffAndNoVideoDecoder() = runBlocking { prove(Alternate.NONE) }

    @ReleaseSmoke
    @Test fun videoRequestsAudioAlternateAndKeepsVideoMetadata() = runBlocking { prove(Alternate.AUDIO) }

    private enum class Alternate { NONE, AUDIO }

    private suspend fun prove(alternate: Alternate) {
        val video =
            instrumentation.context.assets
                .open("media/h264_aac_8s.mp4")
                .use { it.readBytes() }
        val mp3 =
            instrumentation.context.assets
                .open("media/cbr_128k_2s.mp3")
                .use { it.readBytes() }
        val audio = Buffer().apply { repeat(8) { write(mp3) } }.readByteArray()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    paths += path
                    val body = if (path == "/audio.mp3") audio else video
                    val offset =
                        request.headers["Range"]
                            ?.substringAfter("bytes=")
                            ?.substringBefore('-')
                            ?.toInt() ?: 0
                    if (offset >= body.size) return MockResponse(code = 416)
                    return MockResponse
                        .Builder()
                        .code(if (offset == 0) 200 else 206)
                        .addHeader("Content-Type", if (path == "/audio.mp3") "audio/mpeg" else "video/mp4")
                        .addHeader("Accept-Ranges", "bytes")
                        .apply {
                            if (offset >
                                0
                            ) {
                                addHeader("Content-Range", "bytes $offset-${body.lastIndex}/${body.size}")
                            }
                        }.body(Buffer().write(body, offset, body.size - offset))
                        .build()
                }
            }
        val db = graph.databaseOpener.requireDatabase()
        val p = db.podcastDao().insertPodcast(podcastEntity(title = "M5 video fixture")).also { podcast = it }
        val id =
            db.episodeDao().insertStub(
                episodeEntity(podcastId = p) {
                    copy(
                        isVideo = true,
                        enclosureUrl = server.url("/movie.mp4").toString(),
                        enclosureType = "video/mp4",
                        enclosureLength = video.size.toLong(),
                        durationMs = 8_000,
                    )
                },
            )
        if (alternate == Alternate.AUDIO) {
            db.ingestDao().replaceChildren(
                id,
                null,
                emptyList(),
                listOf(
                    EpisodeAltEnclosureEntity(
                        id,
                        0,
                        "audio/mpeg",
                        length = audio.size.toLong(),
                        sourcesJson = """[{"uri":"${server.url("/audio.mp3")}"}]""",
                    ),
                ),
                emptyList(),
                emptyList(),
                emptyList(),
            )
        }
        val next =
            if (alternate == Alternate.NONE) {
                val nextId =
                    db.episodeDao().insertStub(
                        episodeEntity(podcastId = p) {
                            copy(
                                isVideo = true,
                                enclosureUrl = server.url("/next.mp4").toString(),
                                enclosureType = "video/mp4",
                                durationMs = 8_000,
                            )
                        },
                    )
                graph.queueRepository().addNext(listOf(nextId))
                nextId
            } else {
                null
            }
        assertEquals(PlayResult.Started, graph.playbackController.playEpisode(id))
        val connected =
            withContext(Dispatchers.Main) {
                MediaController
                    .Builder(
                        app,
                        SessionToken(app, ComponentName(app, NeutrodynePlaybackService::class.java)),
                    ).buildAsync()
                    .await()
                    .also {
                        controller =
                            it
                    }
            }
        await {
            audioDecoders.get() > 0 &&
                withContext(Dispatchers.Main) { connected.isPlaying && connected.currentPosition > 300 }
        }
        assertTrue(db.episodeDao().byId(id)!!.isVideo)
        device.sleep()
        val before = withContext(Dispatchers.Main) { connected.currentPosition }
        await { withContext(Dispatchers.Main) { connected.currentPosition > before + 1_000 } }
        assertTrue(app.getSystemService(AudioManager::class.java).activePlaybackConfigurations.isNotEmpty())
        val counters = checkNotNull(audioCounters)
        counters.ensureUpdated()
        assertTrue("Audio buffers must reach the sink", counters.renderedOutputBufferCount > 0)
        if (next != null) {
            await {
                withContext(Dispatchers.Main) {
                    connected.currentMediaItem?.mediaId == "episode:$next" && connected.currentPosition > 300
                }
            }
        }
        assertEquals("Video decoder initialization must remain zero", 0, videoDecoders.get())
        if (alternate == Alternate.AUDIO) {
            assertTrue(paths.contains("/audio.mp3"))
            assertTrue(!paths.contains("/movie.mp4"))
        } else {
            assertTrue(paths.contains("/movie.mp4"))
        }
    }

    private suspend fun await(condition: suspend () -> Boolean) = withTimeout(20_000) { while (!condition()) delay(50) }
}
