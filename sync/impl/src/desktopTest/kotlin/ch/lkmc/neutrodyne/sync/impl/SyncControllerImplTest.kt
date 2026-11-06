// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import ch.lkmc.neutrodyne.core.domain.LocalNetworkLocation
import ch.lkmc.neutrodyne.core.domain.NoPlaybackSync
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.NetError
import ch.lkmc.neutrodyne.core.network.NetErrorClassifier
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
    private val remoteNode = NodeId("0000000000000002")
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
                h.controller.confirmInsecure()
                await(h) { h.linkFlow.state.value is LinkState.AwaitingInput }
                assertTrue(h.client.calls.contains("discover"))
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
                // 10 First-link choices: the second confirmation follows the staging pull and
                // names the removals the other devices will see — both server records are
                // lacking locally. Nothing merged or pushed before "Replace everywhere".
                await(h) { h.linkFlow.state.value is LinkState.ConfirmReplaceEverywhere }
                val confirmation = h.linkFlow.state.value as LinkState.ConfirmReplaceEverywhere
                assertEquals(1, confirmation.removedPodcasts)
                assertEquals(1, confirmation.removedGroups)
                assertNull(
                    h.db
                        .syncStateDao()
                        .get()
                        ?.linkedAt,
                )
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

    /** Cancelling the second confirmation aborts the link; nothing merged or pruned. */
    @Test
    fun cancellingTheReplaceEverywhereConfirmationAbortsTheLink() =
        runTest {
            val h = harness(this)
            try {
                h.addLocalPodcast()
                h.client.summary = AccountSummary("alice", LibraryCountsDto(1, 0, 0, 0, 0), 1, 0, 0)
                h.client.changesHandler = { since ->
                    if (since == null) {
                        ChangesPage(listOf(otherPodcastRecord()), "c:00000001:1", false, "")
                    } else {
                        emptyPage()
                    }
                }
                h.controller.startLink(serverUrl, LinkMethod.CODE)
                await(h) { h.linkFlow.state.value is LinkState.ChooseFirstLink }
                h.controller.chooseFirstLink(FirstLinkChoice.USE_DEVICE)
                await(h) { h.linkFlow.state.value is LinkState.ConfirmReplaceEverywhere }
                h.linkFlow.cancel()
                assertEquals(LinkState.Address, h.linkFlow.state.value)
                assertNull(
                    h.db
                        .syncStateDao()
                        .get()
                        ?.linkedAt,
                )
                assertTrue(h.client.calls.none { it == "reset" }, "calls=${h.client.calls}")
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
    fun listingDevicesMarksThisDevice() =
        runTest {
            val h = harness(this)
            try {
                h.seedLinked()
                h.client.devicesList =
                    listOf(
                        DeviceDto(DEVICE_ID, "This", "android", "1.0.0", null, current = true, stale = false),
                        DeviceDto("dev-2", "Other", "linux", "1.0.0", null, current = false, stale = true),
                    )
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
    fun shareFeedPasswordsWritesTheSettingAndTheCache() =
        runTest {
            val h = harness(this)
            try {
                h.controller.setShareFeedPasswords(true)
                assertEquals(listOf(true), h.address.writtenShareFeedPasswords)
                assertTrue(h.flags.sharePasswords)
            } finally {
                h.close()
            }
        }

    @Test
    fun turningShareOnCapturesOneAuthRowPerCredentialedPodcastAndOffStopsIt() =
        runTest {
            val h = harness(this)
            try {
                h.seedLinked()
                h.addLocalPodcast()
                h.linkCredential()

                h.controller.setShareFeedPasswords(true)
                val authRows =
                    h.db
                        .syncOutboxDao()
                        .rows()
                        .filter { it.field == "auth" }
                assertEquals(listOf(PODCAST_SYNC_ID), authRows.map { it.rid })
                assertTrue(h.controller.shareFeedPasswords.value)

                // Turning it off pushes nothing: the pending rows cannot leave while off.
                h.controller.setShareFeedPasswords(false)
                assertTrue(
                    h.db
                        .syncOutboxDao()
                        .rows()
                        .none { it.field == "auth" },
                )
            } finally {
                h.close()
            }
        }

    @Test
    fun unlinkRemovesTheAccountsProtectedSnapshots() =
        runTest {
            val h = harness(this)
            try {
                h.seedLinked()
                h.secrets.put(
                    pendingAuthOrigin(ACCOUNT_ID, PODCAST_SYNC_ID, "ffffffffffffffff-0000000000000001"),
                    "anna",
                    "hunter2".toCharArray(),
                )
                assertTrue(h.controller.unlink() is Outcome.Success)

                assertEquals(emptyList(), h.secrets.originsWithPrefix(PENDING_ORIGIN_PREFIX))
            } finally {
                h.close()
            }
        }

    @Test
    fun aRoundDrainsAPulledCredentialIntoTheStore() =
        runTest {
            val h = harness(this)
            try {
                h.seedLinked()
                h.addLocalPodcast()
                val clock = Hlc.of(now, 5, remoteNode).toString()
                h.client.syncHandler = { _, changes ->
                    defaultSyncResponse(changes).copy(
                        records =
                            listOf(
                                RecordDto(
                                    coll = "podcast",
                                    id = JsonPrimitive(PODCAST_SYNC_ID),
                                    seq = 1,
                                    by = DEVICE_ID,
                                    fields =
                                        mapOf(
                                            "needsCredentials" to FieldValue(JsonPrimitive(true), clock),
                                            "credentialOrigin" to
                                                FieldValue(JsonPrimitive("https://example.com/feed"), clock),
                                            "auth" to
                                                FieldValue(
                                                    buildJsonObject {
                                                        put("u", "anna")
                                                        put("p", "hunter2")
                                                    },
                                                    clock,
                                                ),
                                        ),
                                ),
                            ),
                        cursor = "c:00000001:2",
                    )
                }

                h.engine.round(ch.lkmc.neutrodyne.sync.api.SyncRoundReason.SYNC_NOW)

                // The page's drain installed the credential; no outbox row, no notice.
                val podcast = h.db.podcastDao().bySyncId(PODCAST_SYNC_ID)
                assertNotNull(podcast?.credentialId)
                assertEquals(
                    "hunter2",
                    h.secrets
                        .get("https://example.com/feed")
                        ?.secret
                        ?.concatToString(),
                )
                assertEquals(0, h.db.syncOutboxDao().count())
            } finally {
                h.close()
            }
        }

    @Test
    fun syncPlaybackSettingsWritesTheSettingAndTheCache() =
        runTest {
            val h = harness(this)
            try {
                h.controller.setSyncPlaybackSettings(false)
                assertEquals(listOf(false), h.address.writtenSyncPlaybackSettings)
                assertFalse(h.flags.syncPlaybackSettings)
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

    /**
     * 05 Restore while linked: a token without `linkedAt` is `LinkUnfinished`, but a pending
     * ALL_DEVICES Replace session turns the status into `FinishingRestore` — the restore, not
     * the first-link choices, is the resume point. A THIS_DEVICE session changes nothing.
     */
    @Test
    fun aPendingAllDevicesRestoreShowsFinishingRestore() =
        runTest {
            val h = harness(this)
            try {
                h.address.updateServerUrl(serverUrl)
                h.db.withWriteTransaction {
                    h.db.syncStateDao().upsert(
                        ch.lkmc.neutrodyne.core.database.SyncStateEntity(
                            serverUrl = serverUrl,
                            accountId = ACCOUNT_ID,
                            deviceId = DEVICE_ID,
                            nodeId = "0000000000000001",
                        ),
                    )
                }
                h.controller.refreshStatus()
                assertTrue(h.controller.status.value is SyncStatus.LinkUnfinished)

                h.seedRestoreSession(reach = "ALL_DEVICES")
                h.controller.refreshStatus()
                val finishing = h.controller.status.value as SyncStatus.FinishingRestore
                assertEquals(serverUrl, finishing.serverUrl)

                h.db.withWriteTransaction {
                    val dao = h.db.importDao()
                    dao.backupPreviewSessions().forEach { dao.deleteSession(it.id) }
                }
                h.seedRestoreSession(reach = "THIS_DEVICE")
                h.controller.refreshStatus()
                assertTrue(h.controller.status.value is SyncStatus.LinkUnfinished, "${h.controller.status.value}")
            } finally {
                h.close()
            }
        }

    private fun harness(scope: TestScope): Harness = Harness(scope, now)

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
) {
    /** The controller's own scope, cancelled before the database closes. */
    private val controllerJob = kotlinx.coroutines.Job()
    private val controllerScope =
        kotlinx.coroutines.CoroutineScope(scope.testScheduler + controllerJob)

    val db = TestDb.inMemory()
    val clock = TestClock(now)
    val secrets =
        FakeSecretStore(
            rowWriter = { origin, username ->
                db
                    .credentialDao()
                    .putByOrigin(
                        ch.lkmc.neutrodyne.core.database.CredentialEntity(
                            origin = origin,
                            username = username,
                            secretCipher = null,
                            iv = null,
                            createdAt = 1,
                        ),
                    )
            },
        )
    val settings = FakeSettingsSyncPort()
    val address = FakeSyncServerAddress(serverUrl = null)
    val flags = SyncFlags()
    val client = ScriptedSyncClient()
    val gate = FakeLocalNetworkGate()
    val classifier = FakeNetErrorClassifier()
    val linkFlow = LinkFlowImpl()
    val tokens = SyncTokenStore(secrets)
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
            sharePasswords = { flags.sharePasswords },
        )
    val effects =
        SyncApplyEffects(
            db = db,
            settings = settings,
            secrets = secrets,
            coordinator =
                ch.lkmc.neutrodyne.core.testing
                    .FakeCredentialCommitCoordinator(),
        )
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
            effects = effects,
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
            settings = FakeSettingsSyncPort(),
            secrets = secrets,
            sharePasswords = { flags.sharePasswords },
            coordinator =
                ch.lkmc.neutrodyne.core.testing
                    .FakeCredentialCommitCoordinator(),
            effects = effects,
            clock = clock,
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
            secrets = secrets,
            clock = clock,
            address = address,
            flags = flags,
            settingsCapture = SettingsCapture(db, settings),
            build = build,
            merger = merger,
            gate = gate,
            errors = classifier,
            scheduler = scheduler,
            noticeBus = noticeBus,
            io = kotlinx.coroutines.Dispatchers.Default,
            scope = controllerScope,
        )

    /** Runs queued continuations and advances the poll delays by one second. */
    fun tick(ms: Long = 1_000) {
        scope.testScheduler.advanceTimeBy(ms)
        scope.testScheduler.runCurrent()
    }

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

    /** A `PREVIEW` backup session whose options request a Replace with [reach]. */
    suspend fun seedRestoreSession(reach: String) {
        db.withWriteTransaction {
            db
                .importDao()
                .insertSession(
                    ch.lkmc.neutrodyne.core.database.ImportSessionEntity(
                        createdAt = now,
                        sourceFormat = ch.lkmc.neutrodyne.core.model.ImportFormat.NEUTRODYNE_BACKUP,
                        state = ch.lkmc.neutrodyne.core.model.ImportState.PREVIEW,
                        payloadPath = "import/1.bin",
                        optionsJson =
                            """{"restoreMode":"REPLACE","restoreRequestedAt":$now,"linkedReach":"$reach"}""",
                    ),
                )
        }
    }

    /** Puts one podcast row in the outbox, as an un-pushed local edit would. */
    suspend fun captureLocalPodcast() {
        db.withWriteTransaction {
            db.syncStateDao().withApplying {
                db.syncOutboxDao().captureAll("podcast", SyncControllerImplTest.PODCAST_SYNC_ID)
            }
        }
    }

    suspend fun tokenStoreContains(host: String): Boolean = tokens.load(host) != null

    /** Links the local podcast to a credential row, with triggers silenced. */
    suspend fun linkCredential() {
        db.withWriteTransaction {
            db.syncStateDao().withApplying {
                val id =
                    db
                        .credentialDao()
                        .putByOrigin(
                            ch.lkmc.neutrodyne.core.database.CredentialEntity(
                                origin = "https://example.com/feed",
                                username = "anna",
                                secretCipher = null,
                                iv = null,
                                createdAt = 1,
                            ),
                        )
                db
                    .podcastDao()
                    .allForExport()
                    .single()
                    .let { db.podcastDao().setCredentialId(it.id, id) }
            }
        }
    }

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
    var devicesList: List<DeviceDto> = emptyList()
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
        return devicesList
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
