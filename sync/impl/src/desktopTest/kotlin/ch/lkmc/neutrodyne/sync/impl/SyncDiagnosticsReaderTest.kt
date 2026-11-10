// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncHeldEntity
import ch.lkmc.neutrodyne.core.database.SyncOutboxEntity
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.LocalNetworkLocation
import ch.lkmc.neutrodyne.core.domain.SyncServerAddress
import ch.lkmc.neutrodyne.core.testing.FakeLocalNetworkGate
import ch.lkmc.neutrodyne.core.testing.FakeSyncServerAddress
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.sync.api.SyncDiagnosticsError
import ch.lkmc.neutrodyne.sync.api.SyncDiagnosticsSnapshot
import ch.lkmc.neutrodyne.sync.api.SyncLanGateState
import ch.lkmc.neutrodyne.sync.api.SyncPendingCollection
import ch.lkmc.neutrodyne.sync.api.SyncRoundReason
import ch.lkmc.neutrodyne.sync.api.SyncRoundSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncDiagnosticsReaderTest {
    @Test
    fun exposesCountsAndSafeIdentityWithoutPayloadsOrFullCursor() =
        runTest {
            fixture { db, address, gate, runtime ->
                val outbox = db.syncOutboxDao()
                outbox.upsert(SyncOutboxEntity("podcast", "private-rid", "title", 1, "node"))
                outbox.upsert(SyncOutboxEntity("podcast", "private-rid", "feedUrl", 2, "node", value = PRIVATE_URL))
                outbox.upsert(SyncOutboxEntity("session", "current", "*", 3, "node"))
                outbox.upsert(SyncOutboxEntity(PRIVATE_URL, "hidden", "*", 4, "node"))
                val snapshot = success(reader(db, address, gate, runtime).read())
                assertEquals("anna", snapshot.accountName)
                assertEquals("Desk", snapshot.deviceName)
                assertEquals(1, snapshot.protocol)
                assertEquals(1234L, snapshot.lastSyncAtMs)
                assertEquals(27L, snapshot.clockOffsetMs)
                assertEquals("01234567", snapshot.cursorPrefix)
                assertEquals(2, snapshot.pendingByCollection[SyncPendingCollection.PODCAST])
                assertEquals(1, snapshot.pendingByCollection[SyncPendingCollection.SESSION])
                assertEquals(1, snapshot.pendingByCollection[SyncPendingCollection.OTHER])
                assertFalse(snapshot.toString().contains(PRIVATE_URL))
                assertFalse(snapshot.toString().contains(URL))
                assertFalse(snapshot.toString().contains(CURSOR))
                assertFalse(snapshot.toString().contains("private-rid"))
            }
        }

    @Test
    fun heldCountExcludesStagedDeferredAndMalformedRows() =
        runTest {
            fixture { db, address, gate, runtime ->
                for (batch in listOf(
                    """{"state":"HELD","podcasts":[],"groups":[]}""",
                    """{"state":"STAGED","podcasts":[],"groups":[]}""",
                    """{"state":"DEFERRED","podcasts":[],"groups":[]}""",
                    """{"state":"HELD","podcasts":"bad","groups":[]}""",
                    "not-json",
                )) {
                    db.syncHeldDao().insert(SyncHeldEntity(batch = batch, summary = "{}", heldAt = 1))
                }
                assertEquals(1, success(reader(db, address, gate, runtime).read()).heldCount)
            }
        }

    @Test
    fun historyIsOnlyForTheCurrentProfileAndVersionIsNotFetched() =
        runTest {
            fixture { db, address, gate, runtime ->
                val profile = STATE.diagnosticProfile(URL)
                runtime.completed(runtime.activate(profile), SyncRoundSummary(SyncRoundReason.SYNC_NOW, 10, null))
                runtime.rememberVersion(URL, "1.2.3")
                val before = success(reader(db, address, gate, runtime).read())
                assertEquals(1, before.rounds.size)
                assertEquals("1.2.3", before.serverVersion)
                db.syncStateDao().upsert(STATE.copy(accountId = "other"))
                val after = success(reader(db, address, gate, runtime).read())
                assertTrue(after.rounds.isEmpty())
                assertEquals("1.2.3", after.serverVersion)
            }
        }

    @Test
    fun localNetworkDiagnosticsNeverGrantPermission() =
        runTest {
            fixture { db, address, gate, runtime ->
                gate.setLocation(LocalNetworkLocation.LOCAL)
                gate.setPermissionGranted(false)
                assertEquals(SyncLanGateState.REQUIRED, success(reader(db, address, gate, runtime).read()).lanGate)
                assertTrue(gate.results.isEmpty())
                gate.setPermissionGranted(true)
                assertEquals(SyncLanGateState.GRANTED, success(reader(db, address, gate, runtime).read()).lanGate)
                assertTrue(gate.results.isEmpty())
            }
        }

    @Test
    fun unknownStoredErrorCannotBecomeAnExportedExceptionOrUrl() =
        runTest {
            fixture { db, address, gate, runtime ->
                db.syncStateDao().upsert(STATE.copy(lastError = PRIVATE_URL))
                val snapshot = success(reader(db, address, gate, runtime).read())
                assertEquals("UNKNOWN", snapshot.lastErrorCode)
                assertFalse(snapshot.toString().contains(PRIVATE_URL))
                db.syncStateDao().upsert(STATE.copy(lastError = SYNC_ERROR_REVOKED))
                assertEquals(SYNC_ERROR_REVOKED, success(reader(db, address, gate, runtime).read()).lastErrorCode)
            }
        }

    @Test
    fun failedSettingsReadIsUnavailableRatherThanSuccessfulEmptyCounts() =
        runTest {
            fixture { db, address, gate, runtime ->
                val broken =
                    object : SyncServerAddress by address {
                        override suspend fun username(): String = error("private diagnostic failure $PRIVATE_URL")
                    }
                assertEquals(
                    Outcome.Failure(SyncDiagnosticsError.UNAVAILABLE),
                    reader(db, broken, gate, runtime).read(),
                )
            }
        }

    @Test
    fun cancellationFromSettingsIsNotAnUnavailableResult() =
        runTest {
            fixture { db, address, gate, runtime ->
                val cancelled =
                    object : SyncServerAddress by address {
                        override suspend fun username(): String = throw CancellationException("cancel")
                    }
                assertFailsWith<CancellationException> { reader(db, cancelled, gate, runtime).read() }
            }
        }

    @Test
    fun profileChangingDuringTheReadReturnsUnavailable() =
        runTest {
            fixture { db, address, gate, runtime ->
                var reads = 0
                val changing =
                    object : SyncServerAddress by address {
                        override suspend fun serverUrl(): String = if (++reads == 1) URL else "https://new.example"
                    }
                assertEquals(
                    Outcome.Failure(SyncDiagnosticsError.UNAVAILABLE),
                    reader(db, changing, gate, runtime).read(),
                )
            }
        }

    @Test
    fun anUnconfiguredInstallHasNoInventedVersionOrCursor() =
        runTest {
            fixture { db, address, gate, runtime ->
                address.updateServerUrl(null)
                db.syncStateDao().upsert(SyncStateEntity())
                val snapshot = success(reader(db, address, gate, runtime).read())
                assertNull(snapshot.serverVersion)
                assertNull(snapshot.cursorPrefix)
                assertEquals(SyncLanGateState.UNCONFIGURED, snapshot.lanGate)
                assertEquals(0, snapshot.parkedCount)
                assertEquals(0, snapshot.heldCount)
            }
        }

    private fun reader(
        db: NeutrodyneDatabase,
        address: SyncServerAddress,
        gate: FakeLocalNetworkGate,
        runtime: SyncRuntimeDiagnostics,
    ): SyncDiagnosticsReader = SyncDiagnosticsReader(db, address, gate, runtime, Dispatchers.Default)

    private fun success(outcome: Outcome<SyncDiagnosticsSnapshot, SyncDiagnosticsError>): SyncDiagnosticsSnapshot =
        assertIs<Outcome.Success<SyncDiagnosticsSnapshot>>(outcome).value

    private suspend fun fixture(
        block: suspend (
            NeutrodyneDatabase,
            FakeSyncServerAddress,
            FakeLocalNetworkGate,
            SyncRuntimeDiagnostics,
        ) -> Unit,
    ) {
        val db = TestDb.inMemory()
        try {
            db.syncStateDao().upsert(STATE)
            block(db, FakeSyncServerAddress(URL, "anna", "Desk"), FakeLocalNetworkGate(), SyncRuntimeDiagnostics())
        } finally {
            db.close()
        }
    }

    private companion object {
        const val URL = "https://sync.secret.example/path"
        const val PRIVATE_URL = "https://private.example/feed?token=secret"
        const val CURSOR = "0123456789SECRET"
        val STATE =
            SyncStateEntity(
                enabled = true,
                serverUrl = URL,
                accountId = "account",
                deviceId = "device",
                nodeId = "node",
                linkedAt = 1,
                protocol = 1,
                lastSyncAt = 1234,
                clockOffsetMs = 27,
                cursor = CURSOR,
            )
    }
}
