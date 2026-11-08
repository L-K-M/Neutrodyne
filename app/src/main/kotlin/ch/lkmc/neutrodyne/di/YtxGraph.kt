// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.di

import android.app.Application
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.YtxScope
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.platform.AndroidBuildInfo
import ch.lkmc.neutrodyne.platform.AndroidPlatformInfo
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides

/**
 * The `:ytx` process's graph (01 Components and scopes, D73): only `YtxScope` contributions — the engine host
 * and the credential-free network core — and never a database, DataStore or credential binding.
 */
@DependencyGraph(YtxScope::class)
interface YtxGraph {
    val application: Application

    @Provides
    fun buildInfo(): BuildInfo = AndroidBuildInfo.create()

    @Provides
    fun platformInfo(): PlatformInfo = AndroidPlatformInfo()

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides application: Application,
        ): YtxGraph
    }
}
