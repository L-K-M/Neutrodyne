// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import ch.lkmc.neutrodyne.MainActivity
import ch.lkmc.neutrodyne.NeutrodyneApplication
import ch.lkmc.neutrodyne.core.artwork.ArtworkKeys
import ch.lkmc.neutrodyne.core.database.ArtworkEntity
import ch.lkmc.neutrodyne.core.database.ChapterEntity
import ch.lkmc.neutrodyne.core.model.ChapterSource
import ch.lkmc.neutrodyne.core.model.SleepTimerMode
import ch.lkmc.neutrodyne.core.model.SleepTimerState
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.StreamOnMetered
import ch.lkmc.neutrodyne.core.testing.Nightly
import ch.lkmc.neutrodyne.core.testing.ReleaseSmoke
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.playback.api.PlayResult
import ch.lkmc.neutrodyne.playback.api.PlaybackEvent
import ch.lkmc.neutrodyne.playback.api.PlaybackIssue
import ch.lkmc.neutrodyne.playback.api.StreamKind
import ch.lkmc.neutrodyne.pressHomeAndAwaitBackground
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import ch.lkmc.neutrodyne.BuildConfig as AppBuildConfig

/**
 * Compiled into the app test APK, explicitly hosted by NeutrodyneApplication and its real graph.
 * The enclosure server and platform-event inputs are controlled. Player/store/session/service are real.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackServiceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as NeutrodyneApplication
    private val graph get() = app.graph
    private val device = UiDevice.getInstance(instrumentation)
    private var scenario: ActivityScenario<MainActivity>? = null
    private var controller: MediaController? = null
    private val server = MockWebServer()
    private var hardening = false
    private var focus: AudioFocusRequest? = null
    private val audio get() = app.getSystemService(AudioManager::class.java)
    private val notifications get() = app.getSystemService(NotificationManager::class.java)
    private var ids = emptyList<Long>()
    private var cover: File? = null
    private var phase = "setup"
    private val observation = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Before fun startHost() =
        runBlocking {
            device.wakeUp()
            device.executeShellCommand("wm dismiss-keyguard")
            if (Build.VERSION.SDK_INT >= 33) {
                instrumentation.uiAutomation.grantRuntimePermission(
                    app.packageName,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
            }
            // Set before the service exists, never through a production preference.
            if (AppBuildConfig.DEBUG) PlaybackTestHooks.foregroundServiceTimeoutMs = 5_000
            observation.launch { graph.playbackStateSource.nowPlaying.collect {} }
            graph.databaseOpener.awaitOpen()
            graph.playbackController.dismiss()
            server.start()
            scenario = ActivityScenario.launch(MainActivity::class.java)
        }

    @After fun stopHost() =
        runBlocking<Unit> {
            focus?.let { audio.abandonAudioFocusRequest(it) }
            withContext(Dispatchers.Main) {
                graph.playbackController.pause()
                controller?.release()
                controller = null
            }
            graph.playbackController.dismiss()
            observation.cancel()
            scenario?.close()
            app.stopService(Intent(app, NeutrodynePlaybackService::class.java))
            if (AppBuildConfig.DEBUG) PlaybackTestHooks.foregroundServiceTimeoutMs = null
            if (hardening) device.executeShellCommand("cmd audio set-enable-hardening enable")
            device.wakeUp()
            server.close()
            cover?.delete()
        }

    @Test fun queueFirstStartSeekAndTransitionSave() =
        runCase {
            phase("queue/start")
            seed()
            graph.queueRepository().addLast(ids)
            start()
            assertEquals(ids.first(), nowId())
            withContext(Dispatchers.Main) { graph.playbackController.seekTo(8_000) }
            await { snapshotPosition() >= 8_000 }
            withContext(Dispatchers.Main) { graph.playbackController.skipToNext() }
            await {
                nowId() == ids[1] && graph.playbackStateSource.nowPlaying.value
                    ?.isPlaying == true
            }
            val saved =
                graph.databaseOpener
                    .requireDatabase()
                    .positionDao()
                    .byEpisode(ids.first())
            assertNotNull("outgoing position is durable before arrival", saved)
            assertTrue(checkNotNull(saved).positionMs >= 8_000)
            assertEquals(
                ids[1],
                graph
                    .playSessionStore()
                    .virtualQueue(0)
                    .current
                    ?.episodeId,
            )
            assertTrue(
                graph
                    .playSessionStore()
                    .virtualQueue(0)
                    .upNext
                    .isEmpty(),
            )
        }

    @Test @ReleaseSmoke
    fun notificationTransportAndLocalArtwork() =
        runCase {
            phase("notification/start")
            seed()
            graph.queueRepository().addLast(ids)
            start()
            val notification = awaitNotification(PLAYBACK_NOTIFICATION)
            assertEquals("playback", notification.channelId)
            assertTrue(notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
            val metadata = withContext(Dispatchers.Main) { connect().mediaMetadata }
            val artwork = checkNotNull(metadata.artworkUri)
            assertEquals("content", artwork.scheme)
            // ArtworkProviderTest isolates network checks; this server also serves UI/audio.
            val pinned = app.contentResolver.openInputStream(artwork).use(BitmapFactory::decodeStream)
            assertNotNull("the session exposes the pinned cover", pinned)
            assertEquals(Color.BLUE, checkNotNull(pinned).getPixel(0, 0))
            pinned.recycle()
            checkNotNull(cover).delete()
            val fallback = app.contentResolver.openInputStream(artwork).use(BitmapFactory::decodeStream)
            assertNotNull("missing cover returns a local monogram", fallback)
            assertTrue(checkNotNull(fallback).getPixel(0, 0) != Color.BLUE)
            fallback.recycle()
            tapNotification("Pause")
            await {
                graph.playbackStateSource.nowPlaying.value
                    ?.isPlaying == false
            }
            tapNotification("Play")
            awaitAudio()
        }

    @Test @ReleaseSmoke
    fun sleepEndOfEpisodeKeepsQueueAndMarksPlayedOnce() =
        runCase {
            phase("sleep-eoe/start")
            seed()
            graph.queueRepository().addLast(ids)
            val events = CopyOnWriteArrayList<PlaybackEvent>()
            val collecting =
                observation.launch { graph.playbackStateSource.events.collect { events += it } }
            try {
                start()
                graph.playbackController.setSleepTimer(SleepTimerMode.EndOfEpisode)
                await {
                    graph.playbackStateSource.sleepTimer.value ==
                        SleepTimerState.EndOfEpisode(ids.first())
                }
                withContext(Dispatchers.Main) {
                    val live = connect()
                    live.seekTo(live.duration - 1_000)
                }
                await { events.contains(PlaybackEvent.SleepTimerFired) }
                val db = graph.databaseOpener.requireDatabase()
                await { db.episodeStateDao().byEpisode(ids.first())?.playedAt != null }
                delay(SETTLE_MS)
                assertEquals(ids.first(), nowId())
                assertEquals(
                    listOf(ids[1]),
                    graph
                        .playSessionStore()
                        .virtualQueue(0)
                        .upNext
                        .map { it.episodeId },
                )
                assertEquals(1, events.count { it == PlaybackEvent.SleepTimerFired })
                assertEquals(1, events.count { it == PlaybackEvent.MarkedPlayed(ids.first()) })
                assertEquals(SleepTimerState.Off, graph.playbackStateSource.sleepTimer.value)
                withContext(Dispatchers.Main) {
                    assertFalse(connect().playWhenReady)
                    assertEquals(1f, connect().volume)
                }
            } finally {
                collecting.cancel()
            }
        }

    // The metered next item holds the arbiter's other reason after the timer's reason clears.
    @Test fun sleepEndOfEpisodeRespectsMeteredBoundary() =
        runCase {
            phase("metered/start")
            seed()
            graph.queueRepository().addLast(ids)
            start()
            // The stream source is loopback, so it survives the wifi->cellular handoff.
            device.executeShellCommand("svc data enable")
            device.executeShellCommand("svc wifi disable")
            graph.settingsRepository.set(PlaybackSettingKeys.STREAM_ON_METERED, StreamOnMetered.NEVER)
            try {
                val connectivity = app.getSystemService(ConnectivityManager::class.java)
                phase("metered/await-metered")
                await {
                    connectivity
                        .getNetworkCapabilities(connectivity.activeNetwork)
                        ?.let {
                            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                                !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                        } == true
                }
                val events = CopyOnWriteArrayList<PlaybackEvent>()
                val collecting =
                    observation.launch { graph.playbackStateSource.events.collect { events += it } }
                try {
                    graph.playbackController.setSleepTimer(SleepTimerMode.EndOfEpisode)
                    await {
                        graph.playbackStateSource.sleepTimer.value ==
                            SleepTimerState.EndOfEpisode(ids.first())
                    }
                    withContext(Dispatchers.Main) {
                        val live = connect()
                        live.seekTo(live.duration - 1_000)
                    }
                    await { events.contains(PlaybackEvent.SleepTimerFired) }
                    val db = graph.databaseOpener.requireDatabase()
                    await { db.episodeStateDao().byEpisode(ids.first())?.playedAt != null }
                    await {
                        graph.playbackStateSource.nowPlaying.value
                            ?.issue == PlaybackIssue.METERED_PAUSE
                    }
                    // Had the timer cleared pauseAtEndOfMediaItems outright, the next item plays.
                    val holdUntil = SystemClock.elapsedRealtime() + SETTLE_MS
                    while (SystemClock.elapsedRealtime() < holdUntil) {
                        assertEquals(ids.first(), nowId())
                        delay(100)
                    }
                    withContext(Dispatchers.Main) { assertFalse(connect().playWhenReady) }
                    assertEquals(1, events.count { it == PlaybackEvent.MarkedPlayed(ids.first()) })
                } finally {
                    collecting.cancel()
                }
            } finally {
                graph.settingsRepository.set(
                    PlaybackSettingKeys.STREAM_ON_METERED,
                    PlaybackSettingKeys.STREAM_ON_METERED.default,
                )
                device.executeShellCommand("svc wifi enable")
                device.executeShellCommand("svc data enable")
            }
        }

    @Test @ReleaseSmoke
    fun chaptersAndTimestampSeekThroughRealSession() =
        runCase {
            phase("chapters/seed")
            // A generated long stream makes the 12:34 timestamp a valid seek without a large asset.
            seed(repeats = TIMESTAMP_STREAM_REPEATS)
            val db = graph.databaseOpener.requireDatabase()
            db.chapterDao().replace(
                ids.first(),
                ChapterSource.PSC,
                listOf(
                    ChapterEntity(ids.first(), ChapterSource.PSC, 0, 0, title = "Opening"),
                    ChapterEntity(ids.first(), ChapterSource.PSC, 1, 30_000, title = "Second"),
                ),
            )
            assertEquals(PlayResult.Started, graph.playbackController.playEpisode(ids.first()))
            awaitAudio()
            await {
                graph.playbackStateSource.currentChapter.value
                    ?.index == 0
            }
            withContext(Dispatchers.Main) { graph.playbackController.nextChapter() }
            await {
                graph.playbackStateSource.currentChapter.value
                    ?.index == 1
            }
            // Pin the position so previous picks a deterministic branch: under three
            // seconds into the chapter rewinds to the prior chapter; over it restarts.
            withContext(Dispatchers.Main) {
                connect().pause()
                graph.playbackController.seekTo(30_500)
            }
            withContext(Dispatchers.Main) { graph.playbackController.previousChapter() }
            await { snapshotPosition() < 3_000 }
            withContext(Dispatchers.Main) { graph.playbackController.seekTo(35_000) }
            await { snapshotPosition() >= 34_000 }
            withContext(Dispatchers.Main) { graph.playbackController.previousChapter() }
            await { snapshotPosition() in 29_000..31_000 }
            phase("chapters/timestamp")
            withContext(Dispatchers.Main) { connect().play() }
            awaitAudio()
            withContext(Dispatchers.Main) { graph.playbackController.seekTo(TIMESTAMP_SEEK_MS) }
            await {
                snapshotPosition() in
                    TIMESTAMP_SEEK_MS..TIMESTAMP_SEEK_MS + SEEK_TOLERANCE_MS
            }
            assertTrue(withContext(Dispatchers.Main) { connect().playWhenReady })
            assertEquals(
                PlayResult.Started,
                graph.playbackController.playEpisodeAt(ids[1], TIMESTAMP_SEEK_MS),
            )
            await {
                nowId() == ids[1] &&
                    snapshotPosition() in
                    TIMESTAMP_SEEK_MS..TIMESTAMP_SEEK_MS + SEEK_TOLERANCE_MS
            }
            assertTrue(withContext(Dispatchers.Main) { connect().playWhenReady })
        }

    @Test @Nightly
    fun screenOffListeningTimerExpiresAndRestoresVolume() =
        runCase {
            phase("sleep-off/start")
            seed()
            graph.queueRepository().addLast(ids)
            start()
            val live = withContext(Dispatchers.Main) { connect() }
            val timerStarted = SystemClock.elapsedRealtime()
            graph.playbackController.setSleepTimer(SleepTimerMode.Minutes(1))
            await { graph.playbackStateSource.sleepTimer.value is SleepTimerState.Running }
            backgroundHost()
            device.sleep()
            var faded = false
            withTimeout(TIMER_EXPIRY_MS) {
                while (graph.playbackStateSource.sleepTimer.value != SleepTimerState.Off) {
                    faded = faded || withContext(Dispatchers.Main) { live.volume < 1f }
                    delay(250)
                }
            }
            assertTrue("The last-ten-second fade must lower the player volume", faded)
            assertTrue(SystemClock.elapsedRealtime() - timerStarted >= 60_000)
            assertFalse(device.isScreenOn)
            assertEquals(ids.first(), nowId())
            withContext(Dispatchers.Main) {
                assertFalse(live.playWhenReady)
                assertEquals(1f, live.volume)
            }
        }

    @Test @Nightly
    fun embeddedId3NeroAndQuickTimeChaptersReachSession() =
        runCase {
            for ((asset, source) in EMBEDDED_CHAPTER_FIXTURES) {
                phase("embedded/$asset")
                seed(repeats = 1, asset = asset)
                withContext(Dispatchers.Main) {
                    val live = connect()
                    live.setMediaItem(
                        MediaItem
                            .Builder()
                            .setMediaId("episode:${ids.first()}")
                            .build(),
                    )
                    live.prepare()
                }
                await {
                    graph.playbackStateSource.currentChapter.value
                        ?.let {
                            it.episodeId == ids.first() &&
                                it.count == 2 &&
                                it.chapter.source == source
                        } == true
                }
                assertFalse(withContext(Dispatchers.Main) { connect().playWhenReady })
                withContext(Dispatchers.Main) { graph.playbackController.nextChapter() }
                await { snapshotPosition() in 1_000..1_100 }
                assertEquals(
                    "Closing",
                    graph.playbackStateSource.currentChapter.value
                        ?.chapter
                        ?.title,
                )
            }
        }

    @Test fun focusLossAndNoisyPause() =
        runCase {
            phase("focus/start")
            seed()
            graph.queueRepository().addLast(ids)
            start()
            val request =
                AudioFocusRequest
                    .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .build(),
                    ).setOnAudioFocusChangeListener({}, android.os.Handler(android.os.Looper.getMainLooper()))
                    .build()
            focus = request
            phase("focus/request")
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audio.requestAudioFocus(request))
            phase("focus/await-pause")
            await {
                graph.playbackStateSource.nowPlaying.value
                    ?.isPlaying == false
            }
            audio.abandonAudioFocusRequest(request)
            focus = null
            phase("focus/await-resume")
            awaitAudio()
            phase("noisy/send")
            // Stock emulator root supplies the system privilege required by this protected event.
            val sender = shell("su 0 id -u")
            check(sender.exitCode == 0 && sender.text.trim() == "0") { "System sender is unavailable: ${sender.text}" }
            logEvidence("noisy-sender", "uid=${sender.text.trim()}")
            val sent =
                shell(
                    "su 0 am broadcast -a android.media.AUDIO_BECOMING_NOISY -p ${app.packageName}",
                )
            logEvidence("noisy-command", "exit=${sent.exitCode}\n${sent.text}")
            check(sent.exitCode == 0) { "Noisy broadcast was not admitted: ${sent.text}" }
            phase("noisy/await-pause")
            await {
                graph.playbackStateSource.nowPlaying.value
                    ?.playWhenReady == false
            }
        }

    @Test fun serviceRecreationLoadsPausedAtSavedPosition() =
        runCase {
            phase("recreation/start")
            seed()
            graph.queueRepository().addLast(ids)
            start()
            withContext(Dispatchers.Main) {
                graph.playbackController.seekTo(9_000)
                graph.playbackController.pause()
                connect().stop()
                controller?.release()
                controller = null
            }
            backgroundHost()
            await {
                val saved =
                    graph.databaseOpener
                        .requireDatabase()
                        .positionDao()
                        .byEpisode(ids.first())
                saved != null && saved.positionMs >= 9_000
            }
            await { !serviceDump().contains(FOREGROUND_SERVICE_MARKER) }
            app.stopService(Intent(app, NeutrodynePlaybackService::class.java))
            await { !serviceDump().contains("ServiceRecord{") }
            phase("recreation/foreground")
            foregroundHost()
            val restored = withContext(Dispatchers.Main) { connect() }
            await { withContext(Dispatchers.Main) { restored.mediaItemCount > 0 } }
            withContext(Dispatchers.Main) { assertFalse(restored.playWhenReady) }
            await { withContext(Dispatchers.Main) { restored.currentPosition >= 9_000 } }
        }

    // The external background-start case moved to the external bgstart lane as
    // externalBackgroundStartDenialIsUnreachable: an active instrumentation
    // grants the target PROCESS_CAPABILITY_BFSL (OomAdjuster), so a genuine
    // foreground-start denial is unreachable inside an instrumented target.

    @Test @Nightly
    @SdkSuppress(minSdkVersion = 37)
    fun hardeningUiNotificationAndColdMediaKeyStarts() =
        runCase {
            phase("hardening/start")
            hardening = true
            device.executeShellCommand("cmd audio set-enable-hardening throw")
            device.executeShellCommand("logcat -c")
            seed()
            graph.queueRepository().addLast(ids)
            start()
            withContext(Dispatchers.Main) { graph.playbackController.pause() }
            backgroundHost()
            tapNotification("Play")
            awaitAudio()
            withContext(Dispatchers.Main) { graph.playbackController.pause() }
            device.executeShellCommand("cmd media_session dispatch play")
            awaitAudio()
            withContext(Dispatchers.Main) {
                graph.playbackController.pause()
                controller?.release()
                controller = null
            }
            // Let Media3's post-pause foreground grace finish before the cold-start probe.
            await { !serviceDump().contains(FOREGROUND_SERVICE_MARKER) }
            app.stopService(Intent(app, NeutrodynePlaybackService::class.java))
            await { !serviceDump().contains("ServiceRecord{") }
            // System media-session dispatch exercises the manifest's standard MediaButtonReceiver.
            device.executeShellCommand("cmd media_session dispatch play")
            awaitAudio()
            val hardeningLog = device.executeShellCommand("logcat -d -s AudioHardening")
            android.util.Log.i("PlaybackEvidence", "AudioHardening log: $hardeningLog")
            assertFalse("audio hardening violations: $hardeningLog", hardeningLog.contains(app.packageName))
        }

    @Test(timeout = 2_100_000)
    @Nightly
    @SdkSuppress(minSdkVersion = 34, maxSdkVersion = 34)
    fun screenOffStreamsForThirtyMinutesAndAdvances() =
        runCase {
            phase("screen-off/start")
            seed(repeats = LONG_STREAM_REPEATS)
            graph.queueRepository().addLast(ids)
            start()
            val pid = Process.myPid()
            backgroundHost()
            device.sleep()
            val started = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - started < THIRTY_MINUTES_MS) {
                delay(15_000)
                assertFalse(device.isScreenOn)
                awaitAudio()
                assertEquals(pid, Process.myPid())
                assertTrue(serviceDump().contains("isForeground=true"))
                val notification = awaitNotification(PLAYBACK_NOTIFICATION)
                assertTrue(notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
                android.util.Log.i(
                    "PlaybackEvidence",
                    "elapsed=${SystemClock.elapsedRealtime() - started} pid=$pid " +
                        "episode=${nowId()} position=${snapshotPosition()} foreground=true",
                )
                assertEquals(
                    StreamKind.STREAM,
                    graph.playbackStateSource.nowPlaying.value
                        ?.stream,
                )
            }
            // The last 15s poll can fire just before the transition; judge the settled state.
            await { nowId() == ids[1] }
            assertTrue(server.requestCount >= 2)
        }

    private suspend fun seed(
        repeats: Int = SHORT_STREAM_REPEATS,
        asset: String = "cbr_128k_2s.mp3",
    ) {
        val frame =
            instrumentation.context.assets
                .open("media/$asset")
                .use { it.readBytes() }
        val body = Buffer().apply { repeat(repeats) { write(frame) } }.readByteArray()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val offset =
                        request.headers["Range"]
                            ?.substringAfter("bytes=")
                            ?.substringBefore('-')
                            ?.toIntOrNull() ?: 0
                    if (offset >= body.size) return MockResponse(code = 416)
                    return MockResponse
                        .Builder()
                        .code(if (offset == 0) 200 else 206)
                        .addHeader("Content-Type", if (asset.endsWith("m4b")) "audio/mp4" else "audio/mpeg")
                        .addHeader("Accept-Ranges", "bytes")
                        .apply {
                            if (offset >
                                0
                            ) {
                                addHeader("Content-Range", "bytes $offset-${body.lastIndex}/${body.size}")
                            }
                        }.body(Buffer().write(body, offset, body.size - offset))
                        .throttleBody(32_768, 1, TimeUnit.SECONDS)
                        .build()
                }
            }
        val db = graph.databaseOpener.requireDatabase()
        val coverUrl = server.url("/cover.png").toString()
        val key = ArtworkKeys.forUrl(coverUrl)
        val image = File(app.filesDir, "artwork/$key.png").also { cover = it }
        image.parentFile!!.mkdirs()
        val bitmap = Bitmap.createBitmap(COVER_SIDE, COVER_SIDE, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        db.artworkDao().upsert(
            ArtworkEntity(
                key,
                url = coverUrl,
                localPath = image.name,
                width = COVER_SIDE,
                height = COVER_SIDE,
                fetchedAt = System.currentTimeMillis(),
            ),
        )
        val podcast =
            db.podcastDao().insertPodcast(
                podcastEntity(title = "Playback fixture", artworkKey = key) {
                    copy(artworkUrl = coverUrl)
                },
            )
        ids =
            (1..2).map { number ->
                db.episodeDao().insertStub(
                    episodeEntity(podcastId = podcast, title = "Stream $number") {
                        copy(enclosureUrl = server.url("/$number.mp3").toString(), durationMs = repeats * 2_000L)
                    },
                )
            }
    }

    private suspend fun start() {
        assertEquals(PlayResult.Started, graph.playbackController.play())
        awaitAudio()
    }

    private suspend fun connect(): MediaController {
        controller?.let { return it }
        return MediaController
            .Builder(
                app,
                SessionToken(app, ComponentName(app, NeutrodynePlaybackService::class.java)),
            ).buildAsync()
            .await()
            .also { controller = it }
    }

    private suspend fun awaitAudio() {
        await {
            graph.playbackStateSource.nowPlaying.value
                ?.isPlaying == true
        }
        val before = snapshotPosition()
        await { snapshotPosition() > before + 200 }
        assertTrue(
            "Android must report an active audio track",
            audio.activePlaybackConfigurations.any {
                it.audioAttributes.contentType == AudioAttributes.CONTENT_TYPE_SPEECH
            },
        )
    }

    private fun debugBuild() = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun nowId() =
        graph.playbackStateSource.nowPlaying.value
            ?.episodeId

    private suspend fun snapshotPosition() = withContext(Dispatchers.Main) { connect().currentPosition }

    private fun serviceDump() =
        device.executeShellCommand(
            "dumpsys activity services ${app.packageName}/$SERVICE_CLASS",
        )

    private suspend fun await(condition: suspend () -> Boolean) =
        withTimeout(WAIT_MS) {
            while (!condition()) delay(100)
        }

    private suspend fun awaitNotification(id: Int): Notification {
        var notification: Notification? = null
        await {
            notification =
                notifications.activeNotifications
                    .firstOrNull { it.id == id }
                    ?.notification
            notification != null
        }
        return checkNotNull(notification)
    }

    private suspend fun backgroundHost() {
        val host = checkNotNull(scenario)
        lateinit var existing: MainActivity
        host.onActivity { existing = it }
        pressHomeAndAwaitBackground(device, existing, WAIT_MS)
        logEvidence("background-state", stateSnapshot())
    }

    private suspend fun foregroundHost() {
        val host = checkNotNull(scenario)
        lateinit var existing: MainActivity
        host.onActivity { existing = it }

        val flags =
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
        val component = ComponentName(app, MainActivity::class.java).flattenToString()

        // Keep launcher identity: onNewIntent replaces the intent that ActivityScenario matches.
        val brought =
            shell(
                "am start -W -a ${Intent.ACTION_MAIN} -c ${Intent.CATEGORY_LAUNCHER} -f $flags -n $component",
            )
        logEvidence("foreground-command", "exit=${brought.exitCode}\n${brought.text}")
        check(brought.exitCode == 0) { "The existing host task could not be foregrounded: ${brought.text}" }

        await {
            withContext(Dispatchers.Main) { existing.lifecycle.currentState == Lifecycle.State.RESUMED } &&
                device.currentPackageName == app.packageName
        }
        host.onActivity { assertSame("Foregrounding must retain the host instance", existing, it) }
    }

    private fun tapNotification(label: String) {
        phase("notification/action-$label")
        check(device.openNotification()) { "Notification shade could not be opened" }
        // Notification action buttons live in the SystemUI window. App content can
        // contain the label as a substring ("Play" in "Playback fixture"), so scope
        // every locator to SystemUI; unscoped matching clicked an episode row.
        val deadline = SystemClock.elapsedRealtime() + 2 * WAIT_MS
        var staleAttempts = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) break
            val action =
                device.wait(
                    Until.findObject(By.descContains(label).pkg(SYSTEM_UI_PACKAGE)),
                    minOf(1_000L, remaining),
                )
                    ?: device.findObject(By.text(label).pkg(SYSTEM_UI_PACKAGE))
                    ?: continue
            try {
                action.click()
                device.pressBack()
                return
            } catch (stale: StaleObjectException) {
                if (++staleAttempts == 3) throw stale
            }
        }
        error("Notification action $label is missing")
    }

    private fun runCase(block: suspend () -> Unit) =
        runBlocking<Unit> {
            try {
                phase("image/ready")
                val probe = shell("printf ND_STDERR_PROBE >&2; exit 7")
                check(probe.exitCode == 7 && probe.text.contains("ND_STDERR_PROBE"))
                val systemUi = shell("pm path $SYSTEM_UI_PACKAGE")
                check(systemUi.exitCode == 0 && systemUi.text.lineSequence().any { it.startsWith("package:") }) {
                    "Playback framework tests require a full image with SystemUI: ${systemUi.text}"
                }
                block()
            } catch (failure: Throwable) {
                runCatching { captureFailure() }.onFailure { failure.addSuppressed(it) }
                throw failure
            }
        }

    private fun phase(value: String) {
        phase = value
        Log.i(
            "PlaybackEvidence",
            "phase=$value elapsed=${SystemClock.elapsedRealtime()} fingerprint=${Build.FINGERPRINT}",
        )
    }

    private fun stateSnapshot(): String {
        val process = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(process)
        val current = graph.playbackStateSource.nowPlaying.value
        return "phase=$phase uid=${Process.myUid()} pid=${Process.myPid()} importance=${process.importance} " +
            "playWhenReady=${current?.playWhenReady} isPlaying=${current?.isPlaying} issue=${current?.issue}"
    }

    private fun captureFailure() {
        fun record(
            name: String,
            value: () -> String,
        ) {
            logEvidence(name, runCatching(value).getOrElse { "Capture failed: $it" })
        }
        record("state", ::stateSnapshot)
        record("hierarchy") {
            val path = File(app.cacheDir, "playback-window.xml")
            try {
                device.dumpWindowHierarchy(path)
                path.readText()
            } finally {
                path.delete()
            }
        }
        record("notification") { shell("dumpsys -t 5 notification").text }
        record("service") { shell("dumpsys -t 5 activity services ${app.packageName}/$SERVICE_CLASS").text }
        record("uid") { shell("cmd activity get-uid-state ${Process.myUid()}").text }
        record("processes") { shell("dumpsys -t 5 activity processes").text }
    }

    private fun logEvidence(
        name: String,
        value: String,
    ) {
        val chunks = value.chunked(1_000)
        chunks.forEachIndexed { index, chunk -> Log.i("PlaybackEvidence", "$name[$index/${chunks.size}] $chunk") }
        Log.i("PlaybackEvidence", "$name complete chars=${value.length}")
    }

    private data class ShellResult(
        val text: String,
        val exitCode: Int,
    )

    private fun shell(command: String): ShellResult {
        val marker = "ND_PLAYBACK_SHELL_EXIT="
        val script = "($command) 2>&1; result=\$?; printf '\\n$marker%d\\n' \"\$result\""
        // A file avoids command-argument tokenization differences across the supported APIs.
        val file = File.createTempFile("playback-shell-", ".sh", checkNotNull(app.externalCacheDir))
        try {
            file.writeText(script)
            val output = device.executeShellCommand("sh ${file.absolutePath}")
            val exit =
                output
                    .lineSequence()
                    .lastOrNull { it.startsWith(marker) }
                    ?.removePrefix(marker)
                    ?.trim()
                    ?.toIntOrNull()
                    ?: error("Shell command returned no exit evidence: $command\n$output")
            return ShellResult(output.substringBeforeLast("\n$marker"), exit)
        } finally {
            file.delete()
        }
    }

    private companion object {
        const val COVER_SIDE = 256
        const val SERVICE_CLASS = "ch.lkmc.neutrodyne.playback.impl.NeutrodynePlaybackService"
        const val FOREGROUND_SERVICE_MARKER = "isForeground=true"
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        const val PLAYBACK_NOTIFICATION = 4000
        const val SHORT_STREAM_REPEATS = 60
        const val LONG_STREAM_REPEATS = 480
        const val TIMESTAMP_STREAM_REPEATS = 480
        const val TIMESTAMP_SEEK_MS = 754_000L
        const val SEEK_TOLERANCE_MS = 4_000L
        const val SETTLE_MS = 500L
        const val TIMER_EXPIRY_MS = 75_000L
        const val WAIT_MS = 30_000L
        const val THIRTY_MINUTES_MS = 30 * 60 * 1_000L
        val EMBEDDED_CHAPTER_FIXTURES =
            listOf(
                "chapters_id3.mp3" to ChapterSource.ID3,
                "chapters_nero.m4b" to ChapterSource.MP4,
                "chapters_quicktime.m4b" to ChapterSource.MP4,
                "chapters_both.m4b" to ChapterSource.MP4,
            )
    }
}
