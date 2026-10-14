// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.di

import android.app.Application
import androidx.work.WorkerFactory
import ch.lkmc.neutrodyne.core.artwork.NeutrodyneImageLoaderFactory
import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.database.DatabaseOpener
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.work.MetroWorkerFactory
import ch.lkmc.neutrodyne.youtube.YouTubeBindingsModule
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metrox.viewmodel.MetroViewModelFactory
import kotlinx.coroutines.CoroutineScope

/**
 * The Android main process's graph (01 Dependency injection, D82). It exists only in the main process
 * (D73): `:ytx` builds [YtxGraph], `:acra` builds nothing.
 */
@DependencyGraph(AppScope::class, bindingContainers = [YouTubeBindingsModule::class])
interface AndroidAppGraph {
    val application: Application

    @ApplicationScope
    val appScope: CoroutineScope

    val initializers: Set<AppInitializer>

    val workerFactory: WorkerFactory

    val buildInfo: BuildInfo

    /** `MainActivity` collects `appearance.*` through it into `AppearancePrefs` (08 App scheme). */
    val settingsRepository: SettingsRepository

    /** Every feature's navigation entries (01 Feature entry installers). */
    val entryInstallers: Set<EntryProviderInstaller>

    /** `MainActivity` maps `openState` onto the start-up gate (01 Splash and start-up gate). */
    val databaseOpener: DatabaseOpener

    /** The Coil `SingletonImageLoader.Factory` installed in `NeutrodyneApplication` (08 Coil ImageLoader). */
    val imageLoaderFactory: NeutrodyneImageLoaderFactory

    /**
     * `metroViewModel()`/`assistedMetroViewModel()` resolve through `LocalMetroViewModelFactory`,
     * provided with this binding at the root (01 Feature entry installers).
     */
    val metroViewModelFactory: MetroViewModelFactory

    @Binds
    val MetroWorkerFactory.bindWorkerFactory: WorkerFactory

    /**
     * The one database accessor (02 Error handling and recovery): blocks a background caller until
     * the open finishes, throws on the main thread before that — callers hold this lazily.
     */
    @Provides
    fun provideDatabase(opener: DatabaseOpener): NeutrodyneDatabase = opener.requireDatabase()

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides application: Application,
        ): AndroidAppGraph
    }
}
