// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.ProtocolLimits

internal data class SyncClockOffset(
    val offsetMs: Long,
    val adjustedReceivedAtMs: Long,
)

/** Odd round trips use the earlier whole-millisecond midpoint; small jitter is ignored. */
internal fun estimateSyncClockOffset(
    serverTimeMs: Long,
    sentAtMs: Long,
    receivedAtMs: Long,
): SyncClockOffset? {
    if (serverTimeMs !in ProtocolLimits.MIN_CLOCK_MS..Hlc.MAX_MILLIS) return null
    if (sentAtMs < 0 || receivedAtMs < sentAtMs) return null
    // Nonnegative, ordered samples make both the subtraction and this sum safe.
    val midpoint = sentAtMs + (receivedAtMs - sentAtMs) / 2
    val estimate = serverTimeMs - midpoint
    val offset = if (estimate in -OFFSET_JITTER_MS..OFFSET_JITTER_MS) 0 else estimate
    val adjusted = adjustedSyncWallMs(receivedAtMs, offset) ?: return null
    return SyncClockOffset(offset, adjusted)
}

/** Persisted offsets must never overflow or wrap when packed into an HLC. */
internal fun adjustedSyncWallMs(
    nowMs: Long,
    offsetMs: Long,
): Long? {
    if (nowMs < 0 || offsetMs == Long.MIN_VALUE) return null
    if (offsetMs > 0 && nowMs > Long.MAX_VALUE - offsetMs) return null
    if (offsetMs < 0 && nowMs < -offsetMs) return null
    return (nowMs + offsetMs).takeIf { it in 0..Hlc.MAX_MILLIS }
}

private const val OFFSET_JITTER_MS = 120_000L
