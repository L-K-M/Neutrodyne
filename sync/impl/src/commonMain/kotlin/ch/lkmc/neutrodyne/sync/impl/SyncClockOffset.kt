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
    if (offset > 0 && receivedAtMs > Long.MAX_VALUE - offset) return null
    if (offset < 0 && receivedAtMs < -offset) return null
    val adjusted = receivedAtMs + offset
    if (adjusted !in 0..Hlc.MAX_MILLIS) return null
    return SyncClockOffset(offset, adjusted)
}

private const val OFFSET_JITTER_MS = 120_000L
