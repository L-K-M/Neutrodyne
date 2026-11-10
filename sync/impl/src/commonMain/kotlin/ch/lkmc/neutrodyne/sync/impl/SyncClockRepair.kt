// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.ProtocolLimits
import okio.IOException

/** Caller owns the profile-checked Room writer; accepted wire clocks are immutable. */
internal suspend fun repairSyncClock(
    db: NeutrodyneDatabase,
    state: SyncStateEntity,
    adjustedNowMs: Long,
) {
    val node = state.nodeId ?: return
    val min = ProtocolLimits.MIN_CLOCK_MS shl Hlc.COUNTER_BITS
    val latestMs = (adjustedNowMs + CLOCK_ADMISSION_MS).coerceAtMost(Hlc.MAX_MILLIS)
    val max = (latestMs shl Hlc.COUNTER_BITS) or Hlc.COUNTER_MASK
    var old = db.syncOutboxDao().nextLocalClockOutside(min, max, node, -1)
    if (state.hlc in min..max && old == null) return
    val seed = maxOf(maxOf(adjustedNowMs, ProtocolLimits.MIN_CLOCK_MS) shl Hlc.COUNTER_BITS, greatestAcceptedClock(db))
    // A server-accepted future clock cannot be rewritten as an unaccepted local edit.
    if (seed > max) return
    if (state.hlc !in min..max) db.syncStateDao().clampHlc(seed)
    db.syncStateDao().raiseHlc(seed)
    while (old != null) {
        val current = checkNotNull(db.syncStateDao().get()).hlc
        check(current < max) { "Corrected sync clock has exhausted its admission window" }
        val next = maxOf(current + 1, adjustedNowMs shl Hlc.COUNTER_BITS)
        db.syncStateDao().raiseHlc(next)
        restampSettingReceipts(db, old, next, node)
        db.syncOutboxDao().restampLocalClock(old, next, node)
        old = db.syncOutboxDao().nextLocalClockOutside(min, max, node, old)
    }
}

/** Remap only a matching unaccepted intent; accepted fields and remote effects stay intact. */
private suspend fun restampSettingReceipts(
    db: NeutrodyneDatabase,
    previous: Long,
    replacement: Long,
    node: String,
) {
    val store = SyncClockStore(db)
    val oldClock = Hlc(previous, NodeId(node)).toString()
    val newClock = Hlc(replacement, NodeId(node)).toString()
    var after = ""
    while (true) {
        val page = db.syncOutboxDao().localSettingIdsAtClock(previous, node, after, CLOCK_SCAN_PAGE)
        if (page.isEmpty()) return
        for (rid in page) {
            val row = db.syncClockDao().get("setting", rid) ?: continue
            val raw = store.get(row.coll, row.rid) ?: throw IOException("Setting clock metadata is unreadable")
            val boundary = raw.settingIntent?.takeIf { it.clock == oldClock } ?: continue
            store.put(
                "setting",
                rid,
                raw.copy(
                    settingIntent = boundary.copy(clock = newClock, receiptClock = boundary.receiptClock ?: oldClock),
                    settingHash = raw.settingHash?.let { if (it.clock == oldClock) it.copy(clock = newClock) else it },
                ),
            )
        }
        after = page.last()
    }
}

private suspend fun greatestAcceptedClock(db: NeutrodyneDatabase): Long {
    val store = SyncClockStore(db)
    var coll = ""
    var rid = ""
    var greatest = 0L
    while (true) {
        val page = db.syncClockDao().keysAfter(coll, rid, CLOCK_SCAN_PAGE)
        if (page.isEmpty()) return greatest
        for (key in page) {
            val raw = store.get(key.coll, key.rid) ?: throw IOException("Sync clock metadata is unreadable")
            greatest = maxOf(greatest, raw.state.maxClock?.packed ?: 0)
        }
        coll = page.last().coll
        rid = page.last().rid
    }
}

private const val CLOCK_ADMISSION_MS = 300_000L
private const val CLOCK_SCAN_PAGE = 128
