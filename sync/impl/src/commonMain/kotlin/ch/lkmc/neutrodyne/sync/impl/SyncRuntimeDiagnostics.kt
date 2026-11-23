// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.sync.api.SyncRoundInFlight
import ch.lkmc.neutrodyne.sync.api.SyncRoundPhase
import ch.lkmc.neutrodyne.sync.api.SyncRoundReason
import ch.lkmc.neutrodyne.sync.api.SyncRoundSummary
import ch.lkmc.neutrodyne.sync.api.SyncStreamState
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

/** Private identity, never a diagnostics field or log value. */
internal data class SyncDiagnosticProfile(
    val serverUrl: String?,
    val accountId: String?,
    val deviceId: String?,
    val nodeId: String?,
    val linkedAt: Long?,
    val enabled: Boolean,
) {
    override fun toString(): String = "SyncDiagnosticProfile(redacted)"
}

internal fun SyncStateEntity?.diagnosticProfile(url: String?): SyncDiagnosticProfile =
    SyncDiagnosticProfile(url, this?.accountId, this?.deviceId, this?.nodeId, this?.linkedAt, this?.enabled == true)

internal data class SyncDiagnosticTicket(
    val profile: SyncDiagnosticProfile,
    val generation: Long,
) {
    override fun toString(): String = "SyncDiagnosticTicket(redacted)"
}

internal data class SyncRuntimeSnapshot(
    val rounds: List<SyncRoundSummary> = emptyList(),
    val inFlight: List<SyncRoundInFlight> = emptyList(),
    val streamState: SyncStreamState = SyncStreamState.STOPPED,
    val reconnectCount: Int = 0,
    val serverVersion: String? = null,
)

/** App-owned memory only: readers never cause a request, persistence or background job. */
@Inject
@SingleIn(AppScope::class)
class SyncRuntimeDiagnostics {
    private data class State(
        val profile: SyncDiagnosticProfile? = null,
        val generation: Long = 0,
        val rounds: List<SyncRoundSummary> = emptyList(),
        val streamState: SyncStreamState = SyncStreamState.STOPPED,
        val streamAttempts: Int = 0,
    ) {
        fun matches(ticket: SyncDiagnosticTicket): Boolean =
            profile == ticket.profile && generation == ticket.generation
    }

    private data class ServerVersion(
        val url: String,
        val version: String,
    ) {
        override fun toString(): String = "ServerVersion(redacted)"
    }

    private val state = MutableStateFlow(State())
    private val version = MutableStateFlow<ServerVersion?>(null)

    /**
     * Unfinished rounds by start order. Kept outside the profile-scoped [State] on purpose: they
     * carry no identity, a round starts before it knows its profile, and they mirror the
     * process-wide `SyncActivity` count that shows "Syncing…".
     */
    private val inFlight = MutableStateFlow<Map<Long, SyncRoundInFlight>>(emptyMap())
    private val nextRound = MutableStateFlow(0L)

    /** Records a round as [SyncRoundPhase.STARTING]; pass the id to [roundPhase] and [roundEnded]. */
    internal fun roundStarted(
        reason: SyncRoundReason,
        atMs: Long,
    ): Long {
        val id = nextRound.updateAndGet { it + 1 }
        inFlight.update { it + (id to SyncRoundInFlight(reason, atMs, SyncRoundPhase.STARTING, atMs)) }
        return id
    }

    internal fun roundPhase(
        id: Long,
        phase: SyncRoundPhase,
        atMs: Long,
    ) {
        inFlight.update { rounds ->
            val round = rounds[id] ?: return@update rounds
            if (round.phase == phase) rounds else rounds + (id to round.copy(phase = phase, phaseSinceMs = atMs))
        }
    }

    internal fun roundEnded(id: Long) {
        inFlight.update { it - id }
    }

    /** Last known identity for failures before a round reaches its existing state read. */
    internal fun currentTicket(): SyncDiagnosticTicket? =
        state.value.let { current -> current.profile?.let { SyncDiagnosticTicket(it, current.generation) } }

    internal fun activate(profile: SyncDiagnosticProfile): SyncDiagnosticTicket {
        while (true) {
            val before = state.value
            if (before.profile == profile) return SyncDiagnosticTicket(profile, before.generation)
            val after = State(profile = profile, generation = before.generation + 1)
            if (state.compareAndSet(before, after)) return SyncDiagnosticTicket(profile, after.generation)
        }
    }

    internal fun completed(
        ticket: SyncDiagnosticTicket,
        summary: SyncRoundSummary,
    ) {
        state.update {
            if (it.matches(ticket)) it.copy(rounds = (it.rounds + summary).takeLast(HISTORY_LIMIT)) else it
        }
    }

    internal fun streamOpening(ticket: SyncDiagnosticTicket) {
        state.update {
            if (it.matches(ticket)) {
                it.copy(
                    streamState = SyncStreamState.CONNECTING,
                    streamAttempts = if (it.streamAttempts == Int.MAX_VALUE) Int.MAX_VALUE else it.streamAttempts + 1,
                )
            } else {
                it
            }
        }
    }

    internal fun streamState(
        ticket: SyncDiagnosticTicket,
        streamState: SyncStreamState,
    ) {
        state.update { if (it.matches(ticket)) it.copy(streamState = streamState) else it }
    }

    internal fun rememberVersion(
        url: String,
        serverVersion: String,
    ) {
        version.value = ServerVersion(url, serverVersion)
    }

    internal fun snapshot(profile: SyncDiagnosticProfile): SyncRuntimeSnapshot {
        val current = state.value
        val serverVersion = version.value?.takeIf { it.url == profile.serverUrl }?.version
        val unfinished =
            inFlight.value.entries
                .sortedBy { it.key }
                .map { it.value }
        if (current.profile != profile) return SyncRuntimeSnapshot(inFlight = unfinished, serverVersion = serverVersion)
        return SyncRuntimeSnapshot(
            rounds = current.rounds,
            inFlight = unfinished,
            streamState = current.streamState,
            reconnectCount = (current.streamAttempts - 1).coerceAtLeast(0),
            serverVersion = serverVersion,
        )
    }

    private companion object {
        const val HISTORY_LIMIT = 20
    }
}
