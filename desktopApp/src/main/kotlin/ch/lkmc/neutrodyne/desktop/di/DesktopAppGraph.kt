// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.di

import ch.lkmc.neutrodyne.core.artwork.NeutrodyneImageLoaderFactory
import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.CrashContext
import ch.lkmc.neutrodyne.core.common.CrashReporter
import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.data.refresh.DesktopJobRunner
import ch.lkmc.neutrodyne.core.database.DatabaseOpener
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.SecretStore
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.desktop.crash.DesktopCrashReporter
import ch.lkmc.neutrodyne.desktop.youtube.DesktopYouTubeBindingsModule
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createGraphFactory
import dev.zacsweers.metrox.viewmodel.MetroViewModelFactory
import kotlinx.coroutines.CoroutineScope

/**
 * The desktop shell's composition root (11 DesktopAppGraph, 01 Dependency injection). It
 * contributes the desktop implementations of the shared interfaces and nothing Android has.
 *
 * `AppDirs`, `BuildInfo` and the shell's [DesktopCrashReporter] come in through the factory (the
 * crash reporter is constructed by `MainKt` before the graph so 11's start-up order — logging,
 * crash handler, `session.json` before the graph — holds; 01's sketch is amended accordingly,
 * 2026-10-06). M1a adds the database plumbing: [databaseOpener] drives the open at initializer
 * band 100 and the window's start-up gate, and [jobRunner] serves the shutdown's
 * `stop(grace = 3 s)` (11 Shutdown step 2). The media/OS bindings of `:desktop:system` join with
 * their milestones.
 */
@DependencyGraph(AppScope::class, bindingContainers = [DesktopYouTubeBindingsModule::class])
interface DesktopAppGraph {
    val dirs: AppDirs

    val buildInfo: BuildInfo

    @ApplicationScope
    val appScope: CoroutineScope

    val initializers: Set<AppInitializer>

    /** Every feature's navigation entries (01 Feature entry installers). */
    val entryInstallers: Set<EntryProviderInstaller>

    /** The shell's shared bindings (the M0b graph test resolves these; 01 Graph tests). */
    val settingsRepository: SettingsRepository

    val networkMonitor: NetworkMonitor

    val crashReporter: CrashReporter

    /** The window maps `openState` onto the start-up gate (01 Splash and start-up gate). */
    val databaseOpener: DatabaseOpener

    /**
     * M1b: the secrets-file store — graph tests prove `CredentialLookup` resolves to it, so
     * `AuthInterceptor` can never be left reading `None` in the app process.
     */
    val secretStore: SecretStore

    /** What `AuthInterceptor` injects: the same singleton as [secretStore]. */
    val credentialLookup: CredentialLookup

    /**
     * The runner the shutdown stops (11 Shutdown step 2). Resolving pulls the lane chain through
     * [provideDatabase], which blocks until the band-100 open finishes — held behind a lambda at
     * the call site so an early quit never pays it, and by then the runner is the started
     * singleton anyway.
     */
    val jobRunner: DesktopJobRunner

    /**
     * `metroViewModel()`/`assistedMetroViewModel()` resolve through `LocalMetroViewModelFactory`,
     * provided at the window root (01 Feature entry installers) — the Library grid is the first
     * VM-backed entry.
     */
    val metroViewModelFactory: MetroViewModelFactory

    /** The Coil `SingletonImageLoader.Factory` the shell installs once (08 Coil ImageLoader). */
    val imageLoaderFactory: NeutrodyneImageLoaderFactory

    @Binds
    val DesktopCrashReporter.asCrashReporter: CrashReporter

    @Binds
    val DesktopCrashReporter.asCrashContext: CrashContext

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
            @Provides buildInfo: BuildInfo,
            @Provides crashReporter: DesktopCrashReporter,
        ): DesktopAppGraph
    }
}

/** Builds the graph from the shell's pre-graph instances (single construction path, also smoke's). */
internal fun createDesktopGraph(
    dirs: AppDirs,
    buildInfo: BuildInfo,
    crashReporter: DesktopCrashReporter,
): DesktopAppGraph =
    createGraphFactory<DesktopAppGraph.Factory>().create(
        dirs = dirs,
        buildInfo = buildInfo,
        crashReporter = crashReporter,
    )
