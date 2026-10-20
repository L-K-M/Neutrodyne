// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.api

import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.ExternalReason
import ch.lkmc.neutrodyne.core.model.IpFamily
import kotlinx.coroutines.flow.StateFlow

/** externalReason is null iff all five capabilities are true (04). Read at each operation. */
data class YouTubeCapabilities(
    val inAppPlayback: Boolean,
    val downloads: Boolean,
    val channelSearch: Boolean,
    val enrichment: Boolean,
    val backCatalogue: Boolean,
    val externalReason: ExternalReason?,
)

/** App-scoped thread-safe capability observation, never starts the engine just to read state. */
interface YouTubeCapabilitiesSource {
    val capabilities: StateFlow<YouTubeCapabilities>
}

/**
 * Main-safe, cache-aware stream lookup (04). Cancellation propagates; expected failures are values.
 * Concurrent same-video calls may coalesce. Invalidation is thread-safe/non-blocking and affects
 * later lookups, not already pinned streams. External mode returns Unsupported without network.
 */
interface YouTubeStreamResolver {
    suspend fun resolveAudio(
        videoId: String,
        pref: AudioPref,
    ): ResolveResult

    fun invalidate(videoId: String)

    fun invalidateAll()
}

sealed interface ResolveResult {
    data class Ok(
        val audio: ResolvedAudio,
    ) : ResolveResult

    data class Unavailable(
        val reason: Availability,
    ) : ResolveResult

    data class Transient(
        val cause: Throwable,
        val kind: TransientKind = TransientKind.NETWORK,
    ) : ResolveResult

    data object Unsupported : ResolveResult
}

enum class TransientKind { NETWORK, TIMEOUT, RATE_LIMITED, EXTRACTION, BREAKER_OPEN, ENGINE_UNAVAILABLE }

enum class AudioQuality(
    val ranks: List<Int>,
) {
    STANDARD(listOf(140, 251, 250, 139, 249)),
    DATA_SAVER(listOf(250, 249, 139, 140)),
    OPUS(listOf(251, 250, 140)),
}

data class AudioPref(
    val quality: AudioQuality = AudioQuality.STANDARD,
    val preferDrc: Boolean = false,
    val pinnedItag: Int? = null,
    val preferredLanguage: String? = null,
)

/** Expiry/availability timestamps are epoch milliseconds. URLs must never appear in logs. */
data class ResolvedAudio(
    val videoId: String,
    val url: String,
    val itag: Int,
    val formatId: String,
    val mimeType: String,
    val codecs: String?,
    val averageBitrate: Int?,
    val contentLength: Long?,
    val durationMs: Long?,
    val expiresAtMs: Long,
    val lastModifiedMicros: Long?,
    val isDrc: Boolean,
    val audioTrackId: String?,
    val trackLabel: String?,
    val ipFamily: IpFamily?,
    val availableAtMs: Long?,
    val resolvedAtMs: Long,
) {
    override fun toString(): String = "ResolvedAudio($videoId, $formatId, expiresAt=$expiresAtMs)"
}
