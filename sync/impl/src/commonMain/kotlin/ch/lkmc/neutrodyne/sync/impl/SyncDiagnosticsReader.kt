// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withReadTransaction
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.LocalNetworkGate
import ch.lkmc.neutrodyne.core.domain.LocalNetworkLocation
import ch.lkmc.neutrodyne.core.domain.SyncServerAddress
import ch.lkmc.neutrodyne.sync.api.SyncDiagnosticsError
import ch.lkmc.neutrodyne.sync.api.SyncDiagnosticsSnapshot
import ch.lkmc.neutrodyne.sync.api.SyncLanGateState
import ch.lkmc.neutrodyne.sync.api.SyncPendingCollection
import ch.lkmc.neutrodyne.sync.api.SyncRoundFailure
import ch.lkmc.neutrodyne.sync.api.SyncRoundSummary
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.SyncErrorCode
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** A local-only read boundary; deliberately has no client, token store or secret-store dependency. */
@Inject
@SingleIn(AppScope::class)
class SyncDiagnosticsReader internal constructor(
    private val db: NeutrodyneDatabase,
    private val address: SyncServerAddress,
    private val gate: LocalNetworkGate,
    private val runtime: SyncRuntimeDiagnostics,
    @Dispatcher(NeutrodyneDispatchers.IO) private val io: CoroutineDispatcher,
) {
    internal suspend fun read(): Outcome<SyncDiagnosticsSnapshot, SyncDiagnosticsError> =
        withContext(io) {
            try {
                readLocal()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Outcome.Failure(SyncDiagnosticsError.UNAVAILABLE)
            }
        }

    private suspend fun readLocal(): Outcome<SyncDiagnosticsSnapshot, SyncDiagnosticsError> {
        // Settings and host classification can suspend/block: neither belongs in a Room
        // transaction. The second identity read rejects an unlink or account switch.
        val url = address.serverUrl()
        val accountName = address.username()
        val deviceName = address.deviceName()
        val stored =
            db.withReadTransaction {
                val state = db.syncStateDao().get()
                val pending = SyncPendingCollection.entries.associateWith { 0 }.toMutableMap()
                for (row in db.syncOutboxDao().pendingCounts()) {
                    val collection = pendingCollection(row.coll)
                    pending[collection] = pending.getValue(collection) + row.count
                }
                Stored(
                    state,
                    pending.toMap(),
                    db.syncParkedDao().count(),
                    db.syncHeldDao().all().count { MassChangeGuard.heldBatch(it.batch) != null },
                )
            }
        val profile = stored.state.diagnosticProfile(url)
        val lanGate = lanGate(url)
        val nextUrl = address.serverUrl()
        val nextAccountName = address.username()
        val nextDeviceName = address.deviceName()
        val nextState = db.syncStateDao().get()
        if (profile != nextState.diagnosticProfile(nextUrl) ||
            accountName != nextAccountName || deviceName != nextDeviceName
        ) {
            return Outcome.Failure(SyncDiagnosticsError.UNAVAILABLE)
        }
        runtime.activate(profile)
        val memory = runtime.snapshot(profile)
        return Outcome.Success(
            SyncDiagnosticsSnapshot(
                serverVersion = memory.serverVersion,
                protocol = stored.state?.protocol,
                accountName = accountName,
                deviceName = deviceName,
                lastSyncAtMs = stored.state?.lastSyncAt,
                rounds = memory.rounds,
                pendingByCollection = stored.pending,
                parkedCount = stored.parked,
                heldCount = stored.held,
                clockOffsetMs = stored.state?.clockOffsetMs ?: 0,
                streamState = memory.streamState,
                reconnectCount = memory.reconnectCount,
                lanGate = lanGate,
                lastErrorCode = errorCode(stored.state?.lastError) ?: roundErrorCode(memory.rounds.lastOrNull()),
                cursorPrefix = stored.state?.cursor?.take(CURSOR_PREFIX_LENGTH),
            ),
        )
    }

    private fun lanGate(url: String?): SyncLanGateState {
        val host = url?.let(::bareHostOf) ?: return SyncLanGateState.UNCONFIGURED
        return when {
            gate.location(host) != LocalNetworkLocation.LOCAL -> SyncLanGateState.NOT_REQUIRED
            gate.hasPermission() -> SyncLanGateState.GRANTED
            gate.needsPermission(host) -> SyncLanGateState.REQUIRED
            else -> SyncLanGateState.NOT_REQUIRED
        }
    }

    private fun errorCode(code: String?): String? =
        when {
            code == null -> null
            code == SYNC_ERROR_REVOKED -> code
            SyncErrorCode.entries.any { it.wire == code } -> code
            else -> "UNKNOWN"
        }

    private fun roundErrorCode(round: SyncRoundSummary?): String? =
        when (round?.failure) {
            SyncRoundFailure.TRANSPORT -> {
                round.httpStatus?.takeIf { it in 100..599 }?.let { "HTTP_$it" } ?: "TRANSPORT"
            }

            SyncRoundFailure.PROTOCOL -> {
                round.protocolCode?.takeIf { code -> SyncErrorCode.entries.any { it.wire == code } } ?: "PROTOCOL"
            }

            SyncRoundFailure.UNEXPECTED -> {
                "UNEXPECTED"
            }

            null -> {
                null
            }
        }

    private fun pendingCollection(wire: String): SyncPendingCollection =
        when (runCatching { SyncCollection.fromWire(wire) }.getOrNull()) {
            SyncCollection.PODCAST -> SyncPendingCollection.PODCAST
            SyncCollection.GROUP -> SyncPendingCollection.GROUP
            SyncCollection.MEMBER -> SyncPendingCollection.MEMBER
            SyncCollection.EPISODE -> SyncPendingCollection.EPISODE
            SyncCollection.UPNEXT -> SyncPendingCollection.UPNEXT
            SyncCollection.SESSION -> SyncPendingCollection.SESSION
            SyncCollection.SETTING -> SyncPendingCollection.SETTING
            null -> SyncPendingCollection.OTHER
        }

    private data class Stored(
        val state: ch.lkmc.neutrodyne.core.database.SyncStateEntity?,
        val pending: Map<SyncPendingCollection, Int>,
        val parked: Int,
        val held: Int,
    )

    private companion object {
        const val CURSOR_PREFIX_LENGTH = 8
    }
}
