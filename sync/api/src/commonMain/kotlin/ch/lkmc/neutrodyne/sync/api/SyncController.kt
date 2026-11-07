// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Why a round runs (10 Scheduling on Android / Scheduling on the desktop). */
enum class SyncRoundReason { FOREGROUND, SYNC_NOW, OUTBOX, PLAYBACK, PERIODIC, SSE, LINK, FIRST_LINK }

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

    fun unlink()

    fun deleteServerData(confirm: String)

    fun renameDevice(
        deviceId: String,
        name: String,
    )

    fun revokeDevice(deviceId: String)

    fun resolveHeld(
        id: Long,
        resolution: HeldResolution,
    )

    fun dismissRemoteSession()

    fun setSyncPlaybackSettings(enabled: Boolean)

    fun setShareFeedPasswords(enabled: Boolean)
}
