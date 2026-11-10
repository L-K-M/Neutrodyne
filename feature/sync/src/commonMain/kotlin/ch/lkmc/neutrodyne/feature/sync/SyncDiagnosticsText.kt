// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.sync

import ch.lkmc.neutrodyne.sync.api.SyncDiagnosticsSnapshot
import ch.lkmc.neutrodyne.sync.api.SyncPendingCollection
import ch.lkmc.neutrodyne.sync.api.SyncReport
import ch.lkmc.neutrodyne.sync.api.SyncRoundSummary

/**
 * The copy text (08 Sync diagnostics): the snapshot as `key: value` lines with the same stable
 * keys the diagnostics export uses for this data (10 Diagnostics). The snapshot never carries the
 * token, feed/server URLs or the full cursor, so neither does the copy — [cursorPrefix] is the
 * only cursor shown anywhere.
 */
internal fun SyncDiagnosticsSnapshot.plainText(): String =
    buildString {
        line("serverVersion", serverVersion)
        line("protocol", protocol)
        line("accountName", accountName)
        line("deviceName", deviceName)
        line("lastSyncAtMs", lastSyncAtMs)
        lastRound?.let { round ->
            line("lastRoundAtMs", round.completedAtMs)
            line("lastRoundReason", round.reason.name)
            line("lastRoundReport", round.result() ?: "unknown")
        }
        rounds.forEachIndexed { index, round ->
            line(
                "round.$index",
                listOfNotNull(round.completedAtMs, round.reason.name, round.result()).joinToString(" "),
            )
        }
        for (collection in SyncPendingCollection.entries) {
            line("pending.${collection.name.lowercase()}", pendingByCollection[collection] ?: 0)
        }
        line("parkedChanges", parkedCount)
        line("heldChanges", heldCount)
        line("clockOffsetMs", clockOffsetMs)
        line("streamState", streamState.name)
        line("reconnectCount", reconnectCount)
        line("lanGate", lanGate.name)
        line("lastError", lastErrorCode)
        line("cursorPrefix", cursorPrefix ?: "none")
    }

private fun StringBuilder.line(
    key: String,
    value: Any?,
) {
    append(key).append(": ").append(value?.toString() ?: "unknown").append('\n')
}

/** `SyncReport`'s fields without the class name, for screen lines and the copy text. */
internal fun SyncReport.fields(): String =
    "pushed=$pushed applied=$applied stale=$stale rejected=$rejected pulled=$pulled " +
        "held=$held durationMs=$durationMs"

/** One round's result: the report fields, or the failure with its HTTP and protocol codes. */
internal fun SyncRoundSummary.result(): String? {
    report?.let { return it.fields() }
    val failure = failure ?: return null
    return listOfNotNull(failure.name, httpStatus?.let { "http $it" }, protocolCode).joinToString(" ")
}
