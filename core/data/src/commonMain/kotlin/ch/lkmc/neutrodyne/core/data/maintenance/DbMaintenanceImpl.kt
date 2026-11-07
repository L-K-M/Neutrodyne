// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.maintenance

import androidx.room3.useWriterConnection
import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.artwork.ArtworkStore
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.data.importing.ImportSessionCleaner
import ch.lkmc.neutrodyne.core.database.DatabaseFactory
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.CredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.domain.DbMaintenance
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.settings.DiagnosticsSettingKeys
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 02's `db-maintenance` steps (Retention and maintenance), shared by the Android worker and
 * the desktop `maintenance` lane. Every step is idempotent, so a deadline stop or a crash
 * leaves a consistent database the next run continues from.
 *
 * Retention deletes inside `SyncStateDao.withApplying` — the flag documents "housekeeping,
 * never user intent" and is what MS0's triggers check once they land; `episode`/`upnext`
 * `sync_clock` rows of deleted episodes go with them (02 Retention versus sync tombstones).
 * A failing step is logged and skipped: the remaining steps still run, and the stop is
 * visible in the stats record the diagnostics screen shows.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class DbMaintenanceImpl(
    private val db: NeutrodyneDatabase,
    private val factory: DatabaseFactory,
    private val importSessionCleaner: ImportSessionCleaner,
    private val artworkStore: ArtworkStore,
    private val credentials: CredentialCommitCoordinator,
    private val settings: SettingsRepository,
    private val clock: Clock,
    @Dispatcher(NeutrodyneDispatchers.IO) private val io: CoroutineDispatcher,
) : DbMaintenance {
    override suspend fun run(
        now: Long,
        deadlineElapsedMs: Long?,
        vacuumBlocker: (suspend () -> String?)?,
    ) = withContext(io) {
        val stats = Stats(startedElapsed = clock.elapsedRealtime())

        suspend fun deadlineReached(): Boolean =
            deadlineElapsedMs != null && clock.elapsedRealtime() >= deadlineElapsedMs

        suspend fun step(
            name: String,
            block: suspend () -> Unit,
        ) {
            if (deadlineReached()) {
                stats.stoppedEarly = true
                return
            }
            val start = clock.elapsedRealtime()
            suspendRunCatching { block() }
                .onFailure { Log.w(TAG) { "db-maintenance step $name failed: ${it.message}" } }
            stats.durationsMs[name] = clock.elapsedRealtime() - start
        }

        step("retention") { retentionSweep(now, deadlineElapsedMs, stats) }
        step("orphans") { orphanSweep(stats) }
        step("imports") { stats.sessionsDeleted = importSessionCleaner.cleanExpired(now) }
        step("sync") {
            // Linked devices only: parked rows older than 180 days (02 step 3a).
            if (db.syncStateDao().get()?.enabled == true) {
                stats.parkedExpired = db.syncParkedDao().expire(now - PARKED_TTL_MS)
            }
        }
        step("artwork") { stats.artworkCollected = artworkStore.collectGarbage() }
        step("optimize") { optimize() }
        step("quick_check") { quickCheck(now, deadlineElapsedMs, stats) }
        step("vacuum") { vacuum(now, vacuumBlocker, stats) }

        recordStats(now, stats)
    }

    // ---- Step 1: retention ------------------------------------------------------------------------

    /**
     * Select-and-delete in one write transaction per batch (02): the candidate scan repeats
     * under the writer lock, so a queue or favourite write between batches is honoured on the
     * next iteration. Stops when a batch selects nothing or the deadline arrives mid-loop.
     */
    private suspend fun retentionSweep(
        now: Long,
        deadlineElapsedMs: Long?,
        stats: Stats,
    ) {
        val dao = db.maintenanceDao()
        val absentBefore = now - RETENTION_ABSENT_MS
        val playedSince = now - RETENTION_PLAYED_MS
        while (true) {
            if (deadlineElapsedMs != null && clock.elapsedRealtime() >= deadlineElapsedMs) {
                stats.stoppedEarly = true
                break
            }
            val batch =
                db.withWriteTransaction {
                    db.syncStateDao().withApplying {
                        val ids = dao.retentionBatch(absentBefore, playedSince, RETENTION_BATCH)
                        if (ids.isEmpty()) return@withApplying 0
                        val rids = dao.episodeSyncRids(ids)
                        db.syncClockDao().deleteRids("episode", rids)
                        db.syncClockDao().deleteRids("upnext", rids)
                        dao.deleteEpisodePersons(ids)
                        dao.deleteEpisodeFunding(ids)
                        dao.deleteEpisodes(ids)
                    }
                }
            // Counted per committed batch so a mid-sweep failure keeps the partial progress.
            stats.episodesDeleted += batch
            if (batch == 0) break
            // The desktop lane has no deadline; yielding keeps the writer lock shareable.
            yield()
        }
    }

    // ---- Step 2: orphan sweeps ----------------------------------------------------------------------

    private suspend fun orphanSweep(stats: Stats) {
        val dao = db.maintenanceDao()
        db.withWriteTransaction {
            stats.personsDeleted += dao.deleteOrphanedPersons()
            stats.fundingDeleted += dao.deleteOrphanedFunding()
        }
        // Never hold the credential mutex across the Room writer — the coordinator doc's
        // deadlock rule: mutex first, then the write transaction.
        stats.credentialsDeleted =
            credentials.withCredentialCommit {
                db.withWriteTransaction { dao.deleteUnreferencedCredentials() }
            }
    }

    // ---- Steps 5–7: PRAGMAs on the raw writer connection -------------------------------------------------

    private suspend fun optimize() {
        db.useWriterConnection { conn ->
            conn.usePrepared("PRAGMA optimize") { it.step() }
        }
    }

    private suspend fun quickCheck(
        now: Long,
        deadlineElapsedMs: Long?,
        stats: Stats,
    ) {
        // 02 step 6: skip when fewer than two minutes remain before the deadline.
        if (deadlineElapsedMs != null &&
            deadlineElapsedMs - clock.elapsedRealtime() < QUICK_CHECK_HEADROOM_MS
        ) {
            stats.quickCheck = "skipped"
            return
        }
        val bad =
            db.useWriterConnection { conn ->
                conn.usePrepared("PRAGMA quick_check") { stmt ->
                    buildList {
                        while (stmt.step()) {
                            val row = stmt.getText(0)
                            if (row != "ok") add(row)
                        }
                    }
                }
            }
        if (bad.isEmpty()) {
            stats.quickCheck = "ok"
            return
        }
        stats.quickCheck = "failed"
        Log.e(TAG) { "PRAGMA quick_check reported ${bad.size} problem rows" }
        suspendRunCatching { settings.set(DiagnosticsSettingKeys.DB_QUICK_CHECK_FAILED_AT, now) }
    }

    /** 02 step 7's five guards, each recorded so the diagnostics row can say why it skipped. */
    private suspend fun vacuum(
        now: Long,
        vacuumBlocker: (suspend () -> String?)?,
        stats: Stats,
    ) {
        val pages = db.useWriterConnection { conn -> pageStats(conn) }
        val dbBytes = pages.pageCount * pages.pageSize
        val freeBytes = pages.freelistCount * pages.pageSize
        when {
            pages.pageCount == 0L ||
                freeBytes * 4 <= dbBytes ||
                freeBytes <= VACUUM_MIN_FREELIST_BYTES -> {
                stats.vacuum = "skipped:fragmentation"
            }

            // Positions tick every few seconds while playing; either table's recency vetoes.
            (db.maintenanceDao().lastPlaySessionActivity() ?: 0L) >= now - VACUUM_IDLE_MS ||
                (db.maintenanceDao().lastPositionUpdate() ?: 0L) >= now - VACUUM_IDLE_MS -> {
                stats.vacuum = "skipped:playback"
            }

            // The desktop's "no download running" half; on Android idle covers it, and a
            // queued-but-not-running row does not veto.
            db.maintenanceDao().runningDownloadCount() > 0 -> {
                stats.vacuum = "skipped:downloads"
            }

            else -> {
                val usable = factory.usableSpaceBytes()
                when {
                    usable == null || usable <= dbBytes * 2 + VACUUM_HEADROOM_BYTES -> {
                        stats.vacuum = "skipped:space"
                    }

                    vacuumBlocker != null -> {
                        val reason = vacuumBlocker()
                        if (reason != null) {
                            stats.vacuum = "skipped:$reason"
                        } else {
                            runVacuum(stats)
                        }
                    }

                    else -> {
                        runVacuum(stats)
                    }
                }
            }
        }
    }

    private suspend fun runVacuum(stats: Stats) {
        // Outside any transaction on the writer connection (02): it blocks other writers for
        // seconds, which the playback/download/activity guards above make acceptable.
        db.useWriterConnection { conn ->
            conn.usePrepared("VACUUM") { it.step() }
        }
        stats.vacuum = "done"
    }

    private suspend fun pageStats(conn: androidx.room3.PooledConnection): PageStats =
        PageStats(
            pageCount = conn.pragmaLong("page_count"),
            pageSize = conn.pragmaLong("page_size"),
            freelistCount = conn.pragmaLong("freelist_count"),
        )

    private suspend fun androidx.room3.PooledConnection.pragmaLong(name: String): Long =
        usePrepared("PRAGMA $name") { stmt ->
            if (stmt.step()) stmt.getLong(0) else 0L
        }

    // ---- Step 8: the diagnostics record ----------------------------------------------------------------

    private suspend fun recordStats(
        now: Long,
        stats: Stats,
    ) {
        val dao = db.maintenanceDao()
        val pages =
            suspendRunCatching {
                db.useWriterConnection { conn -> pageStats(conn) }
            }.getOrNull()
        val json =
            buildJsonObject {
                put("ranAt", now)
                put("durationMs", clock.elapsedRealtime() - stats.startedElapsed)
                put("stoppedEarly", stats.stoppedEarly)
                put("episodesDeleted", stats.episodesDeleted)
                put("personsDeleted", stats.personsDeleted)
                put("fundingDeleted", stats.fundingDeleted)
                put("credentialsDeleted", stats.credentialsDeleted)
                put("sessionsDeleted", stats.sessionsDeleted)
                put("parkedExpired", stats.parkedExpired)
                put("artworkCollected", stats.artworkCollected)
                put("quickCheck", stats.quickCheck)
                put("vacuum", stats.vacuum)
                pages?.let { p ->
                    put("pageCount", p.pageCount)
                    put("pageSize", p.pageSize)
                    put("freelistCount", p.freelistCount)
                }
                put("podcasts", suspendRunCatching { dao.podcastCount() }.getOrDefault(-1))
                put("episodes", suspendRunCatching { dao.episodeCount() }.getOrDefault(-1))
                put("downloads", suspendRunCatching { dao.downloadCount() }.getOrDefault(-1))
                put("queued", suspendRunCatching { dao.queueEntryCount() }.getOrDefault(-1))
                put("durationsMs", buildJsonObject { stats.durationsMs.forEach { (k, v) -> put(k, v) } })
            }
        suspendRunCatching {
            settings.set(DiagnosticsSettingKeys.MAINTENANCE_LAST_RUN_AT, now)
            settings.set(DiagnosticsSettingKeys.MAINTENANCE_STATS, json.toString())
        }
    }

    private class Stats(
        val startedElapsed: Long,
        var stoppedEarly: Boolean = false,
        var episodesDeleted: Int = 0,
        var personsDeleted: Int = 0,
        var fundingDeleted: Int = 0,
        var credentialsDeleted: Int = 0,
        var sessionsDeleted: Int = 0,
        var parkedExpired: Int = 0,
        var artworkCollected: Int = 0,
        var quickCheck: String = "skipped",
        var vacuum: String = "skipped",
        val durationsMs: MutableMap<String, Long> = mutableMapOf(),
    )

    private class PageStats(
        val pageCount: Long,
        val pageSize: Long,
        val freelistCount: Long,
    )

    private companion object {
        const val TAG = "DbMaintenance"
        const val RETENTION_BATCH = 500
        const val RETENTION_ABSENT_MS = 90L * 86_400_000
        const val RETENTION_PLAYED_MS = 30L * 86_400_000
        const val PARKED_TTL_MS = 180L * 86_400_000
        const val QUICK_CHECK_HEADROOM_MS = 120_000L
        const val VACUUM_IDLE_MS = 600_000L
        const val VACUUM_MIN_FREELIST_BYTES = 8L * 1_048_576
        const val VACUUM_HEADROOM_BYTES = 100L * 1_048_576
    }
}
