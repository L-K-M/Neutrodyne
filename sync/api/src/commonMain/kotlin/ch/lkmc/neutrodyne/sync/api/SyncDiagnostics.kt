// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.api

/** Canonical collections only; unrecognised stored collection names are counted as OTHER. */
enum class SyncPendingCollection { PODCAST, GROUP, MEMBER, EPISODE, UPNEXT, SESSION, SETTING, OTHER }

enum class SyncStreamState { STOPPED, CONNECTING, CONNECTED, WAITING, UNSUPPORTED }

enum class SyncLanGateState { UNCONFIGURED, NOT_REQUIRED, GRANTED, REQUIRED }

/**
 * Why a round has no report. [CANCELLED] is not an error: the round stopped before it finished
 * (an unlink, its caller's cancellation or the process ending), and the next trigger runs another.
 */
enum class SyncRoundFailure { TRANSPORT, PROTOCOL, UNEXPECTED, CANCELLED }

/** Where an unfinished round is; a stuck "Syncing…" shows which step it waits in. */
enum class SyncRoundPhase {
    /** Reading the linked profile and token. */
    STARTING,

    /** Waiting for an earlier round to finish: the engine runs one at a time. */
    WAITING,

    /** Local work before the first request: settings capture and pending effect drains. */
    PREPARING,

    /** A push or pull request is out. */
    REQUESTING,

    /** Applying a received page and its settings and password effects. */
    APPLYING,
}

/** A round that started and has not finished. */
data class SyncRoundInFlight(
    val reason: SyncRoundReason,
    val startedAtMs: Long,
    val phase: SyncRoundPhase,
    /** When the round entered [phase]. */
    val phaseSinceMs: Long,
)

enum class SyncDiagnosticsError { UNAVAILABLE }

/** A finished round, including a cancelled one ([SyncRoundFailure.CANCELLED]). No exception text. */
data class SyncRoundSummary(
    val reason: SyncRoundReason,
    val completedAtMs: Long,
    val report: SyncReport?,
    val failure: SyncRoundFailure? = null,
    val httpStatus: Int? = null,
    val protocolCode: String? = null,
)

/**
 * Local diagnostics (10 Diagnostics). No server/feed URLs, credentials or full cursor.
 * Round summaries are oldest first and limited to the last twenty completed rounds.
 */
data class SyncDiagnosticsSnapshot(
    val serverVersion: String? = null,
    val protocol: Int? = null,
    val accountName: String? = null,
    val deviceName: String? = null,
    val lastSyncAtMs: Long? = null,
    val rounds: List<SyncRoundSummary> = emptyList(),
    /** Rounds that started and have not finished, oldest first; they keep "Syncing…" shown. */
    val inFlight: List<SyncRoundInFlight> = emptyList(),
    val pendingByCollection: Map<SyncPendingCollection, Int> = emptyMap(),
    val parkedCount: Int = 0,
    val heldCount: Int = 0,
    val clockOffsetMs: Long = 0,
    val streamState: SyncStreamState = SyncStreamState.STOPPED,
    /** Attempts to open the stream after its first attempt, for this profile/process. */
    val reconnectCount: Int = 0,
    val lanGate: SyncLanGateState = SyncLanGateState.UNCONFIGURED,
    val lastErrorCode: String? = null,
    val cursorPrefix: String? = null,
) {
    val lastRound: SyncRoundSummary?
        get() = rounds.lastOrNull()
}
