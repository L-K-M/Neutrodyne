// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.sync.api.FirstLinkChoice
import ch.lkmc.neutrodyne.sync.api.HeldResolution
import ch.lkmc.neutrodyne.sync.api.LinkFlow
import ch.lkmc.neutrodyne.sync.api.LinkMethod
import ch.lkmc.neutrodyne.sync.api.MassChangePrompt
import ch.lkmc.neutrodyne.sync.api.RemoteSessionOffer
import ch.lkmc.neutrodyne.sync.api.SyncController
import ch.lkmc.neutrodyne.sync.api.SyncNotice
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
        passwords += username to password
    }

    override fun chooseFirstLink(choice: FirstLinkChoice) {
        firstLinkChoices += choice
    }

    override fun syncNow() {
        syncNows += Unit
    }

    override fun unlink() {
        unlinks += Unit
    }

    override fun deleteServerData(confirm: String) {
        serverDataDeletes += confirm
    }

    override fun renameDevice(
        deviceId: String,
        name: String,
    ) {
        renames += deviceId to name
    }

    override fun revokeDevice(deviceId: String) {
        revokes += deviceId
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

    override fun setSyncPlaybackSettings(enabled: Boolean) {
        playbackSettings += enabled
    }

    override fun setShareFeedPasswords(enabled: Boolean) {
        passwordSharing += enabled
    }

    fun post(notice: SyncNotice) {
        _notices.tryEmit(notice)
    }
}
