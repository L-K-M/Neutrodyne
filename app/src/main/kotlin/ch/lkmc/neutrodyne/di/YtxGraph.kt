// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.di

import android.app.Application
import ch.lkmc.neutrodyne.core.common.YtxScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides

/**
 * The `:ytx` process's graph (01 Components and scopes, D73): only `YtxScope` contributions — the engine host
 * and the credential-free network core — and never a database, DataStore or credential binding.
 */
@DependencyGraph(YtxScope::class)
interface YtxGraph {
    val application: Application

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides application: Application,
        ): YtxGraph
    }
}
