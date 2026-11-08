// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlp

import ch.lkmc.neutrodyne.core.datastore.DeviceSettingsStore
import ch.lkmc.neutrodyne.core.datastore.SettingsStore
import ch.lkmc.neutrodyne.core.model.settings.YouTubeSettingKeys
import ch.lkmc.neutrodyne.youtube.engine.CompileMarker
import ch.lkmc.neutrodyne.youtube.engine.EngineSettings
import ch.lkmc.neutrodyne.youtube.engine.EngineSettingsSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** App-process adapter. Failure counts belong to this installed bundled version only. */
class DataStoreEngineSettings(
    private val portable: SettingsStore,
    private val device: DeviceSettingsStore,
    private val identity: CompileMarker,
    scope: CoroutineScope,
) : EngineSettings {
    private val mutableState = MutableStateFlow(EngineSettingsSnapshot())
    override val state = mutableState.asStateFlow()
    private val loaded = CompletableDeferred<Unit>()

    init {
        scope.launch {
            try {
                val count = device.get(YouTubeSettingKeys.ENGINE_START_FAILURES)
                val saved = device.get(YouTubeSettingKeys.ENGINE_FAILURE_IDENTITY)
                mutableState.value =
                    EngineSettingsSnapshot(
                        portable.get(YouTubeSettingKeys.ENGINE_ENABLED),
                        if (saved == identityKey(identity)) count else 0,
                        identity,
                    )
                loaded.complete(Unit)
                portable.observe(YouTubeSettingKeys.ENGINE_ENABLED).collect { enabled ->
                    mutableState.value = mutableState.value.copy(enabled = enabled)
                }
            } catch (cancelled: CancellationException) {
                loaded.cancel(cancelled)
                throw cancelled
            } catch (failure: Exception) {
                loaded.completeExceptionally(failure)
            }
        }
    }

    override suspend fun awaitLoaded() = loaded.await()

    override suspend fun recordStartFailures(
        identity: CompileMarker,
        count: Int,
    ) {
        // Invalidate the old count first. A failed write or process death must not attribute
        // a previous installation's failures to a newly installed bundled engine.
        device.set(YouTubeSettingKeys.ENGINE_START_FAILURES, 0)
        device.set(YouTubeSettingKeys.ENGINE_FAILURE_IDENTITY, identityKey(identity))
        device.set(YouTubeSettingKeys.ENGINE_START_FAILURES, count)
        mutableState.value = mutableState.value.copy(startFailures = count, failureIdentity = identity)
    }

    private fun identityKey(marker: CompileMarker) =
        "${marker.appVersionCode}|${marker.engineVersion}|${marker.pythonMinor}"
}
