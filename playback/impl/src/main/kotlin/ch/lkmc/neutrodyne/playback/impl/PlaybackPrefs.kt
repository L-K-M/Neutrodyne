// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.StreamOnMetered
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

internal data class PlaybackPrefsSnapshot(
    val skipBackMs: Long,
    val skipForwardMs: Long,
    val pauseForNavigation: Boolean,
    val streamOnMetered: StreamOnMetered,
    val streamCacheMb: Int,
) {
    companion object {
        val DEFAULT: PlaybackPrefsSnapshot =
            PlaybackPrefsSnapshot(
                skipBackMs = PlaybackSettingKeys.SKIP_BACK_MS.default,
                skipForwardMs = PlaybackSettingKeys.SKIP_FORWARD_MS.default,
                pauseForNavigation = PlaybackSettingKeys.PAUSE_FOR_NAVIGATION.default,
                streamOnMetered = PlaybackSettingKeys.STREAM_ON_METERED.default,
                streamCacheMb = PlaybackSettingKeys.STREAM_CACHE_MB.default,
            )
    }
}

@SingleIn(AppScope::class)
internal class PlaybackPrefs
    @Inject
    constructor(
        private val settings: Provider<SettingsRepository>,
    ) {
        private val _current = MutableStateFlow(PlaybackPrefsSnapshot.DEFAULT)
        val current: StateFlow<PlaybackPrefsSnapshot> = _current.asStateFlow()

        private val started = AtomicBoolean(false)
        private val ready = CompletableDeferred<Unit>()

        /** Defaults are for construction only; starts must use the first durable snapshot. */
        suspend fun awaitReady() {
            ready.await()
        }

        fun start(scope: CoroutineScope) {
            if (!started.compareAndSet(false, true)) return
            scope.launch {
                try {
                    val repo = settings()
                    combine(
                        repo.observe(PlaybackSettingKeys.SKIP_BACK_MS),
                        repo.observe(PlaybackSettingKeys.SKIP_FORWARD_MS),
                        repo.observe(PlaybackSettingKeys.PAUSE_FOR_NAVIGATION),
                        repo.observe(PlaybackSettingKeys.STREAM_ON_METERED),
                        repo.observe(PlaybackSettingKeys.STREAM_CACHE_MB),
                    ) { back, forward, navigation, metered, cacheMb ->
                        PlaybackPrefsSnapshot(back, forward, navigation, metered, cacheMb)
                    }.distinctUntilChanged()
                        .collect {
                            _current.value = it
                            ready.complete(Unit)
                        }
                } catch (failure: Exception) {
                    ready.completeExceptionally(failure)
                    throw failure
                }
            }
        }
    }

@ContributesIntoSet(AppScope::class)
internal class PlaybackPrefsInitializer
    @Inject
    constructor(
        private val prefs: Provider<PlaybackPrefs>,
        @ApplicationScope private val appScope: CoroutineScope,
    ) : AppInitializer {
        override val order: Int = 300

        override suspend fun run() {
            prefs().start(appScope)
        }
    }
