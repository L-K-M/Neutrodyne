// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.artwork

import android.app.Application
import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.common.WorkerCreator
import ch.lkmc.neutrodyne.core.common.WorkerKey
import ch.lkmc.neutrodyne.core.database.DatabaseOpener
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Metro must see this container across modules to include the artwork worker in the app graph. */
@BindingContainer
@ContributesTo(AppScope::class)
public object AndroidArtworkBindings {
    @Provides
    fun context(application: Application): PlatformContext = application

    @Provides
    fun loader(application: Application): ImageLoader = SingletonImageLoader.get(application)

    @Provides
    @IntoMap
    @WorkerKey(ArtworkSyncWorker::class)
    fun worker(factory: ArtworkSyncWorker.Factory): WorkerCreator =
        { context, params -> factory.create(context, params) }
}

@ContributesIntoSet(AppScope::class)
@SingleIn(AppScope::class)
@Inject
internal class ArtworkInitializer(
    private val opener: DatabaseOpener,
    private val store: Provider<DefaultArtworkStore>,
    private val database: Provider<NeutrodyneDatabase>,
    @param:ApplicationScope private val scope: CoroutineScope,
    private val scheduler: ArtworkSyncScheduler,
    @param:Dispatcher(NeutrodyneDispatchers.IO) private val io: CoroutineDispatcher,
) : AppInitializer {
    private var observation: Job? = null

    override val order: Int = 300

    override suspend fun run() =
        withContext(io) {
            opener.awaitOpen()
            store().refreshIndex()
            if (observation == null) {
                // Source commits include subscribe/import/restore and art/title changes.
                // Artwork writes are excluded so a completed pass cannot schedule itself.
                observation =
                    scope.launch(io) {
                        database()
                            .invalidationTracker
                            .createFlow("podcast", "episode", "download", "podcast_group")
                            .collect { scheduler.request() }
                    }
            }
            scheduler.request()
        }
}

/** Merged into the app graph by Metro; framework providers resolve it only on first access. */
@ContributesTo(AppScope::class)
public interface ArtworkProviderGraph {
    public val artworkProviderAccess: Provider<ArtworkProviderAccess>
}
