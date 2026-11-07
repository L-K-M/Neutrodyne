// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.api

import kotlinx.coroutines.flow.StateFlow

/** The link methods 10 Link methods offers, in the app's order. */
enum class LinkMethod { CODE, INVITE, PASSWORD }

/** The first-link choices of 10 First-link choices. */
enum class FirstLinkChoice { MERGE, USE_SERVER, USE_DEVICE }

/** The merge phase shown while a first-link step runs. */
enum class LinkPhase { UPLOADING, DOWNLOADING }

/** The server's discovery result, after the local-network gate and the insecure check. */
data class DiscoveredServer(
    val name: String,
    val serverVersion: String,
    val serverUrl: String,
    val features: List<String>,
    val maxBatch: Int,
    val maxSkewMs: Long,
)

/** The privacy disclosure shown before every first link (R1.9, N3; 10 Privacy and disclosure). */
data class SyncDisclosure(
    val host: String,
    val privateFeedLinks: Int,
    val encrypted: Boolean,
)

/** The pending device an already linked device approves (10 Link methods). */
data class PendingDevice(
    val userCode: String,
    val deviceName: String,
    val platform: String,
    val appVersion: String,
    val requestedAtMs: Long,
)

/**
 * The state machine of 10 Link methods. `:sync:impl`'s `LinkFlow` implementation drives it from
 * the UI's calls (`startLink`, `discover`, `grantLocalNetwork`, `submitInvite`, `submitPassword`,
 * `chooseFirstLink`, `cancel`).
 */
sealed interface LinkState {
    /** The address step: no flow has started. */
    data object Address : LinkState

    data object Discovering : LinkState

    data class NeedsLocalNetworkPermission(
        val host: String,
    ) : LinkState

    data class InsecureConnection(
        val host: String,
    ) : LinkState

    data class AwaitingInput(
        val method: LinkMethod,
        val server: DiscoveredServer,
        val disclosure: SyncDisclosure,
    ) : LinkState

    data class ShowCode(
        val userCode: String,
        val verificationUri: String,
        val expiresAtMs: Long,
    ) : LinkState

    data object Authorising : LinkState

    data class ChooseFirstLink(
        val localPodcasts: Int,
        val localGroups: Int,
        val serverPodcasts: Int,
        val serverGroups: Int,
    ) : LinkState

    data class Merging(
        val phase: LinkPhase,
        val done: Int,
        val total: Int,
    ) : LinkState

    data class Linked(
        val accountName: String,
        val deviceName: String,
    ) : LinkState

    data class Failed(
        val error: SyncProblem,
    ) : LinkState
}

/** The `LinkFlow` service: the state machine plus its cancel (08 Linking a device). */
interface LinkFlow {
    val state: StateFlow<LinkState>

    /** Leaves the flow before `Authorising`; a merge in progress continues in the background. */
    fun cancel()
}
