// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.sync.api.FirstLinkChoice
import ch.lkmc.neutrodyne.sync.api.HeldResolution
import ch.lkmc.neutrodyne.sync.api.LinkFlow
import ch.lkmc.neutrodyne.sync.api.LinkMethod
import ch.lkmc.neutrodyne.sync.api.MassChangePrompt
import ch.lkmc.neutrodyne.sync.api.PendingDevice
import ch.lkmc.neutrodyne.sync.api.RemoteSessionOffer
import ch.lkmc.neutrodyne.sync.api.SyncController
import ch.lkmc.neutrodyne.sync.api.SyncDevice
import ch.lkmc.neutrodyne.sync.api.SyncNotice
import ch.lkmc.neutrodyne.sync.api.SyncProblem
import ch.lkmc.neutrodyne.sync.api.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A scripted [SyncController] for UI tests: every action is recorded and the flows are settable.
 * [notices] is a broadcast flow; [link] is the configured [FakeLinkFlow] or `null`.
 */
class FakeSyncController(
    status: SyncStatus = SyncStatus.NotConfigured,
    override val link: FakeLinkFlow? = null,
) : SyncController {
    override val status = MutableStateFlow(status)
    override val heldChanges = MutableStateFlow<List<MassChangePrompt>>(emptyList())
    override val remoteSession = MutableStateFlow<RemoteSessionOffer?>(null)
    override val syncing = MutableStateFlow(false)
    override val shareFeedPasswords = MutableStateFlow(false)

    private val _notices = MutableSharedFlow<SyncNotice>(extraBufferCapacity = 16)
    override val notices: Flow<SyncNotice> = _notices

    val startLinks = mutableListOf<Pair<String, LinkMethod>>()
    val discoveries = mutableListOf<String>()
    val localNetworkGrants = mutableListOf<Boolean>()
    val insecureConfirmations = mutableListOf<Unit>()
    val invites = mutableListOf<String>()
    val passwords = mutableListOf<Pair<String, CharArray>>()
    val firstLinkChoices = mutableListOf<FirstLinkChoice>()
    val syncNows = mutableListOf<Unit>()
    val unlinks = mutableListOf<Unit>()
    val serverDataDeletes = mutableListOf<String>()
    val renames = mutableListOf<Pair<String, String>>()
    val revokes = mutableListOf<String>()
    val heldResolutions = mutableListOf<Pair<Long, HeldResolution>>()
    val remoteSessionDismissals = mutableListOf<Unit>()
    val playbackSettings = mutableListOf<Boolean>()
    val passwordSharing = mutableListOf<Boolean>()
    val approvals = mutableListOf<String>()
    val confirmations = mutableListOf<Pair<String, Boolean>>()
    val deviceLists = mutableListOf<Unit>()

    /** Scripted results; the defaults succeed (UI tests rarely exercise failures). */
    var devicesResult: Outcome<List<SyncDevice>, SyncProblem> = Outcome.Success(emptyList())
    var approveResult: Outcome<PendingDevice, SyncProblem> =
        Outcome.Success(PendingDevice("", "", "", "", 0))
    var confirmResult: Outcome<Unit, SyncProblem> = Outcome.Success(Unit)
    var unlinkResult: Outcome<Unit, SyncProblem> = Outcome.Success(Unit)
    var deleteServerDataResult: Outcome<Unit, SyncProblem> = Outcome.Success(Unit)
    var renameResult: Outcome<Unit, SyncProblem> = Outcome.Success(Unit)
    var revokeResult: Outcome<Unit, SyncProblem> = Outcome.Success(Unit)

    override fun startLink(
        serverUrl: String,
        method: LinkMethod,
    ) {
        startLinks += serverUrl to method
    }

    override fun discover(address: String) {
        discoveries += address
    }

    override fun grantLocalNetwork(granted: Boolean) {
        localNetworkGrants += granted
    }

    override fun confirmInsecure() {
        insecureConfirmations += Unit
    }

    override fun submitInvite(code: String) {
        invites += code
    }

    override fun submitPassword(
        username: String,
        password: CharArray,
    ) {
        // Copy: callers scrub their array after submit, and the fake must hold the submitted value.
        passwords += username to password.copyOf()
    }

    override fun chooseFirstLink(choice: FirstLinkChoice) {
        firstLinkChoices += choice
    }

    override fun syncNow() {
        syncNows += Unit
    }

    override suspend fun approveLink(userCode: String): Outcome<PendingDevice, SyncProblem> {
        approvals += userCode
        return approveResult
    }

    override suspend fun confirmLink(
        userCode: String,
        approve: Boolean,
    ): Outcome<Unit, SyncProblem> {
        confirmations += userCode to approve
        return confirmResult
    }

    override suspend fun devices(): Outcome<List<SyncDevice>, SyncProblem> {
        deviceLists += Unit
        return devicesResult
    }

    override suspend fun unlink(): Outcome<Unit, SyncProblem> {
        unlinks += Unit
        return unlinkResult
    }

    override suspend fun deleteServerData(confirm: String): Outcome<Unit, SyncProblem> {
        serverDataDeletes += confirm
        return deleteServerDataResult
    }

    override suspend fun renameDevice(
        deviceId: String,
        name: String,
    ): Outcome<Unit, SyncProblem> {
        renames += deviceId to name
        return renameResult
    }

    override suspend fun revokeDevice(deviceId: String): Outcome<Unit, SyncProblem> {
        revokes += deviceId
        return revokeResult
    }

    override fun resolveHeld(
        id: Long,
        resolution: HeldResolution,
    ) {
        heldResolutions += id to resolution
    }

    override fun dismissRemoteSession() {
        remoteSessionDismissals += Unit
        remoteSession.value = null
    }

    override suspend fun setSyncPlaybackSettings(enabled: Boolean) {
        playbackSettings += enabled
    }

    override suspend fun setShareFeedPasswords(enabled: Boolean) {
        passwordSharing += enabled
        shareFeedPasswords.value = enabled
    }

    fun post(notice: SyncNotice) {
        _notices.tryEmit(notice)
    }
}
