// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.di

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.CrashContext
import ch.lkmc.neutrodyne.core.common.CrashReporter
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.desktop.crash.DesktopCrashReporter
import ch.lkmc.neutrodyne.desktop.youtube.DesktopYouTubeBindingsModule
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createGraphFactory
import kotlinx.coroutines.CoroutineScope

/**
 * The desktop shell's composition root (11 DesktopAppGraph, 01 Dependency injection). It
 * contributes the desktop implementations of the shared interfaces and nothing Android has.
 *
 * M0b shape: `AppDirs`, `BuildInfo` and the shell's [DesktopCrashReporter] come in through the
 * factory (the crash reporter is constructed by `MainKt` before the graph so 11's start-up order
 * — logging, crash handler, `session.json` before the graph — holds; 01's sketch is amended
 * accordingly, 2026-10-06). `DesktopJobRunner` (as `jobRunner`) and the media/OS bindings of
 * `:desktop:system` join with their milestones.
 */
@DependencyGraph(AppScope::class, bindingContainers = [DesktopYouTubeBindingsModule::class])
interface DesktopAppGraph {
    val dirs: AppDirs

    val buildInfo: BuildInfo

    @ApplicationScope
    val appScope: CoroutineScope

    val initializers: Set<AppInitializer>

    /** The shell's shared bindings (the M0b graph test resolves these; 01 Graph tests). */
    val settingsRepository: SettingsRepository

    val networkMonitor: NetworkMonitor

    val crashReporter: CrashReporter

    @Binds
    val DesktopCrashReporter.asCrashReporter: CrashReporter

    @Binds
    val DesktopCrashReporter.asCrashContext: CrashContext

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
): DesktopAppGraph = createGraphFactory<DesktopAppGraph.Factory>().create(
    dirs = dirs,
    buildInfo = buildInfo,
    crashReporter = crashReporter,
)
