// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube

import android.app.Application
import androidx.work.testing.TestListenableWorkerBuilder
import ch.lkmc.neutrodyne.di.AndroidAppGraph
import ch.lkmc.neutrodyne.youtube.ytdlp.EngineUpdateWorker
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.createGraphFactory
import kotlinx.coroutines.cancel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The engine-update half of 01's graph test: the real AppScope graph assembles the update
 * chain — `EngineKeys` parses off the packaged assets, the updater/monitor/hooks singletons
 * share the one store and transport, and the worker binding resolves by class name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class EngineUpdateGraphTest {
    private val graph = createGraphFactory<AndroidAppGraph.Factory>().create(RuntimeEnvironment.getApplication())

    @Test
    fun updateWorkerBindingBuildsTheRealUpdateChain() {
        val worker =
            TestListenableWorkerBuilder<EngineUpdateWorker>(RuntimeEnvironment.getApplication())
                .setWorkerFactory(graph.workerFactory)
                .build()

        assertThat(worker).isNotNull()
        graph.appScope.cancel()
    }

    @Test
    fun updateInitializerJoinsStartupAtOrder200() {
        val orders = graph.initializers.associate { it::class.simpleName to it.order }
        assertThat(orders["EngineUpdateInitializer"]).isEqualTo(200)
        graph.appScope.cancel()
    }
}
