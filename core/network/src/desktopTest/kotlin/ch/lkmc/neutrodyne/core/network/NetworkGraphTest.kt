// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.HttpClientKind
import ch.lkmc.neutrodyne.core.common.LocalNetworkAccess
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.PowerMonitor
import ch.lkmc.neutrodyne.core.common.UserAgentProvider
import ch.lkmc.neutrodyne.core.common.YtxScope
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.network.okhttp.CoreClients
import ch.lkmc.neutrodyne.core.network.okhttp.DnsFamilyHints
import ch.lkmc.neutrodyne.core.network.okhttp.LocalNetworkGuard
import ch.lkmc.neutrodyne.core.network.okhttp.NetworkClients
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createGraphFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Test

/** The AppScope graph the shells will shape — requesting every networking binding this package adds. */
@DependencyGraph(AppScope::class)
interface S12AppGraph {
    val clients: NeutrodyneHttpClients
    val monitor: NetworkMonitor
    val classifier: NetErrorClassifier
    val coreClients: CoreClients
    val networkClients: NetworkClients
    val userAgent: UserAgentProvider
    val hints: DnsFamilyHints
    val guard: LocalNetworkGuard
    val access: LocalNetworkAccess

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides platformInfo: PlatformInfo,
            @Provides buildInfo: BuildInfo,
            @Provides powerMonitor: PowerMonitor,
            @Provides @ApplicationScope scope: CoroutineScope,
        ): S12AppGraph
    }
}

/** The `:ytx` process's graph: `CoreClients` and its inputs only, per 01 Components and scopes. */
@DependencyGraph(YtxScope::class)
interface S12YtxGraph {
    val coreClients: CoreClients

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides platformInfo: PlatformInfo,
            @Provides buildInfo: BuildInfo,
        ): S12YtxGraph
    }
}

class NetworkGraphTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun appGraph(): S12AppGraph =
        createGraphFactory<S12AppGraph.Factory>().create(
            platformInfo = fakePlatform(),
            buildInfo = testBuildInfo(),
            powerMonitor = FakePowerMonitor(),
            scope = scope,
        )

    @Test
    fun `the app graph resolves every networking binding`() {
        val graph = appGraph()
        try {
            assertThat(graph.clients).isNotNull()
            assertThat(graph.monitor).isInstanceOf(DesktopNetworkMonitor::class.java)
            assertThat(graph.classifier).isNotNull()
            assertThat(graph.networkClients[HttpClientKind.FEED]).isNotNull()
            // Scoped bindings really are per-graph singletons.
            assertThat(graph.coreClients).isSameInstanceAs(graph.coreClients)
            assertThat(graph.networkClients).isSameInstanceAs(graph.networkClients)
            assertThat(graph.userAgent.value).startsWith("Neutrodyne/0.1.0")
        } finally {
            graph.clients.closeAll()
        }
    }

    @Test
    fun `the ytx graph resolves CoreClients through the island's YtxScope container`() {
        val graph =
            createGraphFactory<S12YtxGraph.Factory>().create(
                platformInfo = fakePlatform(),
                buildInfo = testBuildInfo(),
            )
        assertThat(graph.coreClients).isSameInstanceAs(graph.coreClients)
        assertThat(graph.coreClients.youtube).isNotNull()
        // A YtxScope graph is its own instance — nothing shared with the AppScope graph.
        val app = appGraph()
        try {
            assertThat(graph.coreClients).isNotSameInstanceAs(app.coreClients)
        } finally {
            app.clients.closeAll()
        }
    }

    @Test
    fun `each scope gets its own per-process singleton`() {
        val graph = appGraph()
        try {
            val provider = graph.userAgent
            assertThat(provider).isSameInstanceAs(graph.userAgent)
        } finally {
            graph.clients.closeAll()
        }
    }
}
