// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import io.ktor.http.ContentType
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal data class ServerStats(
    val accounts: Int,
    val records: Long,
    val databaseBytes: Long,
    val backupAt: Long?,
    val backupResult: String,
)

/** Bounded labels, no monitoring dependency. W7 records backup/GC results through these hooks. */
internal class ServerMetrics(
    private val store: AuthStore? = null,
    private val dataDir: Path? = null,
) {
    private val requests = ConcurrentHashMap<Pair<String, Int>, AtomicLong>()
    private val changes = listOf("applied", "stale", "merged", "rejected").associateWith { AtomicLong() }
    private val returned = AtomicLong()
    private val streams = AtomicLong()
    private val purged = AtomicLong()

    @Volatile private var backup = BackupState(null, "none")

    private data class BackupState(
        val at: Long?,
        val result: String,
    )

    fun request(
        route: String,
        status: Int,
    ) {
        require(route in RequestLogger.TEMPLATES && status in 100..599)
        requests.computeIfAbsent(route to status) { AtomicLong() }.incrementAndGet()
    }

    fun changes(statuses: List<String>) {
        statuses.forEach { changes[it]?.incrementAndGet() }
    }

    fun returned(count: Int) {
        require(count >= 0)
        returned.addAndGet(count.toLong())
    }

    fun streamOpened() {
        streams.incrementAndGet()
    }

    fun streamClosed() {
        streams.decrementAndGet()
    }

    fun gcPurged(count: Long) {
        require(count >= 0)
        purged.addAndGet(count)
    }

    fun backupFinished(
        at: Long,
        result: BackupResult,
    ) {
        backup = BackupState(at, result.name.lowercase())
    }

    suspend fun snapshot(): ServerStats {
        val counts = store?.readAuth { accounts().size to storedRecordCount() } ?: (0 to 0L)
        val size =
            dataDir?.resolve(SqliteSyncStore.DATABASE_FILE)?.let { if (Files.exists(it)) Files.size(it) else 0L } ?: 0L
        val state = backup
        return ServerStats(counts.first, counts.second, size, state.at, state.result)
    }

    suspend fun prometheus(now: Long = System.currentTimeMillis()): String {
        val stats = snapshot()
        val backupAge = stats.backupAt?.let { ((now - it) / 1000).coerceAtLeast(0) } ?: -1
        return buildString {
            append("# TYPE neutrodyne_requests_total counter\n")
            requests.entries.sortedBy { it.key.toString() }.forEach { (key, count) ->
                append("neutrodyne_requests_total{route=\"${key.first}\",status=\"${key.second}\"} ${count.get()}\n")
            }
            append("# TYPE neutrodyne_changes_total counter\n")
            changes.forEach { (status, count) ->
                append("neutrodyne_changes_total{status=\"$status\"} ${count.get()}\n")
            }
            append(
                "# TYPE neutrodyne_records_returned_total counter\nneutrodyne_records_returned_total ${returned.get()}\n",
            )
            append("# TYPE neutrodyne_sse_streams gauge\nneutrodyne_sse_streams ${streams.get()}\n")
            append("# TYPE neutrodyne_accounts gauge\nneutrodyne_accounts ${stats.accounts}\n")
            append("# TYPE neutrodyne_records gauge\nneutrodyne_records ${stats.records}\n")
            append("# TYPE neutrodyne_database_bytes gauge\nneutrodyne_database_bytes ${stats.databaseBytes}\n")
            append("# TYPE neutrodyne_last_backup_age_seconds gauge\n")
            append("neutrodyne_last_backup_age_seconds $backupAge\n")
            append("# TYPE neutrodyne_last_backup_success gauge\n")
            append("neutrodyne_last_backup_success ${if (stats.backupResult == "success") 1 else 0}\n")
            append("# TYPE neutrodyne_gc_purges_total counter\nneutrodyne_gc_purges_total ${purged.get()}\n")
        }
    }

    fun listen(address: ListenAddress): AutoCloseable {
        val server =
            embeddedServer(CIO, host = address.host, port = address.port) {
                routing {
                    get(
                        "/metrics",
                    ) { call.respondText(prometheus(), ContentType.parse("text/plain; version=0.0.4; charset=utf-8")) }
                }
            }
        server.start(wait = false)
        return AutoCloseable { server.stop(500, 2000) }
    }
}

internal enum class BackupResult { SUCCESS, FAILURE }
