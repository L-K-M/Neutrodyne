// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.api

import kotlinx.coroutines.flow.StateFlow

/** M9a controls only. Status reads never bind a host; prewarm/retry are main-safe and nonblocking. */
interface YouTubeEngine {
    val status: StateFlow<EngineStatus>

    /** Coalesced; resets idle timer. External mode is a no-op. Never compiles on a playback call. */
    fun prewarm(reason: PrewarmReason)

    /** Clears version-scoped start failures, rechecks platform eligibility before any host start. */
    fun retryStart()
}

enum class PrewarmReason { PROJECTION, SCREEN, DOWNLOAD, SEARCH }

enum class EngineAvailability {
    NOT_IN_THIS_APK,
    DISABLED,
    FAILED,
    STOPPED,
    PREPARING,
    STARTING,
    READY,
    NOT_YET_AVAILABLE,
}

enum class EngineVersionSource { BUNDLED }

data class EngineStatus(
    val availability: EngineAvailability,
    val version: String? = null,
    val source: EngineVersionSource = EngineVersionSource.BUNDLED,
    val jsChallenges: Boolean = false,
    val platformLimitation: PlatformLimitation? = null,
)

enum class PlatformLimitation { NO_ENGINE, PROCESS_32_BIT, X86_64_API_26_27, HOST_UNUSABLE }

/** Non-reserving reads for planners and claimers; W6 implements the synchronous atomic reservation.
 * Exactly one admitted engine operation owns a token and completes it once, including cancellation
 * as Inconclusive. Composite enrichment owns one token across its tabs/facts, not one per nested call.
 * Resolver/enricher/search/lookup report extraction; callers never repeat it. Media callers alone
 * report fresh URL 403s and media 429s. Breaker opening invalidates URL cache before another gate.
 */
interface YouTubeExtractionHealth : YouTubeHealth {
    fun reserveExtraction(nowMs: Long): ExtractionAdmission

    /** Immediately visible to the next gate/reservation; persist versioned snapshots afterwards. */
    fun completeExtraction(
        reservation: ExtractionReservation,
        outcome: ExtractionOutcome,
    )
}

data class ExtractionReservation(
    val id: Long,
)

sealed interface ExtractionAdmission {
    data class Admitted(
        val reservation: ExtractionReservation,
    ) : ExtractionAdmission

    data class Denied(
        val gate: ExtractionGate.Deny,
    ) : ExtractionAdmission
}
