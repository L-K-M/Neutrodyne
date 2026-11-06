// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.sync.api.DiscoveredServer
import ch.lkmc.neutrodyne.sync.api.LinkFlow
import ch.lkmc.neutrodyne.sync.api.LinkMethod
import ch.lkmc.neutrodyne.sync.api.LinkState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The [LinkFlow] state machine (10 Link methods). The controller drives it; the UI only reads
 * [state] and calls [cancel].
 */
class LinkFlowImpl : LinkFlow {
    private val _state = MutableStateFlow<LinkState>(LinkState.Address)
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    fun showAddress() {
        _state.value = LinkState.Address
    }

    fun discovering() {
        _state.value = LinkState.Discovering
    }

    fun needsLocalNetwork(host: String) {
        _state.value = LinkState.NeedsLocalNetworkPermission(host)
    }

    fun insecure(host: String) {
        _state.value = LinkState.InsecureConnection(host)
    }

    fun awaitingInput(
        method: LinkMethod,
        server: DiscoveredServer,
        disclosure: ch.lkmc.neutrodyne.sync.api.SyncDisclosure,
    ) {
        _state.value = LinkState.AwaitingInput(method, server, disclosure)
    }

    fun showCode(
        userCode: String,
        verificationUri: String,
        expiresAtMs: Long,
    ) {
        _state.value = LinkState.ShowCode(userCode, verificationUri, expiresAtMs)
    }

    fun authorising() {
        _state.value = LinkState.Authorising
    }

    fun confirmUpload(
        accountName: String,
        host: String,
    ) {
        _state.value = LinkState.ConfirmUpload(accountName, host)
    }

    fun chooseFirstLink(
        localPodcasts: Int,
        localGroups: Int,
        serverPodcasts: Int,
        serverGroups: Int,
    ) {
        _state.value =
            LinkState.ChooseFirstLink(localPodcasts, localGroups, serverPodcasts, serverGroups)
    }

    /** 10 First-link choices: the USE_DEVICE second confirmation, after its staging pull. */
    fun confirmReplaceEverywhere(
        removedPodcasts: Int,
        removedGroups: Int,
    ) {
        _state.value = LinkState.ConfirmReplaceEverywhere(removedPodcasts, removedGroups)
    }

    fun merging(
        phase: ch.lkmc.neutrodyne.sync.api.LinkPhase,
        done: Int,
        total: Int,
    ) {
        _state.value = LinkState.Merging(phase, done, total)
    }

    fun linked(
        accountName: String,
        deviceName: String,
    ) {
        _state.value = LinkState.Linked(accountName, deviceName)
    }

    fun failed(error: ch.lkmc.neutrodyne.sync.api.SyncProblem) {
        _state.value = LinkState.Failed(error)
    }

    override fun cancel() {
        _state.value = LinkState.Address
    }
}
