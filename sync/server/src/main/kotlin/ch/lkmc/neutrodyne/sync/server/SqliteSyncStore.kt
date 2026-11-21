// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.ProtocolLimits
import ch.lkmc.neutrodyne.sync.protocol.ProtocolValidation
import ch.lkmc.neutrodyne.sync.protocol.RecordId
import ch.lkmc.neutrodyne.sync.protocol.RecordMerger
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Types
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** Connections are confined to callbacks. WAL read transactions see one snapshot throughout.
 * The lifetime lock lets close drain all callbacks before releasing connections/dispatchers.
 */
internal class SqliteSyncStore(
    dataDir: Path,
    private val publish: (ServerEvent) -> Unit = {},
    migrator: Migrator = Migrator(),
    private val feedCacheMaxEntryBytes: Int = FEED_CACHE_MAX_ENTRY_BYTES,
    private val feedCacheMaxAccountBytes: Long = FEED_CACHE_MAX_ACCOUNT_BYTES,
) : SyncStore,
    AuthStore {
    init {
        require(feedCacheMaxEntryBytes > 0)
        require(feedCacheMaxAccountBytes >= feedCacheMaxEntryBytes)
    }

    private val writerDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val readerDispatcher = Executors.newFixedThreadPool(READ_CONNECTIONS).asCoroutineDispatcher()
    private val lifetime = ReentrantReadWriteLock(true)
    private val readers = LinkedBlockingQueue<Connection>()
    private val snapshots = mutableSetOf<SqlSnapshot>()
    private val database = dataDir.resolve(DATABASE_FILE)
    private val writer: Connection
    private var closed = false

    init {
        prepareSqliteNative(dataDir)
        var opened: Connection? = null
        try {
            opened = connect(database)
            migrator.migrate(opened, dataDir)
            // An empty database already in WAL mode ignores the initial auto_vacuum setting.
            opened.sql("PRAGMA journal_mode = WAL")
            repeat(READ_CONNECTIONS) { readers += connect(database).apply { sql("PRAGMA query_only = ON") } }
            writer = opened
        } catch (failure: Throwable) {
            readers.forEach { it.close() }
            opened?.close()
            writerDispatcher.close()
            readerDispatcher.close()
            throw failure
        }
    }

    override suspend fun <T> read(
        accountId: Long,
        block: SyncRead.() -> T,
    ): T =
        withContext(readerDispatcher) {
            lifetime.read {
                check(!closed) { "Store is closed" }
                val connection = readers.take()
                try {
                    transaction(connection) {
                        val tx = JdbcRead(connection, accountId)
                        try {
                            tx.block()
                        } finally {
                            tx.end()
                        }
                    }
                } finally {
                    readers.put(connection)
                }
            }
        }

    override suspend fun <T> write(
        accountId: Long,
        block: SyncWrite.() -> T,
    ): T =
        withContext(writerDispatcher) {
            lifetime.read {
                check(!closed) { "Store is closed" }
                val tx =
                    JdbcWrite(writer, accountId, feedCacheMaxEntryBytes, feedCacheMaxAccountBytes)
                val result =
                    transaction(writer) {
                        try {
                            tx.block()
                        } finally {
                            tx.end()
                        }
                    }
                // No suspension between commit and publication: the next writer cannot overtake it.
                tx.events.forEach(publish)
                result
            }
        }

    override suspend fun <T> readAuth(block: AuthRead.() -> T): T =
        withContext(readerDispatcher) {
            lifetime.read {
                check(!closed) { "Store is closed" }
                val connection = readers.take()
                try {
                    transaction(connection) {
                        val tx = JdbcAuthRead(connection)
                        try {
                            tx.block()
                        } finally {
                            tx.end()
                        }
                    }
                } finally {
                    readers.put(connection)
                }
            }
        }

    override suspend fun <T> writeAuth(block: AuthWrite.() -> T): T =
        withContext(writerDispatcher) {
            lifetime.read {
                check(!closed) { "Store is closed" }
                val tx = JdbcAuthWrite(writer)
                val result =
                    transaction(writer) {
                        try {
                            tx.block()
                        } finally {
                            tx.end()
                        }
                    }
                tx.events.forEach(publish)
                result
            }
        }

    override suspend fun snapshot(accountId: Long): AccountSnapshot =
        withContext(readerDispatcher) {
            lifetime.read {
                check(!closed) { "Store is closed" }
                val connection = connect(database)
                try {
                    connection.sql("PRAGMA query_only = ON")
                    connection.autoCommit = false
                    // Pin the WAL snapshot before the caller starts iterating.
                    JdbcRead(connection, accountId).account
                    val installation =
                        connection
                            .query("SELECT installation_id FROM server_metadata") {
                                it.getString(1)
                            }.single()
                    SqlSnapshot(accountId, installation, connection).also { snapshot ->
                        synchronized(snapshots) { snapshots += snapshot }
                    }
                } catch (failure: Throwable) {
                    connection.close()
                    throw failure
                }
            }
        }

    override suspend fun backup(destination: Path): Path =
        withContext(writerDispatcher) {
            lifetime.read {
                check(!closed) { "Store is closed" }
                val target = destination.toAbsolutePath().normalize()
                val source = database.toAbsolutePath().normalize()
                val reserved = listOf(source, Path.of("$source-wal"), Path.of("$source-shm"))
                require(
                    reserved.none { path ->
                        path == target || (Files.exists(path) && Files.exists(target) && Files.isSameFile(path, target))
                    },
                ) { "Backup destination overlaps the live database" }
                databaseBackup(writer, destination)
            }
        }

    /** DB-only maintenance callback, serialized with requests. No connection escapes this scope. */
    suspend fun <T> maintenance(block: Connection.() -> T): T =
        withContext(writerDispatcher) {
            lifetime.read {
                check(!closed)
                writer.block()
            }
        }

    /** A real rolled-back write checks the database, not merely the volume's free-space estimate. */
    suspend fun writeProbe(): Boolean =
        withContext(writerDispatcher) {
            lifetime.read {
                if (closed) return@read false
                writer.autoCommit = false
                try {
                    runCatching { writer.sql("UPDATE server_metadata SET installation_id = installation_id") }.isSuccess
                } finally {
                    writer.rollback()
                    writer.autoCommit = true
                }
            }
        }

    /** Called only by the serve owner after a missing clean marker and successful quick_check. */
    suspend fun recoverUncleanStart(
        epoch: () -> String,
        nowMs: Long,
    ) = withContext(writerDispatcher) {
        lifetime.read {
            check(!closed)
            quickCheck(writer)
            transaction(writer) {
                val ids = writer.query("SELECT id FROM account") { it.getLong(1) }
                ids.forEach { id ->
                    val old = JdbcRead(writer, id).account.cursor.epoch
                    var next = epoch()
                    while (next == old) next = epoch()
                    writer.update("UPDATE account SET epoch = ? WHERE id = ?", next, id)
                }
                writer.update("INSERT INTO audit(at,event) VALUES (?,?)", nowMs, "unclean_start")
            }
        }
    }

    @Synchronized
    override fun close() {
        if (lifetime.read { closed }) return
        // The writer fence runs after already queued writes, before connections are closed.
        runBlocking {
            withContext(writerDispatcher) {
                lifetime.write {
                    closed = true
                    synchronized(snapshots) { snapshots.toList().forEach { it.close() } }
                    try {
                        readers.forEach { it.close() }
                        writer.close()
                    } finally {
                        writerDispatcher.close()
                        readerDispatcher.close()
                    }
                }
            }
        }
    }

    private inner class SqlSnapshot(
        override val accountId: Long,
        override val installationId: String,
        private val connection: Connection,
    ) : AccountSnapshot {
        override fun redirect(key: RecordKey): RecordKey? {
            check(!snapshotClosed) { "Snapshot is closed" }
            return connection
                .query(
                    "SELECT to_rid FROM identity_redirect WHERE account_id=? AND coll=? AND from_rid=?",
                    accountId,
                    key.collection.wire,
                    key.id.rid,
                ) { row ->
                    RecordKey(key.collection, RecordId.fromRid(key.collection, row.getString(1)))
                }.singleOrNull()
        }

        override val schemaVersion = Migrator.SCHEMA_VERSION
        private var snapshotClosed = false
        override val records: Sequence<StoredRecord> get() =
            sequence {
                check(!snapshotClosed) { "Snapshot is closed" }
                connection.prepareStatement("SELECT * FROM record WHERE account_id = ? ORDER BY seq").use { statement ->
                    statement.setLong(1, accountId)
                    statement.executeQuery().use { rows -> while (rows.next()) yield(rows.storedRecord()) }
                }
            }

        override fun close() {
            if (snapshotClosed) return
            snapshotClosed = true
            connection.close()
            synchronized(snapshots) { snapshots.remove(this) }
        }
    }

    internal companion object {
        const val DATABASE_FILE = "neutrodyne-server.db"
        const val READ_CONNECTIONS = 4
        const val BUSY_TIMEOUT_MS = 5_000

        /** Hard per-entry body bound; larger upstream responses are never cached. */
        const val FEED_CACHE_MAX_ENTRY_BYTES = ProtocolLimits.MAX_FEED_BYTES

        /** Hard per-account body bound; the oldest entries are evicted once it is exceeded. */
        const val FEED_CACHE_MAX_ACCOUNT_BYTES = 512L shl 20

        private fun connect(database: Path): Connection =
            DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").apply {
                try {
                    sql("PRAGMA synchronous = FULL")
                    sql("PRAGMA foreign_keys = ON")
                    sql("PRAGMA busy_timeout = $BUSY_TIMEOUT_MS")
                } catch (failure: Throwable) {
                    close()
                    throw failure
                }
            }
    }
}

private open class JdbcRead(
    private val jdbcConnection: Connection,
    protected val accountId: Long,
) : SyncRead {
    private var active = true
    private val owner = Thread.currentThread().threadId()
    protected val connection: Connection get() {
        check(active && owner == Thread.currentThread().threadId()) { "Transaction scope has ended" }
        return jdbcConnection
    }

    fun end() {
        active = false
    }

    override val account: AccountSyncState get() =
        connection
            .query(
                "SELECT epoch, seq, min_cursor, quota_records, clock_packed FROM account WHERE id = ?",
                accountId,
            ) {
                AccountSyncState(
                    AccountCursor(it.getString(1), it.getLong(2), it.getLong(3)),
                    it.getLong(4),
                    it.getLong(5),
                )
            }.singleOrNull() ?: error("Account does not exist")

    override fun requirePrincipal(
        principal: AccountPrincipal,
        now: Long,
    ) {
        val auth = JdbcAuthRead(connection)
        try {
            val verified = auth.requireToken(principal.tokenHash, principal.tokenKind, now)
            if (verified.accountId != accountId || verified.deviceId != principal.deviceId) unauthorized()
        } finally {
            auth.end()
        }
    }

    override fun liveCount(): Long =
        connection
            .query("SELECT COUNT(*) FROM record WHERE account_id = ? AND dead = 0", accountId) {
                it.getLong(1)
            }.single()

    override fun liveKeys(maxSeq: Long): List<RecordKey> =
        connection
            .query(
                "SELECT coll, rid FROM record WHERE account_id = ? AND dead = 0 AND seq <= ? ORDER BY seq",
                accountId,
                maxSeq,
            ) { key(SyncCollection.fromWire(it.getString(1)), it.getString(2)) }

    override fun record(key: RecordKey): StoredRecord? =
        connection
            .query(
                "SELECT * FROM record WHERE account_id = ? AND coll = ? AND rid = ?",
                accountId,
                key.collection.wire,
                key.id.rid,
            ) { it.storedRecord() }
            .singleOrNull()

    override fun records(): Sequence<StoredRecord> =
        connection
            .query(
                "SELECT * FROM record WHERE account_id = ? ORDER BY seq",
                accountId,
            ) { it.storedRecord() }
            .asSequence()

    override fun livePodcasts(): List<StoredRecord> =
        connection.query(
            "SELECT * FROM record WHERE account_id = ? AND coll = 'podcast' AND dead = 0 AND merged_into IS NULL",
            accountId,
        ) { it.storedRecord() }

    override fun maximumClock(): Hlc? =
        connection
            .query(
                "SELECT MAX(max_hlc) FROM record WHERE account_id = ?",
                accountId,
            ) { it.getString(1)?.let(Hlc::parse) }
            .single()

    override fun page(
        afterSequence: Long,
        limit: Int,
    ): List<StoredRecord> {
        require(afterSequence >= 0 && limit > 0)
        return connection.query(
            "SELECT * FROM record WHERE account_id = ? AND seq > ? ORDER BY seq LIMIT ?",
            accountId,
            afterSequence,
            limit,
        ) { it.storedRecord() }
    }

    override fun podcastOwners(feedKey: String): Set<RecordKey> =
        connection
            .query(
                "SELECT podcast_rid FROM podcast_key WHERE account_id = ? AND feed_key = ?",
                accountId,
                feedKey,
            ) { key(SyncCollection.PODCAST, it.getString(1)) }
            .toSet()

    override fun groupOwner(nameKey: String): RecordKey? =
        connection
            .query(
                "SELECT group_rid FROM group_name WHERE account_id = ? AND name_key = ?",
                accountId,
                nameKey,
            ) { key(SyncCollection.GROUP, it.getString(1)) }
            .singleOrNull()

    override fun redirects(): Map<RecordKey, RecordKey> =
        connection
            .query(
                "SELECT coll, from_rid, to_rid FROM identity_redirect WHERE account_id = ?",
                accountId,
            ) {
                val collection = SyncCollection.fromWire(it.getString(1))
                key(collection, it.getString(2)) to key(collection, it.getString(3))
            }.toMap()

    override fun feedCacheHead(url: String): FeedCacheHead? =
        connection
            .query(
                "SELECT final_url, sha256, checked_at, body_bytes, etag, last_modified, permanent_url, http_charset " +
                    "FROM feed_cache WHERE account_id=? AND url=?",
                accountId,
                url,
            ) {
                FeedCacheHead(
                    it.getString(1),
                    it.getString(2),
                    it.getLong(3),
                    it.getInt(4),
                    it.getString(5),
                    it.getString(6),
                    it.getString(7),
                    it.getString(8),
                )
            }.singleOrNull()

    override fun feedCache(urls: Collection<String>): List<StoredFeedCache> {
        if (urls.isEmpty()) return emptyList()
        val values = listOf<Any?>(accountId) + urls
        val placeholders = urls.joinToString(",") { "?" }
        return connection.query(
            "SELECT url, final_url, sha256, etag, last_modified, body, checked_at, podcast_rid, " +
                "permanent_url, http_charset " +
                "FROM feed_cache WHERE account_id = ? AND url IN ($placeholders)",
            *values.toTypedArray(),
        ) { row ->
            StoredFeedCache(
                row.getString(1),
                row.getString(2),
                row.getString(3),
                row.getString(4),
                row.getString(5),
                row.getBytes(6),
                row.getLong(7),
                row.getString(8),
                row.getString(9),
                row.getString(10),
            )
        }
    }
}

/** Podcast fields the scheduler reads; a change to any of them can invalidate the cached body. */
private val FEED_CACHE_POLLING_FIELDS =
    setOf(
        "feedUrl",
        "sourceType",
        "youtubeChannelId",
        "youtubeVariants",
        "needsCredentials",
    )

/** SQLite binds a bounded number of parameters per statement; eviction can select many rows. */
private const val FEED_CACHE_DELETE_BATCH = 500

private class JdbcWrite(
    connection: Connection,
    accountId: Long,
    private val feedCacheMaxEntryBytes: Int,
    private val feedCacheMaxAccountBytes: Long,
) : JdbcRead(connection, accountId),
    SyncWrite {
    val events = mutableListOf<ServerEvent>()

    override fun nextSequence(): Long {
        check(connection.update("UPDATE account SET seq = seq + 1 WHERE id = ?", accountId) == 1)
        return account.cursor.seq
    }

    override fun put(record: StoredRecord) {
        require(record.raw.coll == record.key.collection)
        if (record.key.collection == SyncCollection.PODCAST &&
            feedCacheInvalidated(record.key, record.raw)
        ) {
            connection.update(
                "DELETE FROM feed_cache WHERE account_id=? AND podcast_rid=?",
                accountId,
                record.key.id.rid,
            )
        }
        connection.update(
            """INSERT INTO record(account_id,coll,rid,seq,data,max_hlc,dead,dead_at,merged_into,by_device,updated_at)
               VALUES (?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(account_id,coll,rid) DO UPDATE SET
               seq=excluded.seq,data=excluded.data,max_hlc=excluded.max_hlc,dead=excluded.dead,dead_at=excluded.dead_at,
               merged_into=excluded.merged_into,by_device=excluded.by_device,updated_at=excluded.updated_at""",
            accountId,
            record.key.collection.wire,
            record.key.id.rid,
            record.seq,
            SyncJson.encodeToString(RecordState.serializer(), record.raw),
            record.maxClock?.toString(),
            if (record.deadAtMs != null) 1 else 0,
            record.deadAtMs,
            record.raw.mergedInto?.rid,
            record.byDevice,
            record.updatedAtMs,
        )
    }

    /**
     * A cached body belongs to the podcast's polling identity. Drop it only when that identity,
     * its authorization, or its live/merged state changes; a metadata-only write keeps the body
     * and its validators. The delete shares the write transaction, so a rollback restores both.
     */
    private fun feedCacheInvalidated(
        key: RecordKey,
        raw: RecordState,
    ): Boolean {
        val prior = record(key) ?: return true
        if (prior.raw.mergedInto != raw.mergedInto ||
            RecordMerger.isDead(prior.raw) != RecordMerger.isDead(raw)
        ) {
            return true
        }
        // The passwords are usable only when the credential and its origin carry the same clock.
        if (prior.raw.fields["auth"] != raw.fields["auth"] ||
            prior.raw.fields["credentialOrigin"] != raw.fields["credentialOrigin"]
        ) {
            return true
        }
        return FEED_CACHE_POLLING_FIELDS.any { field -> prior.raw.fields[field]?.v != raw.fields[field]?.v }
    }

    override fun remove(key: RecordKey) {
        connection.update(
            "DELETE FROM record WHERE account_id = ? AND coll = ? AND rid = ?",
            accountId,
            key.collection.wire,
            key.id.rid,
        )
    }

    override fun setAccount(state: AccountSyncState) {
        val current = account
        require(state.cursor.seq >= current.cursor.seq && state.clockPacked >= current.clockPacked)
        connection.update(
            "UPDATE account SET epoch=?,seq=?,min_cursor=?,quota_records=?,clock_packed=? WHERE id=?",
            state.cursor.epoch,
            state.cursor.seq,
            state.cursor.minCursor,
            state.quotaRecords,
            state.clockPacked,
            accountId,
        )
    }

    override fun setPodcastKeys(
        key: RecordKey,
        feedKeys: Set<String>,
    ) {
        require(key.collection == SyncCollection.PODCAST)
        val record = checkNotNull(record(key))
        connection.update("DELETE FROM podcast_key WHERE account_id=? AND podcast_rid=?", accountId, key.id.rid)
        feedKeys.forEach { feedKey ->
            connection.update(
                "INSERT INTO podcast_key(account_id,feed_key,podcast_rid,live,dead_at) VALUES (?,?,?,?,?)",
                accountId,
                feedKey,
                key.id.rid,
                if (record.deadAtMs == null) 1 else 0,
                record.deadAtMs,
            )
        }
    }

    override fun setGroupName(
        key: RecordKey,
        nameKey: String?,
    ) {
        require(key.collection == SyncCollection.GROUP)
        connection.update("DELETE FROM group_name WHERE account_id=? AND group_rid=?", accountId, key.id.rid)
        if (nameKey == null) return
        connection.update(
            "INSERT INTO group_name(account_id,name_key,group_rid) VALUES (?,?,?)",
            accountId,
            nameKey,
            key.id.rid,
        )
    }

    override fun assignRedirect(
        from: RecordKey,
        to: RecordKey,
    ) {
        require(from.collection == to.collection && from != to)
        connection.update(
            "INSERT INTO identity_redirect VALUES (?,?,?,?) ON CONFLICT(account_id,coll,from_rid) DO NOTHING",
            accountId,
            from.collection.wire,
            from.id.rid,
            to.id.rid,
        )
        check(redirects()[from] == to) { "Redirect authority is immutable" }
    }

    override fun putFeedCache(entry: StoredFeedCache) {
        require(entry.body.size <= feedCacheMaxEntryBytes) { "Cached feed body exceeds the entry bound" }
        connection.update(
            """INSERT INTO feed_cache(account_id,url,final_url,sha256,etag,last_modified,body,body_bytes,checked_at,podcast_rid,permanent_url,http_charset)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(account_id,url) DO UPDATE SET
               final_url=excluded.final_url, sha256=excluded.sha256, etag=excluded.etag,
               last_modified=excluded.last_modified, body=excluded.body,
               body_bytes=excluded.body_bytes, checked_at=excluded.checked_at, podcast_rid=excluded.podcast_rid,
               permanent_url=excluded.permanent_url, http_charset=excluded.http_charset""",
            accountId,
            entry.url,
            entry.finalUrl,
            entry.sha256,
            entry.etag,
            entry.lastModified,
            entry.body,
            entry.body.size,
            entry.checkedAtMs,
            entry.podcastRid,
            entry.permanentUrl,
            entry.httpCharset,
        )
        evictFeedCacheOverflow()
    }

    override fun touchFeedCache(
        url: String,
        checkedAt: Long,
    ) {
        connection.update("UPDATE feed_cache SET checked_at=? WHERE account_id=? AND url=?", checkedAt, accountId, url)
    }

    private fun deleteFeedCache(urls: Collection<String>) {
        urls.chunked(FEED_CACHE_DELETE_BATCH).forEach { batch ->
            val values = listOf<Any?>(accountId) + batch
            val placeholders = batch.joinToString(",") { "?" }
            connection.update(
                "DELETE FROM feed_cache WHERE account_id = ? AND url IN ($placeholders)",
                *values.toTypedArray(),
            )
        }
    }

    /** Oldest entries go first; the just-written row is newest, so it survives a normal refresh. */
    private fun evictFeedCacheOverflow() {
        val total =
            connection
                .query(
                    "SELECT COALESCE(SUM(body_bytes), 0) FROM feed_cache WHERE account_id = ?",
                    accountId,
                ) { it.getLong(1) }
                .single()
        if (total <= feedCacheMaxAccountBytes) return
        val ordered =
            connection.query(
                "SELECT url, body_bytes FROM feed_cache WHERE account_id = ? " +
                    "ORDER BY checked_at ASC, url ASC",
                accountId,
            ) { it.getString(1) to it.getLong(2) }
        var remaining = total
        val evict = mutableListOf<String>()
        for ((url, bytes) in ordered) {
            if (remaining <= feedCacheMaxAccountBytes) break
            evict += url
            remaining -= bytes
        }
        if (evict.isNotEmpty()) deleteFeedCache(evict)
    }

    override fun emit(event: ServerEvent) {
        connection // Enforce callback lifetime even for events, which do not execute SQL.
        require(event.accountId == accountId)
        events += event
    }

    override fun audit(
        event: String,
        now: Long,
    ) {
        connection.update("INSERT INTO audit(account_id,at,event) VALUES (?,?,?)", accountId, now, event)
    }

    override fun acknowledge(
        deviceId: String,
        appliedSince: String?,
        seenAtMs: Long,
    ) {
        val sequence =
            appliedSince?.let {
                ProtocolValidation.cursor(it)
                val cursor = account.cursor
                require(it.substringAfter("c:").substringBefore(':') == cursor.epoch)
                it.substringAfterLast(':').toLong().also { seq -> require(seq <= cursor.seq) }
            }
        connection.update(
            "UPDATE device SET last_cursor=?,last_seen_at=? WHERE account_id=? AND id=?",
            sequence,
            seenAtMs,
            accountId,
            deviceId,
        )
    }
}

private fun key(
    collection: SyncCollection,
    rid: String,
) = RecordKey(collection, RecordId.fromRid(collection, rid))

internal fun ResultSet.storedRecord(): StoredRecord =
    StoredRecord(
        key(SyncCollection.fromWire(getString("coll")), getString("rid")),
        SyncJson.decodeFromString(RecordState.serializer(), getString("data")),
        getLong("seq"),
        getString("by_device"),
        getLong("updated_at"),
        getLong("dead_at").let { if (wasNull()) null else it },
    )

internal fun Connection.sql(sql: String) {
    createStatement().use { it.execute(sql) }
}

internal fun Connection.update(
    sql: String,
    vararg values: Any?,
): Int =
    prepareStatement(sql).use { statement ->
        values.forEachIndexed { index, value ->
            if (value == null) statement.setNull(index + 1, Types.NULL) else statement.setObject(index + 1, value)
        }
        statement.executeUpdate()
    }

internal fun <T> Connection.query(
    sql: String,
    vararg values: Any?,
    row: (ResultSet) -> T,
): List<T> =
    prepareStatement(sql).use { statement ->
        values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(row(rows)) } }
    }

internal fun <T> transaction(
    connection: Connection,
    block: () -> T,
): T {
    connection.autoCommit = false
    return try {
        val result = block()
        connection.commit()
        result
    } catch (failure: Throwable) {
        connection.rollback()
        throw failure
    } finally {
        connection.autoCommit = true
    }
}
