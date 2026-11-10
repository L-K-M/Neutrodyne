// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.api

/** Canonical collections only; unrecognised stored collection names are counted as OTHER. */
enum class SyncPendingCollection { PODCAST, GROUP, MEMBER, EPISODE, UPNEXT, SESSION, SETTING, OTHER }

enum class SyncStreamState { STOPPED, CONNECTING, CONNECTED, WAITING, UNSUPPORTED }

enum class SyncLanGateState { UNCONFIGURED, NOT_REQUIRED, GRANTED, REQUIRED }

enum class SyncRoundFailure { TRANSPORT, PROTOCOL, UNEXPECTED }

enum class SyncDiagnosticsError { UNAVAILABLE }

/** A completed round; cancellation is not recorded as a failed round. No exception text. */
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
