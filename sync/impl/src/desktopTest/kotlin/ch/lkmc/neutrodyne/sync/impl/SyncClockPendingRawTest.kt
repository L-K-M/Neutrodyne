// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.database.SyncOutboxEntity
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.sync.InMemorySyncServer
import ch.lkmc.neutrodyne.sync.api.SyncRoundReason
import ch.lkmc.neutrodyne.sync.protocol.FieldState
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.ProtocolValidation
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeDto
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeResultDto
import ch.lkmc.neutrodyne.sync.protocol.dto.FieldValue
import ch.lkmc.neutrodyne.sync.protocol.dto.RecordDto
import ch.lkmc.neutrodyne.sync.protocol.dto.SyncResponse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

private const val RAW_NOW = 1_800_000_000_000L
private const val RAW_GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val RAW_NODE = "a000000000000001"
private val RAW_SERVER_CLOCK = Hlc.of(RAW_NOW, 0, NodeId("0000000000000002"))

class SyncClockPendingRawTest {
    @Test
    fun aRejectedLocalClockIsNotPromotedIntoAcceptedRawByThePull() =
        runTest {
            val db = TestDb.inMemory()
            var serverRecord =
                RecordDto(
                    "group",
                    JsonPrimitive(RAW_GROUP),
                    1,
                    "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                    mapOf(
                        "name" to FieldValue(JsonPrimitive("Remote"), RAW_SERVER_CLOCK.toString()),
                        "deleted" to FieldValue(JsonPrimitive(false), RAW_SERVER_CLOCK.toString()),
                        "ok" to FieldValue(JsonPrimitive("a1"), RAW_SERVER_CLOCK.toString()),
                    ),
                )
            val base = InMemorySyncClient(InMemorySyncServer { RAW_NOW })
            val client =
                object : SyncClient by base {
                    override suspend fun sync(
                        since: String?,
                        changes: List<ChangeDto>,
                    ): SyncResponse {
                        val future =
                            changes.any { change ->
                                change.fields.values.any { field ->
                                    field.c?.let { Hlc.parse(it).millis > RAW_NOW + 300_000L } == true
                                }
                            }
                        if (!future) {
                            changes.forEach { change ->
                                serverRecord = serverRecord.copy(fields = serverRecord.fields + change.fields)
                            }
                        }
                        return SyncResponse(
                            changes.mapIndexed { index, _ ->
                                val status = if (future) "rejected" else "applied"
                                val code = if (future) "clock_skew" else null
                                ChangeResultDto(index, status, code = code)
                            },
                            listOf(serverRecord),
                            "c:00000001:1",
                            false,
                            Instant.fromEpochMilliseconds(RAW_NOW).toString(),
                        )
                    }
                }
            val device = SyncEngineTest.Device(db, client)
            device.clock.advanceBy(6.hours)
            try {
                ProtocolValidation.record(serverRecord)
                val future = Hlc.of(device.clock.now(), 0, NodeId(RAW_NODE))
                db.withWriteTransaction {
                    db.syncStateDao().upsert(
                        SyncStateEntity(
                            enabled = true,
                            serverUrl = "https://sync.example",
                            accountId = "account",
                            deviceId = "device",
                            nodeId = RAW_NODE,
                            linkedAt = RAW_NOW,
                            hlc = future.packed,
                        ),
                    )
                    db.syncStateDao().withApplying {
                        db.groupDao().insert(groupEntity(uuid = RAW_GROUP, name = "Local future", orderKey = "a0"))
                    }
                    SyncClockStore(db).put(
                        "group",
                        RAW_GROUP,
                        RawSyncRecord(
                            RecordState(
                                SyncCollection.GROUP,
                                fields =
                                    mapOf(
                                        "name" to
                                            FieldState(
                                                JsonPrimitive("Old remote"),
                                                Hlc.of(RAW_NOW - 1_000, 0, RAW_SERVER_CLOCK.nodeId),
                                            ),
                                    ),
                            ),
                        ),
                    )
                    db.syncOutboxDao().upsert(
                        SyncOutboxEntity(
                            "group",
                            RAW_GROUP,
                            "name",
                            future.packed,
                            RAW_NODE,
                            value = "\"Local future\"",
                        ),
                    )
                }
                val rejected = device.engine.round(SyncRoundReason.SYNC_NOW)
                assertEquals(1, rejected.rejected)
                val row = db.syncOutboxDao().rows().single()
                assertTrue(
                    row.hlc ushr Hlc.COUNTER_BITS <= RAW_NOW + 300_000L,
                    "the pull cannot turn an unaccepted clock into an immutable frontier",
                )
                val raw = checkNotNull(SyncClockStore(db).get("group", RAW_GROUP))
                assertEquals(
                    "Remote",
                    (
                        raw
                            .confirmed()
                            .state.fields
                            .getValue("name")
                            .v as JsonPrimitive
                    ).content,
                )
                assertEquals(
                    RAW_SERVER_CLOCK,
                    raw.state.fields
                        .getValue("ok")
                        .c,
                    "accepted server clocks stay immutable",
                )
                assertEquals("Local future", db.groupDao().groupByUuid(RAW_GROUP)?.name)

                val retry = device.engine.round(SyncRoundReason.SYNC_NOW)
                assertEquals(0, retry.rejected)
                assertEquals(0, db.syncOutboxDao().count())
                assertEquals("Local future", db.groupDao().groupByUuid(RAW_GROUP)?.name)
            } finally {
                db.close()
            }
        }
}
