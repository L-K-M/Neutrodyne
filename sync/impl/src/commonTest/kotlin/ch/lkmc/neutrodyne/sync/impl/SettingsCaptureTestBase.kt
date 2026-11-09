// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncCaptureKind
import ch.lkmc.neutrodyne.core.database.SyncClockEntity
import ch.lkmc.neutrodyne.core.database.SyncOutboxEntity
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.SettingWriteOrigin
import ch.lkmc.neutrodyne.core.domain.SettingsSyncPort
import ch.lkmc.neutrodyne.core.domain.SettingsWriteCoordinator
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.testing.FakeSettingsSyncPort
import ch.lkmc.neutrodyne.sync.protocol.FieldState
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 10 Settings capture: the durable local-write boundary, recovery and lifecycle gates. */
abstract class SettingsCaptureTestBase {
    private val now = 1_800_000_000_000L
    private val remote = NodeId("0000000000000002")
    private val localNode = NodeId("0000000000000001")

    @Test
    fun aRecorderFailurePropagatesAndDataStoreStaysUntouched() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val port = FakeSettingsSyncPort()
                capture(db, port).install()
                // A Room write failure surfaces through recordLocal, before any DataStore write:
                // the repository contract is "record first, write only on success".
                db.close()
                assertFails {
                    port.recordLocal(PlaybackSettingKeys.SKIP_BACK_MS, 20_000L)
                }
            } finally {
                db.close()
            }
        }

    @Test
    fun anUnmaterializedCommittedIntentReplaysWithItsOriginalClock() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val port = FakeSettingsSyncPort().apply { current[PlaybackSettingKeys.SKIP_BACK_MS.name] = "10000" }
                capture(db, port).install()

                // The Room intent commits; the DataStore write never lands (crash or failure —
                // emitLocal is never called).
                port.recordLocal(PlaybackSettingKeys.SKIP_BACK_MS, 20_000L)
                val intent = db.syncOutboxDao().get("setting", PlaybackSettingKeys.SKIP_BACK_MS.name, "v")
                assertNotNull(intent)

                // The next start reconciles through a fresh capture instance.
                capture(db, port).reconcile()

                // The committed literal is re-materialized, and the outbox row keeps the complete
                // original clock — recovery never takes a fresh tick.
                assertEquals(1, port.applied.size)
                assertEquals(PlaybackSettingKeys.SKIP_BACK_MS, port.applied.single().first)
                assertEquals("20000", port.applied.single().second)
                val row = db.syncOutboxDao().get("setting", PlaybackSettingKeys.SKIP_BACK_MS.name, "v")
                assertNotNull(row, "the recovered intent stays queued for upload")
                assertEquals(intent.hlc, row.hlc)
                assertEquals(intent.nodeId, row.nodeId)
                val baseline = baselineOf(db, PlaybackSettingKeys.SKIP_BACK_MS)
                assertEquals(Hlc(intent.hlc, NodeId(intent.nodeId)).toString(), baseline?.clock)
            } finally {
                db.close()
            }
        }

    @Test
    fun anAlreadyMaterializedIntentOnlyAdvancesTheBaseline() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val port = FakeSettingsSyncPort()
                capture(db, port).install()

                // The intent commits AND its DataStore write lands before the crash.
                port.recordLocal(PlaybackSettingKeys.SKIP_BACK_MS, 20_000L)
                port.commitLocal(PlaybackSettingKeys.SKIP_BACK_MS, 20_000L)
                port.emitLocal(PlaybackSettingKeys.SKIP_BACK_MS, 20_000L, now)
                val intent =
                    db.syncOutboxDao().get("setting", PlaybackSettingKeys.SKIP_BACK_MS.name, "v")
                assertNotNull(intent)
                port.applied.clear()

                capture(db, port).reconcile()

                assertTrue(port.applied.isEmpty(), "a landed intent must not be re-applied")
                val baseline = baselineOf(db, PlaybackSettingKeys.SKIP_BACK_MS)
                assertEquals(Hlc(intent.hlc, NodeId(intent.nodeId)).toString(), baseline?.clock)
            } finally {
                db.close()
            }
        }

    @Test
    fun aReplayKindIntentMaterializesOverTheStoredValue() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val port = FakeSettingsSyncPort()
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                port.current[key.name] = "5000"
                val clock = Hlc.of(now, 7, remote)
                db.syncOutboxDao().upsert(
                    SyncOutboxEntity(
                        coll = SyncCollection.SETTING.wire,
                        rid = key.name,
                        field = "v",
                        hlc = clock.packed,
                        nodeId = clock.nodeId.value,
                        captureKind = SyncCaptureKind.REPLAY,
                        value = "15000",
                    ),
                )

                capture(db, port).reconcile()

                assertEquals(key, port.applied.single().first)
                assertEquals("15000", port.applied.single().second)
                val baseline = baselineOf(db, key)
                assertEquals(clock.toString(), baseline?.clock)
            } finally {
                db.close()
            }
        }

    @Test
    fun aNewerRemoteMarkerDrainsInsteadOfTheOlderIntent() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val port = FakeSettingsSyncPort()
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val coordinator = TestSettingsWriteCoordinator()
                val capture = SettingsCapture(db, port, coordinator) { true }
                capture.install()

                port.recordLocal(key, 20_000L)
                val intent = db.syncOutboxDao().get("setting", key.name, "v")
                assertNotNull(intent)
                // A pending remote effect newer than the committed intent.
                val remoteClock =
                    Hlc.of(now, 1, remote).let { Hlc(it.packed + 1L shl 16, it.nodeId) }
                seedPending(db, key, "15000", remoteClock)

                // The start ordering: recovery first, then the remote drain — the remote value
                // wins, the intent stays queued, and the older intent was never materialized.
                SyncApplyEffects(db, port, coordinator) { true }.drainAtStart(capture)

                assertEquals(key, port.applied.single().first)
                assertEquals("15000", port.applied.single().second)
                assertNull(pendingOf(db, key))
                val row = db.syncOutboxDao().get("setting", key.name, "v")
                assertEquals("20000", row?.value)
            } finally {
                db.close()
            }
        }

    @Test
    fun aNewerLocalIntentBeatsAnOlderPendingRemoteMarker() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val port = FakeSettingsSyncPort().apply { current[PlaybackSettingKeys.SKIP_BACK_MS.name] = "10000" }
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val coordinator = TestSettingsWriteCoordinator()
                val capture = SettingsCapture(db, port, coordinator) { true }
                capture.install()

                // The capture tick must raise the local clock past the seeded remote one.
                val remoteClock = Hlc.of(now - 10_000L, 1, remote)
                db.syncStateDao().upsert(db.syncStateDao().get()!!.copy(hlc = remoteClock.packed))
                port.recordLocal(key, 20_000L)
                seedPending(db, key, "15000", remoteClock)

                SyncApplyEffects(db, port, coordinator) { true }.drainAtStart(capture)

                // Recovery materialized the local intent; the drain cleared the superseded marker
                // without applying it.
                assertEquals(key, port.applied.single().first)
                assertEquals("20000", port.applied.single().second)
                assertNull(pendingOf(db, key))
            } finally {
                db.close()
            }
        }

    /**
     * The MS2 regression: a committed but unmaterialized intent, then the switch off, then a
     * newer local set while off. Recovery must not roll the off-switch edit back to the older
     * intent — the intent retires as bookkeeping and the surviving value re-captures on the
     * next enable, under a normal capture tick (never an invented wall clock).
     */
    @Test
    fun anOffSwitchEditIsNotRolledBackByTheOlderIntent() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val port = FakeSettingsSyncPort()
                var switchOn = true
                val coordinator = TestSettingsWriteCoordinator()
                val capture = SettingsCapture(db, port, coordinator) { switchOn }
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                capture.install()

                // 1. Committed intent, never materialized (no emitLocal).
                port.recordLocal(key, 15_000L)
                val intent = db.syncOutboxDao().get("setting", key.name, "v")
                assertNotNull(intent)

                // 2. The switch turns off: the recorder goes inert.
                switchOn = false
                capture.reconcile()

                // 3. A newer local set while off: nothing is captured, but the successful write
                //    still baselines clocklessly through the materialized listener.
                port.recordLocal(key, 25_000L)
                port.commitLocal(key, 25_000L)
                port.emitLocal(key, 25_000L, now)
                assertEquals(
                    "15000",
                    db.syncOutboxDao().get("setting", key.name, "v")?.value,
                    "no new capture while the switch is off",
                )

                // 4. The switch turns on again; the same reconcile runs recovery.
                switchOn = true
                capture.reconcile()

                // The older intent never materialized: the off-switch value survived and is the
                // intent now queued for upload.
                assertTrue(
                    port.applied.none { it.second == "15000" },
                    "the off-switch edit must not be rolled back to the older intent",
                )
                val row = db.syncOutboxDao().get("setting", key.name, "v")
                assertNotNull(row, "the surviving value re-captures as a fresh intent")
                assertEquals("25000", row.value)
                assertEquals(localNode.value, row.nodeId)
                assertTrue(
                    row.hlc != intent.hlc || row.nodeId != intent.nodeId,
                    "the re-capture is a new tick, not the retired intent's clock",
                )
            } finally {
                db.close()
            }
        }

    @Test
    fun aRemoteMarkerStaysPendingWhileTheSwitchIsOff() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val port = FakeSettingsSyncPort()
                var switchOn = false
                val coordinator = TestSettingsWriteCoordinator()
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                seedPending(db, key, "15000", Hlc.of(now, 1, remote))

                // Off: neither recovery nor the drain runs the remote write.
                SyncApplyEffects(db, port, coordinator) { switchOn }
                    .drainAtStart(SettingsCapture(db, port, coordinator) { switchOn })
                assertTrue(port.applied.isEmpty())
                assertEquals("15000", pendingOf(db, key)?.value)

                // On: the server-wins drain applies it.
                switchOn = true
                SyncApplyEffects(db, port, coordinator) { switchOn }
                    .drainAtStart(SettingsCapture(db, port, coordinator) { switchOn })
                assertEquals(key, port.applied.single().first)
                assertEquals("15000", port.applied.single().second)
                assertNull(pendingOf(db, key))
            } finally {
                db.close()
            }
        }

    @Test
    fun anUnlinkedCaptureStaysInert() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val port = FakeSettingsSyncPort()
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val capture = capture(db, port)
                capture.install()

                db.withWriteTransaction { db.syncStateDao().unlink() }
                capture.reconcile()

                port.recordLocal(key, 20_000L)
                port.commitLocal(key, 20_000L)
                port.emitLocal(key, 20_000L, now)
                assertNull(
                    db.syncOutboxDao().get("setting", key.name, "v"),
                    "nothing captures while unlinked",
                )
                assertNull(
                    baselineOf(db, key),
                    "an unlinked materialized write baselines nothing",
                )
            } finally {
                db.close()
            }
        }

    @Test
    fun anAccountAbsentKeyCapturesItsStoredValueOnInstall() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val port = FakeSettingsSyncPort()
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                port.current[key.name] = "7000"

                capture(db, port).reconcile()

                val row = db.syncOutboxDao().get("setting", key.name, "v")
                assertNotNull(row, "a key the account does not carry uploads its stored value")
                assertEquals("7000", row.value)
                assertTrue(port.applied.isEmpty())
            } finally {
                db.close()
            }
        }

    @Test
    fun anAckedSettingDoesNotRecaptureOnRestart() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val port = FakeSettingsSyncPort()
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                // The ack merged the sent value into raw state; the outbox row is gone.
                db.syncClockDao().upsert(
                    SyncClockEntity(
                        SyncCollection.SETTING.wire,
                        key.name,
                        SyncJson.encodeToString(
                            RawSyncRecord(
                                state =
                                    RecordState(
                                        coll = SyncCollection.SETTING,
                                        fields =
                                            mapOf(
                                                "v" to
                                                    FieldState(
                                                        JsonPrimitive("15000"),
                                                        Hlc.of(now, 1, remote),
                                                    ),
                                            ),
                                    ),
                                settingHash =
                                    SettingBaseline(
                                        settingBaselineHash("15000"),
                                        Hlc.of(now, 1, remote).toString(),
                                    ),
                            ),
                        ),
                    ),
                )
                port.current[key.name] = "15000"

                capture(db, port).reconcile()

                assertNull(
                    db.syncOutboxDao().get("setting", key.name, "v"),
                    "an acknowledged value must not re-capture or echo",
                )
                assertTrue(port.applied.isEmpty())
            } finally {
                db.close()
            }
        }

    @Test
    fun aLocalMaterializedWriteAdvancesTheBaselineToTheIntentClock() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val port = FakeSettingsSyncPort()
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                capture(db, port).install()

                port.recordLocal(key, 20_000L)
                val intent = db.syncOutboxDao().get("setting", key.name, "v")
                assertNotNull(intent)
                port.commitLocal(key, 20_000L)
                port.emitLocal(key, 20_000L, now)

                val baseline = baselineOf(db, key)
                assertEquals(settingBaselineHash("20000"), baseline?.hash)
                assertEquals(Hlc(intent.hlc, NodeId(intent.nodeId)).toString(), baseline?.clock)
            } finally {
                db.close()
            }
        }

    @Test
    fun theBaselineHashIsCanonicalAcrossSetOrder() =
        runTest {
            val ordered = settingBaselineHash("""["a","b","c"]""")
            val shuffled = settingBaselineHash("""["c","a","b"]""")
            assertEquals(ordered, shuffled)
            assertEquals(settingBaselineHash("null"), settingBaselineHash("null"))
        }

    @Test
    fun anUnreadableStoredValueDoesNotRetireACommittedIntent() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val port = FakeSettingsSyncPort()
                val capture = capture(db, port)
                capture.install()
                port.recordLocal(key, 5000L)
                port.commitLocal(key, 5000L)
                port.emitLocal(key, 5000L, now)
                port.recordLocal(key, 20000L)
                val before = db.syncOutboxDao().get("setting", key.name, "v")!!
                port.current.remove(key.name)
                capture.reconcile()
                assertEquals(
                    before,
                    db.syncOutboxDao().get("setting", key.name, "v"),
                    "read failure must preserve the intent",
                )
            } finally {
                db.close()
            }
        }

    @Test
    fun anUnrecordedDifferenceFromAKnownBaselineCapturesTheActualValue() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val accepted = Hlc.of(now - 10000, 1, remote)
                db.syncClockDao().upsert(
                    SyncClockEntity(
                        "setting",
                        key.name,
                        SyncJson.encodeToString(
                            RawSyncRecord(
                                RecordState(
                                    SyncCollection.SETTING,
                                    fields =
                                        mapOf("v" to FieldState(JsonPrimitive(5000), accepted)),
                                ),
                                settingHash = SettingBaseline(settingBaselineHash("5000"), accepted.toString()),
                            ),
                        ),
                    ),
                )
                val port = FakeSettingsSyncPort().apply { current[key.name] = "7000" }
                capture(db, port).reconcile()
                val intent = db.syncOutboxDao().get("setting", key.name, "v")
                assertEquals("7000", intent?.value, "a real unrecorded edit must not be ignored forever")
                assertTrue(port.applied.isEmpty(), "the newer local value must not be rolled back")
            } finally {
                db.close()
            }
        }

    @Test
    fun anOffSwitchEditOfAKnownKeyYieldsToTheAccountOnEnable() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val accepted = Hlc.of(now - 10000, 1, remote)
                db.syncClockDao().upsert(
                    SyncClockEntity(
                        "setting",
                        key.name,
                        SyncJson.encodeToString(
                            RawSyncRecord(
                                RecordState(
                                    SyncCollection.SETTING,
                                    fields =
                                        mapOf("v" to FieldState(JsonPrimitive(9000), accepted)),
                                ),
                                settingHash = SettingBaseline(settingBaselineHash("25000")),
                            ),
                        ),
                    ),
                )
                val port = FakeSettingsSyncPort().apply { current[key.name] = "25000" }
                capture(db, port).reconcile()
                assertEquals<List<Pair<SettingKey<*>, String>>>(
                    listOf(key to "9000"),
                    port.applied,
                    "known server value wins on enable",
                )
                assertNull(db.syncOutboxDao().get("setting", key.name, "v"))
            } finally {
                db.close()
            }
        }

    @Test
    fun aRestartWhileOffStillTracksANewerWriteEqualToTheOldBaseline() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val port = FakeSettingsSyncPort().apply { current[key.name] = "5000" }
                var enabled = true
                val coordinator = TestSettingsWriteCoordinator()
                val first = SettingsCapture(db, port, coordinator) { enabled }
                first.install()
                port.recordLocal(key, 5000L)
                port.commitLocal(key, 5000L)
                port.emitLocal(key, 5000L, now)
                port.recordLocal(key, 20000L)
                first.clear()
                enabled = false
                val restarted = SettingsCapture(db, port, coordinator) { enabled }
                restarted.reconcile()
                port.recordLocal(key, 5000L)
                port.commitLocal(key, 5000L)
                port.emitLocal(key, 5000L, now + 1)
                enabled = true
                restarted.reconcile()
                assertTrue(port.applied.none { it.second == "20000" }, "the newer off-switch write must survive")
                assertEquals("5000", db.syncOutboxDao().get("setting", key.name, "v")?.value)
            } finally {
                db.close()
            }
        }

    @Test
    fun aWriteAfterOutboxSelectionMustMaterializeBeforeItCanBeSent() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val port = FakeSettingsSyncPort().apply { current[key.name] = "5000" }
                val capture = capture(db, port)
                capture.install()
                val selected = db.syncOutboxDao().rows()
                port.recordLocal(key, 20000L)
                val intent = db.syncOutboxDao().get("setting", key.name, "v")!!
                port.remoteResult = false
                assertTrue(capture.prepareForPush(selected, firstLink = false).none { it.value == "20000" })
                assertEquals(intent, db.syncOutboxDao().get("setting", key.name, "v"))
                port.remoteResult = true
                val ready = capture.prepareForPush(selected, firstLink = false).single { it.rid == key.name }
                assertEquals(intent, ready)
                assertEquals("20000", port.current[key.name])
            } finally {
                db.close()
            }
        }

    @Test
    fun anAcceptedResetDoesNotRecaptureItsEffectiveDefaultOnRestart() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val clock = Hlc.of(now, 1, remote)
                db.syncClockDao().upsert(
                    SyncClockEntity(
                        "setting",
                        key.name,
                        SyncJson.encodeToString(
                            RawSyncRecord(
                                RecordState(
                                    SyncCollection.SETTING,
                                    fields =
                                        mapOf("v" to FieldState(kotlinx.serialization.json.JsonNull, clock)),
                                ),
                                settingHash = SettingBaseline(settingBaselineHash("null"), clock.toString()),
                            ),
                        ),
                    ),
                )
                val port = FakeSettingsSyncPort().apply { current[key.name] = encode(key, key.default)!! }
                capture(db, port).reconcile()
                assertNull(db.syncOutboxDao().get("setting", key.name, "v"))
                assertTrue(port.applied.isEmpty())
            } finally {
                db.close()
            }
        }

    @Test
    fun anOffWriteMatchingAnIntentDoesNotBorrowItsClock() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val accepted = Hlc.of(now, 1, remote)
                seedKnown(db, key, "9000", accepted)
                db.syncStateDao().raiseHlc(accepted.packed)
                val port = FakeSettingsSyncPort().apply { current[key.name] = "9000" }
                var enabled = true
                val capture = capture(db, port, settingsSyncEnabled = { enabled })
                capture.install()
                port.recordLocal(key, 15000L)
                enabled = false
                capture.reconcile()
                port.recordLocal(key, 15000L)
                port.commitLocal(key, 15000L)
                port.emitLocal(key, 15000L, now)
                assertNull(baselineOf(db, key)?.clock, "an off-write is not the earlier captured write")
            } finally {
                db.close()
            }
        }

    @Test
    fun aClocklessBaselineSupersedesAnEqualOldIntent() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val accepted = Hlc.of(now, 1, remote)
                seedKnown(db, key, "9000", accepted, SettingBaseline(settingBaselineHash("15000")))
                val old = Hlc.of(now, 2, localNode)
                db.syncOutboxDao().upsert(
                    SyncOutboxEntity(
                        "setting",
                        key.name,
                        "v",
                        old.packed,
                        old.nodeId.value,
                        SyncCaptureKind.LOCAL,
                        "15000",
                    ),
                )
                val port = FakeSettingsSyncPort().apply { current[key.name] = "15000" }
                capture(db, port).reconcile()
                assertEquals("9000", port.current[key.name], "the account must win over the equal off-edit")
                assertNull(db.syncOutboxDao().get("setting", key.name, "v"))
            } finally {
                db.close()
            }
        }

    @Test
    fun aFailedIntentDoesNotTurnExternalDriftIntoAnOffWrite() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val accepted = Hlc.of(now, 1, remote)
                seedKnown(db, key, "9000", accepted)
                val failed = Hlc.of(now, 2, localNode)
                db.syncOutboxDao().upsert(
                    SyncOutboxEntity(
                        "setting",
                        key.name,
                        "v",
                        failed.packed,
                        failed.nodeId.value,
                        SyncCaptureKind.LOCAL,
                        "12000",
                    ),
                )
                val port = FakeSettingsSyncPort().apply { current[key.name] = "7000" }
                capture(db, port).reconcile()
                assertEquals("7000", db.syncOutboxDao().get("setting", key.name, "v")?.value)
                assertTrue(port.applied.isEmpty(), "external edits must not be overwritten as if made while off")
            } finally {
                db.close()
            }
        }

    @Test
    fun externalDriftMatchingAnOlderIntentGetsAFreshClock() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val accepted = Hlc.of(now, 2, remote)
                seedKnown(db, key, "9000", accepted)
                db.syncStateDao().raiseHlc(accepted.packed)
                val old = Hlc.of(now, 1, localNode)
                db.syncOutboxDao().upsert(
                    SyncOutboxEntity(
                        "setting",
                        key.name,
                        "v",
                        old.packed,
                        old.nodeId.value,
                        SyncCaptureKind.LOCAL,
                        "15000",
                    ),
                )
                val port = FakeSettingsSyncPort().apply { current[key.name] = "15000" }
                capture(db, port).reconcile()
                val fresh = assertNotNull(db.syncOutboxDao().get("setting", key.name, "v"))
                assertEquals("15000", fresh.value)
                assertTrue(Hlc(fresh.hlc, NodeId(fresh.nodeId)) > old, "the restored edit must be newer")
                assertTrue(port.applied.isEmpty())
            } finally {
                db.close()
            }
        }

    @Test
    fun anOnEdgeWriteCannotEscapeTheInstalledRecorder() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                seedKnown(db, key, "9000", Hlc.of(now - 1000, 3, remote))
                db.syncStateDao().raiseHlc(Hlc.of(now - 1000, 3, remote).packed)
                var enabled = false
                val port = FakeSettingsSyncPort().apply { current[key.name] = "9000" }
                val capture = SettingsCapture(db, port, TestSettingsWriteCoordinator()) { enabled }
                capture.reconcile()
                enabled = true
                port.recordLocal(key, 20000L)
                port.commitLocal(key, 20000L)
                port.emitLocal(key, 20000L, now)
                capture.reconcile()
                assertEquals("20000", port.current[key.name])
                assertEquals("20000", db.syncOutboxDao().get("setting", key.name, "v")?.value)
            } finally {
                db.close()
            }
        }

    @Test
    fun anOffAdmittedWriteCannotBorrowAClockAfterEnabling() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                seedKnown(db, key, "9000", Hlc.of(now - 1000, 3, remote))
                db.syncStateDao().raiseHlc(Hlc.of(now - 1000, 3, remote).packed)
                var enabled = true
                val port = FakeSettingsSyncPort().apply { current[key.name] = "9000" }
                val capture = SettingsCapture(db, port, TestSettingsWriteCoordinator()) { enabled }
                capture.install()
                port.recordLocal(key, 15000L)
                enabled = false
                capture.reconcile()
                port.recordLocal(key, 15000L)
                enabled = true
                port.commitLocal(key, 15000L)
                port.emitLocal(key, 15000L, now)
                capture.reconcile()
                assertEquals("9000", port.current[key.name])
                assertNull(db.syncOutboxDao().get("setting", key.name, "v"))
            } finally {
                db.close()
            }
        }

    @Test
    fun anOnAdmittedWriteKeepsItsClockAfterDisabling() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                seedKnown(db, key, "9000", Hlc.of(now - 1000, 3, remote))
                db.syncStateDao().raiseHlc(Hlc.of(now - 1000, 3, remote).packed)
                var enabled = true
                val port = FakeSettingsSyncPort().apply { current[key.name] = "9000" }
                val capture = SettingsCapture(db, port, TestSettingsWriteCoordinator()) { enabled }
                capture.install()
                port.recordLocal(key, 15000L)
                val intent = db.syncOutboxDao().get("setting", key.name, "v")!!
                enabled = false
                capture.reconcile()
                port.commitLocal(key, 15000L)
                port.emitLocal(key, 15000L, now)
                assertEquals(Hlc(intent.hlc, NodeId(intent.nodeId)).toString(), baselineOf(db, key)?.clock)
                enabled = true
                capture.reconcile()
                assertEquals("15000", port.current[key.name])
                assertEquals(intent, db.syncOutboxDao().get("setting", key.name, "v"))
            } finally {
                db.close()
            }
        }

    @Test
    fun anOffWriteSurvivesACrashBeforeItsRoomCallback() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                seedKnown(db, key, "9000", Hlc.of(now - 1000, 3, remote))
                db.syncStateDao().raiseHlc(Hlc.of(now - 1000, 3, remote).packed)
                var enabled = true
                val port = FakeSettingsSyncPort().apply { current[key.name] = "9000" }
                val capture = SettingsCapture(db, port, TestSettingsWriteCoordinator()) { enabled }
                capture.install()
                port.recordLocal(key, 15000L)
                enabled = false
                capture.reconcile()
                port.recordLocal(key, 15000L)
                port.commitLocal(key, 15000L)
                // DataStore committed, but no Room callback ran before the process died.
                val restarted = SettingsCapture(db, port, TestSettingsWriteCoordinator()) { enabled }
                restarted.reconcile()
                enabled = true
                restarted.reconcile()
                assertEquals("9000", port.current[key.name])
                assertNull(db.syncOutboxDao().get("setting", key.name, "v"))
            } finally {
                db.close()
            }
        }

    @Test
    fun aFailedOffWriteCannotEraseAPreviousCommittedOnIntent() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                seedKnown(db, key, "9000", Hlc.of(now - 1000, 3, remote))
                db.syncStateDao().raiseHlc(Hlc.of(now - 1000, 3, remote).packed)
                var enabled = true
                val port = FakeSettingsSyncPort().apply { current[key.name] = "9000" }
                val capture = SettingsCapture(db, port, TestSettingsWriteCoordinator()) { enabled }
                capture.install()
                port.recordLocal(key, 15000L)
                val intent = db.syncOutboxDao().get("setting", key.name, "v")!!
                enabled = false
                capture.reconcile()
                port.recordLocal(key, 20000L)
                // This DataStore write fails: neither the stored value nor its receipt changes.
                enabled = true
                capture.reconcile()
                assertEquals("15000", port.current[key.name])
                assertEquals(intent, db.syncOutboxDao().get("setting", key.name, "v"))
            } finally {
                db.close()
            }
        }

    @Test
    fun aCapturedOnWriteSurvivesAnOlderClocklessBaseline() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val accepted = Hlc.of(now - 1000, 3, remote)
                seedKnown(db, key, "9000", accepted)
                db.syncStateDao().raiseHlc(accepted.packed)
                var enabled = false
                val port = FakeSettingsSyncPort().apply { current[key.name] = "9000" }
                val capture = SettingsCapture(db, port, TestSettingsWriteCoordinator()) { enabled }
                capture.reconcile()
                port.recordLocal(key, 20000L)
                port.commitLocal(key, 20000L)
                port.emitLocal(key, 20000L, now)
                assertNull(baselineOf(db, key)?.clock)
                enabled = true
                port.recordLocal(key, 15000L)
                val intent = db.syncOutboxDao().get("setting", key.name, "v")!!
                port.commitLocal(key, 15000L)
                // Crash before the callback: the durable receipt supersedes the old OFF baseline.
                SettingsCapture(db, port, TestSettingsWriteCoordinator()) { enabled }.reconcile()
                assertEquals("15000", port.current[key.name])
                assertEquals(intent, db.syncOutboxDao().get("setting", key.name, "v"))
                assertEquals(Hlc(intent.hlc, NodeId(intent.nodeId)).toString(), baselineOf(db, key)?.clock)
            } finally {
                db.close()
            }
        }

    @Test
    fun aReceiptFromAnotherLinkCannotBecomeANewAccountEdit() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val port = FakeSettingsSyncPort().apply { current[key.name] = "9000" }
                val capture = capture(db, port)
                capture.install()
                port.recordLocal(key, 15000L)
                port.commitLocal(key, 15000L)
                db.syncStateDao().unlink()
                link(db)
                db.syncStateDao().upsert(
                    db.syncStateDao().get()!!.copy(nodeId = "0000000000000003", accountId = "new-account"),
                )
                seedKnown(db, key, "7000", Hlc.of(now, 5, remote))
                db.syncStateDao().raiseHlc(Hlc.of(now, 5, remote).packed)
                capture.reconcile()
                assertEquals("7000", port.current[key.name])
                assertNull(db.syncOutboxDao().get("setting", key.name, "v"))
            } finally {
                db.close()
            }
        }

    @Test
    fun offAdmissionNeedsNoRoomConnection() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val port = FakeSettingsSyncPort()
                val capture = SettingsCapture(db, port, TestSettingsWriteCoordinator()) { false }
                capture.reconcile()
                db.close()
                assertEquals(SettingWriteOrigin.Uncaptured, port.recordLocal(key, 15000L))
                port.commitLocal(key, 15000L)
                assertEquals("15000", port.currentSnapshot(key)?.json)
                assertEquals(SettingWriteOrigin.Uncaptured, port.currentSnapshot(key)?.origin)
            } finally {
                db.close()
            }
        }

    @Test
    fun aFailedOnWriteRecoversAfterAnOlderUncapturedWrite() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val accepted = Hlc.of(now - 1000, 3, remote)
                seedKnown(db, key, "9000", accepted)
                db.syncStateDao().raiseHlc(accepted.packed)
                var enabled = false
                val port = FakeSettingsSyncPort().apply { current[key.name] = "9000" }
                val capture = SettingsCapture(db, port, TestSettingsWriteCoordinator()) { enabled }
                capture.reconcile()
                port.recordLocal(key, 20000L)
                port.commitLocal(key, 20000L)
                port.emitLocal(key, 20000L, now)
                enabled = true
                port.recordLocal(key, 15000L)
                val intent = db.syncOutboxDao().get("setting", key.name, "v")!!
                // This ON DataStore write failed. The surviving UNC receipt belongs to an older write.
                SettingsCapture(db, port, TestSettingsWriteCoordinator()) { enabled }.reconcile()
                assertEquals("15000", port.current[key.name])
                assertEquals(intent, db.syncOutboxDao().get("setting", key.name, "v"))
            } finally {
                db.close()
            }
        }

    @Test
    fun bootstrapAdmissionSurvivesLinkCompletionBeforeAck() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                db.syncStateDao().upsert(db.syncStateDao().get()!!.copy(linkedAt = null))
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val port = FakeSettingsSyncPort()
                port.recordLocal(key, 20000L)
                port.commitLocal(key, 20000L)
                db.withWriteTransaction { db.syncOutboxDao().captureLiteral("setting", key.name, "v", "20000") }
                val intent = db.syncOutboxDao().get("setting", key.name, "v")!!
                val capture = capture(db, port)
                assertEquals(listOf(intent), capture.prepareForPush(listOf(intent), firstLink = true))
                db.syncStateDao().upsert(db.syncStateDao().get()!!.copy(linkedAt = now))
                capture.reconcile()
                assertEquals(intent, db.syncOutboxDao().get("setting", key.name, "v"), "keep the admitted clock")
            } finally {
                db.close()
            }
        }

    @Test
    fun aLaterOffWriteOfTheSameValueSupersedesTheNewIntent() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val accepted = Hlc.of(now - 1000, 3, remote)
                seedKnown(db, key, "9000", accepted)
                db.syncStateDao().raiseHlc(accepted.packed)
                var enabled = false
                val port = FakeSettingsSyncPort().apply { current[key.name] = "9000" }
                val capture = SettingsCapture(db, port, TestSettingsWriteCoordinator()) { enabled }
                capture.reconcile()
                port.recordLocal(key, 20000L)
                port.commitLocal(key, 20000L)
                port.emitLocal(key, 20000L, now)
                enabled = true
                port.recordLocal(key, 15000L)
                enabled = false
                port.recordLocal(key, 20000L)
                port.commitLocal(key, 20000L)
                // Same value, another physical OFF write, and no Room callback before the crash.
                enabled = true
                SettingsCapture(db, port, TestSettingsWriteCoordinator()) { enabled }.reconcile()
                assertEquals("9000", port.current[key.name])
                assertNull(db.syncOutboxDao().get("setting", key.name, "v"))
            } finally {
                db.close()
            }
        }

    @Test
    fun anUncapturedAccountAbsentValueCanBeAdmittedForPush() =
        runTest {
            val db = newTestDb()
            try {
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val port = FakeSettingsSyncPort()
                port.recordLocal(key, 20000L)
                port.commitLocal(key, 20000L)
                link(db)
                val capture = capture(db, port)
                capture.install()
                val intent = db.syncOutboxDao().get("setting", key.name, "v")!!
                assertEquals(listOf(intent), capture.prepareForPush(listOf(intent), firstLink = false))
            } finally {
                db.close()
            }
        }

    @Test
    fun aRecordedRestoreAfterAnOffEditKeepsItsHistoricalIntent() = restoreAfterOffEdit(RestoreStore.COMMITTED)

    @Test
    fun aFailedRestoreAfterAnOffEditRecoversWithItsHistoricalIntent() = restoreAfterOffEdit(RestoreStore.FAILED)

    @Test
    fun aRestoreCommittedBeforeItsStoreWriteRecoversAfterAnOffEdit() = restoreAfterOffEdit(RestoreStore.NOT_STARTED)

    private enum class RestoreStore { COMMITTED, FAILED, NOT_STARTED }

    private fun restoreAfterOffEdit(write: RestoreStore) =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val accepted = Hlc.of(1_600_000_000_000, 3, remote)
                seedKnown(db, key, "9000", accepted)
                db.syncStateDao().raiseHlc(accepted.packed)
                var enabled = true
                val port = FakeSettingsSyncPort().apply { current[key.name] = "9000" }
                val capture = SettingsCapture(db, port, TestSettingsWriteCoordinator()) { enabled }
                capture.install()
                enabled = false
                capture.reconcile()
                port.recordLocal(key, 25000L)
                port.commitLocal(key, 25000L)
                port.emitLocal(key, 25000L, now)
                val before = port.currentSnapshot(key)!!
                val origin =
                    db.withWriteTransaction {
                        assertTrue(db.syncOutboxDao().captureAt("setting", key.name, "v", 1_700_000_000_000, "15000"))
                        port.recordCommitted(key, "15000", before)
                    }
                val original = db.syncOutboxDao().get("setting", key.name, "v")!!
                port.remoteResult = write == RestoreStore.COMMITTED
                if (write != RestoreStore.NOT_STARTED) {
                    assertEquals(write == RestoreStore.COMMITTED, port.applyRecorded(key, "15000", origin))
                }
                if (write != RestoreStore.COMMITTED) assertEquals("25000", port.current[key.name])
                port.remoteResult = true
                enabled = true
                val recovered = SettingsCapture(db, port, TestSettingsWriteCoordinator()) { enabled }
                recovered.reconcile()
                assertEquals("15000", port.current[key.name])
                assertEquals(original, db.syncOutboxDao().get("setting", key.name, "v"))
                assertEquals(listOf(original), recovered.prepareForPush(listOf(original), firstLink = false))
            } finally {
                db.close()
            }
        }

    @Test
    fun typedFloatNormalizationKeepsTheHistoricalRestoreClock() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val key = PlaybackSettingKeys.SPEED
                val accepted = Hlc.of(1_600_000_000_000, 3, remote)
                seedKnown(db, key, "2.0", accepted)
                db.syncStateDao().raiseHlc(accepted.packed)
                val delegate = FakeSettingsSyncPort().apply { current[key.name] = "2.0" }
                // The real DataStore adapter decodes a Float32 JSON integer 1 as Float 1.0.
                val typed =
                    object : SettingsSyncPort by delegate {
                        override suspend fun applyRecorded(
                            key: SettingKey<*>,
                            json: String,
                            origin: ch.lkmc.neutrodyne.core.domain.SettingWriteOrigin,
                        ): Boolean =
                            delegate.applyRecorded(
                                key,
                                if (json == "null") json else JsonPrimitive(json.toFloat()).toString(),
                                origin,
                            )
                    }
                val capture = SettingsCapture(db, typed, TestSettingsWriteCoordinator()) { true }
                capture.install()
                val before = typed.currentSnapshot(key)!!
                val origin =
                    db.withWriteTransaction {
                        assertTrue(db.syncOutboxDao().captureAt("setting", key.name, "v", 1_700_000_000_000, "1"))
                        typed.recordCommitted(key, "1", before)
                    }
                val historical = db.syncOutboxDao().get("setting", key.name, "v")!!
                assertTrue(typed.applyRecorded(key, "1", origin))
                assertEquals("1.0", typed.currentJson(key))
                capture.reconcile()
                assertEquals(
                    historical,
                    db.syncOutboxDao().get("setting", key.name, "v"),
                    "valid typed Float32 normalization must not give a historical restore a new clock",
                )
            } finally {
                db.close()
            }
        }

    @Test
    fun aRemoteFloatLiteralDoesNotBecomeAnUnrecordedLocalEdit() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val key = PlaybackSettingKeys.SPEED
                val accepted = Hlc.of(1_700_000_000_000, 3, remote)
                seedKnown(db, key, "1", accepted)
                val row = db.syncClockDao().get("setting", key.name)!!
                val raw = SyncJson.decodeFromString<RawSyncRecord>(row.clocks)
                db.syncClockDao().upsert(
                    row.copy(
                        clocks =
                            SyncJson.encodeToString(
                                raw.copy(pendingSetting = PendingSetting("1", accepted.toString())),
                            ),
                    ),
                )
                val delegate = FakeSettingsSyncPort().apply { current[key.name] = "2.0" }
                val typed =
                    object : SettingsSyncPort by delegate {
                        override suspend fun applyRemote(
                            key: SettingKey<*>,
                            json: String,
                        ): Boolean {
                            val stored = if (json == "null") json else JsonPrimitive(json.toFloat()).toString()
                            return delegate.applyRemote(key, stored)
                        }
                    }
                val locks = TestSettingsWriteCoordinator()
                SyncApplyEffects(db, typed, locks) { true }.drainSettings()
                assertEquals("1.0", typed.currentJson(key))
                SettingsCapture(db, typed, locks) { true }.reconcile()
                assertNull(db.syncOutboxDao().get("setting", key.name, "v"))
                val finalRow = db.syncClockDao().get("setting", key.name)!!
                val finalRaw = SyncJson.decodeFromString<RawSyncRecord>(finalRow.clocks)
                assertEquals(accepted, finalRaw.state.fields["v"]!!.c)
                assertEquals(SettingBaseline(settingBaselineHash("1.0"), accepted.toString()), finalRaw.settingHash)
            } finally {
                db.close()
            }
        }

    private suspend fun seedKnown(
        db: NeutrodyneDatabase,
        key: SettingKey<*>,
        json: String,
        clock: Hlc,
        baseline: SettingBaseline = SettingBaseline(settingBaselineHash(json), clock.toString()),
    ) {
        db.syncClockDao().upsert(
            SyncClockEntity(
                "setting",
                key.name,
                SyncJson.encodeToString(
                    RawSyncRecord(
                        RecordState(
                            SyncCollection.SETTING,
                            fields =
                                mapOf(
                                    "v" to
                                        FieldState(SyncJson.parseToJsonElement(json), clock),
                                ),
                        ),
                        settingHash = baseline,
                    ),
                ),
            ),
        )
    }

    /** The linked fixture: `enabled` plus `linkedAt`, as `SyncStateDao.enableLinked` writes. */
    private suspend fun link(db: NeutrodyneDatabase) {
        db.withWriteTransaction {
            db
                .syncStateDao()
                .upsert(
                    SyncStateEntity(
                        enabled = true,
                        nodeId = localNode.value,
                        hlc = 1L shl 16,
                        linkedAt = 1L,
                    ),
                )
        }
    }

    private fun capture(
        db: NeutrodyneDatabase,
        port: SettingsSyncPort,
        coordinator: SettingsWriteCoordinator = TestSettingsWriteCoordinator(),
        settingsSyncEnabled: suspend () -> Boolean = { true },
    ): SettingsCapture = SettingsCapture(db, port, coordinator, settingsSyncEnabled)

    private suspend fun baselineOf(
        db: NeutrodyneDatabase,
        key: SettingKey<*>,
    ): SettingBaseline? = rawOf(db, key)?.settingHash

    private suspend fun pendingOf(
        db: NeutrodyneDatabase,
        key: SettingKey<*>,
    ): PendingSetting? = rawOf(db, key)?.pendingSetting

    private suspend fun rawOf(
        db: NeutrodyneDatabase,
        key: SettingKey<*>,
    ): RawSyncRecord? {
        val row = db.syncClockDao().get(SyncCollection.SETTING.wire, key.name) ?: return null
        return runCatching { SyncJson.decodeFromString<RawSyncRecord>(row.clocks) }.getOrNull()
    }

    private suspend fun seedPending(
        db: NeutrodyneDatabase,
        key: SettingKey<*>,
        value: String,
        clock: Hlc,
    ) {
        db.syncClockDao().upsert(
            SyncClockEntity(
                SyncCollection.SETTING.wire,
                key.name,
                SyncJson.encodeToString(
                    RawSyncRecord(
                        state =
                            RecordState(
                                coll = SyncCollection.SETTING,
                                fields = mapOf("v" to FieldState(JsonPrimitive(value), clock)),
                            ),
                        pendingSetting = PendingSetting(value, clock.toString()),
                    ),
                ),
            ),
        )
    }

    /** The host's real in-memory Room database; the Android host supplies a Robolectric context. */
    protected abstract fun newTestDb(): NeutrodyneDatabase
}
