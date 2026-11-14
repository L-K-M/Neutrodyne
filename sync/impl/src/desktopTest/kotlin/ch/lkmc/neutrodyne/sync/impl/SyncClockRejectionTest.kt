// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.sync.InMemorySyncServer
import ch.lkmc.neutrodyne.sync.api.SyncRoundReason
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeDto
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeResultDto
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangesPage
import ch.lkmc.neutrodyne.sync.protocol.dto.SyncResponse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

private const val SERVER_TIME_MS = 1_800_000_000_000L

class SyncClockRejectionTest {
    @Test
    fun aClockResponseCannotOverwriteAConcurrentRevocation() =
        runTest {
            val db = TestDb.inMemory()
            val base = InMemorySyncClient(InMemorySyncServer { SERVER_TIME_MS })
            val client =
                object : SyncClient by base {
                    override suspend fun sync(
                        since: String?,
                        changes: List<ChangeDto>,
                    ): SyncResponse {
                        db.withWriteTransaction { db.syncStateDao().markRevoked(SYNC_ERROR_REVOKED) }
                        return SyncResponse(
                            changes.mapIndexed { index, _ -> ChangeResultDto(index, "rejected", code = "clock_skew") },
                            emptyList(),
                            "c:00000001:1",
                            false,
                            Instant.fromEpochMilliseconds(SERVER_TIME_MS).toString(),
                        )
                    }
                }
            val device = SyncEngineTest.Device(db, client)
            try {
                db.withWriteTransaction {
                    db.syncStateDao().upsert(
                        SyncStateEntity(
                            enabled = true,
                            serverUrl = "https://sync.example",
                            accountId = "account",
                            deviceId = "device",
                            nodeId = "a000000000000001",
                            linkedAt = SERVER_TIME_MS,
                            hlc = SERVER_TIME_MS shl Hlc.COUNTER_BITS,
                        ),
                    )
                }
                val capture = SettingsCapture(db, device.settingsPort, device.writeLocks) { true }
                capture.install()
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                device.settingsPort.recordLocal(key, 20_000L)
                device.settingsPort.commitLocal(key, 20_000L)
                device.settingsPort.emitLocal(key, 20_000L, device.clock.now())
                assertFailsWith<IllegalStateException> { device.engine.round(SyncRoundReason.SYNC_NOW) }
                assertEquals(false, db.syncStateDao().get()?.enabled)
                assertEquals(
                    SYNC_ERROR_REVOKED,
                    db.syncStateDao().get()?.lastError,
                    "a successful old transport response must not erase revocation",
                )
            } finally {
                db.close()
            }
        }

    @Test
    fun aSecondClockRejectionBlocksPushesButPullsUntilAnotherCorrection() =
        runTest {
            val db = TestDb.inMemory()
            val requests = mutableListOf<List<ChangeDto>>()
            var pulls = 0
            val base = InMemorySyncClient(InMemorySyncServer { SERVER_TIME_MS })
            val client =
                object : SyncClient by base {
                    override suspend fun sync(
                        since: String?,
                        changes: List<ChangeDto>,
                    ): SyncResponse {
                        requests += changes
                        val reject = requests.count { it.isNotEmpty() } <= 2
                        return SyncResponse(
                            results =
                                changes.mapIndexed { index, _ ->
                                    ChangeResultDto(
                                        index,
                                        if (reject) "rejected" else "applied",
                                        code = if (reject) "clock_skew" else null,
                                    )
                                },
                            records = emptyList(),
                            cursor = "c:00000001:${requests.size}",
                            hasMore = changes.isEmpty() && requests.size == 4,
                            serverTime = Instant.fromEpochMilliseconds(SERVER_TIME_MS).toString(),
                        )
                    }

                    override suspend fun changes(
                        since: String?,
                        limit: Int,
                    ): ChangesPage {
                        pulls++
                        return ChangesPage(
                            emptyList(),
                            "c:00000001:100",
                            false,
                            Instant.fromEpochMilliseconds(SERVER_TIME_MS).toString(),
                        )
                    }
                }
            val device = SyncEngineTest.Device(db, client)
            try {
                device.clock.advanceBy(6.hours)
                db.withWriteTransaction {
                    db.syncStateDao().upsert(
                        SyncStateEntity(
                            enabled = true,
                            serverUrl = "https://sync.example",
                            accountId = "account",
                            deviceId = "device",
                            nodeId = "a000000000000001",
                            linkedAt = SERVER_TIME_MS,
                            hlc = device.clock.now() shl Hlc.COUNTER_BITS,
                        ),
                    )
                }
                val capture = SettingsCapture(db, device.settingsPort, device.writeLocks) { true }
                capture.install()
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                device.settingsPort.recordLocal(key, 20_000L)
                device.settingsPort.commitLocal(key, 20_000L)
                device.settingsPort.emitLocal(key, 20_000L, device.clock.now())

                val first = device.engine.round(SyncRoundReason.OUTBOX)
                assertEquals(1, requests.size, "a clock rejection retries in the next public round")
                assertEquals(1, first.rejected, "diagnostics must count the rejection")
                assertEquals("clock_skew", db.syncStateDao().get()?.lastError)
                assertTrue(
                    db
                        .syncOutboxDao()
                        .rows()
                        .single()
                        .hlc ushr Hlc.COUNTER_BITS <= SERVER_TIME_MS + 300_000,
                )

                device.settingsPort.current.remove(key.name)
                device.engine.round(SyncRoundReason.PERIODIC)
                assertEquals(2, requests.size)
                assertTrue(requests.last().isEmpty(), "an unreadable setting remains queued while pulls continue")
                assertEquals(
                    "clock_skew",
                    db.syncStateDao().get()?.lastError,
                    "an empty pull does not acknowledge the rejected intent",
                )
                device.settingsPort.current[key.name] = "20000"

                val second = device.engine.round(SyncRoundReason.SYNC_NOW)
                assertEquals(3, requests.size)
                assertEquals(1, second.rejected)
                assertEquals("ClockSkew", db.syncStateDao().get()?.lastError)

                val blocked = device.engine.round(SyncRoundReason.OUTBOX)
                assertEquals(4, requests.size)
                assertTrue(requests.last().isEmpty(), "blocked pushes still request a pull")
                assertEquals(1, pulls, "the blocked round drains later pages")
                assertEquals("c:00000001:100", db.syncStateDao().get()?.cursor)
                assertEquals(0, blocked.pushed)
                assertEquals("ClockSkew", db.syncStateDao().get()?.lastError)
                assertEquals(1, db.syncOutboxDao().count())

                device.clock.advanceBy(1.hours)
                val resumed = device.engine.round(SyncRoundReason.PERIODIC)
                assertEquals(6, requests.size, "the corrected empty pull may resume pending pushes")
                assertTrue(requests[4].isEmpty())
                assertEquals(1, requests[5].size)
                assertEquals(1, resumed.pushed)
                assertEquals(0, db.syncOutboxDao().count())
                assertEquals(null, db.syncStateDao().get()?.lastError)
            } finally {
                db.close()
            }
        }
}
