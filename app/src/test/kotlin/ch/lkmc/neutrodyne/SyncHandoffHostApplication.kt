// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.app.Application
import androidx.work.testing.WorkManagerTestInitHelper
import ch.lkmc.neutrodyne.core.common.GraphHolder
import ch.lkmc.neutrodyne.core.testing.FakePlaybackController
import ch.lkmc.neutrodyne.core.testing.FakeSyncController
import ch.lkmc.neutrodyne.di.AndroidAppGraph
import dev.zacsweers.metro.createGraphFactory
import java.lang.reflect.Proxy

/**
 * The framework creates the real `MainActivity`; only the MS3 sync/playback seams are
 * replaced, exactly like `ImportHostApplication` swaps the import repository. The real graph
 * still answers everything else — the database gate, the metro factory and the settings —
 * so the root composition exercises the same path as the app.
 */
class SyncHandoffHostApplication :
    Application(),
    GraphHolder {
    val productionGraph by lazy { createGraphFactory<AndroidAppGraph.Factory>().create(this) }
    val sync = FakeSyncController()
    val playback = FakePlaybackController()

    override fun onCreate() {
        super.onCreate()
        // Post-open resolutions (the download lane) reach WorkManager; the host task supplies it.
        WorkManagerTestInitHelper.initializeTestWorkManager(this)
    }

    /** Resolutions of `graph.syncController`; the inert start-up keeps this at zero pre-open. */
    var syncResolutions = 0

    override val graph: AndroidAppGraph =
        Proxy.newProxyInstance(
            AndroidAppGraph::class.java.classLoader,
            arrayOf(AndroidAppGraph::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getSyncController" -> {
                    syncResolutions++
                    sync
                }

                "getPlaybackController" -> {
                    playback
                }

                "getPlaybackStateSource" -> {
                    playback.state
                }

                else -> {
                    method.invoke(productionGraph, *(args ?: emptyArray()))
                }
            }
        } as AndroidAppGraph
}
