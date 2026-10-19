// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import androidx.media3.session.MediaSession
import ch.lkmc.neutrodyne.core.model.EffectivePlayback
import ch.lkmc.neutrodyne.core.model.SettingScope
import ch.lkmc.neutrodyne.playback.api.NowPlaying
import ch.lkmc.neutrodyne.playback.api.PlayResult
import ch.lkmc.neutrodyne.playback.api.ScopeWriteResult
import ch.lkmc.neutrodyne.playback.core.StartRequest
import kotlinx.coroutines.flow.StateFlow

/** Main-thread handle of the active service. Pausing through it never binds or starts a service. */
internal interface ServiceBridge {
    val nowPlaying: StateFlow<NowPlaying?>
    val effectivePlayback: StateFlow<EffectivePlayback?>
    val isPlaying: Boolean

    fun pause()

    suspend fun pauseBeforeUnsubscribe(podcastIds: List<Long>)

    suspend fun start(
        request: StartRequest,
        origin: StartOrigin,
    ): PlayResult

    suspend fun setMediaItems(
        request: StartRequest,
        origin: StartOrigin,
    ): MediaSession.MediaItemsWithStartPosition

    suspend fun resume(origin: StartOrigin): MediaSession.MediaItemsWithStartPosition

    suspend fun beforePlay(origin: StartOrigin): Boolean

    suspend fun previous()

    suspend fun stop()

    suspend fun dismiss()

    suspend fun setSpeed(
        speed: Float,
        scope: SettingScope,
    ): ScopeWriteResult

    suspend fun setSkipSilence(
        enabled: Boolean,
        scope: SettingScope,
    ): ScopeWriteResult

    suspend fun cycleSpeed(): ScopeWriteResult
}
