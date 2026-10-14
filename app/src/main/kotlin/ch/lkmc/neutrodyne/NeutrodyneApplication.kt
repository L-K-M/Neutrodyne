// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import ch.lkmc.neutrodyne.core.common.GraphHolder
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.LogLevel
import ch.lkmc.neutrodyne.core.common.runInitializers
import ch.lkmc.neutrodyne.crash.installAcra
import ch.lkmc.neutrodyne.di.AndroidAppGraph
import ch.lkmc.neutrodyne.di.YtxGraph
import ch.lkmc.neutrodyne.platform.LogcatSink
import coil3.SingletonImageLoader
import dev.zacsweers.metro.createGraphFactory
import kotlinx.coroutines.launch

/**
 * Process-aware application (01 Application start-up, D73). The main process creates [AndroidAppGraph] lazily and
 * runs every `AppInitializer` in order; `:ytx` builds only [YtxGraph]; `:acra` builds nothing. The graph is lazy
 * so a `ContentProvider` or an early WorkManager call before `onCreate` still works.
 */
class NeutrodyneApplication :
    Application(),
    Configuration.Provider,
    GraphHolder {
    private lateinit var role: ProcessRole

    override val graph: AndroidAppGraph by lazy {
        ProcessStartProbe.record(ProcessStartProbe.Event.APP_GRAPH_REQUESTED)
        check(role == ProcessRole.MAIN) { "AndroidAppGraph exists only in the main process (D73)" }
        createGraphFactory<AndroidAppGraph.Factory>().create(this)
    }

    /** The `:ytx` process's graph; never created elsewhere. */
    lateinit var ytxGraph: YtxGraph
        private set

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        role = ProcessRole.current()
        ProcessStartProbe.attach(this, role)

        // ACRA is never installed in :ytx (D62), nor while an instrumented test runs (09 Gradle Managed Devices)
        val instrumented = System.getProperty(INSTRUMENTED_TEST_PROPERTY) != null
        if (role != ProcessRole.YTX && BuildConfig.ACRA_MAILTO.isNotEmpty() && !instrumented) installAcra(this)
    }

    override fun onCreate() {
        super.onCreate()
        if (role == ProcessRole.ACRA) {
            ProcessStartProbe.writeReport(this)
            return
        }

        Log.install(LogcatSink(if (BuildConfig.DEBUG) LogLevel.DEBUG else LogLevel.WARN))
        if (role == ProcessRole.YTX) {
            ytxGraph = createGraphFactory<YtxGraph.Factory>().create(this)
            ProcessStartProbe.record(ProcessStartProbe.Event.YTX_GRAPH_CREATED)
            ProcessStartProbe.writeReport(this)
            return
        }

        ProcessStartProbe.record(ProcessStartProbe.Event.INITIALIZERS_LAUNCHED)
        graph.appScope.launch { runInitializers(graph.initializers) }

        // 08 Coil ImageLoader: every AsyncImage shares the artwork-aware singleton (D58).
        SingletonImageLoader.setSafe(graph.imageLoaderFactory)
        ProcessStartProbe.writeReport(this)
    }

    override val workManagerConfiguration: Configuration
        get() {
            // WorkManager init reaches this getter from any process that asks for it (D73: never :ytx)
            ProcessStartProbe.record(ProcessStartProbe.Event.WORK_MANAGER_CONFIG_REQUESTED)
            return Configuration
                .Builder()
                .setWorkerFactory(graph.workerFactory)
                .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.INFO else android.util.Log.ERROR)
                .build()
        }

    internal companion object {
        /** Set only by `NeutrodyneTestRunner` in `:app`'s instrumented tests. */
        const val INSTRUMENTED_TEST_PROPERTY = "neutrodyne.instrumentedTest"
    }
}
