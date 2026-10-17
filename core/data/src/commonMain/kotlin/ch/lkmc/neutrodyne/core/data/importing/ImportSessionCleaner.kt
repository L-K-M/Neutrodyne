// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.importing

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Dispatcher
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
 * Each expired session's file goes first; a deletion failure keeps the row so the next pass
 * retries the file instead of orphaning it.
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
            // Re-read before touching the file: a confirm or a restore request landing since
            // the scan made this row live work — its payload is not garbage.
            if (!isExpirable(expired.id, cutoff)) continue
            val payloadPath = expired.payloadPath
            if (payloadPath != null &&
                !withContext(io) { payloads.deleteIfPresent(payloads.payloadFile(payloadPath)) }
            ) {
                continue
            }
            if (deleteIfStillExpired(expired.id, cutoff)) deleted++
        }
        return deleted
    }

    /** The scan's expiry rule re-evaluated on the current row (see [isExpired]). */
    private suspend fun isExpirable(
        sessionId: Long,
        cutoff: Long,
    ): Boolean = db.importDao().sessionById(sessionId)?.let { isExpired(it, cutoff) } == true

    /**
     * The row delete re-checks the expiry rule inside the write transaction: a row that moved
     * on since the scan (a fix-up reopened `DONE`, a restore was requested on an abandoned
     * `PREVIEW`) is not deleted under it.
     */
    private suspend fun deleteIfStillExpired(
        sessionId: Long,
        cutoff: Long,
    ): Boolean =
        db.withWriteTransaction {
            val dao = db.importDao()
            val session = dao.sessionById(sessionId) ?: return@withWriteTransaction false
            if (!isExpired(session, cutoff)) return@withWriteTransaction false
            dao.deleteSession(sessionId) > 0
        }

    /**
     * `DONE`/`CANCELLED`/abandoned `PREVIEW` past the window. A `PREVIEW` session whose
     * restore was requested is its host's work; `COMMITTED`/`FETCHING` never arrive here.
     */
    private fun isExpired(
        session: ImportSessionEntity,
        cutoff: Long,
    ): Boolean {
        if (session.state !in EXPIRABLE_STATES) return false
        if ((session.finishedAt ?: session.createdAt) >= cutoff) return false
        return session.state != ImportState.PREVIEW ||
            session.optionsJson
                ?.let(ImportOptionsJson::decode)
                ?.restoreRequestedAt == null
    }

    private companion object {
        /** 05 §9: `COALESCE(finishedAt, createdAt)` older than this sweeps the session. */
        const val SESSION_TTL_MS = 7L * 86_400_000

        /** The states `expiredSessions` may return — re-checked before every row delete. */
        val EXPIRABLE_STATES = setOf(ImportState.DONE, ImportState.CANCELLED, ImportState.PREVIEW)
    }
}
