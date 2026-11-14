// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.sync.InMemorySyncServer
import ch.lkmc.neutrodyne.sync.api.SyncRoundReason
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeDto
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeResultDto
import ch.lkmc.neutrodyne.sync.protocol.dto.SyncResponse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

private const val BATCH_NOW = 1_800_000_000_000L

class SyncOversizeBatchTest {
    @Test
    fun anOversizeBatchShrinksAndDrainsWithoutAcknowledgingFailedAttempts() =
        runTest {
            val db = TestDb.inMemory()
            val sizes = mutableListOf<Int>()
            val client =
                client { _, changes ->
                    sizes += changes.size
                    if (changes.size > 2) throw tooLarge()
                    accepted(changes)
                }
            try {
                seed(db, 5)
                val report = SyncEngineTest.Device(db, client).engine.round(SyncRoundReason.SYNC_NOW)
                assertEquals(listOf(5, 2, 2, 1), sizes)
                assertEquals(5, report.pushed)
                assertEquals(0, report.rejected)
                assertEquals(0, db.syncOutboxDao().count())
            } finally {
                db.close()
            }
        }

    @Test
    fun aSingleOversizeChangeIsInvalidWithoutBlockingTheOtherRecord() =
        runTest {
            val db = TestDb.inMemory()
            val sizes = mutableListOf<Int>()
            val client =
                client { _, changes ->
                    sizes += changes.size
                    if (changes.any { it.id.toString().contains(id(0)) }) throw tooLarge()
                    accepted(changes)
                }
            try {
                seed(db, 2)
                val report = SyncEngineTest.Device(db, client).engine.round(SyncRoundReason.SYNC_NOW)
                assertEquals(listOf(2, 1, 1), sizes)
                assertEquals(1, report.pushed)
                assertEquals(1, report.rejected)
                assertEquals(0, db.syncOutboxDao().count())
                assertNull(db.syncClockDao().get("group", id(0)), "a rejection is never accepted raw state")
            } finally {
                db.close()
            }
        }

    @Test
    fun aNewerLocalWriteSurvivesTheOversizeResponseForItsPredecessor() =
        runTest {
            val db = TestDb.inMemory()
            val names = mutableListOf<String>()
            val client =
                client { _, changes ->
                    val name =
                        changes
                            .single()
                            .fields
                            .getValue("name")
                            .v
                            .toString()
                    names += name
                    if (names.size == 1) {
                        db.withWriteTransaction {
                            db.syncOutboxDao().captureLiteral("group", id(0), "name", "\"Newer\"")
                        }
                        throw tooLarge()
                    }
                    accepted(changes)
                }
            try {
                seed(db, 1)
                val report = SyncEngineTest.Device(db, client).engine.round(SyncRoundReason.SYNC_NOW)
                assertEquals(listOf("\"Group 0\"", "\"Newer\""), names)
                assertEquals(1, report.rejected)
                assertEquals(0, db.syncOutboxDao().count())
            } finally {
                db.close()
            }
        }

    @Test
    fun anOversizeResponseCannotDiscardAnotherProfilesPendingWrites() =
        runTest {
            val db = TestDb.inMemory()
            val client =
                client { _, _ ->
                    db.withWriteTransaction {
                        val state = checkNotNull(db.syncStateDao().get())
                        db.syncStateDao().upsert(state.copy(accountId = "replacement"))
                    }
                    throw tooLarge()
                }
            try {
                seed(db, 1)
                assertFailsWith<IllegalStateException> {
                    SyncEngineTest.Device(db, client).engine.round(SyncRoundReason.SYNC_NOW)
                }
                assertEquals(1, db.syncOutboxDao().count())
            } finally {
                db.close()
            }
        }

    @Test
    fun emptyPushAndUnrelatedHttpErrorsAreNeverRetriedAsOversizeBatches() =
        runTest {
            for ((count, status) in listOf(0 to 413, 1 to 429, 1 to 503)) {
                val db = TestDb.inMemory()
                var calls = 0
                val client =
                    client { _, _ ->
                        calls++
                        throw SyncTransportException(status, null, "test boundary")
                    }
                try {
                    seed(db, count)
                    val failure =
                        assertFailsWith<SyncTransportException> {
                            SyncEngineTest.Device(db, client).engine.round(SyncRoundReason.SYNC_NOW)
                        }
                    assertEquals(status, failure.status)
                    assertEquals(1, calls)
                    assertEquals(count, db.syncOutboxDao().count())
                } finally {
                    db.close()
                }
            }
        }

    @Test
    fun aRejectedOnlyRecordStillAllowsTheRequestedPull() =
        runTest {
            val db = TestDb.inMemory()
            val sizes = mutableListOf<Int>()
            val client =
                client { _, changes ->
                    sizes += changes.size
                    if (changes.isNotEmpty()) throw tooLarge()
                    accepted(changes)
                }
            try {
                seed(db, 1)
                val report = SyncEngineTest.Device(db, client).engine.round(SyncRoundReason.PERIODIC)
                assertEquals(listOf(1, 0), sizes)
                assertEquals(1, report.rejected)
                assertEquals("c:00000001:1", db.syncStateDao().get()?.cursor)
            } finally {
                db.close()
            }
        }

    @Test
    fun discardingAnOversizeSettingDoesNotAcceptOrRecaptureIt() =
        runTest {
            val db = TestDb.inMemory()
            var reject = true
            val client =
                client { _, changes ->
                    if (reject && changes.isNotEmpty()) throw tooLarge()
                    accepted(changes)
                }
            try {
                seed(db, 0)
                val device = SyncEngineTest.Device(db, client)
                val capture = SettingsCapture(db, device.settingsPort, device.writeLocks) { true }
                capture.install()
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                device.settingsPort.recordLocal(key, 20_000L)
                device.settingsPort.commitLocal(key, 20_000L)
                device.settingsPort.emitLocal(key, 20_000L, device.clock.now())

                val report = device.engine.round(SyncRoundReason.SYNC_NOW)
                assertEquals(1, report.rejected)
                val raw = db.syncClockDao().get("setting", key.name)?.let { decodeRawClocks(it.clocks) }
                assertNull(raw?.state?.fields?.get("v"), "rejected data was never accepted")
                assertEquals("20000", device.settingsPort.current[key.name])
                capture.reconcile()
                assertEquals(0, db.syncOutboxDao().count(), "an unchanged rejected value is not captured again")
                val restarted = SettingsCapture(db, device.settingsPort, device.writeLocks) { true }
                restarted.install()
                assertEquals(0, db.syncOutboxDao().count(), "the rejection receipt survives capture restart")

                reject = false
                device.settingsPort.recordLocal(key, 20_000L)
                device.settingsPort.commitLocal(key, 20_000L)
                device.settingsPort.emitLocal(key, 20_000L, device.clock.now())
                val retried = device.engine.round(SyncRoundReason.SYNC_NOW)
                assertEquals(1, retried.pushed, "an explicit new receipt can retry the same value")
                assertEquals(0, db.syncOutboxDao().count())
                val accepted =
                    checkNotNull(db.syncClockDao().get("setting", key.name))
                        .let { decodeRawClocks(it.clocks) }
                assertNull(accepted?.rejectedSetting, "the newer accepted receipt clears the rejection")
            } finally {
                db.close()
            }
        }

    @Test
    fun discardingTheSentFieldsKeepsCredentialsWithheldByTheShareGate() =
        runTest {
            val db = TestDb.inMemory()
            val client =
                client { _, changes ->
                    if (changes.isNotEmpty()) {
                        assertEquals(setOf("title"), changes.single().fields.keys)
                        throw tooLarge()
                    }
                    accepted(changes)
                }
            try {
                seed(db, 0)
                db.withWriteTransaction {
                    db.syncOutboxDao().captureLiteral("podcast", id(0), "title", "\"Local title\"")
                    db.syncOutboxDao().captureLiteral("podcast", id(0), "auth", "{}")
                }
                val report = SyncEngineTest.Device(db, client).engine.round(SyncRoundReason.SYNC_NOW)
                assertEquals(1, report.rejected)
                assertEquals(listOf("auth"), db.syncOutboxDao().rows().map { it.field })
            } finally {
                db.close()
            }
        }

    private suspend fun seed(
        db: NeutrodyneDatabase,
        count: Int,
    ) {
        db.withWriteTransaction {
            db.syncStateDao().upsert(
                SyncStateEntity(
                    enabled = true,
                    serverUrl = "https://sync.example",
                    accountId = "account",
                    deviceId = "device",
                    nodeId = "a000000000000001",
                    linkedAt = BATCH_NOW,
                    hlc = BATCH_NOW shl 16,
                ),
            )
            repeat(count) { index ->
                db.syncOutboxDao().captureLiteral("group", id(index), "name", "\"Group $index\"")
            }
        }
    }

    private fun client(block: suspend (String?, List<ChangeDto>) -> SyncResponse): SyncClient {
        val base = InMemorySyncClient(InMemorySyncServer { BATCH_NOW })
        return object : SyncClient by base {
            override suspend fun sync(
                since: String?,
                changes: List<ChangeDto>,
            ): SyncResponse = block(since, changes)
        }
    }

    private fun accepted(changes: List<ChangeDto>) =
        SyncResponse(
            changes.mapIndexed { index, _ -> ChangeResultDto(index, "applied") },
            emptyList(),
            "c:00000001:1",
            false,
            Instant.fromEpochMilliseconds(BATCH_NOW).toString(),
        )

    private fun tooLarge() = SyncTransportException(413, "payload_too_large", "test boundary")

    private fun id(index: Int) = "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"
}
