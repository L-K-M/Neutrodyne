// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.desktop

import ch.lkmc.neutrodyne.core.model.ChapterSource
import ch.lkmc.neutrodyne.core.model.PlaybackMediaInfo
import ch.lkmc.neutrodyne.core.model.QueueItem
import ch.lkmc.neutrodyne.core.model.QueueOrigin
import ch.lkmc.neutrodyne.core.model.VirtualQueue
import ch.lkmc.neutrodyne.core.network.okhttp.NetworkClients
import ch.lkmc.neutrodyne.core.testing.FakeChapterRepository
import ch.lkmc.neutrodyne.core.testing.FakeEffectiveSettingsResolver
import ch.lkmc.neutrodyne.core.testing.FakeLocalMediaIndex
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakePlayContextResolver
import ch.lkmc.neutrodyne.core.testing.FakePlaySessionStore
import ch.lkmc.neutrodyne.core.testing.FakePlaybackMediaLookup
import ch.lkmc.neutrodyne.core.testing.FakePositionStore
import ch.lkmc.neutrodyne.core.testing.FakeQueueRepository
import ch.lkmc.neutrodyne.core.testing.FakeScopeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeStreamResolver
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.testEpisodeRow
import ch.lkmc.neutrodyne.desktop.system.AudioRouteMonitor
import ch.lkmc.neutrodyne.desktop.system.RouteEvent
import ch.lkmc.neutrodyne.playback.api.PlayResult
import ch.lkmc.neutrodyne.playback.core.EffectivePlaybackSettings
import ch.lkmc.neutrodyne.playback.core.PlayStarter
import ch.lkmc.neutrodyne.playback.core.PlayedRule
import ch.lkmc.neutrodyne.playback.core.PositionSaver
import ch.lkmc.neutrodyne.playback.core.QueueWindowPlanner
import ch.lkmc.neutrodyne.playback.core.StartPositionRule
import ch.lkmc.neutrodyne.playback.core.WindowDiff
import ch.lkmc.neutrodyne.playback.engine.EngineBackend
import ch.lkmc.neutrodyne.playback.engine.FfAudioEngine
import ch.lkmc.neutrodyne.playback.engine.SpanCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Production FFmpeg/controller boundary, explicitly using miniaudio's null output. */
class DesktopNativePlaybackTest {
    @Test
    fun localPlaybackSeekAndQuitPersistTheNativeTimeline() =
        runBlocking {
            val root = Path.of(requireNotNull(System.getProperty("neutrodyne.rootDir")))
            val target = requireNotNull(System.getProperty("neutrodyne.nativeTarget"))
            val directory = Files.createTempDirectory("md1a2-native")
            val file = directory.resolve("local.wav")
            Files.write(file, wave())
            val clock = TestClock()
            val local = FakeLocalMediaIndex().apply { files[1] = file.toUri().toString() }
            val caps = FakeYouTubeCapabilitiesSource()
            val resolver = DesktopEpisodeSourceResolver(local, caps, FakeYouTubeStreamResolver(), clock)
            val cache = SpanCache(directory.resolve("cache"))
            val clients = mediaClients()
            val engine =
                FfAudioEngine(
                    root.resolve("playback/native/build/ffmpeg/$target"),
                    root.resolve("playback/native/build/ndmedia/$target"),
                    resolver,
                    clients,
                    cache,
                    EngineBackend.NULL,
                )
            val positions = FakePositionStore()
            val queue =
                FakeQueueRepository().apply {
                    this.queue.value =
                        VirtualQueue(1, QueueItem(1, QueueOrigin.CURRENT), emptyList(), emptyList())
                }
            val sessions = FakePlaySessionStore().apply { this.queue = queue.queue.value }
            val row = testEpisodeRow(1)
            val media =
                FakePlaybackMediaLookup().apply {
                    items.value =
                        mapOf(
                            1L to
                                PlaybackMediaInfo(
                                    1,
                                    row.podcastId,
                                    row.title,
                                    row.podcastTitle,
                                    row.artwork,
                                    row.sourceType,
                                    row.availability,
                                    false,
                                    "https://media.example/local.wav",
                                    null,
                                    null,
                                    120_000,
                                    null,
                                    null,
                                ),
                        )
                }
            val rule = PlayedRule()
            val start = StartPositionRule()
            val runtime =
                DesktopPlaybackRuntime(
                    PlayStarter(
                        FakePlayContextResolver(),
                        queue,
                        sessions,
                        media,
                        positions,
                        caps,
                        local,
                        FakeNetworkMonitor(),
                        clock,
                        start,
                        rule,
                    ),
                    QueueWindowPlanner(queue, caps, positions, local, clock, WindowDiff(), start, rule),
                    PositionSaver(positions, clock, rule),
                    EffectivePlaybackSettings(
                        FakeEffectiveSettingsResolver(),
                        FakeScopeSettingsRepository(),
                        FakeSettingsRepository(),
                    ),
                    sessions,
                    queue,
                    media,
                    positions,
                    caps,
                    FakeChapterRepository(),
                    resolver,
                    cache,
                    engine,
                )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val controller =
                DesktopPlaybackController(scope, clock, { runtime }, FakeNetworkMonitor(FakeNetworkMonitor.ONLINE))
            try {
                assertEquals(PlayResult.Started, controller.play())
                withTimeout(10_000) {
                    controller.nowPlaying
                        .filter {
                            it?.isPlaying == true && it.position.durationMs == 5_000L
                        }.first()
                }
                controller.seekTo(1_200)
                withTimeout(10_000) {
                    controller.nowPlaying
                        .filter {
                            it?.isPlaying == true &&
                                it.position.positionMs >= 1_200
                        }.first()
                }
                controller.shutdown()
                assertTrue(requireNotNull(positions.positions.value[1]).positionMs in 1_200..5_000)
                assertEquals(5_000L, positions.measuredDurations[1])
            } finally {
                controller.shutdown()
                scope.cancel()
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun aRemovedOutputDevicePausesSavesAndReopensOnTheNextPlay() =
        runBlocking {
            val root = Path.of(requireNotNull(System.getProperty("neutrodyne.rootDir")))
            val target = requireNotNull(System.getProperty("neutrodyne.nativeTarget"))
            val directory = Files.createTempDirectory("md1b-device-loss")
            val file = directory.resolve("local.wav")
            Files.write(file, wave())
            val clock = TestClock()
            val local = FakeLocalMediaIndex().apply { files[1] = file.toUri().toString() }
            val caps = FakeYouTubeCapabilitiesSource()
            val resolver = DesktopEpisodeSourceResolver(local, caps, FakeYouTubeStreamResolver(), clock)
            val cache = SpanCache(directory.resolve("cache"))
            val engine =
                FfAudioEngine(
                    root.resolve("playback/native/build/ffmpeg/$target"),
                    root.resolve("playback/native/build/ndmedia/$target"),
                    resolver,
                    mediaClients(),
                    cache,
                    EngineBackend.NULL,
                )
            val positions = FakePositionStore()
            val queue =
                FakeQueueRepository().apply {
                    queue.value = VirtualQueue(1, QueueItem(1, QueueOrigin.CURRENT), emptyList(), emptyList())
                }
            val sessions = FakePlaySessionStore().apply { this.queue = queue.queue.value }
            val row = testEpisodeRow(1)
            val media =
                FakePlaybackMediaLookup().apply {
                    items.value =
                        mapOf(
                            1L to
                                PlaybackMediaInfo(
                                    1,
                                    row.podcastId,
                                    row.title,
                                    row.podcastTitle,
                                    row.artwork,
                                    row.sourceType,
                                    row.availability,
                                    false,
                                    "https://media.example/local.wav",
                                    null,
                                    null,
                                    120_000,
                                    null,
                                    null,
                                ),
                        )
                }
            val runtime =
                DesktopPlaybackRuntime(
                    PlayStarter(
                        FakePlayContextResolver(),
                        queue,
                        sessions,
                        media,
                        positions,
                        caps,
                        local,
                        FakeNetworkMonitor(),
                        clock,
                        StartPositionRule(),
                        PlayedRule(),
                    ),
                    QueueWindowPlanner(
                        queue,
                        caps,
                        positions,
                        local,
                        clock,
                        WindowDiff(),
                        StartPositionRule(),
                        PlayedRule(),
                    ),
                    PositionSaver(positions, clock, PlayedRule()),
                    EffectivePlaybackSettings(
                        FakeEffectiveSettingsResolver(),
                        FakeScopeSettingsRepository(),
                        FakeSettingsRepository(),
                    ),
                    sessions,
                    queue,
                    media,
                    positions,
                    caps,
                    FakeChapterRepository(),
                    resolver,
                    cache,
                    engine,
                )
            val route = FakeRouteMonitor()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val controller =
                DesktopPlaybackController(
                    scope,
                    clock,
                    { runtime },
                    FakeNetworkMonitor(FakeNetworkMonitor.ONLINE),
                    routeMonitor = route,
                )
            try {
                assertEquals(PlayResult.Started, controller.play())
                withTimeout(10_000) {
                    controller.nowPlaying.filter { it?.isPlaying == true }.first()
                }
                withTimeout(10_000) {
                    controller.nowPlaying.filter { (it?.position?.positionMs ?: 0) > 500 }.first()
                }
                // A default-device change alone keeps playing on the new default.
                route.defaultChanged()
                Thread.sleep(50)
                assertTrue(controller.nowPlaying.value!!.isPlaying)
                route.removed("device-1")
                withTimeout(10_000) {
                    controller.nowPlaying
                        .filter { it?.isPlaying == false && it.playWhenReady == false }
                        .first()
                }
                val saved = positions.positions.value[1]?.positionMs
                assertTrue(saved != null && saved > 0, "the device loss must save the position")
                assertEquals(PlayResult.Started, controller.play())
                withTimeout(10_000) {
                    controller.nowPlaying.filter { it?.isPlaying == true }.first()
                }
                // The reopen resumes at the saved position; no regression to the start.
                assertTrue(
                    controller.nowPlaying.value!!
                        .position.positionMs >= saved!! - 1_000,
                    "reopened at ${controller.nowPlaying.value!!.position.positionMs}, saved $saved",
                )
            } finally {
                controller.shutdown()
                scope.cancel()
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun embeddedChaptersFromTheCorpusFixturesReachTheSharedRepository() =
        runBlocking {
            val root = Path.of(requireNotNull(System.getProperty("neutrodyne.rootDir")))
            val target = requireNotNull(System.getProperty("neutrodyne.nativeTarget"))
            val corpus = root.resolve("playback/engine/src/test/resources/media")
            val fixtures =
                listOf(
                    ChapterFixture("id3chap_3s.mp3", ChapterSource.ID3, listOf("Intro", "Middle", "Outro")),
                    ChapterFixture("m4a_chapters_3s.m4a", ChapterSource.MP4, listOf("First", "Second", "Third")),
                )
            for (fixture in fixtures) {
                val directory = Files.createTempDirectory("md1b-chapters")
                val file = directory.resolve(fixture.name)
                Files.copy(corpus.resolve(fixture.name), file)
                val clock = TestClock()
                val local = FakeLocalMediaIndex().apply { files[1] = file.toUri().toString() }
                val caps = FakeYouTubeCapabilitiesSource()
                val resolver = DesktopEpisodeSourceResolver(local, caps, FakeYouTubeStreamResolver(), clock)
                val cache = SpanCache(directory.resolve("cache"))
                val engine =
                    FfAudioEngine(
                        root.resolve("playback/native/build/ffmpeg/$target"),
                        root.resolve("playback/native/build/ndmedia/$target"),
                        resolver,
                        mediaClients(),
                        cache,
                        EngineBackend.NULL,
                    )
                val positions = FakePositionStore()
                val queue =
                    FakeQueueRepository().apply {
                        queue.value = VirtualQueue(1, QueueItem(1, QueueOrigin.CURRENT), emptyList(), emptyList())
                    }
                val sessions = FakePlaySessionStore().apply { this.queue = queue.queue.value }
                val row = testEpisodeRow(1)
                val media =
                    FakePlaybackMediaLookup().apply {
                        items.value =
                            mapOf(
                                1L to
                                    PlaybackMediaInfo(
                                        1,
                                        row.podcastId,
                                        row.title,
                                        row.podcastTitle,
                                        row.artwork,
                                        row.sourceType,
                                        row.availability,
                                        false,
                                        "https://media.example/$fixture.name",
                                        null,
                                        null,
                                        120_000,
                                        null,
                                        null,
                                    ),
                            )
                    }
                val chapters = FakeChapterRepository()
                val runtime =
                    DesktopPlaybackRuntime(
                        PlayStarter(
                            FakePlayContextResolver(),
                            queue,
                            sessions,
                            media,
                            positions,
                            caps,
                            local,
                            FakeNetworkMonitor(),
                            clock,
                            StartPositionRule(),
                            PlayedRule(),
                        ),
                        QueueWindowPlanner(
                            queue,
                            caps,
                            positions,
                            local,
                            clock,
                            WindowDiff(),
                            StartPositionRule(),
                            PlayedRule(),
                        ),
                        PositionSaver(positions, clock, PlayedRule()),
                        EffectivePlaybackSettings(
                            FakeEffectiveSettingsResolver(),
                            FakeScopeSettingsRepository(),
                            FakeSettingsRepository(),
                        ),
                        sessions,
                        queue,
                        media,
                        positions,
                        caps,
                        chapters,
                        resolver,
                        cache,
                        engine,
                    )
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val controller =
                    DesktopPlaybackController(scope, clock, { runtime }, FakeNetworkMonitor(FakeNetworkMonitor.ONLINE))
                try {
                    assertEquals(PlayResult.Started, controller.play())
                    controller.pause()
                    withTimeout(10_000) {
                        chapters.rows.filter { it[1]?.isNotEmpty() == true }.first()
                    }
                    val rows = chapters.rows.value[1].orEmpty()
                    assertEquals(fixture.titles, rows.map { it.title })
                    assertTrue(rows.all { it.source == fixture.source })
                    withTimeout(10_000) {
                        controller.currentChapter.filter { it != null }.first()
                    }
                    controller.nextChapter()
                    withTimeout(10_000) {
                        controller.nowPlaying.filter { it?.position?.positionMs == 1_000L }.first()
                    }
                    withTimeout(10_000) {
                        controller.currentChapter.filter { it?.index == 1 }.first()
                    }
                } finally {
                    controller.shutdown()
                    scope.cancel()
                    directory.toFile().deleteRecursively()
                }
            }
        }

    private class FakeRouteMonitor : AudioRouteMonitor {
        private val mutable = MutableSharedFlow<RouteEvent>(extraBufferCapacity = 8)
        override val events: Flow<RouteEvent> = mutable.asSharedFlow()

        fun removed(deviceId: String) {
            mutable.tryEmit(RouteEvent.DeviceRemoved(deviceId))
        }

        fun defaultChanged() {
            mutable.tryEmit(RouteEvent.DefaultChanged)
        }
    }

    private data class ChapterFixture(
        val name: String,
        val source: ChapterSource,
        val titles: List<String>,
    )

    private fun mediaClients(): NetworkClients {
        val platform =
            ch.lkmc.neutrodyne.core.testing
                .FakePlatformInfo()
        return NetworkClients(
            ch.lkmc.neutrodyne.core.network.okhttp.CoreClients(
                ch.lkmc.neutrodyne.core.network.okhttp.UserAgentInterceptor(
                    ch.lkmc.neutrodyne.core.common
                        .UserAgentProvider(platform, "0.1.0", "https://example.com/repo"),
                ),
                ch.lkmc.neutrodyne.core.network.okhttp.LocalNetworkGuard(
                    ch.lkmc.neutrodyne.core.common
                        .LocalNetworkAccess(),
                    platform,
                ),
                ch.lkmc.neutrodyne.core.network.okhttp
                    .DnsFamilyHints(),
                emptySet(),
            ),
            ch.lkmc.neutrodyne.core.network.okhttp
                .AuthInterceptor { ch.lkmc.neutrodyne.core.common.CredentialLookup.None },
        )
    }

    private fun wave(): ByteArray {
        val frames = 22_050 * 5
        val bytes = frames * 2
        return ByteBuffer
            .allocate(44 + bytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put("RIFF".toByteArray())
                putInt(36 + bytes)
                put("WAVEfmt ".toByteArray())
                putInt(16)
                putShort(1)
                putShort(1)
                putInt(22_050)
                putInt(44_100)
                putShort(2)
                putShort(16)
                put("data".toByteArray())
                putInt(bytes)
                repeat(frames) { putShort((if (it % 40 < 20) 1_000 else -1_000).toShort()) }
            }.array()
    }
}
