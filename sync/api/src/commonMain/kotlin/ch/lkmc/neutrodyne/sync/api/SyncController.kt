// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.api

import ch.lkmc.neutrodyne.core.common.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Why a round runs (10 Scheduling on Android / Scheduling on the desktop). */
enum class SyncRoundReason { FOREGROUND, SYNC_NOW, OUTBOX, PLAYBACK, PERIODIC, SSE, LINK, FIRST_LINK, PRE_PLAY }

/** One round's outcome, shown in diagnostics (10 Sync round). */
data class SyncReport(
    val pushed: Int,
    val applied: Int,
    val stale: Int,
    val rejected: Int,
    val pulled: Int,
    val held: Int,
    val durationMs: Long,
)

/**
 * The application service the UI and the shells use for sync (10 Client sync engine, 08 Sync
 * screens). `:sync:impl`'s `SyncControllerImpl` is the only implementation; `:core:testing` ships a
 * `FakeSyncController`.
 */
interface SyncController {
    val status: StateFlow<SyncStatus>

    /** The link state machine; `null` while no link is running. */
    val link: LinkFlow?

    val heldChanges: StateFlow<List<MassChangePrompt>>

    /** The unadopted remote session (MS3 adopts it; MS2 carries and republishes it). */
    val remoteSession: StateFlow<RemoteSessionOffer?>

    /** One-shot notices for the shell's snackbar flow. */
    val notices: Flow<SyncNotice>

    /** Whether a round or a link merge is running (disables "Sync now"). */
    val syncing: StateFlow<Boolean>

    fun startLink(
        serverUrl: String,
        method: LinkMethod = LinkMethod.CODE,
    )

    fun discover(address: String)

    fun grantLocalNetwork(granted: Boolean)

    fun confirmInsecure()

    fun submitInvite(code: String)

    fun submitPassword(
        username: String,
        password: CharArray,
    )

    fun chooseFirstLink(choice: FirstLinkChoice)

    fun syncNow()

    /** Local diagnostics; no sync RPC or credential access. */
    suspend fun diagnostics(): Outcome<SyncDiagnosticsSnapshot, SyncDiagnosticsError>

    /** Approves nothing yet; returns the device the code names (10 Link methods). */
    suspend fun approveLink(userCode: String): Outcome<PendingDevice, SyncProblem>

    /** Confirms or declines the pending link; the code alone never links anything. */
    suspend fun confirmLink(
        userCode: String,
        approve: Boolean,
    ): Outcome<Unit, SyncProblem>

    /** The account's devices, for the Devices screen and rename/revoke (10 Authentication). */
    suspend fun devices(): Outcome<List<SyncDevice>, SyncProblem>

    suspend fun unlink(): Outcome<Unit, SyncProblem>

    suspend fun deleteServerData(confirm: String): Outcome<Unit, SyncProblem>

    suspend fun renameDevice(
        deviceId: String,
        name: String,
    ): Outcome<Unit, SyncProblem>

    suspend fun revokeDevice(deviceId: String): Outcome<Unit, SyncProblem>

    fun resolveHeld(
        id: Long,
        resolution: HeldResolution,
    )

    fun dismissRemoteSession()

    /** Persist device-local switches. A failed write leaves both the stored and runtime gate unchanged. */
    suspend fun setSyncPlaybackSettings(enabled: Boolean): Outcome<Unit, SyncPreferenceError>

    suspend fun setShareFeedPasswords(enabled: Boolean): Outcome<Unit, SyncPreferenceError>
}
