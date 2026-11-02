// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.FieldState
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.RecordId
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SyncContractsTest {
    @Test
    fun clocksSeedFromRawMaximaAndWatermarkAfterGc() {
        val inputs = ServerInputs(wallMs = { 0 }, randomBytes = { ByteArray(it) })
        val clock = Hlc.of(1_791_072_000_000, 2, NodeId.SERVER)
        val timed = record.copy(raw = record.raw.copy(fields = mapOf("v" to FieldState(JsonNull, clock))))
        val account = AccountSyncState(AccountCursor("00000001", 0, 0), ServerDefaults.QUOTA_RECORDS, 0)
        val tick = inputs.clock(account, timed.maxClock).tick()
        assertTrue(tick > clock)
        assertTrue(inputs.clock(account.copy(clockPacked = tick.packed), null).tick() > tick)
    }

    @Test
    fun redemptionTokensNeverBecomePrincipals() {
        listOf(TokenKind.SETUP, TokenKind.INVITE, TokenKind.APP_PASSWORD).forEach {
            assertFailsWith<IllegalArgumentException> { AccountPrincipal(1, AccountRole.USER, it, "hash", null) }
        }
        assertFailsWith<IllegalArgumentException> {
            AccountPrincipal(1, AccountRole.ADMIN, TokenKind.DEVICE, "hash", null)
        }
    }

    private companion object {
        val key = RecordKey(SyncCollection.SETTING, RecordId.Setting("playback.speed"))
        val record = StoredRecord(key, RecordState(SyncCollection.SETTING), 1, null, 0, null)
    }
}
