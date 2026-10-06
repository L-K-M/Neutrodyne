// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.di

import android.app.Application
import androidx.work.WorkerFactory
import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.work.MetroWorkerFactory
import ch.lkmc.neutrodyne.youtube.YouTubeBindingsModule
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
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

    /** Every feature's navigation entries (01 Feature entry installers). */
    val entryInstallers: Set<EntryProviderInstaller>

    @Binds
    val MetroWorkerFactory.bindWorkerFactory: WorkerFactory

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides application: Application,
        ): AndroidAppGraph
    }
}
