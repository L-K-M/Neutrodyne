// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.model.ExternalReason
import ch.lkmc.neutrodyne.download.api.LocalMediaChanges
import ch.lkmc.neutrodyne.download.api.LocalMediaIndex
import ch.lkmc.neutrodyne.playback.api.PlaybackMaintenance
import ch.lkmc.neutrodyne.youtube.api.AudioPref
import ch.lkmc.neutrodyne.youtube.api.ResolvePurpose
import ch.lkmc.neutrodyne.youtube.api.ResolveResult
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilities
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.youtube.api.YouTubeStreamResolver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow

class FakeLocalMediaIndex :
    LocalMediaIndex,
    LocalMediaChanges {
    val files = mutableMapOf<Long, String>()

    private val notifications = MutableSharedFlow<Set<Long>>(extraBufferCapacity = 16)
    override val changes: Flow<Set<Long>> = notifications.asSharedFlow()

    override fun localUriOrNull(episodeId: Long): String? = files[episodeId]

    suspend fun publish(snapshot: Map<Long, String>) {
        val changed = (files.keys + snapshot.keys).filter { files[it] != snapshot[it] }.toSet()
        val next = snapshot.toMap()
        files.clear()
        files.putAll(next)
        if (changed.isNotEmpty()) notifications.emit(changed)
    }
}

class FakeYouTubeCapabilitiesSource : YouTubeCapabilitiesSource {
    override val capabilities =
        MutableStateFlow(YouTubeCapabilities(false, false, false, false, false, ExternalReason.NOT_YET_AVAILABLE))
}

class FakeYouTubeStreamResolver : YouTubeStreamResolver {
    var result: ResolveResult = ResolveResult.Unsupported
    val requests = mutableListOf<Pair<String, AudioPref>>()
    val purposes = mutableListOf<ResolvePurpose>()
    var resolve: suspend (String, AudioPref, ResolvePurpose) -> ResolveResult = { _, _, _ -> result }
    val invalidated = mutableListOf<String>()
    var invalidateAllCalls = 0
        private set

    override suspend fun resolveAudio(
        videoId: String,
        pref: AudioPref,
        purpose: ResolvePurpose,
    ): ResolveResult {
        requests += videoId to pref
        purposes += purpose
        return resolve.invoke(videoId, pref, purpose)
    }

    override fun invalidate(videoId: String) {
        invalidated += videoId
    }

    override fun invalidateAll() {
        invalidateAllCalls++
    }
}

class FakePlaybackMaintenance : PlaybackMaintenance {
    var bytes = 0L
    var failure: Throwable? = null
    var clearCalls = 0
        private set

    override suspend fun streamingCacheBytes(): Long {
        failure?.let { throw it }
        return bytes
    }

    override suspend fun clearStreamingCache() {
        clearCalls++
        failure?.let { throw it }
        bytes = 0
    }
}
