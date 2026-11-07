// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.database.SyncClockEntity
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import ch.lkmc.neutrodyne.core.domain.NoPlaybackSync
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsSyncPort
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import ch.lkmc.neutrodyne.sync.protocol.dto.FieldValue
import ch.lkmc.neutrodyne.sync.protocol.dto.RecordDto
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 10 Durable apply effects and Settings capture. */
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

                SyncApplyEffects(db, port).drainSettings()
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
                SyncApplyEffects(db, port).drainSettings()
                assertEquals("15000", pendingSetting(db, PlaybackSettingKeys.SKIP_BACK_MS.name))

                port.remoteResult = true
                SyncApplyEffects(db, port).drainSettings()
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
