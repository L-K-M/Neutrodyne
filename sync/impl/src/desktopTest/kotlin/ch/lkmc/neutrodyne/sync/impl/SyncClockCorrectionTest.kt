// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncCaptureKind
import ch.lkmc.neutrodyne.core.database.SyncOutboxEntity
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.SettingsSyncPort
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.sync.InMemorySyncServer
import ch.lkmc.neutrodyne.sync.protocol.FieldState
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** 10 offset correction: a future persisted HLC is clamped and future LOCAL rows restamped. */
private const val SERVER_NOW_MS = 1_800_000_000_000L
private const val SENT_AT_MS = SERVER_NOW_MS + 21_600_000L
private const val RECEIVED_AT_MS = SENT_AT_MS
private const val OFFSET_MS = -21_600_000L
private const val ADJUSTED_WALL_MS = SERVER_NOW_MS
private const val ACCEPTED_MS = SERVER_NOW_MS + 120_000L
private const val FUTURE_MS = SERVER_NOW_MS + 3_600_000L
private const val FIVE_MIN_MS = 300_000L
private const val LINKED_AT_MS = 1_700_000_000_000L
private const val SERVER_URL = "https://sync.example/accountA/deviceA/nodea"
private const val OWN_NODE = "a000000000000001"
private const val FOREIGN_NODE = "b000000000000002"
private const val SERVER_NODE = "0000000000000000"
private val SERVER_TIME = Instant.fromEpochMilliseconds(SERVER_NOW_MS).toString()

class SyncClockCorrectionTest {
    @Test
    fun aFuturePersistedHlcIsClampedAndFutureLocalRowsAreRestamped() =
        runTest {
            val db = TestDb.inMemory()
            val engine = device(db).engine
            try {
                seedState(db, FUTURE_MS)
                seedAcceptedRaw(db, ACCEPTED_MS)
                seedOutbox(db, "played", FUTURE_MS, "true")
                seedOutbox(db, "playedAt", FUTURE_MS, "1234")
                seedOutbox(db, "fav", FUTURE_MS, "false", kind = SyncCaptureKind.REPLAY)
                seedOutbox(db, "played", FUTURE_MS, "true", rid = "episode-2", node = FOREIGN_NODE)
                val rawJsonBefore = db.syncClockDao().get("setting", "theme")!!.clocks
                val localBefore =
                    db
                        .syncOutboxDao()
                        .rows()
                        .filter { it.captureKind == SyncCaptureKind.LOCAL && it.nodeId == OWN_NODE }
                        .associateBy { it.field }
                val profile = db.syncStateDao().get()!!
                val correction =
                    checkNotNull(engine.observeServerTime(profile, SERVER_TIME, SENT_AT_MS, RECEIVED_AT_MS))
                assertEquals(OFFSET_MS, correction.offsetMs)
                val seed = packed(ACCEPTED_MS, SERVER_NODE)
                val resultingHlc = db.syncStateDao().get()!!.hlc
                assertTrue(resultingHlc >= seed, "the corrected clock keeps the accepted frontier")
                assertTrue(
                    resultingHlc ushr Hlc.COUNTER_BITS <= ADJUSTED_WALL_MS + FIVE_MIN_MS,
                    "the corrected clock fits the admission bound",
                )
                val restamped =
                    db.syncOutboxDao().rows().filter {
                        it.captureKind == SyncCaptureKind.LOCAL && it.nodeId == OWN_NODE &&
                            it.field in setOf("played", "playedAt")
                    }
                assertEquals(2, restamped.size)
                val newClock = restamped.map { it.hlc }.distinct().single()
                assertTrue(newClock > seed, "the restamped clock is newer than the accepted clock")
                assertEquals(newClock, resultingHlc, "the singleton includes its newly assigned local clock")
                assertTrue(
                    newClock ushr Hlc.COUNTER_BITS <= ADJUSTED_WALL_MS + FIVE_MIN_MS,
                    "the restamped clock stays within the 5-minute admission bound",
                )
                restamped.forEach { row ->
                    val before = localBefore.getValue(row.field)
                    assertEquals(before.value, row.value)
                    assertEquals(before.captureKind, row.captureKind)
                    assertEquals(before.nodeId, row.nodeId)
                    assertEquals(before.coll, row.coll)
                    assertEquals(before.rid, row.rid)
                }
                val replay = db.syncOutboxDao().rows().single { it.captureKind == SyncCaptureKind.REPLAY }
                assertEquals(packed(FUTURE_MS, OWN_NODE), replay.hlc, "a REPLAY clock is immutable")
                assertEquals("false", replay.value)
                assertEquals(OWN_NODE, replay.nodeId)
                val foreign = db.syncOutboxDao().rows().single { it.nodeId == FOREIGN_NODE }
                assertEquals(packed(FUTURE_MS, FOREIGN_NODE), foreign.hlc, "another node's LOCAL row is untouched")
                assertEquals(SyncCaptureKind.LOCAL, foreign.captureKind)
                assertEquals("true", foreign.value)
                assertEquals(rawJsonBefore, db.syncClockDao().get("setting", "theme")!!.clocks)
            } finally {
                db.close()
            }
        }

    @Test
    fun materializedSettingsKeepTheirPhysicalReceiptAfterClockRepair() =
        verifySettingReceipt(materializedBeforeRepair = true)

    @Test
    fun aDelayedPostWriteCallbackUsesTheRestampedSettingClock() = verifySettingReceipt(materializedBeforeRepair = false)

    private fun verifySettingReceipt(materializedBeforeRepair: Boolean) =
        runTest {
            val db = TestDb.inMemory()
            val device = device(db)
            val key = PlaybackSettingKeys.SKIP_BACK_MS
            try {
                seedState(db, FUTURE_MS)
                device.settingsPort.current[key.name] = "10000"
                SyncClockStore(db).put(
                    "setting",
                    key.name,
                    RawSyncRecord(
                        state =
                            RecordState(
                                coll = SyncCollection.SETTING,
                                fields =
                                    mapOf(
                                        "v" to
                                            FieldState(
                                                JsonPrimitive(10_000),
                                                Hlc.of(SERVER_NOW_MS - 120_000, 0, NodeId(SERVER_NODE)),
                                            ),
                                    ),
                            ),
                    ),
                )
                val capture = SettingsCapture(db, device.settingsPort, device.writeLocks) { true }
                capture.install()
                device.settingsPort.recordLocal(key, 20_000L)
                device.settingsPort.commitLocal(key, 20_000L)
                if (materializedBeforeRepair) device.settingsPort.emitLocal(key, 20_000L, SENT_AT_MS)
                val before = checkNotNull(db.syncOutboxDao().get("setting", key.name, "v"))
                val physicalReceipt = device.settingsPort.currentSnapshot(key)
                device.engine.observeServerTime(db.syncStateDao().get(), SERVER_TIME, SENT_AT_MS, RECEIVED_AT_MS)
                val corrected = checkNotNull(db.syncOutboxDao().get("setting", key.name, "v"))
                assertTrue(corrected.hlc < before.hlc, "the unaccepted setting clock must be repaired")
                if (!materializedBeforeRepair) device.settingsPort.emitLocal(key, 20_000L, SENT_AT_MS)
                capture.reconcile()
                val pending =
                    checkNotNull(db.syncOutboxDao().get("setting", key.name, "v")) {
                        "clock repair must not retire a materialized local setting intent"
                    }
                assertEquals(corrected, pending)
                assertEquals(
                    physicalReceipt,
                    device.settingsPort.currentSnapshot(key),
                    "DataStore receipts are immutable",
                )
                assertEquals("20000", device.settingsPort.currentJson(key))
                assertTrue(device.settingsPort.applied.isEmpty(), "a landed setting must not be reapplied")
                assertEquals(
                    Hlc(corrected.hlc, NodeId(corrected.nodeId)).toString(),
                    SyncClockStore(db).get("setting", key.name)?.settingHash?.clock,
                    "a late callback must not restore the rejected future clock",
                )
            } finally {
                db.close()
            }
        }

    @Test
    fun correctionDuringRecoveryCannotRestoreTheOldBaselineClock() =
        runTest {
            val db = TestDb.inMemory()
            val device = device(db)
            val key = PlaybackSettingKeys.SKIP_BACK_MS
            var correctDuringSnapshot = false
            var corrections = 0
            val port =
                object : SettingsSyncPort by device.settingsPort {
                    override suspend fun currentSnapshot(key: SettingKey<*>) =
                        device.settingsPort.currentSnapshot(key).also {
                            if (correctDuringSnapshot) {
                                correctDuringSnapshot = false
                                corrections++
                                device.engine.observeServerTime(
                                    db.syncStateDao().get(),
                                    SERVER_TIME,
                                    SENT_AT_MS,
                                    RECEIVED_AT_MS,
                                )
                            }
                        }
                }
            try {
                seedState(db, FUTURE_MS)
                val capture = SettingsCapture(db, port, device.writeLocks) { true }
                capture.install()
                device.settingsPort.recordLocal(key, 20_000L)
                device.settingsPort.commitLocal(key, 20_000L)
                device.settingsPort.emitLocal(key, 20_000L, SENT_AT_MS)
                correctDuringSnapshot = true
                capture.reconcile()
                assertEquals(1, corrections)
                val pending = checkNotNull(db.syncOutboxDao().get("setting", key.name, "v"))
                assertEquals(
                    Hlc(pending.hlc, NodeId(pending.nodeId)).toString(),
                    SyncClockStore(db).get("setting", key.name)?.settingHash?.clock,
                    "a recovery that read the old intent must not restore its future baseline",
                )
                capture.reconcile()
                assertEquals(pending, db.syncOutboxDao().get("setting", key.name, "v"))
            } finally {
                db.close()
            }
        }

    @Test
    fun acceptedMatchClocksBeyondTheFirstMetadataPageSeedTheRepair() =
        runTest {
            val db = TestDb.inMemory()
            val engine = device(db).engine
            try {
                seedState(db, FUTURE_MS)
                seedOutbox(db, "played", FUTURE_MS, "true")
                val store = SyncClockStore(db)
                val frontier = Hlc.of(SERVER_NOW_MS + 240_000, 7, NodeId(SERVER_NODE))
                repeat(140) { index ->
                    store.put(
                        "episode",
                        "record-${index.toString().padStart(3, '0')}",
                        RawSyncRecord(
                            RecordState(SyncCollection.EPISODE, matchClocks = mapOf("podcast" to frontier)),
                        ),
                    )
                }
                val acceptedBefore = db.syncClockDao().all()
                engine.observeServerTime(db.syncStateDao().get(), SERVER_TIME, SENT_AT_MS, RECEIVED_AT_MS)
                val local = db.syncOutboxDao().rows().single()
                assertTrue(local.hlc > frontier.packed, "match clocks on later metadata pages remain ordered")
                assertEquals(acceptedBefore, db.syncClockDao().all(), "accepted envelopes stay unchanged")
            } finally {
                db.close()
            }
        }

    @Test
    fun localClocksBefore2020AreRepairedWithoutRewritingReplayClocks() =
        runTest {
            val db = TestDb.inMemory()
            try {
                seedState(db, SERVER_NOW_MS)
                val old = 1_500_000_000_000L
                seedOutbox(db, "played", old, "true")
                seedOutbox(db, "playedAt", old, "1234")
                seedOutbox(db, "fav", old, "false", kind = SyncCaptureKind.REPLAY)
                device(db).engine.observeServerTime(db.syncStateDao().get(), SERVER_TIME, SENT_AT_MS, RECEIVED_AT_MS)
                val rows = db.syncOutboxDao().rows()
                val local = rows.filter { it.captureKind == SyncCaptureKind.LOCAL }
                assertEquals(1, local.map { it.hlc }.distinct().size, "paired local fields keep one clock")
                assertTrue(local.all { it.hlc >= packed(SERVER_NOW_MS) })
                assertEquals(packed(old), rows.single { it.captureKind == SyncCaptureKind.REPLAY }.hlc)
            } finally {
                db.close()
            }
        }

    @Test
    fun aSettingCommitAfterRepairStillRecognizesItsOriginalReceipt() =
        runTest {
            val db = TestDb.inMemory()
            val device = device(db)
            val key = PlaybackSettingKeys.SKIP_BACK_MS
            try {
                seedState(db, FUTURE_MS)
                val capture = SettingsCapture(db, device.settingsPort, device.writeLocks) { true }
                capture.install()
                device.settingsPort.recordLocal(key, 20_000L)
                device.engine.observeServerTime(db.syncStateDao().get(), SERVER_TIME, SENT_AT_MS, RECEIVED_AT_MS)
                val repaired = checkNotNull(db.syncOutboxDao().get("setting", key.name, "v"))
                device.settingsPort.commitLocal(key, 20_000L)
                device.settingsPort.emitLocal(key, 20_000L, SENT_AT_MS)
                capture.reconcile()
                assertEquals(repaired, db.syncOutboxDao().get("setting", key.name, "v"))
                assertEquals(
                    Hlc(repaired.hlc, NodeId(repaired.nodeId)).toString(),
                    SyncClockStore(db).get("setting", key.name)?.settingHash?.clock,
                )
            } finally {
                db.close()
            }
        }

    @Test
    fun acceptedParkedClocksCannotBeRewrittenAsLocalIntents() =
        runTest {
            val db = TestDb.inMemory()
            try {
                seedState(db, FUTURE_MS)
                seedOutbox(db, "fav", FUTURE_MS, "true")
                val id =
                    ch.lkmc.neutrodyne.sync.protocol.RecordId.Episode(
                        ch.lkmc.neutrodyne.sync.protocol
                            .PodcastId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                        ch.lkmc.neutrodyne.sync.protocol
                            .EpisodeKey("guid:parked"),
                    )
                val accepted =
                    RecordState(
                        SyncCollection.EPISODE,
                        fields =
                            mapOf(
                                "fav" to FieldState(JsonPrimitive(true), Hlc.of(FUTURE_MS, 0, NodeId(OWN_NODE))),
                            ),
                    ).toDto(id, 1, null)
                ch.lkmc.neutrodyne.sync.protocol.ProtocolValidation
                    .record(accepted)
                val json =
                    ch.lkmc.neutrodyne.sync.protocol.SyncJson
                        .encodeToString(accepted)
                db.syncParkedDao().park(
                    ch.lkmc.neutrodyne.core.database.SyncParkedEntity(
                        podcastSyncId = id.podcast.value,
                        identityKey = id.key.value,
                        record = json,
                        receivedAt = SERVER_NOW_MS,
                    ),
                )
                val before = db.syncOutboxDao().rows()
                val profile = checkNotNull(db.syncStateDao().get())
                device(db).engine.observeServerTime(profile, SERVER_TIME, SENT_AT_MS, RECEIVED_AT_MS)
                assertEquals(before, db.syncOutboxDao().rows(), "accepted future history blocks unsafe repair")
                assertEquals(
                    json,
                    db
                        .syncParkedDao()
                        .all()
                        .single()
                        .record,
                )
            } finally {
                db.close()
            }
        }

    @Test
    fun unreadableParkedMetadataRollsBackTheCorrection() =
        runTest {
            val db = TestDb.inMemory()
            try {
                seedState(db, FUTURE_MS)
                seedOutbox(db, "fav", FUTURE_MS, "true")
                db.syncParkedDao().park(
                    ch.lkmc.neutrodyne.core.database.SyncParkedEntity(
                        podcastSyncId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                        identityKey = "guid:unreadable",
                        record = "{broken",
                        receivedAt = SERVER_NOW_MS,
                    ),
                )
                val before = checkNotNull(db.syncStateDao().get())
                val rows = db.syncOutboxDao().rows()
                assertFailsWith<okio.IOException> {
                    device(db).engine.observeServerTime(before, SERVER_TIME, SENT_AT_MS, RECEIVED_AT_MS)
                }
                assertEquals(before, db.syncStateDao().get())
                assertEquals(rows, db.syncOutboxDao().rows())
            } finally {
                db.close()
            }
        }

    @Test
    fun unreadableAcceptedMetadataRollsBackTheCorrection() =
        runTest {
            val db = TestDb.inMemory()
            try {
                seedState(db, FUTURE_MS, offsetMs = 1234)
                seedOutbox(db, "played", FUTURE_MS, "true")
                db.syncClockDao().upsert(
                    ch.lkmc.neutrodyne.core.database
                        .SyncClockEntity("episode", "broken", "not-json"),
                )
                val before = db.syncStateDao().get()
                val rows = db.syncOutboxDao().rows()
                assertFailsWith<okio.IOException> {
                    device(db).engine.observeServerTime(before, SERVER_TIME, SENT_AT_MS, RECEIVED_AT_MS)
                }
                assertEquals(before, db.syncStateDao().get())
                assertEquals(rows, db.syncOutboxDao().rows())
            } finally {
                db.close()
            }
        }

    @Test
    fun aClockWithinFiveMinutesIsKeptAndValidLocalRowsAreNotRestamped() =
        runTest {
            val db = TestDb.inMemory()
            val engine = device(db).engine
            try {
                seedState(db, ACCEPTED_MS)
                seedAcceptedRaw(db, ACCEPTED_MS)
                seedOutbox(db, "played", SERVER_NOW_MS + 60_000L, "true")
                val profile = db.syncStateDao().get()!!
                val correction =
                    checkNotNull(engine.observeServerTime(profile, SERVER_TIME, SENT_AT_MS, RECEIVED_AT_MS))
                assertEquals(OFFSET_MS, correction.offsetMs)
                assertEquals(packed(ACCEPTED_MS), db.syncStateDao().get()!!.hlc, "a valid clock stays")
                val row = db.syncOutboxDao().rows().single()
                assertEquals(packed(SERVER_NOW_MS + 60_000L), row.hlc, "a valid LOCAL row is not restamped")
            } finally {
                db.close()
            }
        }

    @Test
    fun anObsoleteProfileIsRejectedWithoutTouchingTheStoredState() =
        runTest {
            val db = TestDb.inMemory()
            val engine = device(db).engine
            try {
                seedState(db, FUTURE_MS, offsetMs = 123_456L)
                seedOutbox(db, "played", FUTURE_MS, "true")
                val stored = db.syncStateDao().get()!!
                val rowsBefore = db.syncOutboxDao().rows()
                val obsolete = stored.copy(serverUrl = "https://other.example/accountB/deviceB/nodeb")
                assertFailsWith<IllegalStateException> {
                    engine.observeServerTime(obsolete, SERVER_TIME, SENT_AT_MS, RECEIVED_AT_MS)
                }
                val after = db.syncStateDao().get()!!
                assertEquals(123_456L, after.clockOffsetMs)
                assertEquals(stored.hlc, after.hlc)
                assertEquals(rowsBefore, db.syncOutboxDao().rows())
            } finally {
                db.close()
            }
        }

    @Test
    fun invalidMissingAndReversedSamplesLeaveTheClockHistoryUnchanged() =
        runTest {
            val db = TestDb.inMemory()
            val engine = device(db).engine
            try {
                seedState(db, FUTURE_MS, offsetMs = 555_555L)
                seedOutbox(db, "played", FUTURE_MS, "true")
                val stored = db.syncStateDao().get()!!
                val rowsBefore = db.syncOutboxDao().rows()
                assertNull(engine.observeServerTime(null, SERVER_TIME, SENT_AT_MS, RECEIVED_AT_MS))
                assertNull(engine.observeServerTime(stored, "not-a-time", SENT_AT_MS, RECEIVED_AT_MS))
                assertNull(
                    engine.observeServerTime(
                        stored,
                        Instant.fromEpochMilliseconds(0).toString(),
                        SENT_AT_MS,
                        RECEIVED_AT_MS,
                    ),
                )
                assertNull(engine.observeServerTime(stored, SERVER_TIME, RECEIVED_AT_MS, RECEIVED_AT_MS - 1))
                val after = db.syncStateDao().get()!!
                assertEquals(555_555L, after.clockOffsetMs)
                assertEquals(stored.hlc, after.hlc)
                assertEquals(rowsBefore, db.syncOutboxDao().rows())
            } finally {
                db.close()
            }
        }
}

private fun packed(
    ms: Long,
    node: String = OWN_NODE,
): Long = Hlc.of(ms, 0, NodeId(node)).packed

private fun device(db: NeutrodyneDatabase): SyncEngineTest.Device =
    SyncEngineTest.Device(db, InMemorySyncClient(InMemorySyncServer { SERVER_NOW_MS }))

private suspend fun seedState(
    db: NeutrodyneDatabase,
    hlcMs: Long,
    offsetMs: Long = 0L,
) {
    db.withWriteTransaction {
        db.syncStateDao().upsert(
            SyncStateEntity(
                enabled = true,
                serverUrl = SERVER_URL,
                accountId = "accountA",
                deviceId = "deviceA",
                nodeId = OWN_NODE,
                hlc = packed(hlcMs),
                clockOffsetMs = offsetMs,
                linkedAt = LINKED_AT_MS,
            ),
        )
    }
}

private suspend fun seedOutbox(
    db: NeutrodyneDatabase,
    field: String,
    hlcMs: Long,
    value: String,
    rid: String = "episode-1",
    node: String = OWN_NODE,
    kind: SyncCaptureKind = SyncCaptureKind.LOCAL,
) {
    db.syncOutboxDao().upsert(SyncOutboxEntity("episode", rid, field, packed(hlcMs, node), node, kind, value))
}

private suspend fun seedAcceptedRaw(
    db: NeutrodyneDatabase,
    hlcMs: Long,
): RawSyncRecord {
    val raw =
        RawSyncRecord(
            state =
                RecordState(
                    coll = SyncCollection.SETTING,
                    fields = mapOf("v" to FieldState(JsonPrimitive("dark"), Hlc.of(hlcMs, 0, NodeId(SERVER_NODE)))),
                ),
        )
    SyncClockStore(db).put(SyncCollection.SETTING.wire, "theme", raw)
    return raw
}
