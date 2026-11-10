// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.database.CredentialEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncOutboxEntity
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import ch.lkmc.neutrodyne.core.domain.NoPlaybackSync
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.testing.FakeCredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.testing.FakeLocalNetworkGate
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsSyncPort
import ch.lkmc.neutrodyne.core.testing.FakeSyncServerAddress
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.sync.api.SyncRoundReason
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import ch.lkmc.neutrodyne.sync.protocol.dto.AccountSummary
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeDto
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeResultDto
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangesPage
import ch.lkmc.neutrodyne.sync.protocol.dto.DeviceDto
import ch.lkmc.neutrodyne.sync.protocol.dto.DeviceInfoDto
import ch.lkmc.neutrodyne.sync.protocol.dto.DiscoveryDocument
import ch.lkmc.neutrodyne.sync.protocol.dto.FieldValue
import ch.lkmc.neutrodyne.sync.protocol.dto.InviteRedeemRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.LinkApproveResponse
import ch.lkmc.neutrodyne.sync.protocol.dto.LinkStartResponse
import ch.lkmc.neutrodyne.sync.protocol.dto.LinkTokenRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.LoginRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.RecordDto
import ch.lkmc.neutrodyne.sync.protocol.dto.SyncResponse
import ch.lkmc.neutrodyne.sync.protocol.dto.TokenResponse
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 10 Durable apply effects, the round half: ordinary response pages and later `hasMore` pages
 * drain their committed `pendingSetting` markers through the engine, a marker left by an
 * earlier round retries before the round's first request (offline too), a failed apply stays
 * pending for the next round, and the persisted switch gates every drain. Both platforms run
 * this base against a real Room database.
 */
abstract class SyncEngineEffectsDrainTestBase {
    private val now = 1_800_000_000_000L
    private val remote = NodeId("0000000000000002")
    private val deviceId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"

    /** One linked device over a scripted client; the gate mirrors the graph's live switch read. */
    private class DrainHarness(
        val db: NeutrodyneDatabase,
        val port: FakeSettingsSyncPort,
        val address: FakeSyncServerAddress,
        val flags: SyncFlags,
        val secrets: FakeSecretStore,
        val engine: SyncEngine,
        val effects: SyncApplyEffects,
        val capture: SettingsCapture,
    )

    /** The inert settler for the drain tests: `NoPlaybackSync` keeps its settle a no-op. */
    private fun deferredSettler(
        db: NeutrodyneDatabase,
        h: DrainHarness,
    ): DeferredRemovalSettler =
        DeferredRemovalSettler(db, NoPlaybackSync, TestClock(now), h.address, SyncTokenStore(h.secrets))

    private fun harness(
        db: NeutrodyneDatabase,
        client: SyncClient,
        port: FakeSettingsSyncPort = FakeSettingsSyncPort(),
        switch: Boolean = true,
    ): DrainHarness {
        val address = FakeSyncServerAddress(serverUrl = "https://sync.example")
        address.updateSyncPlaybackSettings(switch)
        val flags = SyncFlags()
        val clock = TestClock(now)
        val writeLocks = TestSettingsWriteCoordinator()
        val secrets = FakeSecretStore()
        val credentials = FakeCredentialCommitCoordinator()
        val effects =
            SyncApplyEffects(
                db,
                port,
                writeLocks,
                { address.syncPlaybackSettings() },
                secrets,
                credentials,
                "__absent_cache__".toPath(),
            )
        val capture =
            SettingsCapture(db, port, writeLocks) {
                address.syncPlaybackSettings()
            }
        val engine =
            SyncEngine(
                db = db,
                client = client,
                applier = applier(db, port, clock, secrets, credentials),
                playback = NoPlaybackSync,
                clock = clock,
                secrets = secrets,
                sharePasswords = { flags.sharePasswords },
                address = address,
                tokens =
                    SyncTokenStore(FakeSecretStore()).apply {
                        runBlocking { save(hostOf("https://sync.example"), "token") }
                    },
                activity = SyncActivityState(),
                gate = FakeLocalNetworkGate(),
                effects = effects,
                capture = capture,
                notices = SyncNoticeBus(),
                refreshPreferences = { flags.refreshFromStore(address) },
            )
        return DrainHarness(db, port, address, flags, secrets, engine, effects, capture)
    }

    @Test
    fun anOrdinaryResponsePageAppliesAndAcksItsSetting() =
        runTest {
            val db = newTestDb(testScheduler)
            try {
                seedLinked(db)
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val client =
                    scriptedClient(syncRecords = listOf(settingRecord(key, 15_000L)))
                val h = harness(db, client)

                h.engine.round(SyncRoundReason.SYNC_NOW)

                assertEquals(1, h.port.applied.size)
                assertEquals(
                    key,
                    h.port.applied
                        .single()
                        .first,
                )
                assertEquals(
                    "15000",
                    h.port.applied
                        .single()
                        .second,
                )
                assertNull(pendingSetting(db, key.name))
            } finally {
                db.close()
            }
        }

    @Test
    fun aLaterHasMorePageAppliesAndAcksItsSetting() =
        runTest {
            val db = newTestDb(testScheduler)
            try {
                seedLinked(db)
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val client =
                    scriptedClient(
                        syncRecords = emptyList(),
                        syncHasMore = true,
                        pageRecords = listOf(settingRecord(key, 15_000L)),
                    )
                val h = harness(db, client)

                h.engine.round(SyncRoundReason.SYNC_NOW)

                assertEquals(1, h.port.applied.size)
                assertEquals(
                    key,
                    h.port.applied
                        .single()
                        .first,
                )
                assertEquals(
                    "15000",
                    h.port.applied
                        .single()
                        .second,
                )
                assertNull(pendingSetting(db, key.name))
            } finally {
                db.close()
            }
        }

    /**
     * A marker committed by an earlier round retries before this round's first request: the
     * drain is local, so the value reaches DataStore even though the server is unreachable
     * and the round itself fails.
     */
    @Test
    fun aPriorMarkerRetriesBeforeThePushEvenWhileOffline() =
        runTest {
            val db = newTestDb(testScheduler)
            try {
                seedLinked(db)
                val port = FakeSettingsSyncPort()
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                applier(db, port, TestClock(now)).applyPage(
                    listOf(settingRecord(key, 15_000L)),
                    roundId = now,
                )
                assertEquals("15000", pendingSetting(db, key.name))
                val client =
                    scriptedClient(
                        syncRecords = emptyList(),
                        syncFailure = { java.io.IOException("sync.example unreachable") },
                    )
                val h = harness(db, client, port)

                val round = runCatching { h.engine.round(SyncRoundReason.SYNC_NOW) }

                assertTrue(round.isFailure, "the offline round must still fail honestly")
                assertEquals(1, h.port.applied.size)
                assertEquals(
                    key,
                    h.port.applied
                        .single()
                        .first,
                )
                assertEquals(
                    "15000",
                    h.port.applied
                        .single()
                        .second,
                )
                assertNull(pendingSetting(db, key.name))
            } finally {
                db.close()
            }
        }

    /** A failed DataStore apply keeps the marker; the next round's pre-push drain recovers it. */
    @Test
    fun aFailedApplyStaysPendingAndANextRoundRecovers() =
        runTest {
            val db = newTestDb(testScheduler)
            try {
                seedLinked(db)
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val failing =
                    scriptedClient(syncRecords = listOf(settingRecord(key, 15_000L)))
                val failingPort = FakeSettingsSyncPort().apply { remoteResult = false }
                val failingHarness = harness(db, failing, failingPort)

                failingHarness.engine.round(SyncRoundReason.SYNC_NOW)

                assertTrue(
                    failingPort.applied.isNotEmpty(),
                    "the failed apply must have been attempted",
                )
                assertEquals("15000", pendingSetting(db, key.name))

                failingPort.remoteResult = true
                val idle = scriptedClient(syncRecords = emptyList())
                val h = harness(db, idle, failingPort)

                h.engine.round(SyncRoundReason.SYNC_NOW)

                assertEquals(2, h.port.applied.size)
                assertNull(pendingSetting(db, key.name))
            } finally {
                db.close()
            }
        }

    /**
     * 01 Sync write ordering through the round: a strictly newer local intent beats the pending
     * remote marker — the pre-push drain clears the superseded marker but never applies over
     * the intent, and an unreachable server keeps the intent un-pushed for the next round.
     */
    @Test
    fun aStrictlyNewerLocalIntentWinsOverTheRoundDrain() =
        runTest {
            val db = newTestDb(testScheduler)
            try {
                seedLinked(db)
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val remoteClock = Hlc.of(1_577_836_800_000L, 1, remote)
                applier(db, FakeSettingsSyncPort(), TestClock(now)).applyPage(
                    listOf(settingRecord(key, 15_000L, remoteClock)),
                    roundId = now,
                )
                val localClock = Hlc.of(1_577_836_800_001L, 0, remote)
                seedLocalIntent(db, key, "20000", localClock)
                val client =
                    scriptedClient(
                        syncRecords = emptyList(),
                        syncFailure = { java.io.IOException("sync.example unreachable") },
                    )
                val h = harness(db, client)

                val round = runCatching { h.engine.round(SyncRoundReason.SYNC_NOW) }

                assertTrue(round.isFailure, "the offline round must still fail honestly")
                assertTrue(h.port.applied.isEmpty(), "the newer local intent must win")
                assertNull(pendingSetting(db, key.name), "the superseded marker must be cleared")
                val local = db.syncOutboxDao().get(SyncCollection.SETTING.wire, key.name, "v")
                assertTrue(local != null && local.value == "20000", "the local intent survives the drain")
                assertTrue(
                    local != null && Hlc(local.hlc, NodeId(local.nodeId)) == localClock,
                    "the surviving intent keeps its own complete clock",
                )
            } finally {
                db.close()
            }
        }

    /**
     * 10 Pull and apply step 3 at the round level: while the persisted switch is off the
     * page's marker stays pending, and once enabled a later round's drain applies it.
     */
    @Test
    fun aRoundWithTheSwitchOffLeavesTheMarkerPendingUntilEnabled() =
        runTest {
            val db = newTestDb(testScheduler)
            try {
                seedLinked(db)
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val client =
                    scriptedClient(syncRecords = listOf(settingRecord(key, 15_000L)))
                val h = harness(db, client, switch = false)

                h.engine.round(SyncRoundReason.SYNC_NOW)

                assertTrue(h.port.applied.isEmpty(), "the disabled switch must leave the marker pending")
                assertEquals("15000", pendingSetting(db, key.name))

                h.address.updateSyncPlaybackSettings(true)
                val idle = scriptedClient(syncRecords = emptyList())
                val enabled = harness(db, idle, h.port, switch = true)

                enabled.engine.round(SyncRoundReason.SYNC_NOW)

                assertEquals(1, enabled.port.applied.size)
                assertEquals(
                    key,
                    enabled.port.applied
                        .single()
                        .first,
                )
                assertNull(pendingSetting(db, key.name))
            } finally {
                db.close()
            }
        }

    /** The band-160 start initializer drains while linked, before any scheduling arm runs. */
    @Test
    fun theStartInitializerDrainsWhileLinked() =
        runTest {
            val db = newTestDb(testScheduler)
            try {
                seedLinked(db)
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val port = FakeSettingsSyncPort()
                applier(db, port, TestClock(now)).applyPage(
                    listOf(settingRecord(key, 15_000L)),
                    roundId = now,
                )
                val h = harness(db, scriptedClient(syncRecords = emptyList()), port)
                val initializer =
                    SyncStartupDrainInitializer(
                        h.flags,
                        dev.zacsweers.metro.Provider { h.address },
                        dev.zacsweers.metro.Provider { h.effects },
                        dev.zacsweers.metro.Provider { h.capture },
                        dev.zacsweers.metro.Provider { deferredSettler(db, h) },
                        backgroundScope,
                    )

                assertEquals(true, initializer.order in 101..199, "the drain belongs to the data band")
                initializer.run()

                assertEquals(1, h.port.applied.size)
                assertEquals(
                    key,
                    h.port.applied
                        .single()
                        .first,
                )
                assertEquals(
                    "15000",
                    h.port.applied
                        .single()
                        .second,
                )
                assertNull(pendingSetting(db, key.name))
            } finally {
                db.close()
            }
        }

    /**
     * The band-160 start initializer re-primes the volatile share gate before the drain: the
     * persisted `shareFeedPasswords` — not the restart default — is what the drain's live
     * switch read and the band-191 parked recovery observe (10 Settings capture). Local only;
     * the scripted client never runs.
     */
    @Test
    fun theStartInitializerHydratesTheShareGateBeforeTheDrain() =
        runTest {
            val db = newTestDb(testScheduler)
            try {
                seedLinked(db)
                val h = harness(db, scriptedClient(syncRecords = emptyList()))
                h.address.updateShareFeedPasswords(true)
                // The drain's live switch read, observed at the moment the gate is consulted.
                val gateReads = mutableListOf<Boolean>()
                val probe =
                    SyncApplyEffects(
                        db,
                        h.port,
                        TestSettingsWriteCoordinator(),
                        {
                            gateReads += h.flags.sharePasswords
                            h.address.syncPlaybackSettings()
                        },
                        h.secrets,
                        FakeCredentialCommitCoordinator(),
                        "__absent_cache__".toPath(),
                    )
                val initializer =
                    SyncStartupDrainInitializer(
                        h.flags,
                        dev.zacsweers.metro.Provider { h.address },
                        dev.zacsweers.metro.Provider { probe },
                        dev.zacsweers.metro.Provider { h.capture },
                        dev.zacsweers.metro.Provider { deferredSettler(db, h) },
                        backgroundScope,
                    )

                assertFalse(h.flags.sharePasswords, "a restart left the cache at its default")
                initializer.run()

                assertTrue(h.flags.sharePasswords, "the persisted switch primed the cache")
                assertEquals(listOf(true), gateReads, "the drain must see the persisted gate")
            } finally {
                db.close()
            }
        }

    /**
     * The unconfigured path keeps R7.1 laziness: the portable address is consulted first, and
     * without a server no switch is read, no flag is primed and nothing heavier resolves.
     */
    @Test
    fun theStartInitializerReadsNoSwitchesWhileUnconfigured() =
        runTest {
            val db = newTestDb(testScheduler)
            try {
                val h = harness(db, scriptedClient(syncRecords = emptyList()))
                h.address.updateServerUrl(null)
                var shareReads = 0
                val unconfigured =
                    object : ch.lkmc.neutrodyne.core.domain.SyncServerAddress by h.address {
                        override suspend fun shareFeedPasswords(): Boolean {
                            shareReads++
                            return true
                        }
                    }
                val initializer =
                    SyncStartupDrainInitializer(
                        h.flags,
                        dev.zacsweers.metro.Provider { unconfigured },
                        dev.zacsweers.metro.Provider { h.effects },
                        dev.zacsweers.metro.Provider { h.capture },
                        dev.zacsweers.metro.Provider { deferredSettler(db, h) },
                        backgroundScope,
                    )

                initializer.run()

                assertFalse(h.flags.sharePasswords, "an unconfigured start primes nothing")
                assertEquals(0, shareReads, "the portable-address guard precedes every switch read")
            } finally {
                db.close()
            }
        }

    /**
     * The first round after a restart re-primes the share gate before the push builds its
     * changes: the persisted consent — not the restart default — lets the local credential
     * ride with the feed (10 Passwords). No real network; the scripted client captures the
     * DTOs.
     */
    @Test
    fun theFirstRoundPushesAuthUnderTheStoredShareGate() =
        runTest {
            val db = newTestDb(testScheduler)
            try {
                seedLinked(db)
                val sent = mutableListOf<ChangeDto>()
                val client =
                    scriptedClient(syncRecords = emptyList(), onSync = { sent += it })
                val h = harness(db, client)
                seedCredentialIntent(db, h.secrets)
                h.address.updateShareFeedPasswords(true)

                assertFalse(h.flags.sharePasswords, "a restart left the cache at its default")
                h.engine.round(SyncRoundReason.SYNC_NOW)

                val pushed = sent.single { it.coll == SyncCollection.PODCAST.wire }
                assertEquals(
                    JsonObject(
                        mapOf(
                            "u" to JsonPrimitive(AUTH_USER),
                            "p" to JsonPrimitive(AUTH_PASS),
                        ),
                    ),
                    pushed.fields
                        .getValue("auth")
                        .v,
                    "the stored consent must reach the first round's push",
                )
                assertTrue(h.flags.sharePasswords, "the round hydrated the cache")
            } finally {
                db.close()
            }
        }

    /** The consent's companion direction: a persisted `false` still withholds `auth`. */
    @Test
    fun aStoredOffShareGateStillExportsNoAuth() =
        runTest {
            val db = newTestDb(testScheduler)
            try {
                seedLinked(db)
                val sent = mutableListOf<ChangeDto>()
                val client =
                    scriptedClient(syncRecords = emptyList(), onSync = { sent += it })
                val h = harness(db, client)
                seedCredentialIntent(db, h.secrets)
                h.address.updateShareFeedPasswords(false)

                h.engine.round(SyncRoundReason.SYNC_NOW)

                val pushed = sent.single { it.coll == SyncCollection.PODCAST.wire }
                assertTrue(pushed.fields.containsKey("subscribed"), "the ordinary fields still push")
                assertFalse(
                    pushed.fields.containsKey("auth"),
                    "a persisted off gate must withhold the credential",
                )
            } finally {
                db.close()
            }
        }

    @Test
    fun aCommittedLocalIntentMaterializesBeforeTheRoundSendsIt() =
        runTest {
            val db = newTestDb(testScheduler)
            try {
                seedLinked(db)
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val port = FakeSettingsSyncPort().apply { current[key.name] = "5000" }
                val client =
                    scriptedClient(syncRecords = emptyList(), onSync = { changes ->
                        assertTrue(port.applied.any { it == key to "20000" }, "materialize before sending")
                        assertTrue(changes.any { it.coll == "setting" && it.fields["v"]?.v == JsonPrimitive(20000) })
                    })
                val h = harness(db, client, port)
                h.capture.install()
                port.recordLocal(key, 20000L)
                val before = db.syncOutboxDao().get("setting", key.name, "v")!!
                h.engine.round(SyncRoundReason.SYNC_NOW)
                assertNull(db.syncOutboxDao().get("setting", key.name, "v"))
                val raw = SyncJson.decodeFromString<RawSyncRecord>(db.syncClockDao().get("setting", key.name)!!.clocks)
                assertEquals(Hlc(before.hlc, NodeId(before.nodeId)).toString(), raw.settingHash?.clock)
            } finally {
                db.close()
            }
        }

    @Test
    fun anUnmaterializableIntentIsNotPushedOrAcknowledged() =
        runTest {
            val db = newTestDb(testScheduler)
            try {
                seedLinked(db)
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val port =
                    FakeSettingsSyncPort().apply {
                        current[key.name] = "5000"
                        remoteResult = false
                    }
                val client =
                    scriptedClient(syncRecords = emptyList(), onSync = { changes ->
                        assertTrue(changes.none { it.coll == "setting" }, "a failed write stays local and retryable")
                    })
                val h = harness(db, client, port)
                h.capture.install()
                port.recordLocal(key, 20000L)
                val before = db.syncOutboxDao().get("setting", key.name, "v")!!
                h.engine.round(SyncRoundReason.SYNC_NOW)
                assertEquals(before, db.syncOutboxDao().get("setting", key.name, "v"))
            } finally {
                db.close()
            }
        }

    @Test
    fun anAckCannotRelabelANewerOffSwitchWrite() =
        runTest {
            val db = newTestDb(testScheduler)
            try {
                seedLinked(db)
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val port = FakeSettingsSyncPort().apply { current[key.name] = "5000" }
                lateinit var h: DrainHarness
                val client =
                    scriptedClient(syncRecords = emptyList(), onSync = {
                        kotlinx.coroutines.runBlocking {
                            h.address.updateSyncPlaybackSettings(false)
                            h.capture.reconcile()
                            port.current[key.name] = "7000"
                            port.recordLocal(key, 7000L)
                            port.commitLocal(key, 7000L)
                            port.emitLocal(key, 7000L, now)
                        }
                    })
                h = harness(db, client, port)
                h.capture.install()
                port.recordLocal(key, 20000L)
                h.engine.round(SyncRoundReason.SYNC_NOW)
                val raw = SyncJson.decodeFromString<RawSyncRecord>(db.syncClockDao().get("setting", key.name)!!.clocks)
                assertEquals(settingBaselineHash("7000"), raw.settingHash?.hash, "preserve the newer baseline")
                assertNull(raw.settingHash?.clock, "the off-write provenance must survive the old ack")
                assertEquals(JsonPrimitive(20000), raw.state.fields["v"]?.v, "ack uses the sent snapshot")
                h.address.updateSyncPlaybackSettings(true)
                h.capture.reconcile()
                assertEquals("20000", port.current[key.name], "the acknowledged account value wins on enable")
                assertNull(db.syncOutboxDao().get("setting", key.name, "v"))
            } finally {
                db.close()
            }
        }

    @Test
    fun anOlderLinkedRestoreCannotAcquireAFreshClock() = olderRestore(RestoreWrite.COMMITTED)

    @Test
    fun anInterruptedOlderRestoreRecoversWithoutAFreshClock() = olderRestore(RestoreWrite.INTERRUPTED)

    @Test
    fun anOlderRestoreCommittedBeforeItsStoreWriteCannotAcquireAFreshClock() = olderRestore(RestoreWrite.NOT_STARTED)

    private enum class RestoreWrite { COMMITTED, INTERRUPTED, NOT_STARTED }

    private fun olderRestore(write: RestoreWrite) =
        runTest {
            val db = newTestDb(testScheduler)
            try {
                seedLinked(db)
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val accepted = Hlc.of(now, 1, remote)
                val server = settingRecord(key, 9000L, accepted)
                val sent = mutableListOf<List<ChangeDto>>()
                val client =
                    object : SyncClient by scriptedClient(syncRecords = emptyList()) {
                        override suspend fun sync(
                            since: String?,
                            changes: List<ChangeDto>,
                        ): SyncResponse {
                            sent += changes
                            return SyncResponse(
                                results = changes.mapIndexed { index, _ -> ChangeResultDto(index, "stale") },
                                records = listOf(server),
                                cursor = "c:00000001:5",
                                hasMore = false,
                                serverTime = "",
                            )
                        }
                    }
                val h = harness(db, client)
                h.engine.round(SyncRoundReason.SYNC_NOW)
                h.capture.reconcile()
                assertEquals("9000", h.port.current[key.name], "precondition: accepted value materialized")
                val archiveCreatedAt = now - 100_000_000_000L
                val before = h.port.currentSnapshot(key)!!
                val origin =
                    db.withWriteTransaction {
                        assertTrue(db.syncOutboxDao().captureAt("setting", key.name, "v", archiveCreatedAt, "15000"))
                        h.port.recordCommitted(key, "15000", before)
                    }
                val historical = db.syncOutboxDao().get("setting", key.name, "v")!!
                val older = Hlc(historical.hlc, NodeId(historical.nodeId))
                assertTrue(older < accepted, "the archive is older than the accepted server setting")
                h.port.remoteResult = write == RestoreWrite.COMMITTED
                if (write != RestoreWrite.NOT_STARTED) {
                    assertEquals(write == RestoreWrite.COMMITTED, h.port.applyRecorded(key, "15000", origin))
                }
                h.port.remoteResult = true
                h.engine.round(SyncRoundReason.SYNC_NOW)
                val published =
                    sent
                        .flatten()
                        .flatMap { it.fields.values }
                        .mapNotNull { it.c }
                        .map(Hlc::parse)
                assertTrue(
                    published.all { it <= older },
                    "captureAt restore must not be re-stamped before push: $published vs $older",
                )
                h.capture.reconcile()
                assertNull(
                    db.syncOutboxDao().get("setting", key.name, "v"),
                    "the old restore must never be re-captured with a fresh clock",
                )
            } finally {
                db.close()
            }
        }

    // --- Fixtures -----------------------------------------------------------------------------------

    private val authRid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val localNode = NodeId("0000000000000001")

    /**
     * One credential feed with a pending local `auth` intent, as an un-pushed credential set
     * left the outbox before this restart (10 Passwords, producer side).
     */
    private suspend fun seedCredentialIntent(
        db: NeutrodyneDatabase,
        secrets: FakeSecretStore,
    ) {
        val podcastId =
            db.podcastDao().insertPodcast(
                podcastEntity(
                    syncId = authRid,
                    feedUrl = "$AUTH_ORIGIN/feed",
                    feedKey = "$AUTH_ORIGIN/feed",
                ),
            )
        val credentialId = secrets.put(AUTH_ORIGIN, AUTH_USER, AUTH_PASS.toCharArray())!!
        db
            .credentialDao()
            .insert(
                CredentialEntity(
                    id = credentialId,
                    origin = AUTH_ORIGIN,
                    username = AUTH_USER,
                    secretCipher = null,
                    iv = null,
                    createdAt = now,
                ),
            )
        val clock = Hlc.of(now, 2, localNode)
        db.withWriteTransaction {
            db.syncStateDao().withApplying {
                db.podcastDao().setCredentialId(podcastId, credentialId)
                db.syncOutboxDao().upsert(
                    SyncOutboxEntity(
                        coll = SyncCollection.PODCAST.wire,
                        rid = authRid,
                        field = "subscribed",
                        hlc = clock.packed,
                        nodeId = localNode.value,
                        value = "true",
                    ),
                )
                db.syncOutboxDao().upsert(
                    SyncOutboxEntity(
                        coll = SyncCollection.PODCAST.wire,
                        rid = authRid,
                        field = "auth",
                        hlc = clock.packed,
                        nodeId = localNode.value,
                    ),
                )
            }
        }
    }

    private fun applier(
        db: NeutrodyneDatabase,
        port: FakeSettingsSyncPort,
        clock: TestClock,
        secrets: FakeSecretStore = FakeSecretStore(),
        credentials: FakeCredentialCommitCoordinator = FakeCredentialCommitCoordinator(),
    ): SyncApplier =
        SyncApplier(
            db = db,
            clock = clock,
            secrets = secrets,
            settings = port,
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
            deviceNames = ClientDeviceNames { emptyList() },
            sharePasswords = { false },
            coordinator = credentials,
        )

    private suspend fun seedLinked(db: NeutrodyneDatabase) {
        db.withWriteTransaction {
            db.syncStateDao().upsert(
                SyncStateEntity(
                    enabled = true,
                    deviceId = deviceId,
                    nodeId = "0000000000000001",
                    hlc = 1L shl 16,
                    linkedAt = 1L,
                ),
            )
        }
    }

    private fun settingRecord(
        key: SettingKey<*>,
        value: Long,
        clock: Hlc = Hlc.of(now, 1, remote),
    ): RecordDto =
        RecordDto(
            coll = SyncCollection.SETTING.wire,
            id = JsonPrimitive(key.name),
            seq = 1,
            by = deviceId,
            fields = mapOf("v" to FieldValue(JsonPrimitive(value), clock.toString())),
        )

    private suspend fun pendingSetting(
        db: NeutrodyneDatabase,
        key: String,
    ): String? {
        val row = db.syncClockDao().get(SyncCollection.SETTING.wire, key) ?: return null
        val raw = runCatching { SyncJson.decodeFromString<RawSyncRecord>(row.clocks) }.getOrNull()
        return raw?.pendingSetting?.value
    }

    private suspend fun seedLocalIntent(
        db: NeutrodyneDatabase,
        key: SettingKey<*>,
        value: String,
        clock: Hlc,
    ) {
        db.withWriteTransaction {
            db.syncOutboxDao().upsert(
                SyncOutboxEntity(
                    coll = SyncCollection.SETTING.wire,
                    rid = key.name,
                    field = "v",
                    hlc = clock.packed,
                    nodeId = clock.nodeId.value,
                    value = value,
                ),
            )
        }
    }

    /** A [SyncClient] whose `sync`/`changes` answers come from parameters; the rest is unused. */
    private fun scriptedClient(
        syncRecords: List<RecordDto>,
        syncHasMore: Boolean = false,
        syncFailure: (() -> Throwable)? = null,
        pageRecords: List<RecordDto> = emptyList(),
        onSync: ((List<ChangeDto>) -> Unit)? = null,
    ): SyncClient =
        object : SyncClient {
            override suspend fun discover(baseUrl: String): DiscoveryDocument = unsupported()

            override suspend fun login(
                baseUrl: String,
                username: String,
                password: String,
                device: DeviceInfoDto,
            ): TokenResponse = unsupported()

            override suspend fun redeemInvite(
                baseUrl: String,
                code: String,
                device: DeviceInfoDto,
            ): TokenResponse = unsupported()

            override suspend fun linkStart(
                baseUrl: String,
                device: DeviceInfoDto,
            ): LinkStartResponse = unsupported()

            override suspend fun linkToken(
                baseUrl: String,
                deviceCode: String,
            ): TokenResponse = unsupported()

            override suspend fun linkApprove(
                baseUrl: String,
                token: String,
                userCode: String,
            ): LinkApproveResponse = unsupported()

            override suspend fun linkConfirm(
                baseUrl: String,
                token: String,
                userCode: String,
                approve: Boolean,
            ) = unsupported()

            override suspend fun logout() = unsupported()

            override suspend fun devices(): List<DeviceDto> = unsupported()

            override suspend fun renameDevice(
                deviceId: String,
                name: String,
            ): DeviceDto = unsupported()

            override suspend fun revokeDevice(deviceId: String) = unsupported()

            override suspend fun account(): AccountSummary = unsupported()

            override suspend fun sync(
                since: String?,
                changes: List<ChangeDto>,
            ): SyncResponse {
                onSync?.invoke(changes)
                syncFailure?.let { throw it() }
                return SyncResponse(
                    results =
                        changes.mapIndexed { index, _ -> ChangeResultDto(index, "applied") },
                    records = syncRecords,
                    cursor = "c:00000001:5",
                    hasMore = syncHasMore,
                    serverTime = "",
                )
            }

            override suspend fun changes(
                since: String?,
                limit: Int,
            ): ChangesPage =
                ChangesPage(
                    records = pageRecords,
                    cursor = "c:00000001:9",
                    hasMore = false,
                    serverTime = "",
                )

            override suspend fun reset(
                confirm: String,
                mode: String,
                beforeCursor: String?,
            ): AccountSummary = unsupported()

            private fun unsupported(): Nothing = throw UnsupportedOperationException()
        }

    /**
     * The host's real in-memory Room database; the Android host supplies a Robolectric context.
     * Its query context is an [kotlinx.coroutines.test.UnconfinedTestDispatcher] sharing
     * [scheduler], so Room work stays on the test's virtual clock.
     */
    protected abstract fun newTestDb(scheduler: TestCoroutineScheduler): NeutrodyneDatabase

    private companion object {
        const val AUTH_ORIGIN = "https://a.example"
        const val AUTH_USER = "sync-user"
        const val AUTH_PASS = "s3ntinel-pass-9f8e"
    }
}
