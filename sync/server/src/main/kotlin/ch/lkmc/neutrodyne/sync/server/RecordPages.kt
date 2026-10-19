// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.ProtocolException
import ch.lkmc.neutrodyne.sync.protocol.ProtocolLimits
import ch.lkmc.neutrodyne.sync.protocol.ProtocolValidation
import ch.lkmc.neutrodyne.sync.protocol.SyncErrorCode
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import ch.lkmc.neutrodyne.sync.protocol.dto.RecordDto

/** Detached under the same transaction as the writes/ack, then sized without holding the writer. */
internal class PageCandidates(
    private val account: AccountCursor,
    private val after: Long,
    private val candidates: List<StoredRecord>,
    private val extras: List<StoredRecord>,
    private val recordLimit: Int = ProtocolLimits.MAX_RECORDS,
) {
    fun page(): RecordPage {
        val records = mutableListOf<StoredRecord>()
        var bytes = 2L // The records array's brackets and separators count toward its byte cap.
        for (record in candidates.take(recordLimit)) {
            val encoded =
                SyncJson.encodeToString(
                    RecordDto.serializer(),
                    record.raw.toDto(record.key.id, record.seq, record.byDevice),
                )
            val size = encoded.encodeToByteArray().size + if (records.isEmpty()) 0 else 1
            if (bytes + size > ProtocolLimits.MAX_PAGE_BYTES) break
            records += record
            bytes += size
        }
        if (records.isEmpty() && candidates.isNotEmpty()) {
            throw ProtocolException(SyncErrorCode.PAYLOAD_TOO_LARGE, "record exceeds page budget")
        }
        val included = records.map { it.key }.toSet()
        return RecordPage(
            records,
            account.wire(records.lastOrNull()?.seq ?: after),
            candidates.size > records.size,
            extras.distinctBy { it.key }.filter { it.key !in included },
        )
    }
}

internal fun AccountCursor.wire(at: Long = seq): String = "c:$epoch:$at"

internal fun AccountCursor.validate(
    value: String?,
    purpose: CursorPurpose = CursorPurpose.PAGE,
): Long {
    if (value == null) {
        if (purpose == CursorPurpose.RESET) invalidChange("reset requires beforeCursor")
        return 0
    }
    ProtocolValidation.cursor(value)
    val parts = value.split(':')
    val after = parts[2].toLong()
    if (parts[1] != epoch || after < minCursor) {
        throw ProtocolException(SyncErrorCode.CURSOR_EXPIRED, "cursor expired")
    }
    if (after > seq) {
        val code = if (purpose == CursorPurpose.RESET) SyncErrorCode.INVALID else SyncErrorCode.CURSOR_EXPIRED
        throw ProtocolException(code, "cursor ahead of account")
    }
    return after
}

internal enum class CursorPurpose { PAGE, RESET }

internal fun SyncRead.candidates(
    after: Long,
    extras: List<StoredRecord> = emptyList(),
    limit: Int = ProtocolLimits.MAX_RECORDS,
): PageCandidates = PageCandidates(account.cursor, after, page(after, limit + 1), extras, limit)
