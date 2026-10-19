// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.app.Application
import android.os.Looper
import androidx.media3.datasource.DefaultDataSource
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import ch.lkmc.neutrodyne.core.artwork.ArtworkStore
import ch.lkmc.neutrodyne.core.artwork.PinReason
import ch.lkmc.neutrodyne.core.common.GraphHolder
import ch.lkmc.neutrodyne.core.database.AndroidDatabaseFactory
import ch.lkmc.neutrodyne.core.database.DatabaseFactory
import ch.lkmc.neutrodyne.core.database.DatabaseOpenState
import ch.lkmc.neutrodyne.core.database.DatabaseOpener
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabaseConstructor
import ch.lkmc.neutrodyne.core.domain.Departure
import ch.lkmc.neutrodyne.core.domain.PlaySessionStore
import ch.lkmc.neutrodyne.core.domain.SessionStart
import ch.lkmc.neutrodyne.core.domain.StoredPosition
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.PlaySessionInfo
import ch.lkmc.neutrodyne.core.model.QueueItem
import ch.lkmc.neutrodyne.core.model.QueueOrigin
import ch.lkmc.neutrodyne.core.model.VirtualQueue
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
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.playback.core.EffectivePlaybackSettings
import ch.lkmc.neutrodyne.playback.core.PlayStarter
import ch.lkmc.neutrodyne.playback.core.PlaybackHistory
import ch.lkmc.neutrodyne.playback.core.PlayedRule
import ch.lkmc.neutrodyne.playback.core.PositionSaver
import ch.lkmc.neutrodyne.playback.core.QueueWindowPlanner
import ch.lkmc.neutrodyne.playback.core.StartPositionRule
import ch.lkmc.neutrodyne.playback.core.WindowDiff
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import okio.Path
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PlaybackTestApplication :
    Application(),
    GraphHolder {
    override lateinit var graph: Any
}

/** Fake ports behind the real database gate; no production service dependency is replaced by a stub. */
internal class LifecycleHarness(
    val app: Application,
    delayed: Boolean = false,
    settingsReadGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null,
) : AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val clock = TestClock()
    val openStarted = CountDownLatch(1)
    val allowOpen = CountDownLatch(if (delayed) 1 else 0)
    private val factory =
        object : DatabaseFactory by AndroidDatabaseFactory(app) {
            override fun exists() = false

            override fun builder(): RoomDatabase.Builder<NeutrodyneDatabase> {
                openStarted.countDown()
                check(allowOpen.await(10, TimeUnit.SECONDS)) { "Test did not release database open" }
                return Room.inMemoryDatabaseBuilder(context = app, factory = NeutrodyneDatabaseConstructor::initialize)
            }
        }
    val opener = DatabaseOpener(factory, AndroidSQLiteDriver(), Dispatchers.IO, clock, true)
    val settings = FakeSettingsRepository()
    private val delayedSettings =
        object : ch.lkmc.neutrodyne.core.domain.SettingsRepository by settings {
            override fun <T : Any> observe(key: ch.lkmc.neutrodyne.core.model.settings.SettingKey<T>): Flow<T> =
                settings.observe(key).onStart { settingsReadGate?.await() }
        }
    val prefs = PlaybackPrefs(Provider { delayedSettings }).also { it.start(scope) }
    val network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE)
    val gate = MeteredStreamingGate(network, prefs, scope)
    val queues = FakeQueueRepository()
    val store = FakePlaySessionStore()
    val positions = FakePositionStore()
    val lookup = FakePlaybackMediaLookup()
    val contexts = FakePlayContextResolver()
    val local = FakeLocalMediaIndex()
    val capabilities = FakeYouTubeCapabilitiesSource()
    val scopes = FakeScopeSettingsRepository(settings = settings)
    val effective = EffectivePlaybackSettings(FakeEffectiveSettingsResolver(), scopes, settings)
    val history = PlaybackHistory()
    private val sources = SourceHarness(File(app.cacheDir, "lifecycle-cache"))
    val index = sources.index
    val resolver = sources.resolver
    var dbProviderReads = 0
    val dbProviderReadsOnMain = AtomicInteger()

    private fun <T> deferred(value: T): Provider<T> =
        Provider {
            check(opener.openState.value is DatabaseOpenState.Opened) { "Provider dereferenced before database open" }
            dbProviderReads++
            if (Looper.myLooper() == Looper.getMainLooper()) dbProviderReadsOnMain.incrementAndGet()
            value
        }

    val sessions =
        object : PlaySessionStore by store {
            override suspend fun commitStart(
                start: SessionStart,
                now: Long,
            ): Long {
                val generation = store.commitStart(start, now)
                publish(
                    store.queue.copy(
                        generation = generation,
                        current = QueueItem(start.currentEpisodeId, QueueOrigin.CURRENT),
                        upNext = store.queue.upNext.filterNot { it.episodeId == start.currentEpisodeId },
                    ),
                )
                return generation
            }

            override suspend fun clearCurrent(
                departure: Departure?,
                now: Long,
            ): Long {
                val generation = store.clearCurrent(departure, now)
                publish(store.queue.copy(generation = generation, current = null, contextTail = emptyList()))
                return generation
            }
        }
    private val art =
        object : ArtworkStore {
            override fun pinnedFile(key: String): Path? = null

            override fun pinnedPath(key: String): Path? = null

            override fun pin(
                ref: ArtworkRef,
                reason: PinReason,
                ownerId: Long,
            ) = Unit

            override fun unpin(
                key: String,
                reason: PinReason,
                ownerId: Long,
            ) = Unit

            override fun isPinned(key: String) = false

            override fun contentUri(
                key: String,
                version: Int,
            ) = "content://synthetic.artwork/$key?v=$version"

            override suspend fun collectGarbage() = 0
        }
    val items = MediaItemFactory(art, local)
    val tree =
        MediaLibraryTree(
            opener,
            deferred(sessions),
            deferred(lookup),
            deferred(items),
            capabilities,
            Provider {
                opener.requireDatabase()
            },
            clock,
        )
    val hub =
        PlaybackStateHub(
            opener,
            deferred(queues),
            deferred(lookup),
            deferred(positions),
            gate,
            clock,
            scope,
            Dispatchers.IO,
        )
    val playerFactory =
        PlayerFactory(
            app,
            NdMediaSourceFactory(DefaultDataSource.Factory(app), NdLoadErrorHandlingPolicy()),
            Provider { BoostLimiterProcessor() },
            Provider { prefs },
        )
    private val saver = PositionSaver(positions, clock, PlayedRule())
    private val planner =
        QueueWindowPlanner(
            queues,
            capabilities,
            positions,
            local,
            clock,
            WindowDiff(),
            StartPositionRule(),
            PlayedRule(),
        )
    private val starter =
        PlayStarter(
            contexts,
            queues,
            sessions,
            lookup,
            positions,
            capabilities,
            local,
            network,
            clock,
            StartPositionRule(),
            PlayedRule(),
            setOf(gate),
        )
    val access =
        PlaybackServiceAccess(
            Provider { playerFactory },
            resolver,
            index,
            prefs,
            hub,
            gate,
            tree,
            SavedResumption(
                opener,
                deferred(queues),
                deferred(lookup),
                deferred(positions),
                deferred(items),
                capabilities,
                Dispatchers.IO,
            ),
            network,
            clock,
            local,
            capabilities,
            history,
            scope,
            Dispatchers.IO,
            opener,
            deferred(starter),
            deferred(planner),
            deferred(saver),
            deferred(effective),
            deferred(sessions),
            deferred(queues),
            deferred(positions),
            deferred(lookup),
            deferred(items),
            Provider {
                ch.lkmc.neutrodyne.core.testing
                    .FakeChapterRepository()
            },
        )
    val connection =
        PlayerConnection(
            androidx.lifecycle.ProcessLifecycleOwner
                .get()
                .lifecycle,
        ) { error("Unexpected service creation") }
    val controller =
        PlaybackControllerImpl(
            connection,
            hub,
            gate,
            opener,
            deferred(queues),
            deferred(sessions),
            clock,
            scope,
            Dispatchers.IO,
        )

    fun publish(queue: VirtualQueue) {
        store.queue = queue
        queues.queue.value = queue
        queues.session.value = PlaySessionInfo(queue.current?.episodeId, null, queue.generation)
    }

    fun seedCurrent(
        id: Long = 1,
        positionMs: Long = 12_000,
    ) {
        lookup.items.value += id to media().copy(episodeId = id)
        positions.positions.value += id to StoredPosition(id, positionMs, clock.now(), null)
        publish(VirtualQueue(1, QueueItem(id, QueueOrigin.CURRENT), emptyList(), emptyList()))
    }

    fun <T> run(block: suspend () -> T): T {
        val job = scope.async { block() }
        pumpUntil { job.isCompleted }
        return runBlocking { job.await() }
    }

    override fun close() {
        allowOpen.countDown()
        scope.cancel()
        sources.close()
        if (opener.openState.value is DatabaseOpenState.Opened) opener.requireDatabase().close()
    }
}

internal fun pumpUntil(condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
    while (!condition()) {
        check(System.nanoTime() < deadline) { "Playback test timed out" }
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(5)
    }
    shadowOf(Looper.getMainLooper()).idle()
}
