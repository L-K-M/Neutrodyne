// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.desktop

import ch.lkmc.neutrodyne.core.model.PlaybackMediaInfo
import ch.lkmc.neutrodyne.core.model.QueueItem
import ch.lkmc.neutrodyne.core.model.QueueOrigin
import ch.lkmc.neutrodyne.core.model.VirtualQueue
import ch.lkmc.neutrodyne.core.testing.FakeAudioEngine
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
import ch.lkmc.neutrodyne.playback.api.PlayResult
import ch.lkmc.neutrodyne.playback.core.EffectivePlaybackSettings
import ch.lkmc.neutrodyne.playback.core.PlayStarter
import ch.lkmc.neutrodyne.playback.core.PlayedRule
import ch.lkmc.neutrodyne.playback.core.PositionSaver
import ch.lkmc.neutrodyne.playback.core.QueueWindowPlanner
import ch.lkmc.neutrodyne.playback.core.StartPositionRule
import ch.lkmc.neutrodyne.playback.core.WindowDiff
import ch.lkmc.neutrodyne.playback.engine.EngineEvent
import ch.lkmc.neutrodyne.playback.engine.Gapless
import ch.lkmc.neutrodyne.playback.engine.SpanCache
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopPlaybackTimingTest {
    @Test
    fun fiveSecondSavesCountAdvancementAndPauseFlushesImmediately() =
        runTest {
            val fixture = TimingFixture(this)
            try {
                assertEquals(PlayResult.Started, fixture.controller.play())
                fixture.engine.ready()
                runCurrent()
                fixture.engine.position = 12_000
                advanceTimeBy(4_999)
                runCurrent()
                assertTrue(fixture.positions.calls.isEmpty())
                advanceTimeBy(1)
                runCurrent()
                assertEquals(
                    12_000L,
                    fixture.positions.positions.value[1]
                        ?.positionMs,
                )
                fixture.engine.position = 15_000
                fixture.controller.pause()
                runCurrent()
                assertEquals(
                    15_000L,
                    fixture.positions.positions.value[1]
                        ?.positionMs,
                )
                val saves = fixture.positions.calls.size
                advanceTimeBy(10_000)
                runCurrent()
                assertEquals(saves, fixture.positions.calls.size)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun bufferingStallFlushesTheLastAdvancingPosition() =
        runTest {
            val fixture = TimingFixture(this)
            try {
                fixture.controller.play()
                fixture.engine.ready()
                runCurrent()
                fixture.engine.position = 14_000
                fixture.engine.state.value =
                    ch.lkmc.neutrodyne.playback.engine.EngineState
                        .Buffering(1)
                runCurrent()
                assertEquals(
                    14_000L,
                    fixture.positions.positions.value[1]
                        ?.positionMs,
                )
            } finally {
                fixture.close()
            }
        }

    @Test
    fun pauseCancelsAStartSuspendedInMediaLookup() =
        runTest {
            val fixture = TimingFixture(this)
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            try {
                fixture.media.beforeGet = { release.await() }
                val start = async { fixture.controller.play() }
                runCurrent()
                fixture.controller.pause()
                release.complete(Unit)
                runCurrent()
                kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> { start.await() }
                assertTrue("play" !in fixture.engine.commands)
            } finally {
                release.complete(Unit)
                fixture.close()
            }
        }

    @Test
    fun downloadCompletionDoesNotSwitchAnOpenStreamPin() =
        runTest {
            val fixture = TimingFixture(this)
            val file = Files.createTempFile("md1a2-pin", ".mp3")
            try {
                fixture.controller.play()
                val item = fixture.engine.items.first()
                val original = fixture.resolver.resolve(item, 0)
                assertTrue(original is ch.lkmc.neutrodyne.playback.engine.ResolvedSource.Http)
                fixture.local.files[1] = file.toUri().toString()
                assertEquals(original, fixture.resolver.resolve(item, 1))
            } finally {
                fixture.close()
                Files.delete(file)
            }
        }

    @Test
    fun metadataEmittedDuringPreparationIsNeverDropped() =
        runTest {
            val fixture = TimingFixture(this)
            try {
                fixture.engine.onWindow = {
                    fixture.engine.events.tryEmit(EngineEvent.TracksKnown(1, 80_000, emptyList(), Gapless(0, 0, 0)))
                }
                fixture.controller.play()
                runCurrent()
                assertEquals(80_000L, fixture.positions.measuredDurations[1])
            } finally {
                fixture.close()
            }
        }

    @Test
    fun toggleThenPausePreservesTransportOrder() =
        runTest {
            val fixture = TimingFixture(this)
            try {
                fixture.controller.initialize()
                runCurrent()
                fixture.controller.togglePlayPause()
                fixture.controller.pause()
                runCurrent()
                assertEquals(
                    false,
                    fixture.controller.nowPlaying.value
                        ?.playWhenReady,
                )
                assertEquals("pause", fixture.engine.commands.last { it in setOf("play", "pause") })
            } finally {
                fixture.close()
            }
        }

    @Test
    fun failedShutdownSaveStillClosesEngineAndReportsFailure() =
        runTest {
            val fixture = TimingFixture(this)
            try {
                fixture.controller.play()
                fixture.engine.ready()
                runCurrent()
                fixture.engine.position = 19_000
                fixture.positions.failure = java.io.IOException("disk full")
                kotlin.test.assertFailsWith<java.io.IOException> { fixture.controller.shutdown() }
                assertTrue(fixture.engine.closed)
            } finally {
                fixture.positions.failure = null
                fixture.close()
            }
        }

    @Test
    fun restoreAfterStartReadCannotDispatchStalePlayback() =
        runTest {
            val fixture = TimingFixture(this)
            try {
                fixture.sessions.afterVirtualQueueRead = {
                    fixture.sessions.afterVirtualQueueRead = null
                    fixture.sessions.queue = VirtualQueue(2, null, emptyList(), emptyList())
                    fixture.queue.queue.value = fixture.sessions.queue
                }
                assertEquals(PlayResult.ServiceUnavailable, fixture.controller.play())
                assertTrue("play" !in fixture.engine.commands)
                runCurrent()
                assertNull(fixture.controller.nowPlaying.value)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun foreignSessionClearRemovesPlayingItemFromStateAndEngine() =
        runTest {
            val fixture = TimingFixture(this)
            try {
                fixture.controller.play()
                fixture.engine.ready()
                runCurrent()
                fixture.queue.queue.value = VirtualQueue(2, null, emptyList(), emptyList())
                runCurrent()
                assertNull(fixture.controller.nowPlaying.value)
                assertTrue(fixture.engine.items.isEmpty())
            } finally {
                fixture.close()
            }
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class TimingFixture(
    test: TestScope,
) {
    private val clock = TestClock.from(test.testScheduler)
    val engine = FakeAudioEngine()
    val positions = FakePositionStore()
    val queue = FakeQueueRepository()
    val sessions = FakePlaySessionStore()
    val media = FakePlaybackMediaLookup()
    val local = FakeLocalMediaIndex()
    private val caps = FakeYouTubeCapabilitiesSource()
    private val directory = Files.createTempDirectory("md1a2-timing")
    val resolver = DesktopEpisodeSourceResolver(local, caps, FakeYouTubeStreamResolver(), clock)
    private val rule = PlayedRule()
    private val startPosition = StartPositionRule()
    private val settings = FakeSettingsRepository()
    private val runtime =
        DesktopPlaybackRuntime(
            PlayStarter(
                FakePlayContextResolver(),
                queue,
                sessions,
                media,
                positions,
                caps,
                local,
                FakeNetworkMonitor(FakeNetworkMonitor.ONLINE),
                clock,
                startPosition,
                rule,
            ),
            QueueWindowPlanner(queue, caps, positions, local, clock, WindowDiff(), startPosition, rule),
            PositionSaver(positions, clock, rule),
            EffectivePlaybackSettings(FakeEffectiveSettingsResolver(), FakeScopeSettingsRepository(), settings),
            sessions,
            queue,
            media,
            positions,
            caps,
            resolver,
            SpanCache(directory),
            engine,
        )
    val controller =
        DesktopPlaybackController(
            test.backgroundScope,
            clock,
            { runtime },
            StandardTestDispatcher(test.testScheduler),
            StandardTestDispatcher(test.testScheduler),
        )

    init {
        val row = testEpisodeRow(1)
        media.items.value =
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
                        "https://media.example/1.mp3",
                        null,
                        null,
                        120_000,
                        null,
                        null,
                    ),
            )
        queue.queue.value = VirtualQueue(1, QueueItem(1, QueueOrigin.CURRENT), emptyList(), emptyList())
        sessions.queue = queue.queue.value
    }

    suspend fun close() {
        controller.shutdown()
        directory.toFile().deleteRecursively()
    }
}
