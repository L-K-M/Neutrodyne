// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.HlcClock
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.RecordId
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeResultDto
import ch.lkmc.neutrodyne.sync.protocol.dto.SyncRequest
import kotlinx.coroutines.flow.Flow
import java.nio.file.Path

internal enum class AccountRole { ADMIN, USER }

internal enum class TokenKind { DEVICE, WEB_SESSION, INVITE, SETUP, APP_PASSWORD }

/** Only authenticated device/web credentials become principals. Redemption codes never do. */
internal data class AccountPrincipal(
    val accountId: Long,
    val role: AccountRole,
    val tokenKind: TokenKind,
    val tokenHash: String,
    val deviceId: String?,
) {
    init {
        require(tokenKind == TokenKind.DEVICE || tokenKind == TokenKind.WEB_SESSION)
        require((tokenKind == TokenKind.DEVICE) == (deviceId != null))
    }
}

internal data class RecordKey(
    val collection: SyncCollection,
    val id: RecordId,
) {
    init {
        require(RecordId.fromWire(collection, id.toWire()) == id)
    }
}

/** Persist the complete RecordState, including explicit null, reset and matchClocks. */
internal data class StoredRecord(
    val key: RecordKey,
    val raw: RecordState,
    val seq: Long,
    val byDevice: String?,
    val updatedAtMs: Long,
    val deadAtMs: Long?,
) {
    val maxClock: Hlc? get() = raw.maxClock
}

internal data class AccountCursor(
    val epoch: String,
    val seq: Long,
    val minCursor: Long,
)

internal data class AccountSyncState(
    val cursor: AccountCursor,
    val quotaRecords: Long,
    val clockPacked: Long,
)

/** Extras never advance cursor or count toward the page's 1,000-record/8-MiB budget. */
internal data class RecordPage(
    val records: List<StoredRecord>,
    val cursor: String,
    val hasMore: Boolean,
    val extras: List<StoredRecord> = emptyList(),
)

internal data class PushResult(
    val results: List<ChangeResultDto>,
    val page: RecordPage,
    val serverTimeMs: Long,
)

/** Blocking DB-only callbacks, confined to a store dispatcher. No handle may escape the callback.
 * Write rollback includes sequences, indexes, clock watermark and queued events. No request/response encoding,
 * suspending, network or filesystem work in callbacks; decode before, encode after commit.
 */
internal interface SyncStore : AutoCloseable {
    suspend fun <T> read(
        accountId: Long,
        block: SyncRead.() -> T,
    ): T

    suspend fun <T> write(
        accountId: Long,
        block: SyncWrite.() -> T,
    ): T

    /** A stable detached snapshot; caller closes it after streaming ZIP/OPML outside the writer. */
    suspend fun snapshot(accountId: Long): AccountSnapshot

    /** Consistent VACUUM INTO primitive; validates and atomically publishes destination. */
    suspend fun backup(destination: Path): Path
}

internal interface SyncRead {
    val account: AccountSyncState

    fun record(key: RecordKey): StoredRecord?

    fun records(): Sequence<StoredRecord>

    fun maximumClock(): Hlc?

    fun page(
        afterSequence: Long,
        limit: Int,
    ): List<StoredRecord>

    fun podcastOwners(feedKey: String): Set<RecordKey>

    fun groupOwner(nameKey: String): RecordKey?

    fun redirects(): Map<RecordKey, RecordKey>
}

internal interface SyncWrite : SyncRead {
    /** Each touched record gets a distinct next sequence, even inside a batch. */
    fun nextSequence(): Long

    fun put(record: StoredRecord)

    fun remove(key: RecordKey)

    fun setAccount(state: AccountSyncState)

    fun setPodcastKeys(
        key: RecordKey,
        feedKeys: Set<String>,
    )

    fun setGroupName(
        key: RecordKey,
        nameKey: String?,
    )

    /** Immutable authority, retained beyond record GC and carried over during normal restore. */
    fun assignRedirect(
        from: RecordKey,
        to: RecordKey,
    )

    /** Delivered only after successful commit, in commit order. */
    fun emit(event: ServerEvent)

    /** Security event committed with a library reset. Only fixed event identifiers. */
    fun audit(
        event: String,
        now: Long,
    )

    /** Persist request.since, never the response cursor, for retention acknowledgements. */
    fun acknowledge(
        deviceId: String,
        appliedSince: String?,
        seenAtMs: Long,
    )
}

internal interface AccountSnapshot : AutoCloseable {
    val accountId: Long
    val installationId: String
    val schemaVersion: Int

    fun redirect(key: RecordKey): RecordKey?

    val records: Sequence<StoredRecord>
}

internal interface AccountBackupWriter {
    /** Caller owns snapshot lifetime; output is verified then atomically published at destination. */
    suspend fun export(
        snapshot: AccountSnapshot,
        destination: Path,
    ): Path
}

internal interface RecordWriter {
    /** Validate cursor before mutation; semantic rejection is independent for each change. */
    suspend fun push(
        principal: AccountPrincipal,
        request: SyncRequest,
    ): PushResult
}

internal sealed interface ServerEvent {
    val accountId: Long

    data class Changed(
        override val accountId: Long,
        val cursor: String,
        val byDevice: String?,
    ) : ServerEvent

    /** Null selectors revoke all account streams; otherwise close matching token/device streams. */
    data class Revoked(
        override val accountId: Long,
        val tokenHash: String? = null,
        val deviceId: String? = null,
    ) : ServerEvent
}

internal interface ServerEvents : AutoCloseable {
    fun publish(event: ServerEvent)

    /** Register then recheck credential validity to close subscribe/revoke races. No replay. */
    fun subscribe(principal: AccountPrincipal): Flow<ServerEvent>
}

/** Functions allow deterministic tests without using Kotlin Random for secrets in production. */
internal class ServerInputs(
    val wallMs: () -> Long,
    val randomBytes: (Int) -> ByteArray,
) {
    /** Seed from persisted maxima on opening an account; later transactions use its watermark. Never clamp. */
    fun clock(
        account: AccountSyncState,
        persistedMaximum: Hlc?,
    ): HlcClock =
        HlcClock(
            NodeId.SERVER,
            wallMs,
            packed = maxOf(account.clockPacked, persistedMaximum?.packed ?: 0L),
        )
}
