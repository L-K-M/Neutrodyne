// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.database.SyncClockEntity
import ch.lkmc.neutrodyne.core.database.SyncOutboxEntity
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import ch.lkmc.neutrodyne.core.domain.NoPlaybackSync
import ch.lkmc.neutrodyne.core.domain.SettingsSyncPort
import ch.lkmc.neutrodyne.core.domain.SettingsWriteCoordinator
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsSyncPort
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.sync.protocol.FieldState
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import ch.lkmc.neutrodyne.sync.protocol.dto.FieldValue
import ch.lkmc.neutrodyne.sync.protocol.dto.RecordDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 10 Durable apply effects and Settings capture. */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncApplyEffectsTest {
    private val now = 1_800_000_000_000L
    private val remote = NodeId("0000000000000002")
    private val deviceId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"

    @Test
    fun aRemoteSettingIsJournaledWithTheCursorAndDrainedAfterCommit() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val port = FakeSettingsSyncPort()
                val applier = applier(db, port)
                applier.applyPage(settingPage(PlaybackSettingKeys.SKIP_BACK_MS.name, "15000"), roundId = now)

                // The page committed the cursor; the DataStore write is still pending.
                val pending = pendingSetting(db, PlaybackSettingKeys.SKIP_BACK_MS.name)
                assertEquals("15000", pending)

                SyncApplyEffects(db, port, TestSettingsWriteCoordinator()).drainSettings()
                assertEquals(1, port.applied.size)
                assertEquals(PlaybackSettingKeys.SKIP_BACK_MS, port.applied.single().first)
                assertNull(pendingSetting(db, PlaybackSettingKeys.SKIP_BACK_MS.name))
            } finally {
                db.close()
            }
        }

    @Test
    fun aFailedDataStoreWriteKeepsTheMarkerForTheNextDrain() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val port = FakeSettingsSyncPort()
                val applier = applier(db, port)
                applier.applyPage(settingPage(PlaybackSettingKeys.SKIP_BACK_MS.name, "15000"), roundId = now)

                port.remoteResult = false
                SyncApplyEffects(db, port, TestSettingsWriteCoordinator()).drainSettings()
                assertEquals("15000", pendingSetting(db, PlaybackSettingKeys.SKIP_BACK_MS.name))

                port.remoteResult = true
                SyncApplyEffects(db, port, TestSettingsWriteCoordinator()).drainSettings()
                assertNull(pendingSetting(db, PlaybackSettingKeys.SKIP_BACK_MS.name))
            } finally {
                db.close()
            }
        }

    @Test
    fun aLocalSettingChangeRecordsItsLiteralIntentBeforeDataStore() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val port = FakeSettingsSyncPort()
                SettingsCapture(db, port).install()

                db.withWriteTransaction {
                    port.recordLocal(PlaybackSettingKeys.SKIP_BACK_MS, 20_000L)
                }
                val row = db.syncOutboxDao().get("setting", PlaybackSettingKeys.SKIP_BACK_MS.name, "v")
                assertEquals("20000", row?.value)
            } finally {
                db.close()
            }
        }

    @Test
    fun aNewerLocalSetBeforeDrainIsNotAppliedOverThePendingRemoteValue() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val port = FakeSettingsSyncPort()
                SettingsCapture(db, port).install()
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val remoteClock = Hlc.of(1_577_836_800_000L, 1, remote)
                seedPending(db, key, "15000", remoteClock)
                // The capture tick must be able to raise the local clock past the remote one.
                db.syncStateDao().upsert(
                    SyncStateEntity(
                        enabled = true,
                        nodeId = "0000000000000001",
                        hlc = remoteClock.packed,
                        linkedAt = 1L,
                    ),
                )

                port.recordLocal(key, 20_000L)

                SyncApplyEffects(db, port, TestSettingsWriteCoordinator()).drainSettings()

                assertTrue(
                    port.applied.isEmpty(),
                    "the newer local set must win over the stale remote marker",
                )
                assertEquals("15000", pendingSetting(db, key.name))
                val local = db.syncOutboxDao().get("setting", key.name, "v")
                assertNotNull(local)
                assertTrue(Hlc(local.hlc, NodeId(local.nodeId)) > remoteClock)
            } finally {
                db.close()
            }
        }

    @Test
    fun aNewerLocalResetBeforeDrainIsNotAppliedOverThePendingRemoteValue() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val port = FakeSettingsSyncPort()
                SettingsCapture(db, port).install()
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val remoteClock = Hlc.of(1_577_836_800_000L, 1, remote)
                seedPending(db, key, "15000", remoteClock)
                db.syncStateDao().upsert(
                    SyncStateEntity(
                        enabled = true,
                        nodeId = "0000000000000001",
                        hlc = remoteClock.packed,
                        linkedAt = 1L,
                    ),
                )

                port.recordLocal(key, null)

                SyncApplyEffects(db, port, TestSettingsWriteCoordinator()).drainSettings()

                assertTrue(
                    port.applied.isEmpty(),
                    "the newer local reset must win over the stale remote marker",
                )
                assertEquals("15000", pendingSetting(db, key.name))
                val local = db.syncOutboxDao().get("setting", key.name, "v")
                assertNotNull(local)
                assertEquals("null", local.value)
                assertTrue(Hlc(local.hlc, NodeId(local.nodeId)) > remoteClock)
            } finally {
                db.close()
            }
        }

    @Test
    fun aLocalSetDuringDrainIsSerializedAndLeavesTheNewestStoredIntent() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val port = StoringSettingsSyncPort()
                SettingsCapture(db, port).install()
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val remoteClock = Hlc.of(1_577_836_800_000L, 1, remote)
                seedPending(db, key, "15000", remoteClock)
                db.syncStateDao().upsert(
                    SyncStateEntity(
                        enabled = true,
                        nodeId = "0000000000000001",
                        hlc = remoteClock.packed,
                        linkedAt = 1L,
                    ),
                )

                val coordinator = TestSettingsWriteCoordinator()
                val applyReached = CompletableDeferred<Unit>()
                val releaseApply = CompletableDeferred<Unit>()
                port.applyReached = applyReached
                port.releaseApply = releaseApply

                val drainJob =
                    backgroundScope.launch {
                        SyncApplyEffects(db, port, coordinator).drainSettings()
                    }
                applyReached.await()

                val localJob =
                    backgroundScope.launch {
                        coordinator.withLock(key) {
                            port.recordLocal(key, 20_000L)
                            port.storeLocal(key, "20000")
                        }
                    }
                val secondAttempted = withTimeoutOrNull(1_000) { coordinator.secondAttempt.await() }
                assertTrue(secondAttempted != null, "the local write must reach the per-key lock")

                releaseApply.complete(Unit)
                drainJob.join()
                localJob.join()

                assertEquals(1, port.applied.size)
                assertEquals("15000", port.applied.single().second)
                assertEquals("20000", port.stored[key.name])
                val local = db.syncOutboxDao().get("setting", key.name, "v")
                assertNotNull(local)
                assertTrue(Hlc(local.hlc, NodeId(local.nodeId)) > remoteClock)
                assertNull(pendingSetting(db, key.name))
            } finally {
                db.close()
            }
        }

    @Test
    fun anIdenticalLocalIntentAtThePendingClockStillAppliesAfterAStorageFailure() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val port = FakeSettingsSyncPort()
                SettingsCapture(db, port).install()
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val clock = Hlc.of(1_577_836_800_000L, 1, remote)
                seedPending(db, key, "15000", clock)
                seedLocal(db, key, "15000", clock)

                port.remoteResult = false
                SyncApplyEffects(db, port, TestSettingsWriteCoordinator()).drainSettings()
                assertEquals("15000", pendingSetting(db, key.name))
                assertEquals(1, port.applied.size)

                port.remoteResult = true
                SyncApplyEffects(db, port, TestSettingsWriteCoordinator()).drainSettings()
                assertNull(pendingSetting(db, key.name))
                assertEquals(2, port.applied.size)
            } finally {
                db.close()
            }
        }

    @Test
    fun aLocalIntentWithANewerNodeAtAnEqualPackedClockWins() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val port = FakeSettingsSyncPort()
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                seedPending(db, key, "15000", Hlc.of(now, 1, NodeId("0000000000000002")))
                seedLocal(db, key, "20000", Hlc.of(now, 1, NodeId("0000000000000003")))

                SyncApplyEffects(db, port, TestSettingsWriteCoordinator()).drainSettings()

                assertTrue(port.applied.isEmpty())
            } finally {
                db.close()
            }
        }

    @Test
    fun aLocalIntentWithAnOlderNodeAtAnEqualPackedClockLoses() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val port = FakeSettingsSyncPort()
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                seedPending(db, key, "15000", Hlc.of(now, 1, NodeId("0000000000000002")))
                seedLocal(db, key, "20000", Hlc.of(now, 1, NodeId("0000000000000001")))

                SyncApplyEffects(db, port, TestSettingsWriteCoordinator()).drainSettings()

                assertEquals(1, port.applied.size)
            } finally {
                db.close()
            }
        }

    @Test
    fun aLocalIntentWithANewerPackedClockWinsRegardlessOfNode() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val port = FakeSettingsSyncPort()
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                seedPending(db, key, "15000", Hlc.of(now, 1, NodeId("0000000000000003")))
                seedLocal(db, key, "20000", Hlc.of(now + 1, 0, NodeId("0000000000000001")))

                SyncApplyEffects(db, port, TestSettingsWriteCoordinator()).drainSettings()

                assertTrue(port.applied.isEmpty())
            } finally {
                db.close()
            }
        }

    @Test
    fun anAckDoesNotStompANewerPendingMarkerOrMetadata() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val port = StoringSettingsSyncPort()
                SettingsCapture(db, port).install()
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val pendingClock = Hlc.of(1_577_836_800_000L, 1, remote)
                seedPending(db, key, "15000", pendingClock)
                val newerClock = Hlc.of(1_577_836_800_001L, 0, remote)
                port.onApply = { _, _ ->
                    db.withWriteTransaction {
                        val row = db.syncClockDao().get("setting", key.name)!!
                        val raw = SyncJson.decodeFromString<RawSyncRecord>(row.clocks)
                        db.syncClockDao().upsert(
                            row.copy(
                                clocks =
                                    SyncJson.encodeToString(
                                        raw.copy(
                                            by = "newer-writer",
                                            pendingSetting = PendingSetting("99999", newerClock.toString()),
                                        ),
                                    ),
                            ),
                        )
                    }
                }

                SyncApplyEffects(db, port, TestSettingsWriteCoordinator()).drainSettings()

                assertEquals(1, port.applied.size)
                val row = db.syncClockDao().get("setting", key.name)!!
                val raw = SyncJson.decodeFromString<RawSyncRecord>(row.clocks)
                assertEquals("newer-writer", raw.by)
                assertEquals("99999", raw.pendingSetting?.value)
                assertEquals(newerClock.toString(), raw.pendingSetting?.clock)
            } finally {
                db.close()
            }
        }

    private fun applier(
        db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase,
        port: FakeSettingsSyncPort,
    ): SyncApplier =
        SyncApplier(
            db = db,
            clock = TestClock(now),
            secrets = FakeSecretStore(),
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
            sharePasswords = { false },
        )

    private suspend fun link(db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase) {
        db.withWriteTransaction {
            db
                .syncStateDao()
                .upsert(SyncStateEntity(enabled = true, nodeId = "0000000000000001", hlc = 1L shl 16))
        }
    }

    private fun settingPage(
        key: String,
        value: String,
    ): List<RecordDto> =
        listOf(
            RecordDto(
                coll = "setting",
                id = JsonPrimitive(key),
                seq = 1,
                by = deviceId,
                fields = mapOf("v" to FieldValue(JsonPrimitive(value), Hlc.of(now, 1, remote).toString())),
            ),
        )

    private suspend fun pendingSetting(
        db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase,
        key: String,
    ): String? {
        val row = db.syncClockDao().get("setting", key) ?: return null
        val raw = runCatching { SyncJson.decodeFromString<RawSyncRecord>(row.clocks) }.getOrNull()
        return raw?.pendingSetting?.value
    }
}

/** The production per-key mutex contract, implemented locally because `:core:data` is off-limits. */
private class TestSettingsWriteCoordinator : SettingsWriteCoordinator {
    private val pool = Mutex()
    private val locks = mutableMapOf<String, Mutex>()
    val secondAttempt = CompletableDeferred<Unit>()
    private var attempts = 0

    override suspend fun <T> withLock(
        key: SettingKey<*>,
        block: suspend () -> T,
    ): T {
        if (synchronized(this) { ++attempts } > 1) secondAttempt.complete(Unit)
        val mutex = pool.withLock { locks.getOrPut(key.name) { Mutex() } }
        return mutex.withLock { block() }
    }
}

/** A [FakeSettingsSyncPort] with a DataStore-visible value map and an apply-time hook. */
private class StoringSettingsSyncPort(
    private val delegate: FakeSettingsSyncPort = FakeSettingsSyncPort(),
) : SettingsSyncPort by delegate {
    val applied: List<Pair<SettingKey<*>, String>> get() = delegate.applied
    val stored = mutableMapOf<String, String>()
    var applyReached: CompletableDeferred<Unit>? = null
    var releaseApply: CompletableDeferred<Unit>? = null
    var onApply: (suspend (SettingKey<*>, String) -> Unit)? = null

    override suspend fun applyRemote(
        key: SettingKey<*>,
        json: String,
    ): Boolean {
        onApply?.invoke(key, json)
        val reached = applyReached
        val release = releaseApply
        if (reached != null && release != null) {
            reached.complete(Unit)
            release.await()
        }
        val result = delegate.applyRemote(key, json)
        if (result) stored[key.name] = json
        return result
    }

    /** Stands in for the local repository's DataStore write after the Room intent commits. */
    suspend fun storeLocal(
        key: SettingKey<*>,
        json: String,
    ) {
        stored[key.name] = json
    }
}

private suspend fun seedPending(
    db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase,
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

private suspend fun seedLocal(
    db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase,
    key: SettingKey<*>,
    value: String,
    clock: Hlc,
) {
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
