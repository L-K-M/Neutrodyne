// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.desktop

import ch.lkmc.neutrodyne.core.domain.PlaybackSyncActive
import ch.lkmc.neutrodyne.core.domain.PlaybackSyncEvent
import ch.lkmc.neutrodyne.core.domain.PositionStore
import ch.lkmc.neutrodyne.core.domain.StoredEpisodeMarks
import ch.lkmc.neutrodyne.core.domain.StoredPosition
import ch.lkmc.neutrodyne.core.model.PlaybackMediaInfo
import ch.lkmc.neutrodyne.core.model.QueueItem
import ch.lkmc.neutrodyne.core.model.QueueOrigin
import ch.lkmc.neutrodyne.core.model.VirtualQueue
import ch.lkmc.neutrodyne.core.testing.FakeAudioEngine
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
import ch.lkmc.neutrodyne.playback.core.EffectivePlaybackSettings
import ch.lkmc.neutrodyne.playback.core.PlayStarter
import ch.lkmc.neutrodyne.playback.core.PlayedRule
import ch.lkmc.neutrodyne.playback.core.PositionSaver
import ch.lkmc.neutrodyne.playback.core.QueueWindowPlanner
import ch.lkmc.neutrodyne.playback.core.RemotePlayedGuard
import ch.lkmc.neutrodyne.playback.core.StartPositionRule
import ch.lkmc.neutrodyne.playback.core.WindowDiff
import ch.lkmc.neutrodyne.playback.engine.EngineEvent
import ch.lkmc.neutrodyne.playback.engine.Gapless
import ch.lkmc.neutrodyne.playback.engine.SpanCache
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 06 Sync interplay, the desktop `PlaybackSyncPort`: `active` tracks the loaded item, the
 * `Paused`/`Stopped`/`Transition` boundaries follow their saves or transactions, and a remote
 * mark-played freezes without stopping, seeking or skipping the loaded item.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopPlaybackSyncPortTest {
    @Test
    fun activeFollowsTheLoadedItemAndItsPlayState() =
        runTest {
            val fixture = SyncPortFixture(this)
            try {
                assertEquals(PlaybackSyncActive(false), fixture.port.active.value)
                fixture.controller.play()
                fixture.engine.ready()
                runCurrent()
                assertEquals(PlaybackSyncActive(true, 1), fixture.port.active.value)
                fixture.controller.pause()
                runCurrent()
                assertEquals(PlaybackSyncActive(false, 1), fixture.port.active.value)
                fixture.controller.dismiss()
                runCurrent()
                assertEquals(PlaybackSyncActive(false), fixture.port.active.value)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun pausedFollowsThePauseSave() =
        runTest {
            val fixture = SyncPortFixture(this)
            try {
                fixture.controller.play()
                fixture.engine.ready()
                runCurrent()
                fixture.engine.position = 15_000
                fixture.controller.pause()
                runCurrent()
                assertTrue("save:1:15000" in fixture.timeline)
                assertTrue("event:Paused" in fixture.timeline)
                assertTrue(
                    fixture.timeline.indexOf("save:1:15000") < fixture.timeline.indexOf("event:Paused"),
                    "the Paused boundary must follow the pause save: ${fixture.timeline}",
                )
            } finally {
                fixture.close()
            }
        }

    @Test
    fun stoppedFollowsTheDismissClear() =
        runTest {
            val fixture = SyncPortFixture(this)
            try {
                fixture.controller.play()
                fixture.engine.ready()
                runCurrent()
                fixture.engine.position = 8_000
                fixture.controller.dismiss()
                runCurrent()
                assertTrue("event:Stopped" in fixture.timeline)
                assertTrue(
                    fixture.timeline.indexOf("save:1:8000") < fixture.timeline.indexOf("event:Stopped"),
                    "the Stopped boundary must follow the dismissal save: ${fixture.timeline}",
                )
                assertEquals(PlaybackSyncActive(false), fixture.port.active.value)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun onRemoteMarkedPlayedFreezesSavesAndNeverAdvances() =
        runTest {
            val fixture = SyncPortFixture(this)
            try {
                fixture.controller.play()
                fixture.engine.ready()
                runCurrent()
                // The initial load-paused plan already recorded its pause; only what the
                // remote mark adds may be asserted on.
                val commandsAtFreeze = fixture.engine.commands.size
                fixture.port.onRemoteMarkedPlayed(1, "Pixel")
                runCurrent()

                // The applying projection of the played mark: the item stays current and playing.
                val markedAt = fixture.clock.now()
                fixture.store.markPlayed(1, markedAt)
                runCurrent()
                assertEquals(
                    1L,
                    fixture.controller.nowPlaying.value
                        ?.episodeId,
                )
                assertEquals(
                    true,
                    fixture.controller.nowPlaying.value
                        ?.isPlaying,
                )
                assertTrue(fixture.sessions.calls.none { it.operation == FakePlaySessionStore.Operation.TRANSITION })
                assertTrue(
                    fixture.engine.commands
                        .drop(commandsAtFreeze)
                        .none { it == "seek" || it == "pause" },
                    "a remote played mark never stops or seeks the loaded item: ${fixture.engine.commands.drop(
                        commandsAtFreeze,
                    )}",
                )

                // The freeze stops position writes; the item plays on (06 rule 3).
                fixture.engine.position = 30_000
                advanceTimeBy(5_000)
                runCurrent()
                assertTrue(fixture.store.saves().none { it.id == 1L && it.positionMs == 30_000L })
                val marks = fixture.store.observeMarks(1).first()
                assertEquals(markedAt, marks?.playedAt, "the frozen mark stays applied")
                assertNull(marks?.startedAt)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun freezeLiftsWhenTheRowBecomesUnplayedAgain() =
        runTest {
            val fixture = SyncPortFixture(this)
            try {
                fixture.controller.play()
                fixture.engine.ready()
                runCurrent()
                fixture.port.onRemoteMarkedPlayed(1, "Pixel")
                runCurrent()
                val now = fixture.clock.now()
                fixture.store.positions.value = mapOf(1L to StoredPosition(1, 30_000, now, now, null))
                runCurrent()
                assertTrue(fixture.guard.isFrozen(1))

                // Unplayed again (the user's "Mark unplayed"): the freeze lifts and saves resume.
                fixture.store.positions.value = mapOf(1L to StoredPosition(1, 30_000, now, null, null))
                runCurrent()
                assertFalse(fixture.guard.isFrozen(1))
                fixture.engine.position = 40_000
                advanceTimeBy(5_000)
                runCurrent()
                assertEquals(
                    40_000L,
                    fixture.store.positions.value[1]
                        ?.positionMs,
                )
            } finally {
                fixture.close()
            }
        }

    @Test
    fun aRolledBackMarkReleasesItsFreeze() =
        runTest {
            val fixture = SyncPortFixture(this)
            try {
                fixture.controller.play()
                fixture.engine.ready()
                runCurrent()
                fixture.port.onRemoteMarkedPlayed(1, "Pixel")
                runCurrent()
                assertTrue(fixture.guard.isFrozen(1))

                fixture.port.onRemoteMarkReverted(1)
                runCurrent()
                assertFalse(fixture.guard.isFrozen(1))
            } finally {
                fixture.close()
            }
        }

    @Test
    fun rollingBackARepeatedMarkKeepsWatchingTheCommittedMark() =
        runTest {
            val fixture = SyncPortFixture(this)
            try {
                fixture.controller.play()
                fixture.engine.ready()
                runCurrent()
                fixture.port.onRemoteMarkedPlayed(1, "Pixel")
                val now = fixture.clock.now()
                fixture.store.positions.value = mapOf(1L to StoredPosition(1, 30_000, now, now, null))
                runCurrent()
                assertTrue(fixture.guard.isFrozen(1))

                fixture.port.onRemoteMarkedPlayed(1, "Laptop")
                runCurrent()
                fixture.port.onRemoteMarkReverted(1)
                runCurrent()
                assertTrue(fixture.guard.isFrozen(1), "the first mark committed")

                fixture.store.positions.value = mapOf(1L to StoredPosition(1, 30_000, now, null, null))
                runCurrent()
                assertFalse(fixture.guard.isFrozen(1), "unplayed must lift the surviving mark's freeze")
            } finally {
                fixture.close()
            }
        }

    @Test
    fun automaticTransitionEmitsTheTransitionAfterItsCommit() =
        runTest {
            val fixture = SyncPortFixture(this)
            try {
                fixture.controller.play()
                fixture.engine.ready()
                runCurrent()
                fixture.engine.position = 119_000
                fixture.engine.transition(2)
                runCurrent()
                assertEquals(2L, fixture.port.active.value.episodeId)
                assertTrue(fixture.sessions.calls.any { it.operation == FakePlaySessionStore.Operation.TRANSITION })
                // The start's own boundary, then the automatic transition's after its commit.
                assertEquals(
                    listOf<PlaybackSyncEvent>(PlaybackSyncEvent.Transition(1), PlaybackSyncEvent.Transition(2)),
                    fixture.received,
                )
            } finally {
                fixture.close()
            }
        }

    @Test
    fun automaticTransitionLiftsTheOutgoingFreezeAndGatesTheArrival() =
        runTest {
            val fixture = SyncPortFixture(this)
            try {
                fixture.controller.play()
                fixture.engine.ready()
                runCurrent()
                fixture.port.onRemoteMarkedPlayed(1, "Pixel")
                runCurrent()
                fixture.engine.position = 119_000
                fixture.engine.transition(2)
                runCurrent()
                // A freeze only governs the loaded item (06 rule 3).
                assertFalse(fixture.guard.isFrozen(1))

                // A remote mark-played of the arrival stops its writes like any loaded item's.
                fixture.port.onRemoteMarkedPlayed(2, "Pixel")
                runCurrent()
                fixture.engine.position = 30_000
                advanceTimeBy(15_000)
                runCurrent()
                assertTrue(
                    fixture.store.saves().none { it.id == 2L },
                    "a frozen arrival writes no position: ${fixture.store.saves()}",
                )
            } finally {
                fixture.close()
            }
        }
}

/** Records the store's committed saves beside the port's events, so orderings are provable. */
private class TimelinePositionStore : PositionStore {
    val delegate = FakePositionStore()
    val timeline = mutableListOf<String>()

    val positions get() = delegate.positions

    fun saves() = delegate.calls.filter { it.operation == FakePositionStore.Operation.SAVE }

    override fun observe(episodeId: Long): Flow<StoredPosition?> = delegate.observe(episodeId)

    override fun observeMarks(episodeId: Long): Flow<StoredEpisodeMarks?> = delegate.observeMarks(episodeId)

    override suspend fun setMeasuredDuration(
        episodeId: Long,
        durationMs: Long,
    ) = delegate.setMeasuredDuration(episodeId, durationMs)

    override suspend fun save(
        episodeId: Long,
        positionMs: Long,
        durationMs: Long?,
        source: ch.lkmc.neutrodyne.core.model.PositionSource,
        now: Long,
        pinStartedAt: Long,
        allowed: () -> Boolean,
    ): Boolean {
        val saved = delegate.save(episodeId, positionMs, durationMs, source, now, pinStartedAt, allowed)
        if (saved) timeline += "save:$episodeId:$positionMs"
        return saved
    }

    override suspend fun reset(
        episodeId: Long,
        now: Long,
    ) = delegate.reset(episodeId, now)

    override suspend fun markPlayed(
        episodeId: Long,
        now: Long,
    ) = delegate.markPlayed(episodeId, now)

    override suspend fun markUnplayedForReplay(
        episodeId: Long,
        now: Long,
    ) = delegate.markUnplayedForReplay(episodeId, now)
}

@OptIn(ExperimentalCoroutinesApi::class)
private class SyncPortFixture(
    test: TestScope,
) {
    val clock = TestClock.from(test.testScheduler)
    val engine = FakeAudioEngine()
    val store = TimelinePositionStore()
    val queue = FakeQueueRepository()
    val sessions = FakePlaySessionStore()
    val media = FakePlaybackMediaLookup()
    val guard = RemotePlayedGuard()
    private val local = FakeLocalMediaIndex()
    private val caps = FakeYouTubeCapabilitiesSource()
    private val directory = Files.createTempDirectory("ms3-sync-port")
    private val resolver = DesktopEpisodeSourceResolver(local, caps, FakeYouTubeStreamResolver(), clock)
    private val rule = PlayedRule()
    private val startPosition = StartPositionRule()
    private val settings = FakeSettingsRepository()
    private val network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE)
    private val runtime =
        DesktopPlaybackRuntime(
            PlayStarter(
                FakePlayContextResolver(),
                queue,
                sessions,
                media,
                store,
                caps,
                local,
                network,
                clock,
                startPosition,
                rule,
            ),
            QueueWindowPlanner(queue, caps, store, local, clock, WindowDiff(), startPosition, rule),
            PositionSaver(store, clock, rule),
            EffectivePlaybackSettings(FakeEffectiveSettingsResolver(), FakeScopeSettingsRepository(), settings),
            sessions,
            queue,
            media,
            store,
            caps,
            FakeChapterRepository(),
            resolver,
            SpanCache(directory),
            engine,
        )
    val timeline = store.timeline
    val controller =
        DesktopPlaybackController(
            test.backgroundScope,
            clock,
            { runtime },
            network,
            guard,
            StandardTestDispatcher(test.testScheduler),
            StandardTestDispatcher(test.testScheduler),
        )
    val port = controller.syncPort
    val received = mutableListOf<PlaybackSyncEvent>()

    init {
        val row = testEpisodeRow(1)

        fun media(
            id: Long,
            title: String,
        ) = PlaybackMediaInfo(
            id,
            row.podcastId,
            title,
            row.podcastTitle,
            row.artwork,
            row.sourceType,
            row.availability,
            false,
            "https://media.example/$id.mp3",
            null,
            null,
            120_000,
            null,
            null,
        )
        media.items.value = mapOf(1L to media(1, "First"), 2L to media(2, "Second"))
        val virtual =
            VirtualQueue(
                1,
                QueueItem(1, QueueOrigin.CURRENT),
                listOf(QueueItem(2, QueueOrigin.UP_NEXT)),
                emptyList(),
            )
        queue.queue.value = virtual
        sessions.queue = virtual
        // Emit measured duration as the real engine would, so the played rule has a duration.
        engine.onWindow = {
            engine.events.tryEmit(EngineEvent.TracksKnown(1, 120_000, emptyList(), Gapless(0, 0, 0)))
        }
        test.backgroundScope.launch(StandardTestDispatcher(test.testScheduler)) {
            port.events.collect { event ->
                timeline +=
                    when (event) {
                        PlaybackSyncEvent.Paused -> "event:Paused"
                        PlaybackSyncEvent.Stopped -> "event:Stopped"
                        is PlaybackSyncEvent.Transition -> "event:Transition:${event.episodeId}"
                    }
                received += event
            }
        }
    }

    suspend fun close() {
        controller.shutdown()
        directory.toFile().deleteRecursively()
    }
}
