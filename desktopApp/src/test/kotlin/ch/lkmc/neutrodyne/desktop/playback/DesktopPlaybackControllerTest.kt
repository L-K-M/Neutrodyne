// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.playback

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.SettingScope
import ch.lkmc.neutrodyne.core.testing.FakeAudioEngine
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.TestClock
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
import ch.lkmc.neutrodyne.playback.api.PlayResult
import ch.lkmc.neutrodyne.playback.api.PlayerPhase
import ch.lkmc.neutrodyne.playback.api.ScopeWriteResult
import ch.lkmc.neutrodyne.playback.desktop.DesktopPlaybackController
import ch.lkmc.neutrodyne.playback.desktop.DesktopPlaybackRuntime
import ch.lkmc.neutrodyne.playback.engine.AudioEngine
import ch.lkmc.neutrodyne.playback.engine.EngineState
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.createDynamicGraphFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
                assertEquals(50_000L, fixture.position(2))
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
                assertEquals(41_000L, fixture.position(1))
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
                assertEquals(17_000L, fixture.position(1))
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
                assertEquals(42_000L, fixture.position(1))
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
                assertEquals(1.75f, fixture.engine.speed)
                assertEquals(ScopeWriteResult.APPLIED, fixture.controller.setSkipSilence(true, SettingScope.GLOBAL))
                withTimeout(TIMEOUT_MS) {
                    fixture.controller.effectivePlayback
                        .filter { it?.skipSilence?.value == true }
                        .first()
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
                    assertEquals(PlayerPhase.NOT_LOADED, restored.nowPlaying.value?.phase)
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

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}

internal class PlaybackFixture : AutoCloseable {
    val clock = TestClock()
    val engine = FakeAudioEngine()
    val network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE)
    private val dirs = tempAppDirs("md1a2-playback").also { it.ensureCreated() }
    private val info = BuildInfoLoader.load()
    val graph =
        createDynamicGraphFactory<DesktopAppGraph.Factory>(
            PlaybackTestBindings(engine, clock, network),
        ).create(dirs, info, DesktopCrashReporter(dirs, info, clock, RecentLogBuffer()))
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
            withTimeout(10_000) { controller.nowPlaying.filter { it?.isPlaying == true }.first() }
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

    fun newController(engine: FakeAudioEngine): DesktopPlaybackController {
        runBlocking {
            graph.appScope.coroutineContext[Job]!!.cancelAndJoin()
        }
        graph.databaseOpener.requireDatabase().close()
        retiredOriginal = true
        val bindings = PlaybackTestBindings(engine, clock, network)
        val other =
            createDynamicGraphFactory<DesktopAppGraph.Factory>(bindings)
                .create(dirs, info, DesktopCrashReporter(dirs, info, clock, RecentLogBuffer()))
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
) {
    @Provides fun engine(): AudioEngine = engine

    @Provides fun clock(): Clock = clock

    @Provides fun network(): NetworkMonitor = network
}
