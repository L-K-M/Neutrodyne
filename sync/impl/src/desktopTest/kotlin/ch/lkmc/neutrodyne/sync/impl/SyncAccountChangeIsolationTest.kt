// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.database.SyncCaptureKind
import ch.lkmc.neutrodyne.core.database.SyncClockEntity
import ch.lkmc.neutrodyne.core.database.SyncOutboxEntity
import ch.lkmc.neutrodyne.sync.api.LinkMethod
import ch.lkmc.neutrodyne.sync.api.LinkState
import ch.lkmc.neutrodyne.sync.api.SyncProblem
import ch.lkmc.neutrodyne.sync.protocol.FieldState
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import ch.lkmc.neutrodyne.sync.protocol.dto.AccountSummary
import ch.lkmc.neutrodyne.sync.protocol.dto.LibraryCountsDto
import ch.lkmc.neutrodyne.sync.protocol.dto.TokenResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Account-change isolation at the authorisation point of [SyncControllerImpl]'s link flow (10
 * Link methods).
 *
 * Authorising a different server or account swaps the sync profile: the old profile's raw
 * `sync_clock` records and pending `sync_outbox` rows belong to the old account and must be
 * dropped, its namespace clock and offset correction reset — otherwise records that only
 * account A ever saw replay into account B (the A-only rows a later 410 reasserts into B's
 * dataset). The visible local library stays for the first-link choice to re-seed under B's
 * fresh identity. A same-account reauth keeps the whole state, and a failure before the
 * authorisation clears nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncAccountChangeIsolationTest {
    private val localClock = Hlc.of(NOW - 300_000, 5, NodeId(NODE_A))
    private val remoteClock = Hlc.of(NOW - 600_000, 9, NodeId(NODE_PEER))

    /**
     * Linking account B over a linked account A drops A's raw records, its outbox and its
     * clock state, keeps the local podcast, and leaves the pending B profile disabled until
     * the first-link choice.
     */
    @Test
    fun aDifferentAccountDropsTheOldProfilesRawState() =
        runTest {
            val h = harness(this)
            try {
                h.seedLinked()
                h.addLocalPodcast()
                seedAccountALeftovers(h)
                h.client.login = { _, _ -> TokenResponse("nds_b", ACCOUNT_B, DEVICE_B_ID, "bob") }
                h.client.summary = AccountSummary("bob", LibraryCountsDto(2, 0, 0, 0, 0), 1, 0, 0)

                h.controller.startLink(serverUrl, LinkMethod.PASSWORD)
                await(h) { h.linkFlow.state.value is LinkState.AwaitingInput }
                h.controller.submitPassword("bob", "fixture".toCharArray())
                await(h) { h.linkFlow.state.value is LinkState.ChooseFirstLink }

                assertEquals(LinkState.ChooseFirstLink(1, 0, 2, 0), h.linkFlow.state.value)
                assertTrue(
                    h.db
                        .syncClockDao()
                        .all()
                        .isEmpty(),
                    "account A's raw records must not carry into account B",
                )
                assertTrue(
                    h.db
                        .syncOutboxDao()
                        .rows()
                        .isEmpty(),
                    "account A's pending pushes must not carry into account B",
                )
                val state = checkNotNull(h.db.syncStateDao().get())
                assertFalse(state.enabled, "the pending profile waits for the first-link choice")
                assertEquals(ACCOUNT_B, state.accountId)
                assertEquals(serverUrl, state.serverUrl)
                assertEquals(0L, state.hlc, "the new profile starts a fresh namespace")
                assertEquals(0L, state.clockOffsetMs, "the new profile keeps no offset correction")
                assertNotEquals(NODE_A, state.nodeId, "the new profile gets a fresh node")
                assertEquals(
                    "Example",
                    h.db
                        .podcastDao()
                        .bySyncId(PODCAST_SYNC_ID)
                        ?.title,
                )
                assertEquals("nds_b", h.tokens.load(HOST))
            } finally {
                h.close()
            }
        }

    /**
     * The same server and account reauthenticating — a new device id, node id and token —
     * keeps the raw records, the outbox and the clock: they are this profile's own accepted
     * history.
     */
    @Test
    fun aSameAccountReauthKeepsTheRawRecordsOutboxAndClock() =
        runTest {
            val h = harness(this)
            try {
                h.seedLinked()
                h.addLocalPodcast()
                seedAccountALeftovers(h)
                val clocksBefore = h.db.syncClockDao().all()
                val outboxBefore = h.db.syncOutboxDao().rows()
                h.client.login = { _, _ -> TokenResponse("nds_a2", ACCOUNT_A, DEVICE_B_ID, "alice") }
                h.client.summary = AccountSummary("alice", LibraryCountsDto(1, 0, 0, 0, 0), 1, 0, 0)

                h.controller.startLink(serverUrl, LinkMethod.PASSWORD)
                await(h) { h.linkFlow.state.value is LinkState.AwaitingInput }
                h.controller.submitPassword("alice", "fixture".toCharArray())
                await(h) { h.linkFlow.state.value is LinkState.ChooseFirstLink }

                assertEquals(LinkState.ChooseFirstLink(1, 0, 1, 0), h.linkFlow.state.value)
                assertEquals(
                    clocksBefore.toSet(),
                    h.db
                        .syncClockDao()
                        .all()
                        .toSet(),
                )
                assertEquals(outboxBefore, h.db.syncOutboxDao().rows())
                val state = checkNotNull(h.db.syncStateDao().get())
                assertFalse(state.enabled, "the reauth still waits for the first-link choice")
                assertEquals(ACCOUNT_A, state.accountId)
                assertEquals(seededHlc, state.hlc, "accepted history survives a same-profile reauth")
                assertEquals(OFFSET_MS, state.clockOffsetMs)
                assertNotEquals(NODE_A, state.nodeId, "even a fresh node keeps the profile's state")
                assertEquals("nds_a2", h.tokens.load(HOST))
            } finally {
                h.close()
            }
        }

    /**
     * A wrong password fails before the authorisation: the account A link, its raw state,
     * outbox, clock and library are all untouched.
     */
    @Test
    fun aFailedPasswordKeepsTheOldProfilesRawState() =
        runTest {
            val h = harness(this)
            try {
                h.seedLinked()
                h.addLocalPodcast()
                seedAccountALeftovers(h)
                val clocksBefore = h.db.syncClockDao().all()
                val outboxBefore = h.db.syncOutboxDao().rows()
                h.client.login = { _, _ -> throw SyncTransportException(401, "unauthorized", "") }

                h.controller.startLink(serverUrl, LinkMethod.PASSWORD)
                await(h) { h.linkFlow.state.value is LinkState.AwaitingInput }
                h.controller.submitPassword("alice", "wrong".toCharArray())
                await(h) { h.linkFlow.state.value is LinkState.Failed }

                assertEquals(
                    SyncProblem.WrongPassword,
                    (h.linkFlow.state.value as LinkState.Failed).error,
                )
                assertEquals(
                    clocksBefore.toSet(),
                    h.db
                        .syncClockDao()
                        .all()
                        .toSet(),
                )
                assertEquals(outboxBefore, h.db.syncOutboxDao().rows())
                val state = checkNotNull(h.db.syncStateDao().get())
                assertTrue(state.enabled, "the account A link survives a failed reauth")
                assertEquals(ACCOUNT_A, state.accountId)
                assertEquals(NODE_A, state.nodeId)
                assertEquals(seededHlc, state.hlc)
                assertEquals(OFFSET_MS, state.clockOffsetMs)
                assertNotNull(h.db.podcastDao().bySyncId(PODCAST_SYNC_ID))
                assertEquals("nds_test", h.tokens.load(HOST))
            } finally {
                h.close()
            }
        }

    /**
     * What a session on account A leaves behind: its namespace clock and offset correction,
     * raw records the local library has no rows for (a remote episode, a remote setting),
     * the local podcast's accepted clocks, and two un-pushed outbox rows carrying literal
     * payloads — one replayed remote value, one local edit. The local podcast's own `*`
     * capture row is the real one the insert trigger wrote while the link was enabled.
     */
    private suspend fun seedAccountALeftovers(h: Harness) {
        h.db.withWriteTransaction {
            val state = checkNotNull(h.db.syncStateDao().get())
            h.db.syncStateDao().upsert(state.copy(hlc = seededHlc, clockOffsetMs = OFFSET_MS))
            h.db.syncClockDao().upsert(SyncClockEntity("podcast", PODCAST_SYNC_ID, rawPodcast()))
            h.db.syncClockDao().upsert(SyncClockEntity("episode", EPISODE_RID, rawEpisode()))
            h.db.syncClockDao().upsert(SyncClockEntity("setting", SETTING_KEY, rawSetting()))
            h.db.syncOutboxDao().upsert(
                SyncOutboxEntity(
                    coll = "episode",
                    rid = EPISODE_RID,
                    field = "played",
                    hlc = remoteClock.packed,
                    nodeId = NODE_PEER,
                    captureKind = SyncCaptureKind.REPLAY,
                    value = "{\"played\":true,\"playedAt\":1799930000000}",
                ),
            )
            h.db.syncOutboxDao().upsert(
                SyncOutboxEntity(
                    coll = "setting",
                    rid = SETTING_KEY,
                    field = "v",
                    hlc = localClock.packed,
                    nodeId = NODE_A,
                    captureKind = SyncCaptureKind.LOCAL,
                    value = "1.5",
                ),
            )
        }
    }

    /** A `sync_clock.clocks` envelope as the applier writes it: field clocks in wire form. */
    private fun rawPodcast(): String =
        SyncJson.encodeToString(
            RawSyncRecord(
                RecordState(
                    coll = SyncCollection.PODCAST,
                    fields =
                        mapOf(
                            "feedUrl" to FieldState(JsonPrimitive("https://example.com/feed"), localClock),
                            "title" to FieldState(JsonPrimitive("Example"), localClock),
                            "subscribed" to FieldState(JsonPrimitive(true), localClock),
                        ),
                ),
            ),
        )

    private fun rawEpisode(): String =
        SyncJson.encodeToString(
            RawSyncRecord(
                RecordState(
                    coll = SyncCollection.EPISODE,
                    fields =
                        mapOf(
                            "played" to FieldState(JsonPrimitive(true), remoteClock),
                            "pos" to FieldState(JsonPrimitive(310_000), remoteClock),
                        ),
                ),
            ),
        )

    private fun rawSetting(): String =
        SyncJson.encodeToString(
            RawSyncRecord(
                RecordState(
                    coll = SyncCollection.SETTING,
                    fields = mapOf("v" to FieldState(JsonPrimitive(1.5), remoteClock)),
                ),
            ),
        )

    private fun harness(scope: TestScope): Harness = Harness(scope, NOW)

    /**
     * Waits for [condition], letting the flow's coroutines run and advancing the poll delays:
     * the same cooperative pump as [SyncControllerImplTest]'s await — the database executes
     * on a real dispatcher, so virtual time alone would never complete it.
     */
    private fun await(
        h: Harness,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) {
                "timed out waiting for the link flow: ${h.linkFlow.state.value}"
            }
            h.tick()
            Thread.sleep(2)
        }
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val HOST = "sync.example"
        const val ACCOUNT_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val ACCOUNT_B = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
        const val PODCAST_SYNC_ID = "00000000-0000-4000-8000-00000000000a"
        const val REMOTE_PODCAST_ID = "00000000-0000-4000-8000-00000000000d"
        const val EPISODE_RID = REMOTE_PODCAST_ID + "guid:a-only-episode"
        const val SETTING_KEY = "playback.speed"
        const val NODE_A = "0000000000000001"
        const val NODE_PEER = "0000000000000002"
        const val DEVICE_B_ID = "ffffffff-ffff-4fff-8fff-ffffffffffff"
        const val OFFSET_MS = 250L
        val seededHlc = Hlc.of(NOW - 120_000, 4, NodeId(NODE_A)).packed
        val serverUrl = "https://$HOST"
    }
}
