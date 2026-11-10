// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.PlaybackSyncActive
import ch.lkmc.neutrodyne.core.domain.PlaybackSyncEvent
import ch.lkmc.neutrodyne.core.domain.PlaybackSyncPort
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createGraphFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The band-160 initializer resolves `Provider<DeferredRemovalSettler>` on every `run` —
 * including the startup-recovery rerun — so the binding's scope, not the instance's `job`
 * guard, is what keeps exactly one app-scoped collector armed (10 Mass-change guard 4).
 * `SyncBindings` is excluded so the fake store below is the one token binding; everything
 * else resolves through the real contributed graph (the DataStore address chain included).
 */
@DependencyGraph(AppScope::class, excludes = [SyncBindings::class])
interface SettlerTestGraph {
    val settlerProvider: Provider<DeferredRemovalSettler>

    @Provides
    fun tokens(): SyncTokenStore = SyncTokenStore(FakeSecretStore())

    @Provides
    @Dispatcher(NeutrodyneDispatchers.IO)
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Dispatcher(NeutrodyneDispatchers.Default)
    fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides db: NeutrodyneDatabase,
            @Provides playback: PlaybackSyncPort,
            @Provides clock: Clock,
            @Provides storagePaths: StoragePaths,
            @Provides @ApplicationScope scope: CoroutineScope,
        ): SettlerTestGraph
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DeferredRemovalSettlerGraphTest {
    private val now = 1_800_000_000_000L

    private fun graph(
        db: NeutrodyneDatabase,
        playback: PlaybackSyncPort,
        scope: CoroutineScope,
    ): SettlerTestGraph {
        val root = Files.createTempDirectory("settler-graph")
        val dirs =
            AppDirs(
                data = root.resolve("data"),
                config = root.resolve("config"),
                cache = root.resolve("cache"),
                state = root.resolve("state"),
                logs = root.resolve("logs"),
                downloadsDefault = root.resolve("downloads"),
            ).also { it.ensureCreated() }
        return createGraphFactory<SettlerTestGraph.Factory>().create(
            db = db,
            playback = playback,
            clock = TestClock(now),
            storagePaths = StoragePaths(dirs),
            scope = scope,
        )
    }

    @Test
    fun repeatedProviderResolutionReturnsTheOneAppScopeSettler() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val graph = graph(db, CountingPlaybackSyncPort(), backgroundScope)
                val first = graph.settlerProvider()
                assertSame(
                    first,
                    graph.settlerProvider(),
                    "every `run` must resolve the one app-scoped settler, not mint a new one",
                )
            } finally {
                db.close()
            }
        }

    @Test
    fun anInitializerRetryArmsNoSecondCollector() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val playback = CountingPlaybackSyncPort()
                val graph = graph(db, playback, backgroundScope)
                // `SyncStartupDrainInitializer.run`'s `deferred().start(scope)`, as the
                // startup-recovery band runs it a second time after a failed first band.
                graph.settlerProvider().start(backgroundScope)
                graph.settlerProvider().start(backgroundScope)
                runCurrent()
                assertEquals(
                    1,
                    playback.activeFlow.subscriptionCount.value,
                    "a retried band arms no duplicate app-scoped collector",
                )
            } finally {
                db.close()
            }
        }

    /** [MutableStateFlow.subscriptionCount] counts the collectors the settler arms. */
    private class CountingPlaybackSyncPort : PlaybackSyncPort {
        val activeFlow = MutableStateFlow(PlaybackSyncActive(false, null))
        override val active: StateFlow<PlaybackSyncActive> = activeFlow.asStateFlow()
        private val eventFlow = MutableSharedFlow<PlaybackSyncEvent>(extraBufferCapacity = 16)
        override val events: Flow<PlaybackSyncEvent> = eventFlow

        override suspend fun onRemoteMarkedPlayed(
            episodeId: Long,
            deviceName: String,
        ) = Unit

        override suspend fun onRemoteMarkReverted(episodeId: Long) = Unit
    }
}
