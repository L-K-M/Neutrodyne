// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.importing

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.database.ImportSessionEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.model.ImportState
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * 05 §9 / 02 `db-maintenance` step 3: sessions in `DONE`, `CANCELLED` or abandoned `PREVIEW`
 * past the seven-day window lose their payload file, then the row (`import_item` cascades).
 * `COMMITTED`/`FETCHING` are live work and never sweep — and a `PREVIEW` session whose
 * restore was already requested is the restore host's, not garbage.
 *
 * Until `db-maintenance` ships (M11b) the interim hosts run this directly: Android's
 * `AutoSnapshotWorker` as its first step, the desktop `import-backup` lane once per 24 h.
 * Each expired session's file goes before its row; a deletion failure keeps the row so the
 * next pass retries the file instead of orphaning it.
 */
@Inject
internal class ImportSessionCleaner(
    private val db: NeutrodyneDatabase,
    private val payloads: PayloadStore,
    private val clock: Clock,
    @Dispatcher(NeutrodyneDispatchers.IO) private val io: CoroutineDispatcher,
) {
    /** One pass over the expired set; returns the number of session rows deleted. */
    suspend fun cleanExpired(now: Long = clock.now()): Int {
        val cutoff = now - SESSION_TTL_MS
        var deleted = 0
        for (expired in db.importDao().expiredSessions(cutoff)) {
            if (sweepIfStillExpired(expired.id, cutoff)) deleted++
        }
        return deleted
    }

    /**
     * The ownership claim, the payload delete and the row delete as one write transaction —
     * the scan is only a snapshot, so the expiry rule is re-evaluated on the current row
     * under the writer lock before anything is touched. A restore request (or a fix-up
     * reopen) committed first is seen here and keeps row AND payload; one arriving after
     * the commit finds no row. A failed file delete keeps the row so the next pass retries
     * the file first.
     */
    private suspend fun sweepIfStillExpired(
        sessionId: Long,
        cutoff: Long,
    ): Boolean =
        db.withWriteTransaction {
            val dao = db.importDao()
            val session = dao.sessionById(sessionId) ?: return@withWriteTransaction false
            if (!isExpired(session, cutoff)) return@withWriteTransaction false
            val payloadPath = session.payloadPath
            if (payloadPath != null &&
                !withContext(io) { payloads.deleteIfPresent(payloads.payloadFile(payloadPath)) }
            ) {
                return@withWriteTransaction false
            }
            dao.deleteSession(sessionId) > 0
        }

    /**
     * `DONE`/`CANCELLED`/abandoned `PREVIEW` past the window. A `PREVIEW` session whose
     * restore was requested is its host's work; `COMMITTED`/`FETCHING` never arrive here —
     * and an unreadable `optionsJson` could hide a request, so corrupt rows stay too.
     */
    private fun isExpired(
        session: ImportSessionEntity,
        cutoff: Long,
    ): Boolean {
        if (session.state !in EXPIRABLE_STATES) return false
        if ((session.finishedAt ?: session.createdAt) >= cutoff) return false
        if (session.state != ImportState.PREVIEW) return true
        val encoded = session.optionsJson ?: return true
        val options = ImportOptionsJson.decodeOrNull(encoded)
        if (options == null) {
            Log.w(TAG) { "import session ${session.id} skipped: unreadable options" }
            return false
        }
        return options.restoreRequestedAt == null
    }

    private companion object {
        const val TAG = "ImportSessionCleaner"

        /** 05 §9: `COALESCE(finishedAt, createdAt)` older than this sweeps the session. */
        const val SESSION_TTL_MS = 7L * 86_400_000

        /** The states `expiredSessions` may return — re-checked before every row delete. */
        val EXPIRABLE_STATES = setOf(ImportState.DONE, ImportState.CANCELLED, ImportState.PREVIEW)
    }
}
