// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.di

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.database.DatabaseOpener
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import kotlinx.coroutines.CoroutineScope

/**
 * The desktop shell's Metro graph (01 "Dependency injection", D82; 11 Desktop shell). M1a adds the
 * database plumbing: `AppDirs` is a factory input because `main` resolves it before the graph (the
 * single-instance lock needs it earlier); the window, `BuildInfo` and `DesktopJobRunner` arrive
 * with the M0b shell.
 */
@DependencyGraph(AppScope::class)
interface DesktopAppGraph {
    val dirs: AppDirs

    @ApplicationScope
    val appScope: CoroutineScope

    val initializers: Set<AppInitializer>

    /** The window maps `openState` onto the start-up gate (01 Splash and start-up gate). */
    val databaseOpener: DatabaseOpener

    /**
     * The one database accessor (02 Error handling and recovery): blocks a background caller until
     * the open finishes, throws on the EDT before that — callers hold this lazily.
     */
    @Provides
    fun provideDatabase(opener: DatabaseOpener): NeutrodyneDatabase = opener.requireDatabase()

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides dirs: AppDirs,
        ): DesktopAppGraph
    }
}
