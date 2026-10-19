// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import io.ktor.server.application.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/** Shared by exports, nightly copies and destructive account operations, including live CLI users. */
internal class AccountBackups(
    private val store: SqliteSyncStore,
    private val config: ServerConfig,
    private val inputs: ServerInputs,
    private val writer: AccountBackupWriter = AccountArchiveWriter(inputs),
) {
    private val downloads = mutableMapOf<Long, Pair<Long, Int>>()

    suspend fun authorizeDownload(principal: AccountPrincipal) {
        store.readAuth { requireToken(principal.tokenHash, principal.tokenKind, inputs.wallMs()) }
        synchronized(downloads) {
            val now = inputs.wallMs()
            val old = downloads[principal.accountId]?.takeIf { now < it.first } ?: (now + DOWNLOAD_WINDOW_MS to 0)
            if (old.second >= DOWNLOADS_PER_WINDOW) throw AuthThrottled(((old.first - now) / 1000).coerceAtLeast(1))
            downloads[principal.accountId] = old.first to old.second + 1
        }
    }

    private val locks = ConcurrentHashMap<Long, Mutex>()

    suspend fun <T> locked(
        id: Long,
        block: suspend () -> T,
    ): T =
        locks.computeIfAbsent(id) { Mutex() }.withLock {
            withContext(Dispatchers.IO) {
                privateDirectory(config.dataDir.resolve("account-backups"))
                FileChannel
                    .open(
                        config.dataDir.resolve("account-backups/.$id.lock"),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                    ).use { channel ->
                        // A different process exporting this account must retry; never race deletion.
                        channel.tryLock().use { lease ->
                            check(lease != null) { "Account backup is in use; retry the command" }
                            block()
                        }
                    }
            }
        }

    suspend fun export(
        id: Long,
        date: LocalDate = localDate(inputs.wallMs()),
    ): Path =
        locked(id) {
            exportLocked(id, date)
        }

    suspend fun <T> download(
        principal: AccountPrincipal,
        block: suspend (Path) -> T,
    ): T =
        locked(principal.accountId) {
            store.readAuth { requireToken(principal.tokenHash, principal.tokenKind, inputs.wallMs()) }
            block(exportLocked(principal.accountId, localDate(inputs.wallMs())))
        }

    private suspend fun exportLocked(
        id: Long,
        date: LocalDate,
    ): Path {
        // Recheck after acquiring the lock so a deleted account cannot recreate a backup directory.
        store.readAuth { checkNotNull(account(id)) }
        val directory = config.dataDir.resolve("account-backups/$id")
        val path = directory.resolve("neutrodyne-backup-$date.zip")
        store.snapshot(id).use { writer.export(it, path) }
        retain(directory, Regex("neutrodyne-backup-\\d{4}-\\d{2}-\\d{2}\\.zip"), config.accountBackupsKeep)
        return path
    }

    private companion object {
        const val DOWNLOADS_PER_WINDOW = 6
        const val DOWNLOAD_WINDOW_MS = 3_600_000L
    }

    fun deleteLocked(id: Long) {
        val directory = config.dataDir.resolve("account-backups/$id")
        if (!Files.exists(directory)) return
        Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}

internal class BackupJob(
    private val store: SqliteSyncStore,
    private val config: ServerConfig,
    private val accounts: AccountBackups,
    private val metrics: ServerMetrics,
    private val inputs: ServerInputs,
) {
    private val mutex = Mutex()

    suspend fun run() =
        mutex.withLock {
            val now = inputs.wallMs()
            try {
                val directory = config.dataDir.resolve("backups")
                privateDirectory(directory)
                store.backup(directory.resolve("neutrodyne-server-${localDate(now)}.db"))
                store.readAuth { accounts().map { it.id } }.forEach { accounts.export(it) }
                retain(directory, Regex("neutrodyne-server-\\d{4}-\\d{2}-\\d{2}\\.db"), config.backupsKeep)
                // Pre-upgrade evidence survives until a complete successful nightly run seven days later.
                Files.list(directory).use { paths ->
                    paths
                        .filter { it.fileName.toString().startsWith("neutrodyne-server-pre-migration-") }
                        .filter { Files.getLastModifiedTime(it).toMillis() <= now - 7 * DAY_MS }
                        .forEach(Files::delete)
                }
                metrics.backupFinished(now, BackupResult.SUCCESS)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                metrics.backupFinished(now, BackupResult.FAILURE)
                throw failure
            }
        }

    fun start(
        application: Application,
        gc: GcJob,
    ) {
        application.launch(Dispatchers.IO) {
            while (true) {
                val now = Instant.ofEpochMilli(inputs.wallMs()).atZone(ZoneId.systemDefault())
                val time = LocalTime.parse(config.backupTime)
                var next = ZonedDateTime.of(now.toLocalDate(), time, now.zone)
                if (!next.isAfter(now)) next = next.plusDays(1)
                delay(
                    java.time.Duration
                        .between(now, next)
                        .toMillis(),
                )
                try {
                    run()
                    delay(GC_DELAY_MS)
                    gc.run()
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    application.environment.log.error("Scheduled backup or GC failed; check storage and permissions")
                }
            }
        }
    }

    private companion object {
        const val GC_DELAY_MS = 30 * 60_000L
    }
}

private fun localDate(now: Long): LocalDate = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()

private fun retain(
    directory: Path,
    pattern: Regex,
    keep: Int,
) {
    Files.list(directory).use { paths ->
        paths
            .filter {
                pattern.matches(it.fileName.toString())
            }.sorted(Comparator.reverseOrder())
            .skip(keep.toLong())
            .forEach(Files::delete)
    }
}
