// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.model.ExternalReason
import ch.lkmc.neutrodyne.download.api.LocalMediaIndex
import ch.lkmc.neutrodyne.playback.api.PlaybackMaintenance
import ch.lkmc.neutrodyne.youtube.api.AudioPref
import ch.lkmc.neutrodyne.youtube.api.ResolveResult
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilities
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.youtube.api.YouTubeStreamResolver
import kotlinx.coroutines.flow.MutableStateFlow

class FakeLocalMediaIndex : LocalMediaIndex {
    val files = mutableMapOf<Long, String>()

    override fun localUriOrNull(episodeId: Long): String? = files[episodeId]
}

class FakeYouTubeCapabilitiesSource : YouTubeCapabilitiesSource {
    override val capabilities =
        MutableStateFlow(YouTubeCapabilities(false, false, false, false, false, ExternalReason.NOT_YET_AVAILABLE))
}

class FakeYouTubeStreamResolver : YouTubeStreamResolver {
    var result: ResolveResult = ResolveResult.Unsupported
    val requests = mutableListOf<Pair<String, AudioPref>>()
    val invalidated = mutableListOf<String>()
    var invalidateAllCalls = 0
        private set

    override suspend fun resolveAudio(
        videoId: String,
        pref: AudioPref,
    ): ResolveResult {
        requests += videoId to pref
        return result
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
