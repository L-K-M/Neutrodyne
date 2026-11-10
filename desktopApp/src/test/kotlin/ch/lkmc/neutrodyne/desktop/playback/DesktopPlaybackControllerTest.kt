// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.playback

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.model.ChapterSource
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.EpisodeChapter
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.SettingScope
import ch.lkmc.neutrodyne.core.model.SleepTimerMode
import ch.lkmc.neutrodyne.core.model.SleepTimerState
import ch.lkmc.neutrodyne.core.testing.FakeAudioEngine
import ch.lkmc.neutrodyne.core.testing.FakeLevelMutableLogSink
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.downloadEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeStateEntity
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.memberEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.desktop.buildinfo.BuildInfoLoader
import ch.lkmc.neutrodyne.desktop.crash.DesktopCrashReporter
import ch.lkmc.neutrodyne.desktop.di.DesktopAppGraph
import ch.lkmc.neutrodyne.desktop.log.RecentLogBuffer
import ch.lkmc.neutrodyne.desktop.shell.tempAppDirs
import ch.lkmc.neutrodyne.desktop.system.AudioRouteMonitor
import ch.lkmc.neutrodyne.desktop.system.NoOpAudioRouteMonitor
import ch.lkmc.neutrodyne.desktop.system.RouteEvent
import ch.lkmc.neutrodyne.playback.api.PlayResult
import ch.lkmc.neutrodyne.playback.api.PlaybackEvent
import ch.lkmc.neutrodyne.playback.api.PlaybackIssue
import ch.lkmc.neutrodyne.playback.api.PlayerPhase
import ch.lkmc.neutrodyne.playback.api.ScopeWriteResult
import ch.lkmc.neutrodyne.playback.api.UnplayableReason
import ch.lkmc.neutrodyne.playback.desktop.DesktopPlaybackController
import ch.lkmc.neutrodyne.playback.desktop.DesktopPlaybackRuntime
import ch.lkmc.neutrodyne.playback.engine.AudioEngine
import ch.lkmc.neutrodyne.playback.engine.EngineError
import ch.lkmc.neutrodyne.playback.engine.EngineEvent
import ch.lkmc.neutrodyne.playback.engine.EngineState
import ch.lkmc.neutrodyne.playback.engine.Gapless
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.createDynamicGraphFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Real core rules and Room repositories; engine milestones are hardware-free and explicit. */
class DesktopPlaybackControllerTest {
    @Test
    fun playGroupTechConsumesUpNextBeforeNewestUnplayedContext() =
        runBlocking {
            PlaybackFixture().use { fixture ->
                val group = fixture.seed()
                val controller = fixture.controller
                val queue = fixture.graph.queueRepository()
                queue.addLast(listOf(4, 5))
                assertEquals(
                    PlayResult.Started,
                    controller.playFeed(
                        FeedSource.Group(group),
                        FeedFilters(unplayedOnly = true),
                        FeedOrder.NEWEST_FIRST,
                    ),
                )
                assertEquals(listOf(4L, 5L, 3L, 2L, 1L), fixture.engine.items.map { it.episodeId })
                for (id in listOf(4L, 5L, 3L, 2L, 1L)) {
                    fixture.awaitEpisode(id)
                    fixture.engine.ready()
                    fixture.awaitPlaying()
                    fixture.engine.position = 120_000
                    fixture.engine.end()
                    if (id != 1L) {
                        fixture.awaitEpisode(
                            listOf(4L, 5L, 3L, 2L, 1L).let { it[it.indexOf(id) + 1] },
                        )
                    }
                }
                withTimeout(TIMEOUT_MS) { controller.nowPlaying.filter { it == null }.first() }
                assertTrue(queue.observeUpNext().first().isEmpty())
                assertNull(queue.observeSession().first()?.currentEpisodeId)
                for (id in 1L..5L) {
                    assertNotNull(
                        fixture.graph
                            .positionStore()
                            .observe(id)
                            .first()
                            ?.playedAt,
                    )
                }
            }
        }

    @Test
    fun automaticTransitionAdvancesTheSessionAndMarksTheOutgoingPlayed() =
        runBlocking {
            PlaybackFixture().use { fixture ->
                val group = fixture.seed()
                assertEquals(
                    PlayResult.Started,
                    fixture.controller.playFeed(
                        FeedSource.Group(group),
                        FeedFilters(unplayedOnly = true),
                        FeedOrder.NEWEST_FIRST,
                    ),
                )
                fixture.awaitEpisode(3)
                fixture.engine.ready()
                fixture.awaitPlaying()
                fixture.engine.position = 119_000
                fixture.engine.transition(2)
                fixture.awaitEpisode(2)
                // The outgoing item is marked played; the mark-played chain resets
                // its stored position.
                val outgoing =
                    fixture.graph
                        .positionStore()
                        .observe(3)
                        .first()
                assertNotNull(outgoing?.playedAt)
                assertEquals(0L, outgoing?.positionMs)
                assertEquals(
                    2L,
                    fixture.graph
                        .queueRepository()
                        .observeSession()
                        .first()
                        ?.currentEpisodeId,
                )
                // The engine's window dropped the outgoing item and kept the tail.
                fixture.awaitWindow(listOf(2L, 1L))
                // The arrival plays on without a restart and saves normally.
                fixture.engine.position = 50_000
                fixture.controller.pause()
                fixture.awaitPaused()
                assertEquals(50_000L, fixture.awaitPosition(2, 50_000L))
            }
        }

    @Test
    fun automaticTransitionsDrainToTheLastItemAndEndTheSession() =
        runBlocking {
            PlaybackFixture().use { fixture ->
                val group = fixture.seed()
                assertEquals(
                    PlayResult.Started,
                    fixture.controller.playFeed(
                        FeedSource.Group(group),
                        FeedFilters(unplayedOnly = true),
                        FeedOrder.NEWEST_FIRST,
                    ),
                )
                val window = listOf(3L, 2L, 1L)
                for (index in window.indices) {
                    val id = window[index]
                    fixture.awaitEpisode(id)
                    fixture.engine.ready()
                    fixture.awaitPlaying()
                    fixture.engine.position = 119_000
                    if (index < window.lastIndex) {
                        fixture.engine.transition(window[index + 1])
                    } else {
                        fixture.engine.end()
                    }
                }
                withTimeout(TIMEOUT_MS) {
                    fixture.controller.nowPlaying
                        .filter { it == null }
                        .first()
                }
                for (id in window) {
                    assertNotNull(
                        fixture.graph
                            .positionStore()
                            .observe(id)
                            .first()
                            ?.playedAt,
                    )
                }
                assertNull(
                    fixture.graph
                        .queueRepository()
                        .observeSession()
                        .first()
                        ?.currentEpisodeId,
                )
            }
        }

    /**
     * The projector's applied generation only mirrors committed session generations and
     * can lag it (emissions conflate, an apply is skipped or fails mid-handler). An
     * automatic transition the engine already made must still commit against the live
     * session; expecting the lagged generation drops it and desyncs playback for good.
     */
    @Test
    fun anAutomaticTransitionCommitsWhenTheSessionIsAheadOfTheProjection() =
        runBlocking {
            PlaybackFixture().use { fixture ->
                val group = fixture.seed()
                assertEquals(
                    PlayResult.Started,
                    fixture.controller.playFeed(
                        FeedSource.Group(group),
                        FeedFilters(unplayedOnly = true),
                        FeedOrder.NEWEST_FIRST,
                    ),
                )
                fixture.awaitEpisode(3)
                fixture.engine.ready()
                fixture.awaitPlaying()
                fixture.engine.position = 119_000
                fixture.engine.transition(2)
                fixture.awaitEpisode(2)
                // Advance the persisted generation behind the controller's back, as a
                // coalesced emission or a competing writer would leave the projector.
                val sessions =
                    fixture.graph.databaseOpener
                        .requireDatabase()
                        .playSessionDao()
                val session = requireNotNull(sessions.get())
                sessions.write(session.copy(generation = session.generation + 1))
                fixture.engine.transition(1)
                fixture.awaitEpisode(1)
                assertEquals(
                    1L,
                    fixture.graph
                        .queueRepository()
                        .observeSession()
                        .first()
                        ?.currentEpisodeId,
                )
                // The drain completes: the last item's end still finishes the session.
                fixture.engine.ready()
                fixture.awaitPlaying()
                fixture.engine.position = 119_000
                fixture.engine.end()
                withTimeout(TIMEOUT_MS) {
                    fixture.controller.nowPlaying
                        .filter { it == null }
                        .first()
                }
                assertNull(
                    fixture.graph
                        .queueRepository()
                        .observeSession()
                        .first()
                        ?.currentEpisodeId,
                )
            }
        }

    @Test
    fun coalescedEndsOfShortItemsStillDrainTheQueue() =
        runBlocking {
            PlaybackFixture().use { fixture ->
                val group = fixture.seed()
                fixture.engine.onPlay = { fixture.engine.end() }
                assertEquals(
                    PlayResult.Started,
                    fixture.controller.playFeed(
                        FeedSource.Group(group),
                        FeedFilters(unplayedOnly = true),
                        FeedOrder.NEWEST_FIRST,
                    ),
                )
                withTimeout(TIMEOUT_MS) {
                    fixture.controller.nowPlaying
                        .filter { it == null }
                        .first()
                }
                assertEquals(
                    listOf(3L, 2L, 1L),
                    fixture.engine.windows
                        .filter { it.isNotEmpty() }
                        .map { it.first().episodeId },
                )
            }
        }

    @Test
    fun pauseSeekAndQuitSaveThroughSharedZeroGuard() =
        runBlocking {
            PlaybackFixture().use { fixture ->
                fixture.seed()
                assertEquals(PlayResult.Started, fixture.controller.playEpisode(1))
                fixture.engine.ready()
                fixture.awaitPlaying()
                fixture.engine.position = 41_000
                fixture.controller.pause()
                fixture.awaitPaused()
                assertEquals(41_000L, fixture.awaitPosition(1, 41_000L))
                fixture.controller.seekTo(0)
                withTimeout(TIMEOUT_MS) {
                    fixture.graph
                        .positionStore()
                        .observe(1)
                        .filter { it?.positionMs == 0L }
                        .first()
                }
                fixture.controller.seekTo(17_000)
                withTimeout(TIMEOUT_MS) {
                    fixture.graph
                        .positionStore()
                        .observe(1)
                        .filter { it?.positionMs == 17_000L }
                        .first()
                }
                fixture.engine.position = 0
                fixture.controller.pause()
                fixture.controller.shutdown()
                assertEquals(17_000L, fixture.awaitPosition(1, 17_000L))
                assertTrue(fixture.engine.closed)
                assertTrue(fixture.engine.commands.indexOf("speed") < fixture.engine.commands.indexOf("window"))
            }
        }

    @Test
    fun manualNextSavesOutgoingAndPreviousUsesSharedHistory() =
        runBlocking {
            PlaybackFixture().use { fixture ->
                fixture.seed()
                fixture.graph.queueRepository().addLast(listOf(2, 3))
                fixture.controller.playEpisode(1)
                fixture.engine.ready()
                fixture.awaitPlaying()
                fixture.engine.position = 42_000
                fixture.controller.skipToNext()
                fixture.awaitEpisode(2)
                assertEquals(42_000L, fixture.awaitPosition(1, 42_000L))
                fixture.controller.skipToPrevious()
                fixture.awaitEpisode(1)
                assertEquals(
                    2L,
                    fixture.graph
                        .queueRepository()
                        .observeUpNext()
                        .first()
                        .first()
                        .row.id,
                )
            }
        }

    @Test
    fun liveQueueAndSpeedChangesNeverPrepareCurrentAgain() =
        runBlocking {
            PlaybackFixture().use { fixture ->
                fixture.seed()
                fixture.controller.playEpisode(1)
                fixture.engine.ready()
                fixture.awaitPlaying()
                val count = fixture.engine.windows.size
                fixture.controller.playLast(listOf(2, 3))
                withTimeout(TIMEOUT_MS) {
                    fixture.controller.nowPlaying
                        .filter { it?.hasNext == true }
                        .first()
                }
                assertEquals(listOf(1L, 2L, 3L), fixture.engine.items.map { it.episodeId })
                assertEquals(ScopeWriteResult.APPLIED, fixture.controller.setSpeed(1.75f, SettingScope.GLOBAL))
                withTimeout(TIMEOUT_MS) {
                    fixture.controller.effectivePlayback
                        .filter { it?.speed?.value == 1.75f }
                        .first()
                }
                // The controller's settings collector applies the value to the engine after
                // publishing it, so wait for the engine rather than the flow.
                withTimeout(TIMEOUT_MS) {
                    while (fixture.engine.speed != 1.75f) delay(5)
                }
                assertEquals(1.75f, fixture.engine.speed)
                assertEquals(ScopeWriteResult.APPLIED, fixture.controller.setSkipSilence(true, SettingScope.GLOBAL))
                withTimeout(TIMEOUT_MS) {
                    fixture.controller.effectivePlayback
                        .filter { it?.skipSilence?.value == true }
                        .first()
                }
                withTimeout(TIMEOUT_MS) {
                    while (!fixture.engine.skipSilence) delay(5)
                }
                assertTrue(fixture.engine.skipSilence)
                assertEquals(count, fixture.engine.windows.size)
            }
        }

    @Test
    fun restoredSessionShowsSavedItemWithoutPreparingAudio() =
        runBlocking {
            PlaybackFixture().use { fixture ->
                fixture.seed()
                fixture.controller.playEpisode(1)
                fixture.engine.ready()
                fixture.awaitPlaying()
                fixture.engine.position = 38_000
                fixture.controller.shutdown()
                val restoredEngine = FakeAudioEngine()
                val restored = fixture.newController(restoredEngine)
                try {
                    restored.initialize()
                    withTimeout(TIMEOUT_MS) { restored.nowPlaying.filter { it?.episodeId == 1L }.first() }
                    // 11 Device loss, sleep and session restore: the saved item is
                    // published paused at its position, and nothing is resolved,
                    // fetched or decoded (no engine window) until Play.
                    assertEquals(PlayerPhase.NOT_LOADED, restored.nowPlaying.value?.phase)
                    assertFalse(restored.nowPlaying.value!!.isPlaying)
                    assertFalse(restored.nowPlaying.value!!.playWhenReady)
                    assertEquals(
                        38_000L,
                        restored.nowPlaying.value
                            ?.position
                            ?.positionMs,
                    )
                    assertTrue(restoredEngine.windows.isEmpty())
                    assertEquals(PlayResult.Started, restored.play())
                    assertEquals(38_000L, restoredEngine.position)
                } finally {
                    restored.shutdown()
                }
            }
        }

    @Test
    fun embeddedChaptersReachTheSharedRepositoryAndNavigationSeeks() =
        runBlocking {
            PlaybackFixture().use { fixture ->
                fixture.seed()
                val chapters =
                    listOf(
                        EpisodeChapter(0, 1_000, "Intro", null, null, ChapterSource.ID3, false),
                        EpisodeChapter(1_000, 2_000, "Middle", null, null, ChapterSource.ID3, false),
                    )
                fixture.engine.onWindow = {
                    fixture.engine.events.tryEmit(
                        EngineEvent.TracksKnown(1, 120_000, chapters, Gapless(0, 0, 0)),
                    )
                }
                assertEquals(PlayResult.Started, fixture.controller.playEpisode(1))
                fixture.engine.ready()
                fixture.awaitPlaying()
                withTimeout(TIMEOUT_MS) {
                    fixture.graph
                        .databaseOpener
                        .requireDatabase()
                        .chapterDao()
                        .observe(1)
                        .filter { it.size == 2 }
                        .first()
                }
                withTimeout(TIMEOUT_MS) {
                    fixture.controller.currentChapter
                        .filter { it != null }
                        .first()
                }
                assertEquals(
                    0,
                    fixture.controller.currentChapter.value
                        ?.index,
                )
                fixture.controller.nextChapter()
                withTimeout(TIMEOUT_MS) {
                    fixture.controller.nowPlaying
                        .filter { it?.position?.positionMs == 1_000L }
                        .first()
                }
                withTimeout(TIMEOUT_MS) {
                    fixture.controller.currentChapter
                        .filter { it?.index == 1 }
                        .first()
                }
                assertEquals(1_000L, fixture.engine.position)
            }
        }

    @Test
    fun endOfEpisodeSleepMarksTheItemPlayedAndKeepsTheSession() =
        runBlocking {
            PlaybackFixture().use { fixture ->
                fixture.seed()
                assertEquals(PlayResult.Started, fixture.controller.playEpisode(1))
                fixture.engine.ready()
                fixture.awaitPlaying()
                fixture.controller.setSleepTimer(SleepTimerMode.EndOfEpisode)
                withTimeout(TIMEOUT_MS) {
                    fixture.controller.sleepTimer
                        .filter { it == SleepTimerState.EndOfEpisode(1) }
                        .first()
                }
                fixture.engine.end()
                withTimeout(TIMEOUT_MS) {
                    fixture.controller.sleepTimer
                        .filter { it == SleepTimerState.Off }
                        .first()
                }
                // The played chain resets the position row, so the played mark is
                // read from the episode state rather than PositionStore.observe.
                val playedAt =
                    fixture.graph
                        .databaseOpener
                        .requireDatabase()
                        .episodeStateDao()
                        .byEpisode(1)
                        ?.playedAt
                assertNotNull(playedAt)
                assertEquals(1f, fixture.engine.fade)
                assertEquals(
                    1L,
                    fixture.graph
                        .queueRepository()
                        .observeSession()
                        .first()
                        ?.currentEpisodeId,
                )
                assertFalse(
                    fixture.controller.nowPlaying.value!!
                        .isPlaying,
                )
            }
        }

    /** A user seek on the completed boundary item arms the replay; the played mark clears at the
     *  first actual playback and saves resume (06's M5 note). */
    @Test
    fun aSeekOnTheCompletedBoundaryItemStartsAReplay() =
        runBlocking {
            PlaybackFixture().use { fixture ->
                fixture.seed()
                assertEquals(PlayResult.Started, fixture.controller.playEpisode(1))
                fixture.engine.ready()
                fixture.awaitPlaying()
                fixture.controller.setSleepTimer(SleepTimerMode.EndOfEpisode)
                withTimeout(TIMEOUT_MS) {
                    fixture.controller.sleepTimer
                        .filter { it == SleepTimerState.EndOfEpisode(1) }
                        .first()
                }
                fixture.engine.end()
                withTimeout(TIMEOUT_MS) {
                    fixture.controller.sleepTimer
                        .filter { it == SleepTimerState.Off }
                        .first()
                }
                val db = fixture.graph.databaseOpener.requireDatabase()
                assertNotNull(db.episodeStateDao().byEpisode(1)?.playedAt)
                // Time passes before the user seeks, so the armed replay's saves clear the guard.
                fixture.clock.advanceBy(10_000.milliseconds)
                fixture.controller.seekTo(5_000)
                withTimeout(TIMEOUT_MS) {
                    while (fixture.engine.position != 5_000L) delay(5)
                }
                assertEquals(5_000L, fixture.engine.position)
                // Seeking while paused arms the replay but does not clear the played mark yet.
                assertNotNull(db.episodeStateDao().byEpisode(1)?.playedAt)
                assertEquals(PlayResult.Started, fixture.controller.play())
                fixture.engine.ready()
                fixture.awaitPlaying()
                withTimeout(TIMEOUT_MS) {
                    fixture.graph
                        .positionStore()
                        .observe(1)
                        .filter { it?.playedAt == null }
                        .first()
                }
                fixture.engine.position = 7_000
                fixture.controller.pause()
                fixture.awaitPaused()
                assertEquals(7_000L, fixture.awaitPosition(1, 7_000L))
            }
        }

    /** A Play on the completed boundary item replays it from the start; the played mark clears at
     *  the first actual playback. */
    @Test
    fun playOnTheCompletedBoundaryItemReplaysFromTheStart() =
        runBlocking {
            PlaybackFixture().use { fixture ->
                fixture.seed()
                assertEquals(PlayResult.Started, fixture.controller.playEpisode(1))
                fixture.engine.ready()
                fixture.awaitPlaying()
                fixture.controller.setSleepTimer(SleepTimerMode.EndOfEpisode)
                withTimeout(TIMEOUT_MS) {
                    fixture.controller.sleepTimer
                        .filter { it == SleepTimerState.EndOfEpisode(1) }
                        .first()
                }
                fixture.engine.end()
                withTimeout(TIMEOUT_MS) {
                    fixture.controller.sleepTimer
                        .filter { it == SleepTimerState.Off }
                        .first()
                }
                val db = fixture.graph.databaseOpener.requireDatabase()
                assertNotNull(db.episodeStateDao().byEpisode(1)?.playedAt)
                fixture.clock.advanceBy(10_000.milliseconds)
                assertEquals(PlayResult.Started, fixture.controller.play())
                assertEquals(0L, fixture.engine.position)
                fixture.engine.ready()
                fixture.awaitPlaying()
                withTimeout(TIMEOUT_MS) {
                    fixture.graph
                        .positionStore()
                        .observe(1)
                        .filter { it?.playedAt == null }
                        .first()
                }
                fixture.engine.position = 9_000
                fixture.controller.pause()
                fixture.awaitPaused()
                assertEquals(9_000L, fixture.awaitPosition(1, 9_000L))
            }
        }

    @Test
    fun missingEpisodeAndOfflineStartLeaveSessionAndEngineUntouched() =
        runBlocking {
            PlaybackFixture().use { fixture ->
                fixture.seed()
                assertTrue(fixture.controller.playEpisode(999) is PlayResult.NotPlayable)
                fixture.network.setStatus(FakeNetworkMonitor.OFFLINE)
                assertEquals(PlayResult.Offline, fixture.controller.playEpisode(1))
                assertTrue(fixture.engine.windows.isEmpty())
                assertFalse("play" in fixture.engine.commands)
                assertNull(
                    fixture.graph
                        .queueRepository()
                        .observeSession()
                        .first()
                        ?.currentEpisodeId,
                )
            }
        }

    @Test
    fun playDownloadsStartsTheRequestedEpisodeInTheDownloadsContext() =
        runBlocking {
            PlaybackFixture().use { fixture ->
                fixture.seed()
                // The Downloads context only admits completed downloads (FeedDao's context query).
                fixture.graph.databaseOpener
                    .requireDatabase()
                    .downloadDao()
                    .insert(downloadEntity(3))
                assertEquals(PlayResult.Started, fixture.controller.playDownloads(3))
                fixture.awaitEpisode(3)
                assertEquals(
                    3L,
                    fixture.engine.items
                        .first()
                        .episodeId,
                )
                assertEquals(
                    ContextType.DOWNLOADS,
                    fixture.graph
                        .queueRepository()
                        .observeSession()
                        .first()
                        ?.context
                        ?.type,
                )
            }
        }

    @Test
    fun anHttpErrorSkipsTheItemWithASkippedEvent() =
        runBlocking {
            PlaybackFixture().use { fixture ->
                val group = fixture.seed()
                val skipped = mutableListOf<PlaybackEvent.Skipped>()
                val collector =
                    launch {
                        fixture.controller.events.collect {
                            if (it is PlaybackEvent.Skipped) {
                                skipped +=
                                    it
                            }
                        }
                    }
                try {
                    assertEquals(
                        PlayResult.Started,
                        fixture.controller.playFeed(
                            FeedSource.Group(group),
                            FeedFilters(unplayedOnly = true),
                            FeedOrder.NEWEST_FIRST,
                        ),
                    )
                    fixture.awaitEpisode(3)
                    fixture.engine.ready()
                    fixture.awaitPlaying()
                    fixture.engine.position = 42_000
                    fixture.engine.state.value = EngineState.Error(3, EngineError.Http(404))
                    fixture.awaitEpisode(2)
                    // The collector receives the Skipped event asynchronously; wait for it.
                    withTimeout(TIMEOUT_MS) {
                        while (skipped.isEmpty()) delay(5)
                    }
                    assertEquals(
                        listOf(PlaybackEvent.Skipped(3, "Episode 3", UnplayableReason.Http(404))),
                        skipped,
                    )
                    assertEquals(42_000L, fixture.awaitPosition(3, 42_000L))
                    assertNull(
                        fixture.graph
                            .positionStore()
                            .observe(3)
                            .first()
                            ?.playedAt,
                    )
                } finally {
                    collector.cancel()
                }
            }
        }

    @Test
    fun fiveConsecutiveSkipsThenPauseWithPlayerError() =
        runBlocking {
            PlaybackFixture().use { fixture ->
                val group = fixture.seed()
                fixture.seedEpisodes(7L..12L)
                assertEquals(
                    PlayResult.Started,
                    fixture.controller.playFeed(
                        FeedSource.Group(group),
                        FeedFilters(unplayedOnly = true),
                        FeedOrder.NEWEST_FIRST,
                    ),
                )
                for (id in listOf(12L, 11L, 10L, 9L, 8L)) {
                    fixture.awaitEpisode(id)
                    fixture.engine.ready()
                    fixture.awaitPlaying()
                    fixture.engine.state.value = EngineState.Error(id, EngineError.Http(404))
                }
                fixture.awaitEpisode(7)
                fixture.engine.ready()
                fixture.awaitPlaying()
                // The sixth unplayable item pauses instead of spinning the queue.
                fixture.engine.state.value = EngineState.Error(7, EngineError.Http(404))
                withTimeout(TIMEOUT_MS) {
                    fixture.controller.nowPlaying
                        .filter { it?.issue == PlaybackIssue.PLAYER_ERROR }
                        .first()
                }
                assertFalse(
                    fixture.controller.nowPlaying.value!!
                        .isPlaying,
                )
            }
        }

    @Test
    fun aNetworkErrorKeepsTheItemAndRepreparesWhenOnline() =
        runBlocking {
            PlaybackFixture().use { fixture ->
                fixture.seed()
                assertEquals(PlayResult.Started, fixture.controller.playEpisode(1))
                fixture.awaitEpisode(1)
                fixture.engine.ready()
                fixture.awaitPlaying()
                fixture.engine.position = 30_000
                fixture.engine.state.value = EngineState.Error(1, EngineError.Network)
                withTimeout(TIMEOUT_MS) {
                    fixture.controller.nowPlaying
                        .filter { it?.issue == PlaybackIssue.NETWORK_LOST }
                        .first()
                }
                assertFalse(
                    fixture.controller.nowPlaying.value!!
                        .isPlaying,
                )
                assertEquals(30_000L, fixture.awaitPosition(1, 30_000L))
                val windows = fixture.engine.windows.size
                // Let the controller's collector observe the loss before the
                // recovery; a StateFlow conflates synchronous flips.
                fixture.network.setStatus(FakeNetworkMonitor.OFFLINE)
                delay(ROUTE_TURN_MS)
                fixture.network.setStatus(FakeNetworkMonitor.ONLINE)
                withTimeout(TIMEOUT_MS) {
                    fixture.controller.nowPlaying
                        .filter { it?.issue == null && it?.episodeId == 1L }
                        .first()
                }
                assertTrue(fixture.engine.windows.size > windows, "the network recovery must re-prepare")
            }
        }

    @Test
    fun aMissingLocalFileReResolvesAtTheSamePosition() =
        runBlocking {
            PlaybackFixture().use { fixture ->
                fixture.seed()
                assertEquals(PlayResult.Started, fixture.controller.playEpisode(1))
                fixture.awaitEpisode(1)
                fixture.engine.ready()
                fixture.awaitPlaying()
                fixture.engine.position = 20_000
                val windows = fixture.engine.windows.size
                fixture.engine.state.value = EngineState.Error(1, EngineError.LocalFileMissing)
                withTimeout(TIMEOUT_MS) {
                    fixture.controller.nowPlaying
                        .filter { it?.issue == PlaybackIssue.LOCAL_FILE_MISSING }
                        .first()
                }
                assertTrue(fixture.engine.windows.size > windows, "the missing file must re-resolve")
                // The issue is held until playback actually resumes.
                fixture.engine.ready()
                withTimeout(TIMEOUT_MS) {
                    fixture.controller.nowPlaying
                        .filter { it?.issue == null }
                        .first()
                }
                Unit
            }
        }

    @Test
    fun aContentChangedErrorSkipsWithTheRangeReason() =
        runBlocking {
            PlaybackFixture().use { fixture ->
                val group = fixture.seed()
                val skipped = mutableListOf<PlaybackEvent.Skipped>()
                val collector =
                    launch {
                        fixture.controller.events.collect {
                            if (it is PlaybackEvent.Skipped) {
                                skipped +=
                                    it
                            }
                        }
                    }
                try {
                    assertEquals(
                        PlayResult.Started,
                        fixture.controller.playFeed(
                            FeedSource.Group(group),
                            FeedFilters(unplayedOnly = true),
                            FeedOrder.NEWEST_FIRST,
                        ),
                    )
                    fixture.awaitEpisode(3)
                    fixture.engine.ready()
                    fixture.awaitPlaying()
                    fixture.engine.state.value = EngineState.Error(3, EngineError.ContentChanged)
                    fixture.awaitEpisode(2)
                    // The collector receives the Skipped event asynchronously; wait for it.
                    withTimeout(TIMEOUT_MS) {
                        while (skipped.isEmpty()) delay(5)
                    }
                    assertEquals(
                        listOf(PlaybackEvent.Skipped(3, "Episode 3", UnplayableReason.Http(416))),
                        skipped,
                    )
                } finally {
                    collector.cancel()
                }
            }
        }

    @Test
    fun aSuccessorPrepareFailureSkipsTheSuccessorAndTriesTheNext() =
        runBlocking {
            PlaybackFixture().use { fixture ->
                val group = fixture.seed()
                val skipped = mutableListOf<PlaybackEvent.Skipped>()
                val collector =
                    launch {
                        fixture.controller.events.collect {
                            if (it is PlaybackEvent.Skipped) {
                                skipped +=
                                    it
                            }
                        }
                    }
                try {
                    assertEquals(
                        PlayResult.Started,
                        fixture.controller.playFeed(
                            FeedSource.Group(group),
                            FeedFilters(unplayedOnly = true),
                            FeedOrder.NEWEST_FIRST,
                        ),
                    )
                    fixture.awaitEpisode(3)
                    fixture.engine.ready()
                    fixture.awaitPlaying()
                    fixture.engine.position = 119_000
                    // The engine reports a failure preparing item 2 at the boundary.
                    fixture.engine.state.value = EngineState.Error(2, EngineError.Http(404))
                    fixture.awaitEpisode(1)
                    // The Skipped event is emitted before the session advances, but the
                    // collector receives it asynchronously; wait for the event itself.
                    withTimeout(TIMEOUT_MS) {
                        while (skipped.isEmpty()) delay(5)
                    }
                    assertEquals(
                        listOf(PlaybackEvent.Skipped(2, "Episode 2", UnplayableReason.Http(404))),
                        skipped,
                    )
                    // The outgoing item ended naturally, so it is committed and marked played.
                    assertNotNull(
                        fixture.graph
                            .positionStore()
                            .observe(3)
                            .first()
                            ?.playedAt,
                    )
                    Unit
                } finally {
                    collector.cancel()
                }
            }
        }

    @Test
    fun anOutputErrorPausesSavesAndReopensAtTheNextPlay() =
        runBlocking {
            PlaybackFixture().use { fixture ->
                fixture.seed()
                assertEquals(PlayResult.Started, fixture.controller.playEpisode(1))
                fixture.awaitEpisode(1)
                fixture.engine.ready()
                fixture.awaitPlaying()
                fixture.engine.position = 41_000
                fixture.engine.onDeviceRemoved()
                fixture.awaitPaused()
                assertEquals(41_000L, fixture.awaitPosition(1, 41_000L))
                assertEquals(PlayResult.Started, fixture.controller.play())
                withTimeout(TIMEOUT_MS) {
                    fixture.controller.nowPlaying
                        .filter { it?.episodeId == 1L && it.playWhenReady }
                        .first()
                }
                assertEquals(41_000L, fixture.engine.position)
            }
        }

    @Test
    fun aRemovedRoutePausesAndSavesAndDefaultChangeKeepsPlaying() =
        runBlocking {
            val route = FakeAudioRouteMonitor()
            PlaybackFixture(route).use { fixture ->
                fixture.seed()
                assertEquals(PlayResult.Started, fixture.controller.playEpisode(1))
                fixture.awaitEpisode(1)
                fixture.engine.ready()
                fixture.awaitPlaying()
                fixture.engine.position = 41_000
                route.defaultChanged()
                delay(ROUTE_TURN_MS)
                assertTrue(
                    fixture.controller.nowPlaying.value!!
                        .isPlaying,
                )
                route.removed("device-1")
                fixture.awaitPaused()
                assertEquals(41_000L, fixture.awaitPosition(1, 41_000L))
            }
        }

    private class FakeAudioRouteMonitor : AudioRouteMonitor {
        private val mutable = MutableSharedFlow<RouteEvent>(extraBufferCapacity = 8)
        override val events: Flow<RouteEvent> = mutable.asSharedFlow()

        fun removed(deviceId: String) {
            mutable.tryEmit(RouteEvent.DeviceRemoved(deviceId))
        }

        fun defaultChanged() {
            mutable.tryEmit(RouteEvent.DefaultChanged)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val ROUTE_TURN_MS = 100L
    }
}

internal class PlaybackFixture(
    private val routeMonitor: AudioRouteMonitor = NoOpAudioRouteMonitor,
) : AutoCloseable {
    val clock = TestClock()
    val engine = FakeAudioEngine()
    val network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE)
    private val dirs = tempAppDirs("md1a2-playback").also { it.ensureCreated() }
    private val info = BuildInfoLoader.load()
    val graph =
        createDynamicGraphFactory<DesktopAppGraph.Factory>(
            PlaybackTestBindings(engine, clock, network, routeMonitor),
        ).create(dirs, info, DesktopCrashReporter(dirs, info, clock, RecentLogBuffer()), FakeLevelMutableLogSink())
    val controller get() = graph.desktopPlaybackController

    suspend fun seed(): Long {
        graph.databaseOpener.awaitOpen()
        val db = graph.databaseOpener.requireDatabase()
        db.podcastDao().insertPodcast(podcastEntity(id = 1, title = "Tech"))
        db.podcastDao().insertPodcast(podcastEntity(id = 2, title = "Queued"))
        val group =
            db.groupDao().insertGroupOrIgnore(
                groupEntity(uuid = "550e8400-e29b-41d4-a716-446655440000", name = "tech", orderKey = "a"),
            )
        db.groupDao().insertMember(memberEntity(group, 1, "a"))
        for (id in 1L..6L) {
            db.episodeDao().insertStub(
                episodeEntity(
                    id = id,
                    podcastId = if (id <= 3 || id == 6L) 1 else 2,
                    title = "Episode $id",
                    sortDate = clock.now() - (7 - id) * 1_000,
                ) {
                    copy(
                        enclosureUrl = "https://media.example/$id.mp3",
                        enclosureType = "audio/mpeg",
                        durationMs = 120_000,
                    )
                },
            )
        }
        db.episodeStateDao().upsert(episodeStateEntity(episodeId = 6, playedAt = clock.now() - 1_000))
        return group
    }

    suspend fun awaitEpisode(id: Long) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            try {
                withTimeout(10_000) { controller.nowPlaying.filter { it?.episodeId == id }.first() }
            } catch (failure: Exception) {
                throw AssertionError(
                    "Expected episode $id; now=${controller.nowPlaying.value}; " +
                        "engine=${engine.state.value}; session=${graph.playSessionStore().virtualQueue(20)}",
                    failure,
                )
            }
        }
    }

    suspend fun awaitPlaying() {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            try {
                withTimeout(10_000) { controller.nowPlaying.filter { it?.isPlaying == true }.first() }
            } catch (failure: Exception) {
                throw AssertionError(
                    "Expected playing; now=${controller.nowPlaying.value}; " +
                        "engine=${engine.state.value}; session=${graph.playSessionStore().virtualQueue(20)}",
                    failure,
                )
            }
        }
    }

    suspend fun awaitWindow(ids: List<Long>) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            withTimeout(10_000) {
                while (engine.items.map { it.episodeId } != ids) kotlinx.coroutines.delay(5)
            }
        }
    }

    suspend fun awaitPaused() {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            withTimeout(10_000) {
                controller.nowPlaying.filter { it?.isPlaying == false && it.playWhenReady == false }.first()
            }
        }
    }

    suspend fun position(id: Long): Long? =
        graph
            .positionStore()
            .observe(id)
            .first()
            ?.positionMs

    /** Waits for the stored position to reach [expected] (Room's invalidation is async). */
    suspend fun awaitPosition(
        id: Long,
        expected: Long,
    ): Long? =
        withTimeout(10_000L) {
            graph
                .positionStore()
                .observe(id)
                .filter { it?.positionMs == expected }
                .first()
                ?.positionMs
        }

    /** Inserts more unplayed episodes into the group's podcast, newest last. */
    suspend fun seedEpisodes(ids: LongRange) {
        val db = graph.databaseOpener.requireDatabase()
        for (id in ids) {
            db.episodeDao().insertStub(
                episodeEntity(
                    id = id,
                    podcastId = 1,
                    title = "Episode $id",
                    sortDate = clock.now() + (id - 6) * 1_000,
                ) {
                    copy(
                        enclosureUrl = "https://media.example/$id.mp3",
                        enclosureType = "audio/mpeg",
                        durationMs = 120_000,
                    )
                },
            )
        }
    }

    fun newController(
        engine: FakeAudioEngine,
        routeMonitor: AudioRouteMonitor = NoOpAudioRouteMonitor,
    ): DesktopPlaybackController {
        runBlocking {
            graph.appScope.coroutineContext[Job]!!.cancelAndJoin()
        }
        graph.databaseOpener.requireDatabase().close()
        retiredOriginal = true
        val bindings = PlaybackTestBindings(engine, clock, network, routeMonitor)
        val other =
            createDynamicGraphFactory<DesktopAppGraph.Factory>(bindings)
                .create(
                    dirs,
                    info,
                    DesktopCrashReporter(dirs, info, clock, RecentLogBuffer()),
                    FakeLevelMutableLogSink(),
                )
        // Both graphs open the same on-disk DB, as a subsequent app process would.
        runBlocking { other.databaseOpener.awaitOpen() }
        extraGraphs += other
        return other.desktopPlaybackController
    }

    private val extraGraphs = mutableListOf<DesktopAppGraph>()
    private var retiredOriginal = false

    override fun close() {
        runBlocking {
            for (value in extraGraphs + if (retiredOriginal) emptyList() else listOf(graph)) {
                value.desktopPlaybackController.shutdown()
                value.appScope.coroutineContext[Job]!!.cancelAndJoin()
                value.databaseOpener.requireDatabase().close()
            }
        }
        dirs.data.parent
            .toFile()
            .deleteRecursively()
    }
}

@BindingContainer
private class PlaybackTestBindings(
    private val engine: FakeAudioEngine,
    private val clock: Clock,
    private val network: NetworkMonitor,
    private val routeMonitor: AudioRouteMonitor,
) {
    @Provides fun engine(): AudioEngine = engine

    @Provides fun clock(): Clock = clock

    @Provides fun network(): NetworkMonitor = network

    @Provides fun routeMonitor(): AudioRouteMonitor = routeMonitor
}
