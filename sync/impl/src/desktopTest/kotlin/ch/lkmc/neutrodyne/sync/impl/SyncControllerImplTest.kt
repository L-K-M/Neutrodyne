// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import ch.lkmc.neutrodyne.core.domain.LocalNetworkLocation
import ch.lkmc.neutrodyne.core.domain.NoPlaybackSync
import ch.lkmc.neutrodyne.core.domain.SecretStore
import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.domain.StoredSecret
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.NetError
import ch.lkmc.neutrodyne.core.network.NetErrorClassifier
import ch.lkmc.neutrodyne.core.testing.FakeCredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.testing.FakeLocalNetworkGate
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsSyncPort
import ch.lkmc.neutrodyne.core.testing.FakeSyncServerAddress
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.sync.api.FirstLinkChoice
import ch.lkmc.neutrodyne.sync.api.LinkMethod
import ch.lkmc.neutrodyne.sync.api.LinkState
import ch.lkmc.neutrodyne.sync.api.PendingDevice
import ch.lkmc.neutrodyne.sync.api.SyncPreferenceError
import ch.lkmc.neutrodyne.sync.api.SyncProblem
import ch.lkmc.neutrodyne.sync.api.SyncStatus
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.dto.AccountSummary
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeDto
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeResultDto
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangesPage
import ch.lkmc.neutrodyne.sync.protocol.dto.DeviceDto
import ch.lkmc.neutrodyne.sync.protocol.dto.DeviceInfoDto
import ch.lkmc.neutrodyne.sync.protocol.dto.DiscoveryDocument
import ch.lkmc.neutrodyne.sync.protocol.dto.FieldValue
import ch.lkmc.neutrodyne.sync.protocol.dto.InviteRedeemRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.LibraryCountsDto
import ch.lkmc.neutrodyne.sync.protocol.dto.LinkApproveResponse
import ch.lkmc.neutrodyne.sync.protocol.dto.LinkStartResponse
import ch.lkmc.neutrodyne.sync.protocol.dto.LoginRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.ProtocolRange
import ch.lkmc.neutrodyne.sync.protocol.dto.RecordDto
import ch.lkmc.neutrodyne.sync.protocol.dto.SyncResponse
import ch.lkmc.neutrodyne.sync.protocol.dto.TokenResponse
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The link-flow actions of [SyncControllerImpl] against a scripted [SyncClient]: discovery and the
 * local-network gate, the three link methods, approving on a linked device, the first-link choices
 * through [FirstLinkMerger], unlink/delete/rename/revoke, and their failure paths (bad code, wrong
 * password, revoked device, unreachable server).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncControllerImplTest {
    private val now = 1_800_000_000_000L
    private val host = "sync.example"
    private val serverUrl = "https://$host"

    @Test
    fun discoveryFailureShowsTheTransportProblem() =
        runTest {
            val h = harness(this)
            try {
                h.classifier.result = NetError.Offline
                h.client.discovery = { throw java.io.IOException("offline") }
                h.controller.startLink(serverUrl)
                await(h) { h.linkFlow.state.value is LinkState.Failed }
                assertEquals(SyncProblem.Offline, (h.linkFlow.state.value as LinkState.Failed).error)
                assertEquals(1, h.client.calls.count { it == "discover" })
                assertEquals(SyncStatus.NotConfigured, h.controller.status.value)
            } finally {
                h.close()
            }
        }

    @Test
    fun plainHttpToAPublicAddressIsRefusedBeforeAnyRequest() =
        runTest {
            val h = harness(this)
            try {
                h.controller.startLink("http://sync.example")
                await(h) { h.linkFlow.state.value is LinkState.Failed }
                assertEquals(SyncProblem.InsecureTransport, (h.linkFlow.state.value as LinkState.Failed).error)
                assertTrue(h.client.calls.isEmpty())
            } finally {
                h.close()
            }
        }

    @Test
    fun plainHttpToLocalAsksForConfirmationFirst() =
        runTest {
            val h = harness(this)
            try {
                h.gate.setLocation(LocalNetworkLocation.LOCAL)
                h.controller.discover("http://192.168.1.50")
                await(h) { h.linkFlow.state.value is LinkState.InsecureConnection }
                // The "not encrypted" confirmation is meaningless once traffic left.
                assertTrue(h.client.calls.isEmpty())
                h.controller.confirmInsecure()
                await(h) { h.linkFlow.state.value is LinkState.AwaitingInput }
                assertTrue(h.client.calls.contains("discover"))
            } finally {
                h.close()
            }
        }

    @Test
    fun aLocalServerAsksForPermissionBeforeTheDiscoveryRequest() =
        runTest {
            val h = harness(this)
            try {
                h.gate.setLocation(LocalNetworkLocation.LOCAL)
                h.gate.setPermissionGranted(false)
                h.controller.discover("https://192.168.1.50")
                await(h) { h.linkFlow.state.value is LinkState.NeedsLocalNetworkPermission }
                // Without ACCESS_LOCAL_NETWORK the request would only time out.
                assertTrue(h.client.calls.isEmpty())
                h.controller.grantLocalNetwork(true)
                await(h) { h.linkFlow.state.value is LinkState.AwaitingInput }
                assertEquals(listOf("discover"), h.client.calls)
            } finally {
                h.close()
            }
        }

    @Test
    fun aPortBearingLocalServerReachesTheGateWithoutThePort() =
        runTest {
            val h = harness(this)
            try {
                h.gate.setLocation(LocalNetworkLocation.LOCAL)
                h.gate.setPermissionGranted(false)
                h.controller.discover("https://192.168.1.50:8443")
                await(h) { h.linkFlow.state.value is LinkState.NeedsLocalNetworkPermission }
                // The gate classifies the bare host; an explicit port must not defeat it.
                assertEquals(listOf("192.168.1.50"), h.gate.classifiedHosts)
                assertTrue(h.client.calls.isEmpty())
            } finally {
                h.close()
            }
        }

    @Test
    fun plainHttpToAPublicAddressWithAPortIsStillRefused() =
        runTest {
            val h = harness(this)
            try {
                h.controller.startLink("http://sync.example:8080")
                await(h) { h.linkFlow.state.value is LinkState.Failed }
                assertEquals(SyncProblem.InsecureTransport, (h.linkFlow.state.value as LinkState.Failed).error)
                assertTrue(h.client.calls.isEmpty())
            } finally {
                h.close()
            }
        }

    @Test
    fun localNetworkNeedsPermissionAndDenialFails() =
        runTest {
            val h = harness(this)
            try {
                h.gate.setLocation(LocalNetworkLocation.LOCAL)
                h.gate.setPermissionGranted(false)
                h.controller.discover(serverUrl)
                await(h) { h.linkFlow.state.value is LinkState.NeedsLocalNetworkPermission }
                assertTrue(h.client.calls.isEmpty())
                h.controller.grantLocalNetwork(false)
                await(h) { h.linkFlow.state.value is LinkState.Failed }
                assertEquals(
                    SyncProblem.LocalNetworkPermissionDenied,
                    (h.linkFlow.state.value as LinkState.Failed).error,
                )
            } finally {
                h.close()
            }
        }

    @Test
    fun localNetworkGrantContinuesToAwaitingInput() =
        runTest {
            val h = harness(this)
            try {
                h.gate.setLocation(LocalNetworkLocation.LOCAL)
                h.gate.setPermissionGranted(false)
                h.controller.discover(serverUrl)
                await(h) { h.linkFlow.state.value is LinkState.NeedsLocalNetworkPermission }
                h.controller.grantLocalNetwork(true)
                await(h) { h.linkFlow.state.value is LinkState.AwaitingInput }
                assertEquals(listOf(true), h.gate.results)
            } finally {
                h.close()
            }
        }

    @Test
    fun anAlreadyGrantedLocalServerSetsTheBypassBeforeDiscovery() =
        runTest {
            val h = harness(this)
            try {
                h.gate.setLocation(LocalNetworkLocation.LOCAL)
                h.gate.setPermissionGranted(true)
                h.controller.discover("https://192.168.1.50")
                await(h) { h.linkFlow.state.value is LinkState.AwaitingInput }
                // No prompt ran, so the bypass must come from the gate's real verdict.
                assertEquals(listOf(true), h.gate.results)
                assertEquals(listOf("discover"), h.client.calls)
            } finally {
                h.close()
            }
        }

    @Test
    fun aServerThatSpeaksAnOlderProtocolFails() =
        runTest {
            val h = harness(this)
            try {
                h.client.discovery = { defaultDiscovery(protocolMax = 0) }
                h.controller.startLink(serverUrl)
                await(h) { h.linkFlow.state.value is LinkState.Failed }
                assertEquals(SyncProblem.ServerTooOld, (h.linkFlow.state.value as LinkState.Failed).error)
            } finally {
                h.close()
            }
        }

    @Test
    fun theCodeFlowPollsThenOffersTheFirstLinkChoice() =
        runTest {
            val h = harness(this)
            try {
                h.addLocalPodcast()
                var polls = 0
                h.client.linkToken = {
                    polls++
                    if (polls < 2) throw SyncProblemException("authorization_pending")
                    token()
                }
                h.controller.startLink(serverUrl, LinkMethod.CODE)
                await(h) { h.linkFlow.state.value is LinkState.ShowCode }
                assertEquals("WDJB-MJHT", (h.linkFlow.state.value as LinkState.ShowCode).userCode)
                await(h) { h.linkFlow.state.value is LinkState.ChooseFirstLink }
                assertTrue(polls >= 2)

                h.controller.chooseFirstLink(FirstLinkChoice.MERGE)
                await(h) { h.controller.status.value is SyncStatus.Linked }
                assertNotNull(
                    h.db
                        .syncStateDao()
                        .get()
                        ?.linkedAt,
                )
                assertTrue(h.tokenStoreContains(host))
                assertTrue(h.address.writtenServerUrls.contains(serverUrl))
            } finally {
                h.close()
            }
        }

    @Test
    fun anExpiredCodeFailsWithoutStoringAToken() =
        runTest {
            val h = harness(this)
            try {
                h.client.linkToken = { throw SyncProblemException("expired_token") }
                h.controller.startLink(serverUrl, LinkMethod.CODE)
                await(h) { h.linkFlow.state.value is LinkState.Failed }
                assertEquals(SyncProblem.InvalidCode, (h.linkFlow.state.value as LinkState.Failed).error)
                assertFalse(h.tokenStoreContains(host))
                assertNull(
                    h.db
                        .syncStateDao()
                        .get()
                        ?.deviceId,
                )
                assertNull(
                    h.db
                        .syncStateDao()
                        .get()
                        ?.serverUrl,
                )
            } finally {
                h.close()
            }
        }

    @Test
    fun aDeniedCodeFailsWithTheDeclinedProblem() =
        runTest {
            val h = harness(this)
            try {
                h.client.linkToken = { throw SyncProblemException("access_denied") }
                h.controller.startLink(serverUrl, LinkMethod.CODE)
                await(h) { h.linkFlow.state.value is LinkState.Failed }
                assertEquals(SyncProblem.LinkDeclined, (h.linkFlow.state.value as LinkState.Failed).error)
            } finally {
                h.close()
            }
        }

    @Test
    fun cancellingTheFlowStopsTheCodePolling() =
        runTest {
            val h = harness(this)
            try {
                var polls = 0
                h.client.linkToken = {
                    polls++
                    throw SyncProblemException("authorization_pending")
                }
                h.controller.startLink(serverUrl, LinkMethod.CODE)
                await(h) { h.linkFlow.state.value is LinkState.ShowCode }
                assertEquals(0, polls)
                h.controller.link?.cancel()
                h.tick()
                assertEquals(0, polls)
                assertEquals(LinkState.Address, h.linkFlow.state.value)
            } finally {
                h.close()
            }
        }

    @Test
    fun anInviteRedeemsAndLinks() =
        runTest {
            val h = harness(this)
            try {
                h.addLocalPodcast()
                h.controller.discover(serverUrl)
                await(h) { h.linkFlow.state.value is LinkState.AwaitingInput }
                h.controller.submitInvite("abcd-efgh")
                await(h) { h.linkFlow.state.value is LinkState.ChooseFirstLink }
                assertTrue(h.client.calls.contains("redeem:ABCDEFGH"))
            } finally {
                h.close()
            }
        }

    @Test
    fun aUsedInviteFails() =
        runTest {
            val h = harness(this)
            try {
                h.client.invite = { throw SyncTransportException(401, "unauthorized", "") }
                h.controller.discover(serverUrl)
                await(h) { h.linkFlow.state.value is LinkState.AwaitingInput }
                h.controller.submitInvite("abcd-efgh")
                await(h) { h.linkFlow.state.value is LinkState.Failed }
                assertEquals(SyncProblem.InviteInvalid, (h.linkFlow.state.value as LinkState.Failed).error)
            } finally {
                h.close()
            }
        }

    @Test
    fun cancellingAPasswordAuthWritesNothingAndZeroesThePassword() =
        runTest {
            val h = harness(this)
            try {
                val reply = kotlinx.coroutines.CompletableDeferred<TokenResponse>()
                h.client.login = { _, _ -> reply.await() }
                h.controller.discover(serverUrl)
                await(h) { h.linkFlow.state.value is LinkState.AwaitingInput }
                val password = "s3cret".toCharArray()
                h.controller.submitPassword("alice", password)
                await(h) { h.linkFlow.state.value is LinkState.Authorising }
                h.controller.link?.cancel()
                reply.complete(token())
                h.tick()
                Thread.sleep(20)
                assertFalse(h.tokenStoreContains(host))
                assertNull(
                    h.db
                        .syncStateDao()
                        .get()
                        ?.deviceId,
                )
                assertEquals(LinkState.Address, h.linkFlow.state.value)
                assertTrue(password.all { it == '\u0000' })
            } finally {
                h.close()
            }
        }

    @Test
    fun aNewLinkSupersedesAnInFlightAuth() =
        runTest {
            val h = harness(this)
            try {
                h.addLocalPodcast()
                val reply = kotlinx.coroutines.CompletableDeferred<TokenResponse>()
                h.client.login = { _, _ -> reply.await() }
                h.controller.discover(serverUrl)
                await(h) { h.linkFlow.state.value is LinkState.AwaitingInput }
                h.controller.submitPassword("alice", "s3cret".toCharArray())
                await(h) { h.linkFlow.state.value is LinkState.Authorising }
                h.controller.startLink("https://other.example", LinkMethod.PASSWORD)
                await(h) {
                    (h.linkFlow.state.value as? LinkState.AwaitingInput)?.server?.serverUrl ==
                        "https://other.example"
                }
                // The stale login's answer arrives after the new session started.
                reply.complete(token())
                h.tick()
                Thread.sleep(20)
                assertFalse(h.tokenStoreContains(host))
                assertNull(
                    h.db
                        .syncStateDao()
                        .get()
                        ?.deviceId,
                )
                assertTrue(h.linkFlow.state.value is LinkState.AwaitingInput)
            } finally {
                h.close()
            }
        }

    @Test
    fun aPasswordLoginStoresTheTokenAndClearsThePassword() =
        runTest {
            val h = harness(this)
            try {
                h.addLocalPodcast()
                val password = "s3cret".toCharArray()
                h.controller.discover(serverUrl)
                await(h) { h.linkFlow.state.value is LinkState.AwaitingInput }
                h.controller.submitPassword("alice", password)
                await(h) { h.linkFlow.state.value is LinkState.ChooseFirstLink }
                assertTrue(h.tokenStoreContains(host))
                assertEquals("alice", h.address.username())
                assertTrue(password.all { it == '\u0000' })
                assertTrue(h.client.calls.contains("login:alice"))
            } finally {
                h.close()
            }
        }

    @Test
    fun aWrongPasswordFails() =
        runTest {
            val h = harness(this)
            try {
                h.client.login = { _, _ -> throw SyncTransportException(401, "unauthorized", "") }
                h.controller.discover(serverUrl)
                await(h) { h.linkFlow.state.value is LinkState.AwaitingInput }
                h.controller.submitPassword("alice", "wrong".toCharArray())
                await(h) { h.linkFlow.state.value is LinkState.Failed }
                assertEquals(SyncProblem.WrongPassword, (h.linkFlow.state.value as LinkState.Failed).error)
                assertFalse(h.tokenStoreContains(host))
            } finally {
                h.close()
            }
        }

    @Test
    fun approvingOnALinkedDeviceReturnsThePendingDeviceAndConfirms() =
        runTest {
            val h = harness(this)
            try {
                h.seedLinked()
                val approved = h.controller.approveLink("wdjb mjht")
                assertTrue(approved is Outcome.Success)
                val pending = (approved as Outcome.Success).value
                assertEquals(normaliseUserCode("wdjb mjht"), pending.userCode)
                assertEquals("Pixel 8", pending.deviceName)
                assertEquals("android", pending.platform)
                assertEquals("1.0.0", pending.appVersion)
                assertTrue(pending.requestedAtMs > 0)
                val confirmed = h.controller.confirmLink("wdjb-mjht", approve = true)
                assertTrue(confirmed is Outcome.Success)
                assertTrue(h.client.calls.contains("approve:" + normaliseUserCode("wdjb mjht")))
                assertTrue(h.client.calls.contains("confirm:" + normaliseUserCode("wdjb mjht") + ":true"))
            } finally {
                h.close()
            }
        }

    @Test
    fun denyingOnALinkedDeviceCallsTheServerWithFalse() =
        runTest {
            val h = harness(this)
            try {
                h.seedLinked()
                assertTrue(h.controller.approveLink("WDJB-MJHT") is Outcome.Success)
                assertTrue(h.controller.confirmLink("WDJB-MJHT", approve = false) is Outcome.Success)
                assertTrue(h.client.calls.contains("confirm:" + normaliseUserCode("WDJB-MJHT") + ":false"))
            } finally {
                h.close()
            }
        }

    @Test
    fun anUnknownCodeOnALinkedDeviceFails() =
        runTest {
            val h = harness(this)
            try {
                h.seedLinked()
                h.client.approve = { throw SyncTransportException(401, "unauthorized", "") }
                val result = h.controller.approveLink("WDJB-MJHT")
                assertEquals(SyncProblem.InvalidCode, (result as Outcome.Failure).error)
            } finally {
                h.close()
            }
        }

    @Test
    fun anEmptyAccountNamesItselfAndUploadsAfterConfirmation() =
        runTest {
            val h = harness(this)
            try {
                h.addLocalPodcast()
                val pushed = mutableListOf<ChangeDto>()
                h.client.syncHandler = { _, changes ->
                    pushed += changes
                    defaultSyncResponse(changes)
                }
                h.client.summary = AccountSummary("alice", LibraryCountsDto(0, 0, 0, 0, 0), 1, 0, 0)
                h.controller.discover(serverUrl)
                await(h) { h.linkFlow.state.value is LinkState.AwaitingInput }
                h.controller.submitInvite("abcd-efgh")
                await(h) { h.linkFlow.state.value is LinkState.ConfirmUpload }
                val state = h.linkFlow.state.value as LinkState.ConfirmUpload
                assertEquals("alice", state.accountName)
                assertEquals(host, state.host)

                h.controller.chooseFirstLink(FirstLinkChoice.MERGE)
                await(h) { h.controller.status.value is SyncStatus.Linked }
                // The local podcast was uploaded through the outbox and pushed.
                assertTrue(h.client.calls.any { it == "sync" })
                assertTrue(
                    pushed.any { change -> change.coll == "podcast" },
                    "pushed=${pushed.map { it.coll to it.id }}",
                )
                assertTrue(h.db.syncClockDao().count() > 0)
                assertNotNull(
                    h.db
                        .syncStateDao()
                        .get()
                        ?.linkedAt,
                )
            } finally {
                h.close()
            }
        }

    @Test
    fun anEmptyDeviceMergesTheServerLibraryWithoutAChoice() =
        runTest {
            val h = harness(this)
            try {
                h.client.summary = AccountSummary("alice", LibraryCountsDto(1, 0, 0, 0, 0), 1, 0, 0)
                h.client.changesHandler = { since ->
                    if (since == null) ChangesPage(listOf(podcastRecord()), "c:00000001:1", false, "") else emptyPage()
                }
                h.controller.startLink(serverUrl, LinkMethod.CODE)
                await(h) { h.controller.status.value is SyncStatus.Linked }
                // An empty device runs the server-library merge directly, no choice screen.
                assertEquals(
                    ch.lkmc.neutrodyne.core.model.MergePolicy.LINK_REPLACE,
                    h.libraryMerger.calls
                        .single()
                        .policy,
                )
                assertNotNull(
                    h.db
                        .syncStateDao()
                        .get()
                        ?.linkedAt,
                )
            } finally {
                h.close()
            }
        }

    /** The Replace semantics live in :core:data's merger; here the choice reaches the port. */
    @Test
    fun useServerRunsTheLinkReplaceThroughThePort() =
        runTest {
            val h = harness(this)
            try {
                h.addLocalPodcast()
                h.client.summary = AccountSummary("alice", LibraryCountsDto(1, 0, 0, 0, 0), 1, 0, 0)
                h.client.changesHandler = { since ->
                    if (since ==
                        null
                    ) {
                        ChangesPage(listOf(otherPodcastRecord()), "c:00000001:1", false, "")
                    } else {
                        emptyPage()
                    }
                }
                h.controller.startLink(serverUrl, LinkMethod.CODE)
                await(h) { h.linkFlow.state.value is LinkState.ChooseFirstLink }
                h.controller.chooseFirstLink(FirstLinkChoice.USE_SERVER)
                await(h) { h.controller.status.value is SyncStatus.Linked }
                assertEquals(
                    ch.lkmc.neutrodyne.core.model.MergePolicy.LINK_REPLACE,
                    h.libraryMerger.calls
                        .single()
                        .policy,
                )
            } finally {
                h.close()
            }
        }

    @Test
    fun useDeviceEverywhereUploadsThenPrunesTheServerLibrary() =
        runTest {
            val h = harness(this)
            try {
                h.addLocalPodcast()
                h.addGroup()
                val pushed = mutableListOf<ChangeDto>()
                h.client.syncHandler = { _, changes ->
                    pushed += changes
                    defaultSyncResponse(changes)
                }
                h.client.summary = AccountSummary("alice", LibraryCountsDto(1, 1, 0, 0, 0), 1, 0, 0)
                h.client.changesHandler = { since ->
                    if (since == null) {
                        ChangesPage(listOf(otherPodcastRecord(), otherGroupRecord()), "c:00000001:1", false, "")
                    } else {
                        emptyPage()
                    }
                }
                h.controller.startLink(serverUrl, LinkMethod.CODE)
                await(h) { h.linkFlow.state.value is LinkState.ChooseFirstLink }
                h.controller.chooseFirstLink(FirstLinkChoice.USE_DEVICE)
                await(h) { h.controller.status.value is SyncStatus.Linked }
                // Uploaded this device's library ...
                assertTrue(h.client.calls.any { it == "sync" })
                assertTrue(
                    pushed.any { change -> change.coll == "podcast" || change.coll == "group" },
                    "pushed=${pushed.map { it.coll to it.id }}",
                )
                assertNotNull(h.db.podcastDao().bySyncId(PODCAST_SYNC_ID))
                assertEquals(
                    "c:00000002:1",
                    h.db
                        .syncStateDao()
                        .get()
                        ?.cursor,
                )
                // ... then pruned the server with the staged snapshot's cursor.
                assertEquals("library", h.client.resetMode)
                assertEquals("c:00000001:1", h.client.resetBeforeCursor)
                assertNotNull(
                    h.db
                        .syncStateDao()
                        .get()
                        ?.linkedAt,
                )
            } finally {
                h.close()
            }
        }

    @Test
    fun aFailedMergeLeavesTheLinkUnfinished() =
        runTest {
            val h = harness(this)
            try {
                h.addLocalPodcast()
                h.client.summary = AccountSummary("alice", LibraryCountsDto(1, 0, 0, 0, 0), 1, 0, 0)
                h.client.changesHandler = { throw java.io.IOException("offline") }
                h.controller.startLink(serverUrl, LinkMethod.CODE)
                await(h) { h.linkFlow.state.value is LinkState.ChooseFirstLink }
                h.controller.chooseFirstLink(FirstLinkChoice.MERGE)
                await(h) { h.linkFlow.state.value is LinkState.Failed }
                assertEquals(SyncProblem.MergeFailed, (h.linkFlow.state.value as LinkState.Failed).error)
                assertNull(
                    h.db
                        .syncStateDao()
                        .get()
                        ?.linkedAt,
                )
                assertTrue(h.controller.status.value is SyncStatus.LinkUnfinished)
            } finally {
                h.close()
            }
        }

    @Test
    fun unlinkPushesPendingChangesThenLogsOutAndClearsLocally() =
        runTest {
            val h = harness(this)
            try {
                h.seedLinked()
                h.captureLocalPodcast()
                assertTrue(h.db.syncOutboxDao().count() > 0)
                val result = h.controller.unlink()
                assertTrue(result is Outcome.Success)
                assertTrue(h.client.calls.contains("sync"))
                assertTrue(h.client.calls.contains("logout"))
                assertFalse(h.tokenStoreContains(host))
                assertEquals(0, h.db.syncOutboxDao().count())
                assertEquals(0, h.db.syncClockDao().count())
                val state = h.db.syncStateDao().get()
                assertNull(state?.deviceId)
                assertNull(state?.linkedAt)
                // The address is kept for a quick re-link, so the status is NotConfigured.
                assertEquals(SyncStatus.NotConfigured, h.controller.status.value)
                assertEquals(serverUrl, h.address.serverUrl())
            } finally {
                h.close()
            }
        }

    @Test
    fun unlinkProceedsWhenTheServerIsUnreachable() =
        runTest {
            val h = harness(this)
            try {
                h.seedLinked()
                h.client.syncHandler = { _, _ -> throw java.io.IOException("offline") }
                h.client.logout = { throw java.io.IOException("offline") }
                val result = h.controller.unlink()
                assertTrue(result is Outcome.Success)
                assertFalse(h.tokenStoreContains(host))
                assertEquals(SyncStatus.NotConfigured, h.controller.status.value)
            } finally {
                h.close()
            }
        }

    @Test
    fun deletingServerDataNeedsTheExactConfirmation() =
        runTest {
            val h = harness(this)
            try {
                h.seedLinked()
                val result = h.controller.deleteServerData("delete")
                assertEquals(SyncProblem.Server(400), (result as Outcome.Failure).error)
                assertNull(h.client.resetMode)
            } finally {
                h.close()
            }
        }

    @Test
    fun deletingServerDataPurgesThenUnlinksLocally() =
        runTest {
            val h = harness(this)
            try {
                h.seedLinked()
                val result = h.controller.deleteServerData("DELETE")
                assertTrue(result is Outcome.Success)
                assertEquals("purge", h.client.resetMode)
                assertFalse(h.tokenStoreContains(host))
                assertEquals(SyncStatus.NotConfigured, h.controller.status.value)
            } finally {
                h.close()
            }
        }

    @Test
    fun renamingADeviceSendsTheTrimmedName() =
        runTest {
            val h = harness(this)
            try {
                h.seedLinked()
                val result = h.controller.renameDevice("dev-2", "  Office  ")
                assertTrue(result is Outcome.Success)
                assertEquals(listOf("dev-2" to "Office"), h.client.renames)
            } finally {
                h.close()
            }
        }

    @Test
    fun revokingADeviceCallsTheServer() =
        runTest {
            val h = harness(this)
            try {
                h.seedLinked()
                assertTrue(h.controller.revokeDevice("dev-2") is Outcome.Success)
                assertEquals(listOf("dev-2"), h.client.revokes)
            } finally {
                h.close()
            }
        }

    @Test
    fun aRevokedDeviceMarksTheStatusRevoked() =
        runTest {
            val h = harness(this)
            try {
                h.seedLinked()
                h.client.rename = { _, _ -> throw SyncTransportException(401, "unauthorized", "") }
                val result = h.controller.renameDevice("dev-2", "Office")
                assertEquals(SyncProblem.Revoked, (result as Outcome.Failure).error)
                await(h) { h.controller.status.value is SyncStatus.Revoked }
                assertEquals(
                    "Revoked",
                    h.db
                        .syncStateDao()
                        .get()
                        ?.lastError,
                )
            } finally {
                h.close()
            }
        }

    @Test
    fun aRevokedDeviceStopsAllSyncRounds() =
        runTest {
            val h = harness(this)
            try {
                h.seedLinked()
                h.client.syncHandler = { _, _ -> throw SyncTransportException(401, "unauthorized", "") }
                h.controller.syncNow()
                await(h) { h.controller.status.value is SyncStatus.Revoked }
                val state = h.db.syncStateDao().get()
                assertEquals("Revoked", state?.lastError)
                assertEquals(false, state?.enabled)
                // A later periodic or manual round never reaches the server again.
                val calls = h.client.calls.count { it == "sync" }
                h.controller.syncNow()
                h.tick()
                Thread.sleep(50)
                h.controller.syncNow()
                h.tick()
                Thread.sleep(50)
                assertEquals(calls, h.client.calls.count { it == "sync" })
            } finally {
                h.close()
            }
        }

    @Test
    fun aStaleStatusReadCannotOverwriteTheRevokedStatus() =
        runTest {
            val gated = GatedSecretStore()
            val h = harness(this, gated)
            try {
                h.seedLinked()
                h.client.syncHandler = { _, _ -> throw SyncTransportException(401, "unauthorized", "") }
                // One refresh read parks holding the pre-revocation snapshot — the constructor's
                // refresh or this explicit one, whichever loads the token first. A status computed
                // from that stale snapshot must never publish over the revocation.
                gated.arm()
                val stale = launch { h.controller.refreshStatus() }
                gated.entered.await()
                h.controller.syncNow()
                // The marker is durable before the parked read resumes — it does not wait on the
                // status publish.
                awaitRevoked(h)
                // Let the revocation's own publishes finish so the parked read resumes last —
                // serialized status reads hold it until the gate frees, so idle is only a
                // bounded wait, not a condition.
                val quietDeadline = System.nanoTime() + 2_000_000_000L
                while (h.controllerBusy() && System.nanoTime() < quietDeadline) {
                    h.tick()
                    Thread.sleep(2)
                }
                gated.gate.complete(Unit)
                stale.join()
                await(h, timeoutMs = 10_000) { h.controller.status.value is SyncStatus.Revoked }
                val states = h.db.syncStateDao()
                assertEquals("Revoked", states.get()?.lastError)
            } finally {
                h.close()
            }
        }

    @Test
    fun listingDevicesMarksThisDevice() =
        runTest {
            val h = harness(this)
            try {
                h.seedLinked()
                h.client.devicesHandler = {
                    listOf(
                        DeviceDto(DEVICE_ID, "This", "android", "1.0.0", null, current = true, stale = false),
                        DeviceDto("dev-2", "Other", "linux", "1.0.0", null, current = false, stale = true),
                    )
                }
                val result = h.controller.devices()
                val devices = (result as Outcome.Success).value
                assertTrue(devices[0].current)
                assertFalse(devices[1].current)
                assertTrue(devices[1].stale)
            } finally {
                h.close()
            }
        }

    @Test
    fun aFreshLinkedControlRequestAppliesTheGateVerdictBeforeTheClient() =
        runTest {
            val h = harness(this)
            try {
                h.seedLinked()
                h.gate.setLocation(LocalNetworkLocation.LOCAL)
                h.gate.setPermissionGranted(true)
                // A fresh process with an already-granted permission must not need a prior
                // preflight/round: the control request applies the gate's live verdict first.
                h.client.rename = { id, name ->
                    check(h.gate.results == listOf(true)) { "the gate verdict was not applied before the client" }
                    DeviceDto(id, name, "android", "1.0.0", null, false, false)
                }
                val result = h.controller.renameDevice("dev-2", "Office")
                assertTrue(result is Outcome.Success)
                assertEquals(listOf("dev-2" to "Office"), h.client.renames)
            } finally {
                h.close()
            }
        }

    @Test
    fun everyDirectControlRequestAppliesTheGateVerdictBeforeTheClient() =
        runTest {
            val h = harness(this)
            try {
                h.seedLinked()
                h.gate.setLocation(LocalNetworkLocation.LOCAL)
                h.gate.setPermissionGranted(true)
                var controlCalls = 0

                fun expectGateApplied() {
                    controlCalls++
                    check(h.gate.results.isNotEmpty()) { "the gate verdict was not applied before the client" }
                }
                h.client.approve = {
                    expectGateApplied()
                    LinkApproveResponse("Pixel 8", "android", "1.0.0", "2026-11-04T10:00:00Z", "device", true)
                }
                h.client.confirm = { _, _ -> expectGateApplied() }
                h.client.devicesHandler = {
                    expectGateApplied()
                    emptyList()
                }
                h.client.rename = { id, name ->
                    expectGateApplied()
                    DeviceDto(id, name, "android", "1.0.0", null, false, false)
                }
                h.client.revoke = { expectGateApplied() }
                h.client.resetHandler = { _, _, _ ->
                    expectGateApplied()
                    AccountSummary("alice", LibraryCountsDto(0, 0, 0, 0, 0), 1, 0, 0)
                }

                assertTrue(h.controller.approveLink("WDJB-MJHT") is Outcome.Success)
                assertTrue(h.controller.confirmLink("WDJB-MJHT", approve = true) is Outcome.Success)
                assertTrue(h.controller.devices() is Outcome.Success)
                assertTrue(h.controller.renameDevice("dev-2", "Office") is Outcome.Success)
                assertTrue(h.controller.revokeDevice("dev-2") is Outcome.Success)
                // deleteServerData unlinks locally, so it runs last.
                assertTrue(h.controller.deleteServerData("DELETE") is Outcome.Success)
                assertEquals(6, controlCalls)
                assertTrue(h.client.calls.contains("approve:" + normaliseUserCode("WDJB-MJHT")))
                assertTrue(h.client.calls.contains("confirm:" + normaliseUserCode("WDJB-MJHT") + ":true"))
                assertTrue(h.client.calls.contains("devices"))
                assertTrue(h.client.calls.contains("reset:purge"))
            } finally {
                h.close()
            }
        }

    @Test
    fun controlRequestsFollowTheLivePermissionRevokeAndRegrant() =
        runTest {
            val h = harness(this)
            try {
                h.seedLinked()
                h.gate.setLocation(LocalNetworkLocation.LOCAL)
                h.gate.setPermissionGranted(true)
                assertTrue(h.controller.renameDevice("dev-2", "One") is Outcome.Success)
                assertEquals(listOf(true), h.gate.results)
                // A revocation in system settings is picked up at the next control request ...
                h.gate.setPermissionGranted(false)
                assertTrue(h.controller.renameDevice("dev-2", "Two") is Outcome.Success)
                assertEquals(listOf(true, false), h.gate.results)
                // ... and a regrant re-enables it without a prior round.
                h.gate.setPermissionGranted(true)
                assertTrue(h.controller.renameDevice("dev-2", "Three") is Outcome.Success)
                assertEquals(listOf(true, false, true), h.gate.results)
            } finally {
                h.close()
            }
        }

    @Test
    fun overlappingPasswordSwitchWritesCannotPublishAnOlderRuntimeGate() =
        runTest {
            val h = harness(this)
            val committed = kotlinx.coroutines.CompletableDeferred<Unit>()
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            try {
                h.tick()
                h.address.updateShareFeedPasswords(true)
                h.flags.sharePasswords = true
                h.address.afterShareWrite = { enabled ->
                    if (!enabled) {
                        committed.complete(Unit)
                        release.await()
                    }
                }
                val first = async { h.controller.setShareFeedPasswords(false) }
                committed.await()
                val second =
                    async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        h.controller.setShareFeedPasswords(true)
                    }
                release.complete(Unit)
                first.await()
                second.await()

                assertEquals(h.address.shareFeedPasswords(), h.flags.sharePasswords)
                assertTrue(h.flags.sharePasswords)
            } finally {
                release.complete(Unit)
                h.close()
            }
        }

    @Test
    fun cancellingAfterASwitchCommitsStillPublishesItsRuntimeGate() =
        runTest {
            val h = harness(this)
            val committed = kotlinx.coroutines.CompletableDeferred<Unit>()
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            try {
                h.tick()
                h.address.afterShareWrite = {
                    committed.complete(Unit)
                    release.await()
                }
                val write = async { h.controller.setShareFeedPasswords(true) }
                committed.await()
                write.cancel()
                release.complete(Unit)
                write.join()

                assertTrue(h.address.shareFeedPasswords())
                assertTrue(h.flags.sharePasswords, "a committed preference must survive caller cancellation")
            } finally {
                release.complete(Unit)
                h.close()
            }
        }

    @Test
    fun failedPasswordSharingWritesKeepStoredAndRuntimeValues() =
        runTest {
            for (before in listOf(false, true)) {
                val h = harness(this)
                try {
                    h.tick()
                    h.address.updateShareFeedPasswords(before)
                    h.flags.sharePasswords = before
                    h.address.nextPreferenceError = SettingsError.WriteFailed

                    val result: Any? = h.controller.setShareFeedPasswords(!before)

                    assertEquals(
                        before,
                        h.flags.sharePasswords,
                        "a failed write must not change the runtime gate",
                    )
                    assertEquals(before, h.address.shareFeedPasswords())
                    assertEquals<Any?>(Outcome.Failure(SyncPreferenceError.WriteFailed), result)
                } finally {
                    h.close()
                }
            }
        }

    @Test
    fun failedPlaybackSettingWritesKeepStoredAndRuntimeValues() =
        runTest {
            for (before in listOf(false, true)) {
                val h = harness(this)
                try {
                    h.tick()
                    h.address.updateSyncPlaybackSettings(before)
                    h.flags.syncPlaybackSettings = before
                    h.address.nextPreferenceError = SettingsError.WriteFailed

                    val result: Any? = h.controller.setSyncPlaybackSettings(!before)

                    assertEquals(
                        before,
                        h.flags.syncPlaybackSettings,
                        "a failed write must not change the runtime gate",
                    )
                    assertEquals(before, h.address.syncPlaybackSettings())
                    assertEquals<Any?>(Outcome.Failure(SyncPreferenceError.WriteFailed), result)
                } finally {
                    h.close()
                }
            }
        }

    @Test
    fun shareFeedPasswordsWritesTheSettingAndTheCache() =
        runTest {
            val h = harness(this)
            try {
                assertEquals(Outcome.Success(Unit), h.controller.setShareFeedPasswords(true))
                assertEquals(listOf(true), h.address.writtenShareFeedPasswords)
                assertTrue(h.flags.sharePasswords)
            } finally {
                h.close()
            }
        }

    @Test
    fun syncPlaybackSettingsWritesTheSettingAndTheCache() =
        runTest {
            val h = harness(this)
            try {
                assertEquals(Outcome.Success(Unit), h.controller.setSyncPlaybackSettings(false))
                assertEquals(listOf(false), h.address.writtenSyncPlaybackSettings)
                assertFalse(h.flags.syncPlaybackSettings)
            } finally {
                h.close()
            }
        }

    /**
     * 10 Settings capture: enabling "Sync playback settings" drains the values that stayed
     * pending while it was off (the server's values win); disabling never drains.
     */
    @Test
    fun aFailedPlaybackSwitchWriteLeavesRemoteSettingsPending() =
        runTest {
            val h = harness(this)
            try {
                h.seedLinked()
                val key = ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys.SKIP_BACK_MS
                h.address.updateSyncPlaybackSettings(false)
                h.flags.syncPlaybackSettings = false
                h.journalPendingSetting(key, "15000")
                h.address.nextPreferenceError = SettingsError.WriteFailed

                assertEquals(
                    Outcome.Failure(SyncPreferenceError.WriteFailed),
                    h.controller.setSyncPlaybackSettings(true),
                )

                assertFalse(h.flags.syncPlaybackSettings)
                assertTrue(h.settings.applied.isEmpty())
                assertEquals("15000", h.pendingSettingOf(key.name))
            } finally {
                h.close()
            }
        }

    @Test
    fun enablingSyncPlaybackSettingsDrainsThePendingMarker() =
        runTest {
            val h = harness(this)
            try {
                h.seedLinked()
                val key = ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys.SKIP_BACK_MS
                h.address.updateSyncPlaybackSettings(false)
                h.journalPendingSetting(key, "15000")

                h.controller.setSyncPlaybackSettings(true)

                assertEquals(listOf(true), h.address.writtenSyncPlaybackSettings)
                assertEquals(1, h.settings.applied.size)
                assertEquals(
                    key,
                    h.settings.applied
                        .single()
                        .first,
                )
                assertEquals(
                    "15000",
                    h.settings.applied
                        .single()
                        .second,
                )
                assertNull(h.pendingSettingOf(key.name))
            } finally {
                h.close()
            }
        }

    @Test
    fun disablingSyncPlaybackSettingsLeavesTheMarkerPending() =
        runTest {
            val h = harness(this)
            try {
                h.seedLinked()
                val key = ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys.SKIP_BACK_MS
                h.address.updateSyncPlaybackSettings(true)
                h.journalPendingSetting(key, "15000")

                h.controller.setSyncPlaybackSettings(false)

                assertTrue(h.settings.applied.isEmpty(), "disabling must not drain the marker")
                assertEquals("15000", h.pendingSettingOf(key.name))
            } finally {
                h.close()
            }
        }

    @Test
    fun aReconnectStatusCoversARestoredInstall() =
        runTest {
            val h = harness(this)
            try {
                h.address.updateServerUrl(serverUrl)
                h.address.updateUsername("alice")
                h.controller.refreshStatus()
                val status = h.controller.status.value as SyncStatus.Reconnect
                assertEquals(serverUrl, status.serverUrl)
                assertEquals("alice", status.username)
            } finally {
                h.close()
            }
        }

    private fun harness(
        scope: TestScope,
        secrets: SecretStore = FakeSecretStore(),
    ): Harness = Harness(scope, now, secrets)

    /**
     * Waits for [condition], letting the flow's coroutines run and advancing the poll delays: the
     * database executes on a real dispatcher, so virtual time alone would never complete it.
     */
    private fun await(
        h: Harness,
        timeoutMs: Long = 30_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for the link flow: ${h.linkFlow.state.value}" }
            h.tick()
            Thread.sleep(2)
        }
    }

    /** [await]'s suspending twin: polls the `sync_state` row itself, not a published flow. */
    private suspend fun awaitRevoked(h: Harness) {
        val deadline = System.nanoTime() + 30_000_000_000L
        val states = h.db.syncStateDao()
        while (states.get()?.lastError != "Revoked") {
            check(System.nanoTime() < deadline) { "timed out waiting for the revocation marker" }
            h.tick()
            Thread.sleep(2)
        }
    }

    private fun token(): TokenResponse = TokenResponse("nds_test", ACCOUNT_ID, DEVICE_ID, "alice")

    companion object {
        const val PODCAST_SYNC_ID = "00000000-0000-4000-8000-00000000000a"
        const val OTHER_PODCAST_SYNC_ID = "00000000-0000-4000-8000-00000000000b"
        const val GROUP_UUID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val OTHER_GROUP_UUID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        const val DEVICE_ID = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        const val ACCOUNT_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    }
}

/** The controller, its collaborators and the scripted client for one test. */
private class Harness(
    private val scope: TestScope,
    private val now: Long,
    val secrets: SecretStore = FakeSecretStore(),
) {
    /**
     * The controller's own scope, cancelled before the database closes. A supervisor like the
     * apps' `@ApplicationScope`: one failed controller coroutine must not kill the rest.
     */
    private val controllerJob = kotlinx.coroutines.SupervisorJob()
    private val controllerScope =
        kotlinx.coroutines.CoroutineScope(scope.testScheduler + controllerJob)

    val db = TestDb.inMemory()
    val clock = TestClock(now)
    val settings = FakeSettingsSyncPort()
    val address = FakeSyncServerAddress(serverUrl = null)
    val flags = SyncFlags()
    val client = ScriptedSyncClient()
    val gate = FakeLocalNetworkGate()
    val classifier = FakeNetErrorClassifier()
    val linkFlow = LinkFlowImpl()
    val tokens = SyncTokenStore(secrets)
    val credentials = FakeCredentialCommitCoordinator()
    val applier =
        SyncApplier(
            db = db,
            clock = clock,
            secrets = secrets,
            settings = settings,
            playback = NoPlaybackSync,
            syncIngestHook = SyncIngestHook.None,
            channelSync =
                object : GroupChannelSync {
                    override suspend fun sync() = Unit

                    override suspend fun onUuidChanged(
                        oldUuid: String,
                        newUuid: String,
                    ) = Unit

                    override suspend fun onDeleted(uuid: String) = Unit
                },
            notices = SyncNoticeBus(),
            deviceNames = DeviceNames { emptyList() },
            sharePasswords = { flags.sharePasswords },
            coordinator = credentials,
        )
    val activity = SyncActivityState()
    val writeLocks = TestSettingsWriteCoordinator()
    val effects =
        SyncApplyEffects(
            db,
            settings,
            writeLocks,
            { address.syncPlaybackSettings() },
            secrets,
            credentials,
            testStoragePaths().cacheDir.toPath(),
        )
    val settingsCapture =
        SettingsCapture(db, settings, writeLocks) {
            address.syncPlaybackSettings()
        }
    val noticeBus = SyncNoticeBus()
    val engine =
        SyncEngine(
            db = db,
            client = client,
            applier = applier,
            clock = clock,
            secrets = secrets,
            sharePasswords = { flags.sharePasswords },
            address = address,
            tokens = tokens,
            activity = activity,
            gate = gate,
            effects = effects,
            capture = settingsCapture,
            notices = noticeBus,
        )
    val libraryMerger =
        ch.lkmc.neutrodyne.core.testing
            .FakeLibraryMerger()
    val merger =
        FirstLinkMerger(
            db = db,
            client = client,
            applier = applier,
            engine = engine,
            libraryMerger = libraryMerger,
            storagePaths = testStoragePaths(),
            effects = effects,
            clock = clock,
            activity = activity,
            secrets = secrets,
            coordinator = credentials,
        )

    /** A temp-dir StoragePaths for the staging file and directory. */
    private fun testStoragePaths(): ch.lkmc.neutrodyne.core.common.StoragePaths {
        val root = kotlin.io.path.createTempDirectory("nd-controller")
        return ch.lkmc.neutrodyne.core.common.StoragePaths(
            ch.lkmc.neutrodyne.core.common.AppDirs(
                data = root.resolve("data"),
                config = root.resolve("config"),
                cache = root.resolve("cache"),
                state = root.resolve("state"),
                logs = root.resolve("logs"),
                downloadsDefault = root.resolve("downloads"),
            ),
        )
    }

    /** Records the scheduler's platform callbacks: link arm and work cancellation. */
    val schedulerCalls = mutableListOf<String>()
    val scheduler =
        SyncScheduler(
            db = db,
            round = { reason -> engine.round(reason) },
            playback = NoPlaybackSync,
            network =
                ch.lkmc.neutrodyne.core.testing
                    .FakeNetworkMonitor(),
            clock = clock,
            scope = controllerScope,
            hooks =
                object : SyncScheduleHooks {
                    override fun interactive(): Boolean = false

                    override fun schedulePush(reason: ch.lkmc.neutrodyne.sync.api.SyncRoundReason) = Unit

                    override suspend fun onLinked() {
                        schedulerCalls += "linked"
                    }

                    override suspend fun onUnlinked() {
                        schedulerCalls += "unlinked"
                    }
                },
        )
    val build =
        BuildInfo(
            versionName = "1.0.0",
            versionCode = 10000,
            debug = false,
            platform = BuildInfo.Platform.ANDROID,
            repoUrl = "https://github.com/L-K-M/Neutrodyne",
            updateManifestUrl = "https://example.invalid/update.json",
            engineManifestUrl = "https://example.invalid/engine.json",
            youTubeEngineBundled = false,
            apkAbi = "arm64-v8a",
            shippedLocales = persistentListOf("en", "de"),
            podcastIndexKey = "",
            podcastIndexSecret = "",
        )
    val controller =
        SyncControllerImpl(
            db = db,
            client = client,
            engine = engine,
            linkFlow = linkFlow,
            tokenStore = tokens,
            clock = clock,
            address = address,
            flags = flags,
            build = build,
            merger = merger,
            effects = effects,
            settingsCapture = settingsCapture,
            gate = gate,
            errors = classifier,
            scheduler = scheduler,
            noticeBus = noticeBus,
            secrets = secrets,
            credentials = credentials,
            io = kotlinx.coroutines.Dispatchers.Default,
            scope = controllerScope,
        )

    /** Runs queued continuations and advances the poll delays by one second. */
    fun tick(ms: Long = 1_000) {
        scope.testScheduler.advanceTimeBy(ms)
        scope.testScheduler.runCurrent()
    }

    /** A controller coroutine is still alive — rounds, link steps or a serialized status read. */
    fun controllerBusy(): Boolean = controllerJob.children.any { it.isActive }

    suspend fun addLocalPodcast() {
        db.withWriteTransaction {
            db
                .podcastDao()
                .insertPodcast(
                    podcastEntity(
                        syncId = SyncControllerImplTest.PODCAST_SYNC_ID,
                        feedUrl = "https://example.com/feed",
                        feedKey = "https://example.com/feed",
                        title = "Example",
                    ),
                )
        }
    }

    suspend fun addGroup() {
        db.withWriteTransaction {
            db
                .groupDao()
                .insert(groupEntity(uuid = SyncControllerImplTest.GROUP_UUID, name = "News", orderKey = "a0"))
        }
    }

    /** Seeds a linked device with a stored token. */
    suspend fun seedLinked() {
        address.updateServerUrl("https://sync.example")
        db.withWriteTransaction {
            db.syncStateDao().upsert(
                SyncStateEntity(
                    enabled = true,
                    serverUrl = "https://sync.example",
                    accountId = SyncControllerImplTest.ACCOUNT_ID,
                    deviceId = SyncControllerImplTest.DEVICE_ID,
                    nodeId = "0000000000000001",
                    linkedAt = now,
                    lastSyncAt = now,
                ),
            )
        }
        tokens.save("sync.example", "nds_test")
    }

    /** Puts one podcast row in the outbox, as an un-pushed local edit would. */
    suspend fun captureLocalPodcast() {
        db.withWriteTransaction {
            db.syncStateDao().withApplying {
                db.syncOutboxDao().captureAll("podcast", SyncControllerImplTest.PODCAST_SYNC_ID)
            }
        }
    }

    /** Journals one durable remote setting effect, as a committed page would leave it. */
    suspend fun journalPendingSetting(
        key: ch.lkmc.neutrodyne.core.model.settings.SettingKey<*>,
        value: String,
    ) {
        val clock =
            ch.lkmc.neutrodyne.sync.protocol.Hlc.of(
                now,
                1,
                ch.lkmc.neutrodyne.sync.protocol
                    .NodeId("0000000000000002"),
            )
        db.withWriteTransaction {
            db.syncClockDao().upsert(
                ch.lkmc.neutrodyne.core.database.SyncClockEntity(
                    "setting",
                    key.name,
                    ch.lkmc.neutrodyne.sync.protocol.SyncJson.encodeToString(
                        RawSyncRecord(
                            state =
                                ch.lkmc.neutrodyne.sync.protocol.RecordState(
                                    coll = ch.lkmc.neutrodyne.sync.protocol.SyncCollection.SETTING,
                                    fields =
                                        mapOf(
                                            "v" to
                                                ch.lkmc.neutrodyne.sync.protocol.FieldState(
                                                    kotlinx.serialization.json.JsonPrimitive(value),
                                                    clock,
                                                ),
                                        ),
                                ),
                            pendingSetting = PendingSetting(value, clock.toString()),
                        ),
                    ),
                ),
            )
        }
    }

    /** The key's pending marker value, or null when no marker survives. */
    suspend fun pendingSettingOf(key: String): String? {
        val row = db.syncClockDao().get("setting", key) ?: return null
        val raw =
            runCatching {
                ch.lkmc.neutrodyne.sync.protocol.SyncJson
                    .decodeFromString<RawSyncRecord>(row.clocks)
            }.getOrNull()
        return raw?.pendingSetting?.value
    }

    suspend fun tokenStoreContains(host: String): Boolean = tokens.load(host) != null

    fun close() {
        controllerJob.cancel()
        // Cancellation is asynchronous: the controller's io blocks run on
        // Dispatchers.Default, and closing the pool while one is still between
        // statements surfaced as "Connection pool is closed" on loaded CI
        // runners. Pump the scheduler until every controller coroutine has
        // unwound, mirroring await(), before the database closes.
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!controllerJob.isCompleted) {
            check(System.nanoTime() < deadline) { "the controller job did not unwind after cancellation" }
            scope.testScheduler.runCurrent()
            Thread.sleep(1)
        }
        db.close()
    }
}

/** A [SyncClient] whose every call is scripted and recorded. */
private class ScriptedSyncClient : SyncClient {
    val calls = mutableListOf<String>()
    val renames = mutableListOf<Pair<String, String>>()
    val revokes = mutableListOf<String>()
    var resetMode: String? = null
    var resetBeforeCursor: String? = null

    var discovery: suspend (String) -> DiscoveryDocument = { defaultDiscovery() }
    var login: suspend (
        String,
        String,
    ) -> TokenResponse = { _, _ -> TokenResponse("nds_test", ACCOUNT_ID, DEVICE_ID, "alice") }
    var invite: suspend (String) -> TokenResponse = { TokenResponse("nds_test", ACCOUNT_ID, DEVICE_ID, "alice") }
    var linkStart: suspend () -> LinkStartResponse = {
        LinkStartResponse("device-code", "WDJB-MJHT", "https://sync.example/link", 600, 5)
    }
    var linkToken: suspend (String) -> TokenResponse = { TokenResponse("nds_test", ACCOUNT_ID, DEVICE_ID, "alice") }
    var approve: suspend (String) -> LinkApproveResponse = {
        LinkApproveResponse("Pixel 8", "android", "1.0.0", REQUESTED_AT, "device", true)
    }
    var confirm: suspend (String, Boolean) -> Unit = { _, _ -> }
    var devicesHandler: suspend () -> List<DeviceDto> = { emptyList() }
    var rename: suspend (String, String) -> DeviceDto = { id, name ->
        DeviceDto(id, name, "android", "1.0.0", null, false, false)
    }
    var revoke: suspend (String) -> Unit = {}
    var logout: suspend () -> Unit = {}
    var summary: AccountSummary = AccountSummary("alice", LibraryCountsDto(1, 0, 0, 0, 0), 1, 0, 0)
    var changesHandler: suspend (String?) -> ChangesPage = { emptyPage() }
    var syncHandler: suspend (String?, List<ChangeDto>) -> SyncResponse = { _, changes -> defaultSyncResponse(changes) }
    var resetHandler: suspend (String, String, String?) -> AccountSummary = { _, _, _ ->
        AccountSummary("alice", LibraryCountsDto(0, 0, 0, 0, 0), 1, 0, 0)
    }

    override suspend fun discover(baseUrl: String): DiscoveryDocument {
        calls += "discover"
        return discovery(baseUrl)
    }

    override suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
        device: DeviceInfoDto,
    ): TokenResponse {
        calls += "login:$username"
        return login(username, password)
    }

    override suspend fun redeemInvite(
        baseUrl: String,
        code: String,
        device: DeviceInfoDto,
    ): TokenResponse {
        calls += "redeem:$code"
        return invite(code)
    }

    override suspend fun linkStart(
        baseUrl: String,
        device: DeviceInfoDto,
    ): LinkStartResponse {
        calls += "linkStart"
        return linkStart()
    }

    override suspend fun linkToken(
        baseUrl: String,
        deviceCode: String,
    ): TokenResponse {
        calls += "linkToken"
        return linkToken(deviceCode)
    }

    override suspend fun linkApprove(
        baseUrl: String,
        token: String,
        userCode: String,
    ): LinkApproveResponse {
        calls += "approve:$userCode"
        return approve(userCode)
    }

    override suspend fun linkConfirm(
        baseUrl: String,
        token: String,
        userCode: String,
        approve: Boolean,
    ) {
        calls += "confirm:$userCode:$approve"
        confirm(userCode, approve)
    }

    override suspend fun logout() {
        calls += "logout"
        logout()
    }

    override suspend fun devices(): List<DeviceDto> {
        calls += "devices"
        return devicesHandler()
    }

    override suspend fun renameDevice(
        deviceId: String,
        name: String,
    ): DeviceDto {
        renames += deviceId to name
        return rename(deviceId, name)
    }

    override suspend fun revokeDevice(deviceId: String) {
        revokes += deviceId
        revoke(deviceId)
    }

    override suspend fun account(): AccountSummary {
        calls += "account"
        return summary
    }

    override suspend fun sync(
        since: String?,
        changes: List<ChangeDto>,
    ): SyncResponse {
        calls += "sync"
        return syncHandler(since, changes)
    }

    override suspend fun changes(
        since: String?,
        limit: Int,
    ): ChangesPage {
        calls += "changes:$since"
        return changesHandler(since)
    }

    override suspend fun reset(
        confirm: String,
        mode: String,
        beforeCursor: String?,
    ): AccountSummary {
        calls += "reset:$mode"
        resetMode = mode
        resetBeforeCursor = beforeCursor
        return resetHandler(confirm, mode, beforeCursor)
    }

    private companion object {
        const val ACCOUNT_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val DEVICE_ID = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        const val REQUESTED_AT = "2026-11-04T10:00:00Z"
    }
}

private class FakeNetErrorClassifier(
    var result: NetError = NetError.Offline,
) : NetErrorClassifier {
    override fun classify(e: Throwable): NetError = result
}

/**
 * A [FakeSecretStore] whose first [get] after [arm] parks on [gate], reporting on [entered]:
 * a controlled mid-read stop that holds whatever snapshot the caller already loaded.
 */
private class GatedSecretStore(
    private val inner: FakeSecretStore = FakeSecretStore(),
) : SecretStore by inner {
    private val armed = kotlinx.coroutines.CompletableDeferred<Unit>()
    val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
    val gate = kotlinx.coroutines.CompletableDeferred<Unit>()

    fun arm() {
        armed.complete(Unit)
    }

    override suspend fun get(origin: String): StoredSecret? {
        // The first parked caller wins `entered`, so only one read ever waits on the gate.
        if (armed.isCompleted && entered.complete(Unit)) {
            gate.await()
        }
        return inner.get(origin)
    }
}

internal fun defaultDiscovery(protocolMax: Int = 1): DiscoveryDocument =
    DiscoveryDocument(
        name = "Home",
        serverVersion = "1.0.0",
        protocol = ProtocolRange(min = 1, max = protocolMax),
        features = listOf("link", "password"),
        maxBatch = 1_000,
        maxSkewMs = 300_000,
        serverTime = "2026-11-04T10:00:00Z",
    )

internal fun emptyPage(): ChangesPage = ChangesPage(emptyList(), "c:00000001:1", false, "")

/** The scripted server's acknowledgement of every pushed change. */
internal fun defaultSyncResponse(changes: List<ChangeDto>): SyncResponse =
    SyncResponse(
        results = changes.mapIndexed { index, _ -> ChangeResultDto(index, "applied") },
        records = emptyList(),
        cursor = "c:00000002:1",
        hasMore = false,
        serverTime = "",
    )

internal fun podcastRecord(
    syncId: String = SyncControllerImplTest.PODCAST_SYNC_ID,
    clock: String = Hlc.of(1_790_000_000_000L, 1, NodeId("0000000000000002")).toString(),
): RecordDto =
    RecordDto(
        coll = "podcast",
        id = JsonPrimitive(syncId),
        seq = 1,
        by = SyncControllerImplTest.DEVICE_ID,
        fields =
            mapOf(
                "feedUrl" to FieldValue(JsonPrimitive("https://other.example/feed"), clock),
                "subscribed" to FieldValue(JsonPrimitive(true), clock),
                "title" to FieldValue(JsonPrimitive("Other"), clock),
            ),
    )

internal fun otherPodcastRecord(): RecordDto = podcastRecord(syncId = SyncControllerImplTest.OTHER_PODCAST_SYNC_ID)

internal fun otherGroupRecord(): RecordDto =
    RecordDto(
        coll = "group",
        id = JsonPrimitive(SyncControllerImplTest.OTHER_GROUP_UUID),
        seq = 2,
        by = SyncControllerImplTest.DEVICE_ID,
        fields =
            mapOf(
                "name" to FieldValue(JsonPrimitive("Other"), null),
                "deleted" to FieldValue(JsonPrimitive(false), null),
            ),
    )
