// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.DiagnosticsContributor
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsLine
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsSectionId
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsSeverity
import ch.lkmc.neutrodyne.sync.api.SyncDiagnosticsSnapshot
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider

/**
 * Extends the core:data SYNC section without a reverse module edge. The lazy local reader
 * avoids constructing the operational controller (and its startup credential reads) for export.
 */
@ContributesIntoSet(AppScope::class)
internal class SyncRuntimeDiagnosticsContributor
    @Inject
    constructor(
        private val reader: Provider<SyncDiagnosticsReader>,
    ) : DiagnosticsContributor {
        override val section = DiagnosticsSectionId.SYNC

        override suspend fun collect(): List<DiagnosticsLine> =
            when (val result = reader().read()) {
                is Outcome.Success -> result.value.runtimeLines()
                is Outcome.Failure -> listOf(DiagnosticsLine("runtime", "unavailable", DiagnosticsSeverity.WARNING))
            }
    }

/** DB-only fields already have keys in core:data's contributor; do not duplicate those keys. */
internal fun SyncDiagnosticsSnapshot.runtimeLines(): List<DiagnosticsLine> =
    buildList {
        add(DiagnosticsLine("serverVersion", serverVersion ?: "unknown"))
        add(DiagnosticsLine("accountName", accountName ?: "unknown"))
        add(DiagnosticsLine("deviceName", deviceName ?: "unknown"))
        lastRound?.let {
            add(DiagnosticsLine("lastRoundAtMs", it.completedAtMs.toString()))
            add(DiagnosticsLine("lastRoundReason", it.reason.name))
            add(DiagnosticsLine("lastRoundReport", it.report?.toString() ?: "failed"))
        }
        rounds.forEachIndexed { index, round ->
            add(
                DiagnosticsLine(
                    "round.$index",
                    listOfNotNull(
                        round.completedAtMs.toString(),
                        round.reason.name,
                        round.report?.toString(),
                        round.failure?.name,
                        round.httpStatus?.toString(),
                        round.protocolCode,
                    ).joinToString(" "),
                ),
            )
        }
        pendingByCollection.forEach { (collection, count) ->
            add(DiagnosticsLine("pending.${collection.name.lowercase()}", count.toString()))
        }
        add(DiagnosticsLine("heldChanges", heldCount.toString()))
        add(DiagnosticsLine("clockOffsetMs", clockOffsetMs.toString()))
        add(DiagnosticsLine("streamState", streamState.name))
        add(DiagnosticsLine("reconnectCount", reconnectCount.toString()))
        add(DiagnosticsLine("lanGate", lanGate.name))
        add(DiagnosticsLine("cursorPrefix", cursorPrefix ?: "none"))
        add(DiagnosticsLine("runtimeErrorCode", lastErrorCode ?: "none"))
    }
