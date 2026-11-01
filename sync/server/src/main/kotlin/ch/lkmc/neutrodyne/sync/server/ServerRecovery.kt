// SPDX-License-Identifier: Unlicense
@file:Suppress("ktlint:standard:max-line-length")

package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.RecordMerger
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/** Offline replacement: validate first, retain the replaced WAL snapshot, publish last. */
internal class ServerRecovery(
    private val dataDir: Path,
    private val inputs: ServerInputs = productionInputs(),
) {
    init {
        prepareSqliteNative(dataDir)
    }

    suspend fun restore(backup: Path): Path =
        withContext(Dispatchers.IO) {
            val database = dataDir.resolve(SqliteSyncStore.DATABASE_FILE)
            require(Files.isRegularFile(backup)) { "Backup file does not exist" }
            check(Files.isRegularFile(database)) {
                "Current identity ledger is unavailable; ordinary restore refused. Recover the current database or create a fresh server and re-link every device."
            }
            require(!Files.isSameFile(database, backup)) { "Backup is the current database" }
            open(backup).use { validate(it) }
            // Corrupt authority must never silently reassign identities or revive stale credentials.
            open(database).use { validate(it) }
            val stage = Files.createTempDirectory(dataDir, ".restore-")
            val stagedDatabase = stage.resolve(SqliteSyncStore.DATABASE_FILE)
            val replaced = dataDir.resolve("neutrodyne-server-replaced-${inputs.wallMs()}-${UUID.randomUUID()}.db")
            try {
                open(database).use { current ->
                    databaseBackup(current, stagedDatabase)
                    databaseBackup(current, replaced)
                }
                SqliteSyncStore(stage).use { store ->
                    store.maintenance {
                        prepareStatement("ATTACH DATABASE ? AS recovery").use {
                            it.setString(1, backup.toAbsolutePath().toString())
                            it.execute()
                        }
                        try {
                            transaction(this) {
                                update("DELETE FROM link_request")
                                update("DELETE FROM record")
                                // The staging DB began as current, so current accounts, devices, revocations,
                                // audit and installation identity survive even when created after the backup.
                                update(
                                    """INSERT INTO record SELECT r.* FROM recovery.record r JOIN account a ON a.id=r.account_id
                                JOIN recovery.account old ON old.id=r.account_id AND old.username=a.username""",
                                )
                                update(
                                    """INSERT INTO identity_redirect SELECT r.* FROM recovery.identity_redirect r JOIN account a ON a.id=r.account_id
                                JOIN recovery.account old ON old.id=r.account_id AND old.username=a.username
                                WHERE NOT EXISTS (SELECT 1 FROM identity_redirect live WHERE live.account_id=r.account_id AND live.coll=r.coll AND live.from_rid=r.from_rid)""",
                                )
                                update(
                                    "UPDATE account SET seq=MAX(seq,COALESCE((SELECT MAX(seq) FROM record WHERE account_id=account.id),0)), min_cursor=0",
                                )
                            }
                        } finally {
                            sql("DETACH DATABASE recovery")
                        }
                    }
                    for (id in store.readAuth { accounts().map { it.id } }) {
                        store.write(id) {
                            val changes =
                                RecordChanges(this, inputs.clock(account, maximumClock()), inputs.wallMs(), null)
                            val original = records().toList()
                            // Ignore obsolete backup redirects. The carried ledger is the authority.
                            original.forEach { row ->
                                if (row.raw.mergedInto != null) put(row.copy(raw = row.raw.copy(mergedInto = null)))
                            }
                            original.forEach { row ->
                                val target = changes.resolve(row.key)
                                if (target != row.key) changes.move(row.key, target)
                            }
                            redirects().forEach { (from, to) -> changes.redirect(from, to) }
                            changes.commit(0)
                            records().toList().forEach { row ->
                                when (row.key.collection) {
                                    SyncCollection.PODCAST -> {
                                        setPodcastKeys(
                                            row.key,
                                            RecordChanges.podcastKeys(row.raw),
                                        )
                                    }

                                    SyncCollection.GROUP -> {
                                        setGroupName(row.key, RecordChanges.groupName(row.raw))
                                    }

                                    else -> {
                                        Unit
                                    }
                                }
                            }
                            val state = account
                            setAccount(state.copy(cursor = state.cursor.copy(epoch = freshEpoch(state.cursor.epoch))))
                            audit("database_restored", inputs.wallMs())
                        }
                    }
                    store.maintenance {
                        sql("PRAGMA wal_checkpoint(TRUNCATE)")
                        validate(this)
                    }
                }
                // Closing all staged connections checkpoints WAL. No live process can hold the exclusive lease.
                Files.deleteIfExists(dataDir.resolve("${SqliteSyncStore.DATABASE_FILE}-wal"))
                Files.deleteIfExists(dataDir.resolve("${SqliteSyncStore.DATABASE_FILE}-shm"))
                Files.move(
                    stagedDatabase,
                    database,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
                Files.deleteIfExists(dataDir.resolve(ServerStorage.CLEAN_MARKER))
                replaced
            } finally {
                Files.walk(stage).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
        }

    private fun freshEpoch(old: String): String {
        val secrets = AuthSecrets(inputs)
        var next = secrets.epoch()
        while (next == old) next = secrets.epoch()
        return next
    }

    companion object {
        fun open(path: Path): Connection = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")

        fun validate(connection: Connection) {
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA integrity_check").use {
                    check(it.next() && it.getString(1) == "ok" && !it.next()) { "Database integrity check failed" }
                }
            }
            check(Migrator.version(connection) in 1..Migrator.SCHEMA_VERSION) { "Unsupported database schema" }
            check(
                connection
                    .query("PRAGMA foreign_key_check") {
                        it.getString(1)
                    }.isEmpty(),
            ) { "Database foreign keys are invalid" }
            // Missing authority is not equivalent to an empty ledger.
            connection.query("SELECT COUNT(*) FROM identity_redirect") { it.getLong(1) }
        }
    }
}
