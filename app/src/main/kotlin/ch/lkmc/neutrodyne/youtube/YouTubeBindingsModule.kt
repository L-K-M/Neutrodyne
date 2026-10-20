// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube

import ch.lkmc.neutrodyne.core.model.ExternalReason
import ch.lkmc.neutrodyne.youtube.api.AudioPref
import ch.lkmc.neutrodyne.youtube.api.ResolveResult
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilities
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.youtube.api.YouTubeStreamResolver
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provides
import kotlinx.coroutines.flow.MutableStateFlow

/** The shell alone binds YouTube ports (01). External-only until M9 / MD3; no engine or IO. */
@BindingContainer
object YouTubeBindingsModule {
    private val external =
        object : YouTubeCapabilitiesSource {
            override val capabilities =
                MutableStateFlow(
                    YouTubeCapabilities(false, false, false, false, false, ExternalReason.NOT_YET_AVAILABLE),
                )
        }
    private val unsupported =
        object : YouTubeStreamResolver {
            override suspend fun resolveAudio(
                videoId: String,
                pref: AudioPref,
            ): ResolveResult = ResolveResult.Unsupported

            override fun invalidate(videoId: String) = Unit

            override fun invalidateAll() = Unit
        }

    @Provides fun capabilities(): YouTubeCapabilitiesSource = external

    @Provides fun streamResolver(): YouTubeStreamResolver = unsupported
}
