// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncClockEntity
import ch.lkmc.neutrodyne.core.database.SyncOutboxEntity
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.CredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import ch.lkmc.neutrodyne.core.domain.NoPlaybackSync
import ch.lkmc.neutrodyne.core.domain.SecretStore
import ch.lkmc.neutrodyne.core.domain.SettingsSyncPort
import ch.lkmc.neutrodyne.core.domain.SettingsWriteCoordinator
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.model.settings.DiagnosticsSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.GroupsSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.testing.FakeCredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsSyncPort
import ch.lkmc.neutrodyne.core.testing.TestClock
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 10 Durable apply effects and Settings capture. */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class SyncApplyEffectsTestBase {
    private val now = 1_800_000_000_000L
    private val remote = NodeId("0000000000000002")
    private val deviceId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"

    @Test
    fun aRemoteSettingIsJournaledWithTheCursorAndDrainedAfterCommit() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val port = FakeSettingsSyncPort()
                val applier = applier(db, port)
                applier.applyPage(
                    settingPage(PlaybackSettingKeys.SKIP_BACK_MS.name, JsonPrimitive(15000L)),
                    roundId = now,
                )

                // The page committed the cursor; the DataStore write is still pending.
                val pending = pendingSetting(db, PlaybackSettingKeys.SKIP_BACK_MS.name)
                assertEquals("15000", pending)

                effects(db, port).drainSettings()
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
            val db = newTestDb()
            try {
                link(db)
                val port = FakeSettingsSyncPort()
                val applier = applier(db, port)
                applier.applyPage(
                    settingPage(PlaybackSettingKeys.SKIP_BACK_MS.name, JsonPrimitive(15000L)),
                    roundId = now,
                )

                port.remoteResult = false
                effects(db, port).drainSettings()
                assertEquals("15000", pendingSetting(db, PlaybackSettingKeys.SKIP_BACK_MS.name))

                port.remoteResult = true
                effects(db, port).drainSettings()
                assertNull(pendingSetting(db, PlaybackSettingKeys.SKIP_BACK_MS.name))
            } finally {
                db.close()
            }
        }

    @Test
    fun aLocalSettingChangeRecordsItsLiteralIntentBeforeDataStore() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val port = FakeSettingsSyncPort()
                SettingsCapture(db, port, TestSettingsWriteCoordinator()) { true }.install()

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
            val db = newTestDb()
            try {
                link(db)
                val port = FakeSettingsSyncPort()
                SettingsCapture(db, port, TestSettingsWriteCoordinator()) { true }.install()
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

                effects(db, port).drainSettings()

                assertTrue(
                    port.applied.isEmpty(),
                    "the newer local set must win over the stale remote marker",
                )
                assertNull(
                    pendingSetting(db, key.name),
                    "the superseded marker must be cleared before the upload ack",
                )
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
            val db = newTestDb()
            try {
                link(db)
                val port = FakeSettingsSyncPort()
                SettingsCapture(db, port, TestSettingsWriteCoordinator()) { true }.install()
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

                effects(db, port).drainSettings()

                assertTrue(
                    port.applied.isEmpty(),
                    "the newer local reset must win over the stale remote marker",
                )
                assertNull(
                    pendingSetting(db, key.name),
                    "the superseded marker must be cleared before the upload ack",
                )
                val local = db.syncOutboxDao().get("setting", key.name, "v")
                assertNotNull(local)
                assertEquals("null", local.value)
                assertTrue(Hlc(local.hlc, NodeId(local.nodeId)) > remoteClock)
            } finally {
                db.close()
            }
        }

    @Test
    fun aSupersededMarkerIsClearedOnLocalWinBeforeTheUploadAckDeletesTheOutbox() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val port = FakeSettingsSyncPort()
                SettingsCapture(db, port, TestSettingsWriteCoordinator()) { true }.install()
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

                port.recordLocal(key, 20_000L)

                effects(db, port).drainSettings()
                assertTrue(
                    port.applied.isEmpty(),
                    "the newer local set must win over the stale remote marker",
                )
                assertNull(
                    pendingSetting(db, key.name),
                    "the first drain must clear the superseded marker",
                )

                deleteAckedUploadedOutbox(db, key)
                assertNull(db.syncOutboxDao().get("setting", key.name, "v"))

                // The next drain runs before the server's echo page; with no marker left it must
                // not re-apply the stale remote value.
                effects(db, port).drainSettings()
                assertTrue(
                    port.applied.isEmpty(),
                    "the stale remote value must not be re-applied after the upload ack",
                )
                assertNull(pendingSetting(db, key.name))
            } finally {
                db.close()
            }
        }

    @Test
    fun aSupersededResetMarkerIsClearedOnLocalWinBeforeTheUploadAckDeletesTheOutbox() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val port = FakeSettingsSyncPort()
                SettingsCapture(db, port, TestSettingsWriteCoordinator()) { true }.install()
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

                effects(db, port).drainSettings()
                assertTrue(
                    port.applied.isEmpty(),
                    "the newer local reset must win over the stale remote marker",
                )
                assertNull(
                    pendingSetting(db, key.name),
                    "the first drain must clear the superseded marker",
                )

                deleteAckedUploadedOutbox(db, key)
                assertNull(db.syncOutboxDao().get("setting", key.name, "v"))

                effects(db, port).drainSettings()
                assertTrue(
                    port.applied.isEmpty(),
                    "the stale remote value must not be re-applied after the upload ack",
                )
                assertNull(pendingSetting(db, key.name))
            } finally {
                db.close()
            }
        }

    @Test
    fun aLocalSetDuringDrainIsSerializedAndLeavesTheNewestStoredIntent() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val port = StoringSettingsSyncPort()
                SettingsCapture(db, port, TestSettingsWriteCoordinator()) { true }.install()
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
                        effects(db, port, coordinator).drainSettings()
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
            val db = newTestDb()
            try {
                link(db)
                val port = FakeSettingsSyncPort()
                SettingsCapture(db, port, TestSettingsWriteCoordinator()) { true }.install()
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val clock = Hlc.of(1_577_836_800_000L, 1, remote)
                seedPending(db, key, "15000", clock)
                seedLocal(db, key, "15000", clock)

                port.remoteResult = false
                effects(db, port).drainSettings()
                assertEquals("15000", pendingSetting(db, key.name))
                assertEquals(1, port.applied.size)

                port.remoteResult = true
                effects(db, port).drainSettings()
                assertNull(pendingSetting(db, key.name))
                assertEquals(2, port.applied.size)
            } finally {
                db.close()
            }
        }

    @Test
    fun aLocalIntentWithANewerNodeAtAnEqualPackedClockWins() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val port = FakeSettingsSyncPort()
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                seedPending(db, key, "15000", Hlc.of(now, 1, NodeId("0000000000000002")))
                seedLocal(db, key, "20000", Hlc.of(now, 1, NodeId("0000000000000003")))

                effects(db, port).drainSettings()

                assertTrue(port.applied.isEmpty())
            } finally {
                db.close()
            }
        }

    @Test
    fun aLocalIntentWithAnOlderNodeAtAnEqualPackedClockLoses() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val port = FakeSettingsSyncPort()
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                seedPending(db, key, "15000", Hlc.of(now, 1, NodeId("0000000000000002")))
                seedLocal(db, key, "20000", Hlc.of(now, 1, NodeId("0000000000000001")))

                effects(db, port).drainSettings()

                assertEquals(1, port.applied.size)
            } finally {
                db.close()
            }
        }

    @Test
    fun aLocalIntentWithANewerPackedClockWinsRegardlessOfNode() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val port = FakeSettingsSyncPort()
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                seedPending(db, key, "15000", Hlc.of(now, 1, NodeId("0000000000000003")))
                seedLocal(db, key, "20000", Hlc.of(now + 1, 0, NodeId("0000000000000001")))

                effects(db, port).drainSettings()

                assertTrue(port.applied.isEmpty())
            } finally {
                db.close()
            }
        }

    @Test
    fun anAckDoesNotStompANewerPendingMarkerOrMetadata() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val port = StoringSettingsSyncPort()
                SettingsCapture(db, port, TestSettingsWriteCoordinator()) { true }.install()
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

                effects(db, port).drainSettings()

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

    @Test
    fun aTextSettingIsJournaledAndDrainedAsQuotedJson() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val port = ValidatingSettingsSyncPort()
                val applier = applier(db, port)
                val key = PlaybackSettingKeys.SPEED_PRESETS
                val value = PlaybackSettingKeys.DEFAULT_SPEED_PRESETS
                applier.applyPage(settingPage(key.name, JsonPrimitive(value)), roundId = now)

                val expected = JsonPrimitive(value).toString()
                assertEquals(expected, pendingSetting(db, key.name))
                val marker = Json.parseToJsonElement(checkNotNull(pendingSetting(db, key.name)))
                assertTrue(marker is JsonPrimitive && marker.isString)
                assertEquals(value, marker.content)

                effects(db, port).drainSettings()
                assertEquals(listOf(key to expected), port.applied)
                val applied = Json.parseToJsonElement(port.applied.single().second)
                assertTrue(applied is JsonPrimitive && applied.isString)
                assertEquals(value, applied.content)
                assertNull(pendingSetting(db, key.name))
            } finally {
                db.close()
            }
        }

    @Test
    fun aChoiceSettingIsJournaledAndDrainedAsQuotedJson() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val port = ValidatingSettingsSyncPort()
                val applier = applier(db, port)
                val key = GroupsSettingKeys.ALL_MEDIA_FILTER
                val value = MediaFilter.ALL
                applier.applyPage(settingPage(key.name, JsonPrimitive(value.name)), roundId = now)

                val expected = JsonPrimitive(value.name).toString()
                assertEquals(expected, pendingSetting(db, key.name))
                val marker = Json.parseToJsonElement(checkNotNull(pendingSetting(db, key.name)))
                assertTrue(marker is JsonPrimitive && marker.isString)
                assertEquals(value.name, marker.content)

                effects(db, port).drainSettings()
                assertEquals(listOf(key to expected), port.applied)
                val applied = Json.parseToJsonElement(port.applied.single().second)
                assertTrue(applied is JsonPrimitive && applied.isString)
                assertEquals(value.name, applied.content)
                assertNull(pendingSetting(db, key.name))
            } finally {
                db.close()
            }
        }

    @Test
    fun aTextSettingWithQuotesAndBackslashIsJournaledAndDrainedAsQuotedJson() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val port = ValidatingSettingsSyncPort()
                val applier = applier(db, port)
                val key = DiagnosticsSettingKeys.MAINTENANCE_STATS
                val value = "{\"note\":\"a\\\"b\\\\c\"}"
                applier.applyPage(settingPage(key.name, JsonPrimitive(value)), roundId = now)

                val expected = JsonPrimitive(value).toString()
                assertEquals(expected, pendingSetting(db, key.name))
                val marker = Json.parseToJsonElement(checkNotNull(pendingSetting(db, key.name)))
                assertTrue(marker is JsonPrimitive && marker.isString)
                assertEquals(value, marker.content)

                effects(db, port).drainSettings()
                assertEquals(listOf(key to expected), port.applied)
                val applied = Json.parseToJsonElement(port.applied.single().second)
                assertTrue(applied is JsonPrimitive && applied.isString)
                assertEquals(value, applied.content)
                assertNull(pendingSetting(db, key.name))
            } finally {
                db.close()
            }
        }

    @Test
    fun aJsonNullResetIsJournaledAndDrainedAsNull() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val port = ValidatingSettingsSyncPort()
                val applier = applier(db, port)
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                applier.applyPage(settingPage(key.name, JsonNull), roundId = now)

                assertEquals("null", pendingSetting(db, key.name))
                assertTrue(Json.parseToJsonElement(checkNotNull(pendingSetting(db, key.name))) is JsonNull)

                effects(db, port).drainSettings()
                assertEquals(listOf(key to "null"), port.applied)
                assertTrue(Json.parseToJsonElement(port.applied.single().second) is JsonNull)
                assertNull(pendingSetting(db, key.name))
            } finally {
                db.close()
            }
        }

    /**
     * 10 Pull and apply step 3: while the persisted "Sync playback settings" switch is off the
     * markers stay pending — no drain applies them — and turning it on drains them.
     */
    @Test
    fun aMarkerStaysPendingWhileTheSwitchIsOffAndDrainsOnceEnabled() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val port = FakeSettingsSyncPort()
                val applier = applier(db, port)
                applier.applyPage(
                    settingPage(PlaybackSettingKeys.SKIP_BACK_MS.name, JsonPrimitive(15000L)),
                    roundId = now,
                )
                val switch = mutableMapOf("on" to false)

                effects(db, port, settingsSyncEnabled = { switch["on"] == true }).drainSettings()
                assertTrue(port.applied.isEmpty(), "the disabled switch must leave the marker pending")
                assertEquals("15000", pendingSetting(db, PlaybackSettingKeys.SKIP_BACK_MS.name))

                switch["on"] = true
                effects(db, port, settingsSyncEnabled = { switch["on"] == true }).drainSettings()
                assertEquals(1, port.applied.size)
                assertNull(pendingSetting(db, PlaybackSettingKeys.SKIP_BACK_MS.name))
            } finally {
                db.close()
            }
        }

    @Test
    fun disablingTheSwitchBetweenKeysLeavesLaterEffectsPending() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val keys = listOf(PlaybackSettingKeys.SKIP_BACK_MS, PlaybackSettingKeys.SKIP_FORWARD_MS)
                keys.forEach { seedPending(db, it, "15000", Hlc(now shl 16, remote)) }
                var enabled = true
                val delegate = FakeSettingsSyncPort()
                val port =
                    object : SettingsSyncPort by delegate {
                        override suspend fun applyRemote(
                            key: SettingKey<*>,
                            json: String,
                        ): Boolean {
                            val applied = delegate.applyRemote(key, json)
                            enabled = false
                            return applied
                        }
                    }

                effects(db, port, settingsSyncEnabled = { enabled }).drainSettings()

                assertEquals(1, delegate.applied.size, "a disabled switch must stop later key writes")
                assertEquals(1, keys.count { pendingSetting(db, it.name) != null })
            } finally {
                db.close()
            }
        }

    @Test
    fun disablingTheSwitchWhileAKeyLockIsHeldKeepsItsEffectPending() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                seedPending(db, key, "15000", Hlc(now shl 16, remote))
                val port = FakeSettingsSyncPort()
                val coordinator = TestSettingsWriteCoordinator()
                val lockHeld = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val gateRead = CompletableDeferred<Unit>()
                var enabled = true
                val holder =
                    launch {
                        coordinator.withLock(key) {
                            lockHeld.complete(Unit)
                            release.await()
                        }
                    }
                lockHeld.await()
                val drain =
                    launch {
                        effects(
                            db,
                            port,
                            coordinator,
                            settingsSyncEnabled = {
                                gateRead.complete(Unit)
                                enabled
                            },
                        ).drainSettings()
                    }
                gateRead.await()
                enabled = false
                release.complete(Unit)
                holder.join()
                drain.join()

                assertTrue(port.applied.isEmpty(), "a waiting drain must recheck the persisted switch")
                assertEquals("15000", pendingSetting(db, key.name))
            } finally {
                db.close()
            }
        }

    /**
     * 10 Durable apply effects: the at-each-start drain applies while linked, and an unlink —
     * which empties the sync tables — never resurrects the old account's effects.
     */
    @Test
    fun aStartDrainAppliesWhileLinkedAndNotAfterAnUnlink() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                db.withWriteTransaction {
                    db.syncStateDao().upsert(
                        db.syncStateDao().get()!!.copy(linkedAt = 1L),
                    )
                }
                val port = FakeSettingsSyncPort()
                val applier = applier(db, port)
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                applier.applyPage(settingPage(key.name, JsonPrimitive(15000L)), roundId = now)

                effects(db, port).drainAtStart(capture(db, port))
                assertEquals(1, port.applied.size)
                assertNull(pendingSetting(db, key.name))

                // A second marker of the same account, then the unlink clears it with the
                // whole sync bookkeeping: a later start drain finds nothing to resurrect.
                val secondClock = Hlc.of(now + 1L, 0, remote)
                seedPending(db, key, "20000", secondClock)
                db.withWriteTransaction { db.syncStateDao().unlink() }

                effects(db, port).drainAtStart(capture(db, port))
                assertEquals(1, port.applied.size, "an unlinked device must not drain old account effects")
                assertTrue(db.syncClockDao().all().isEmpty())
            } finally {
                db.close()
            }
        }

    /** The start drain's link gate: a marker present while unlinked stays untouched. */
    @Test
    fun aStartDrainSkipsAMarkerWhileUnlinked() =
        runTest {
            val db = newTestDb()
            try {
                // Never linked: sync_state carries no link, but a stray marker exists.
                db.withWriteTransaction {
                    db.syncStateDao().upsert(
                        SyncStateEntity(enabled = false, nodeId = "0000000000000001", hlc = 1L shl 16),
                    )
                }
                val port = FakeSettingsSyncPort()
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                seedPending(db, key, "15000", Hlc.of(now, 1, remote))

                effects(db, port).drainAtStart(capture(db, port))

                assertTrue(port.applied.isEmpty(), "the start drain must not run while unlinked")
                assertEquals("15000", pendingSetting(db, key.name))
            } finally {
                db.close()
            }
        }

    private fun applier(
        db: NeutrodyneDatabase,
        port: SettingsSyncPort,
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
            notices = SyncNoticeBus(),
            deviceNames = DeviceNames { emptyList() },
            sharePasswords = { false },
            coordinator = FakeCredentialCommitCoordinator(),
        )

    /** The drainer fixture: the persisted-switch gate defaults to on, as the graph wires it. */
    private fun effects(
        db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase,
        port: SettingsSyncPort,
        coordinator: ch.lkmc.neutrodyne.core.domain.SettingsWriteCoordinator =
            TestSettingsWriteCoordinator(),
        settingsSyncEnabled: suspend () -> Boolean = { true },
        secrets: SecretStore = FakeSecretStore(),
        credentials: CredentialCommitCoordinator = FakeCredentialCommitCoordinator(),
    ): SyncApplyEffects =
        SyncApplyEffects(
            db,
            port,
            coordinator,
            settingsSyncEnabled,
            secrets,
            credentials,
            "__absent_cache__".toPath(),
        )

    /** The capture fixture: the same live-gate shape the graph wires. */
    private fun capture(
        db: NeutrodyneDatabase,
        port: SettingsSyncPort,
        coordinator: SettingsWriteCoordinator = TestSettingsWriteCoordinator(),
        settingsSyncEnabled: suspend () -> Boolean = { true },
    ): SettingsCapture = SettingsCapture(db, port, coordinator, settingsSyncEnabled)

    private suspend fun link(db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase) {
        db.withWriteTransaction {
            db
                .syncStateDao()
                .upsert(
                    SyncStateEntity(
                        enabled = true,
                        nodeId = "0000000000000001",
                        hlc = 1L shl 16,
                        linkedAt = 1L,
                    ),
                )
        }
    }

    private fun settingPage(
        key: String,
        value: JsonElement,
    ): List<RecordDto> =
        listOf(
            RecordDto(
                coll = "setting",
                id = JsonPrimitive(key),
                seq = 1,
                by = deviceId,
                fields = mapOf("v" to FieldValue(value, Hlc.of(now, 1, remote).toString())),
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

    private suspend fun deleteAckedUploadedOutbox(
        db: NeutrodyneDatabase,
        key: SettingKey<*>,
    ) {
        val local = db.syncOutboxDao().get("setting", key.name, "v") ?: return
        db.withWriteTransaction {
            db.syncOutboxDao().deleteAcked("setting", key.name, "v", local.hlc, local.nodeId)
        }
    }

    /** The host's real in-memory Room database; the Android host supplies a Robolectric context. */
    protected abstract fun newTestDb(): NeutrodyneDatabase
}

/**
 * The production per-key mutex contract, implemented locally because `:core:data` is
 * off-limits. Shared by every `:sync:impl` fixture so a drain and a local write provably
 * contend for the same lock instance.
 */
internal class TestSettingsWriteCoordinator : SettingsWriteCoordinator {
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
internal class StoringSettingsSyncPort(
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

/** A [FakeSettingsSyncPort] that accepts only values matching the existing JSON wire contract. */
private class ValidatingSettingsSyncPort(
    private val delegate: FakeSettingsSyncPort = FakeSettingsSyncPort(),
) : SettingsSyncPort by delegate {
    val applied: List<Pair<SettingKey<*>, String>> get() = delegate.applied

    override suspend fun applyRemote(
        key: SettingKey<*>,
        json: String,
    ): Boolean {
        if (!isWireValue(key, json)) return false
        return delegate.applyRemote(key, json)
    }

    /**
     * The [SettingsSyncPort] contract: [json] is the value's JSON text in the key's own type. This
     * is a shape check for the types under test, not a second production decoder.
     */
    private fun isWireValue(
        key: SettingKey<*>,
        json: String,
    ): Boolean =
        runCatching {
            val element = Json.parseToJsonElement(json)
            if (element is JsonNull) return@runCatching true
            when (key) {
                is SettingKey.Text -> {
                    element is JsonPrimitive && element.isString
                }

                is SettingKey.Choice<*> -> {
                    element is JsonPrimitive && element.isString &&
                        key.values.any { it.name == element.content }
                }

                is SettingKey.Int64 -> {
                    element is JsonPrimitive && element.longOrNull != null
                }

                else -> {
                    false
                }
            }
        }.getOrDefault(false)
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
