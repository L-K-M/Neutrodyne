// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.dto.SyncRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.nio.file.Path

/** Scripted ports compiled only in server tests, with no shared-testing -> server dependency.
 * These do not simulate transaction isolation, rollback or commit publication; W2 proves those.
 */
internal class FakeSyncStore : SyncStore {
    val accounts = mutableMapOf<Long, FakeSyncTransaction>()
    var writes = 0
        private set
    var snapshot: AccountSnapshot? = null
    var backup: suspend (Path) -> Path = { error("configure backup fixture") }

    override suspend fun <T> read(
        accountId: Long,
        block: SyncRead.() -> T,
    ): T = accounts.getValue(accountId).block()

    override suspend fun <T> write(
        accountId: Long,
        block: SyncWrite.() -> T,
    ): T {
        writes++
        return accounts.getValue(accountId).block()
    }

    override suspend fun snapshot(accountId: Long): AccountSnapshot = checkNotNull(snapshot)

    override suspend fun backup(destination: Path): Path = backup.invoke(destination)

    override fun close() = Unit
}

internal class FakeSyncTransaction : SyncWrite {
    private var state = AccountSyncState(AccountCursor("00000001", 0, 0), ServerDefaults.QUOTA_RECORDS, 0)
    override val account: AccountSyncState get() = state
    val raw = mutableMapOf<RecordKey, StoredRecord>()
    val podcasts = mutableMapOf<RecordKey, Set<String>>()
    val groups = mutableMapOf<String, RecordKey>()
    val ledger = mutableMapOf<RecordKey, RecordKey>()
    val events = mutableListOf<ServerEvent>()
    val acknowledgements = mutableMapOf<String, Pair<String?, Long>>()

    override fun record(key: RecordKey): StoredRecord? = raw[key]

    override fun records(): Sequence<StoredRecord> = raw.values.asSequence()

    override fun maximumClock(): Hlc? = raw.values.mapNotNull { it.maxClock }.maxOrNull()

    override fun page(
        afterSequence: Long,
        limit: Int,
    ): List<StoredRecord> =
        raw.values
            .filter {
                it.seq > afterSequence
            }.sortedBy { it.seq }
            .take(limit)

    override fun podcastOwners(feedKey: String): Set<RecordKey> = podcasts.filterValues { feedKey in it }.keys

    override fun groupOwner(nameKey: String): RecordKey? = groups[nameKey]

    override fun redirects(): Map<RecordKey, RecordKey> = ledger.toMap()

    override fun nextSequence(): Long {
        state = account.copy(cursor = account.cursor.copy(seq = account.cursor.seq + 1))
        return account.cursor.seq
    }

    override fun put(record: StoredRecord) {
        raw[record.key] = record
    }

    override fun remove(key: RecordKey) {
        raw.remove(key)
    }

    override fun setAccount(state: AccountSyncState) {
        this.state = state
    }

    override fun setPodcastKeys(
        key: RecordKey,
        feedKeys: Set<String>,
    ) {
        podcasts[key] = feedKeys
    }

    override fun setGroupName(
        key: RecordKey,
        nameKey: String?,
    ) {
        groups.entries.removeAll { it.value == key }
        if (nameKey != null) groups[nameKey] = key
    }

    override fun assignRedirect(
        from: RecordKey,
        to: RecordKey,
    ) {
        require(from != to && from.collection == to.collection)
        check(ledger[from] == null || ledger[from] == to)
        ledger[from] = to
    }

    override fun emit(event: ServerEvent) {
        events += event
    }

    override fun acknowledge(
        deviceId: String,
        appliedSince: String?,
        seenAtMs: Long,
    ) {
        acknowledgements[deviceId] =
            appliedSince to seenAtMs
    }
}

internal class FakeAccountSnapshot(
    override val records: Sequence<StoredRecord>,
) : AccountSnapshot {
    override val accountId = 1L
    override val installationId = "test-installation"
    override val schemaVersion = 1

    override fun close() = Unit
}

internal class FakeServerEvents : ServerEvents {
    val published = mutableListOf<ServerEvent>()
    var subscription: Flow<ServerEvent> = emptyFlow()

    override fun publish(event: ServerEvent) {
        published += event
    }

    override fun subscribe(principal: AccountPrincipal): Flow<ServerEvent> = subscription

    override fun close() = Unit
}

internal class FakeRecordWriter : RecordWriter {
    var push: suspend (AccountPrincipal, SyncRequest) -> PushResult = { _, _ -> error("configure push fixture") }

    override suspend fun push(
        principal: AccountPrincipal,
        request: SyncRequest,
    ): PushResult = push.invoke(principal, request)
}

internal class FakeAccountBackupWriter : AccountBackupWriter {
    var export: suspend (AccountSnapshot, Path) -> Path = { _, _ -> error("configure export fixture") }

    override suspend fun export(
        snapshot: AccountSnapshot,
        destination: Path,
    ): Path = export.invoke(snapshot, destination)
}
