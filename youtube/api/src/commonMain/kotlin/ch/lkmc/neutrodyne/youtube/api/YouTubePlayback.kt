// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.api

import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.ExternalReason
import ch.lkmc.neutrodyne.core.model.IpFamily
import kotlinx.coroutines.flow.StateFlow
import okio.IOException

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
 * Single-flight key is (videoId, AudioPref), never purpose. Each caller's monotonic wait budget
 * begins at entry. Joining never extends the underlying flight deadline; a short waiter timing out
 * leaves other waiters alive. Last waiter cancels the host call. A background caller may start a
 * new flight within its remaining overall budget. Cache hits do not reserve extraction trials.
 * Playback's 35 s extraction watchdog excludes the separate <=30 s preroll wait.
 * Concurrent same-video calls may coalesce. Invalidation is thread-safe/non-blocking and affects
 * later lookups, not already pinned streams. External mode returns Unsupported without network.
 */
interface YouTubeStreamResolver {
    suspend fun resolveAudio(
        videoId: String,
        pref: AudioPref,
        purpose: ResolvePurpose = ResolvePurpose.PLAYBACK,
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
    ) : ResolveResult {
        override fun toString(): String = "ResolveResult.Transient(kind=$kind)"
    }

    data object Unsupported : ResolveResult
}

enum class TransientKind { NETWORK, TIMEOUT, RATE_LIMITED, EXTRACTION, BREAKER_OPEN, ENGINE_UNAVAILABLE }

/** Source-compatible name for the shared setting type; no second quality enum or mapper. */
typealias AudioQuality = ch.lkmc.neutrodyne.core.model.settings.YouTubeAudioQuality

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

/** Includes queue/start wait; background work gets lower admission priority and a 45 s budget. */
enum class ResolvePurpose(
    val warmTimeoutMs: Long,
    val coldTimeoutMs: Long,
) {
    PLAYBACK(20_000, 25_000),
    BACKGROUND(45_000, 45_000),
}

/** Shared loader exception; expected resolver errors remain values until a blocking loader boundary. */
class YouTubeResolveException(
    val result: ResolveResult,
) : IOException("YouTube source is unavailable")

/** Playback compares the full identity; missing length/lmt cannot prove safe cross-attempt resume. */
data class YouTubeRepresentationKey(
    val formatId: String,
    val contentLength: Long?,
    val lastModifiedMicros: Long?,
)

/** Persist only this proof: itag, totalBytes=clen, lastModified=decimal lmt microseconds. Never URL.
 * Missing or different proof restarts the part. Full formatId must also match within an attempt;
 * preferences are snapshotted for that attempt. W8 must test same-itag DRC/dub and lmt changes.
 * Equal real-world tuples across different representations trigger the D22 contingency, not a guess.
 */
data class YouTubeResumeProof(
    val itag: Int?,
    val contentLength: Long?,
    val lastModifiedMicros: Long?,
)

data class YouTubeTransferSnapshot(
    val pref: AudioPref,
    val representation: YouTubeRepresentationKey,
)
