// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube

import android.app.Application
import android.net.ConnectivityManager
import androidx.work.WorkManager
import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.youtube.api.YouTubeStreamResolver
import ch.lkmc.neutrodyne.youtube.engine.EngineSettings
import ch.lkmc.neutrodyne.youtube.engine.EngineStore
import ch.lkmc.neutrodyne.youtube.ytdlp.AndroidDefaultNetworkReset
import ch.lkmc.neutrodyne.youtube.ytdlp.EnginePrepareInitializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal class EnginePreparationObserver(
    private val app: Application,
    private val store: EngineStore,
    private val settings: EngineSettings,
    private val scope: CoroutineScope,
) : AppInitializer {
    override val order = 210

    override suspend fun run() {
        scope.launch {
            settings.awaitLoaded()
            settings.state.map { it.enabled }.distinctUntilChanged().collect { enabled ->
                if (enabled) EnginePrepareInitializer(store, WorkManager.getInstance(app)).run()
            }
        }
    }
}

internal class YouTubeNetworkInitializer(
    private val app: Application,
    private val streams: YouTubeStreamResolver,
) : AppInitializer {
    override val order = 150
    private var reset: AndroidDefaultNetworkReset? = null

    override suspend fun run() {
        reset =
            AndroidDefaultNetworkReset(
                app.getSystemService(ConnectivityManager::class.java),
                streams::invalidateAll,
            )
    }
}
