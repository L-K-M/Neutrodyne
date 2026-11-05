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

    /**
     * Requests an immediate update run and awaits it (04 Engine updates). The run re-validates the
     * full trust chain every time; the outcome is also persisted and mirrored in
     * [EngineStatus.lastOutcome]. External mode reports [TransientKind.ENGINE_UNAVAILABLE].
     */
    suspend fun checkForUpdate(): EngineUpdateOutcome

    /**
     * Records `current = bundled` so the next idle bind re-serves the APK engine; downloaded
     * versions stay on disk for diagnostics. External mode is a no-op.
     */
    suspend fun resetToBundled()
}

typealias EngineUpdatePolicy = ch.lkmc.neutrodyne.core.model.settings.YouTubeEngineUpdatePolicy

/** Outcome of one update run, persisted verbatim as `youtube.engine_last_outcome`. */
sealed interface EngineUpdateOutcome {
    data object UpToDate : EngineUpdateOutcome

    /** Verified and staged beside the active engine; the idle window expired before activation. */
    data class Staged(
        val version: String,
    ) : EngineUpdateOutcome

    data class Activated(
        val version: String,
    ) : EngineUpdateOutcome

    data class Rejected(
        val version: String?,
        val reason: EngineRejectReason,
    ) : EngineUpdateOutcome

    data class Failed(
        val kind: TransientKind,
    ) : EngineUpdateOutcome
}

enum class EngineRejectReason {
    MANIFEST_SIGNATURE,
    MANIFEST_REPLAYED,
    UPSTREAM_SIGNATURE,
    HASH_MISMATCH,
    ORIGIN,
    BELOW_BUNDLED,
    SHIM_INCOMPATIBLE,
    SIZE_CAP,
    SELFTEST_FAILED,
    ROLLED_BACK,
    REVOKED,
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

enum class EngineVersionSource { BUNDLED, UPDATED }

data class EngineStatus(
    val availability: EngineAvailability,
    val version: String? = null,
    val source: EngineVersionSource = EngineVersionSource.BUNDLED,
    val jsChallenges: Boolean = false,
    val platformLimitation: PlatformLimitation? = null,
    val bundledVersion: String? = null,
    val updatePolicy: EngineUpdatePolicy = EngineUpdatePolicy.APPROVED,
    val lastCheckAtMs: Long? = null,
    val lastOutcome: EngineUpdateOutcome? = null,
    /** A valid heartbeat older than the documented window marks the approval channel stale. */
    val approvalsStale: Boolean = false,
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
