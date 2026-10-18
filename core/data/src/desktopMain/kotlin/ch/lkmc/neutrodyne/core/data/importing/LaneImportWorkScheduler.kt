// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.importing

import ch.lkmc.neutrodyne.core.common.JobLanePoker
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The desktop `ImportWorkScheduler` (05 §7's platform column, 11's `import-backup` row): both
 * fetch policies map to one coalesced `JobLanePoker.poke` — the lane re-reads
 * `activeSessions()` itself, so `KEEP` vs `APPEND_OR_REPLACE` carry no extra meaning. The
 * poke only wakes; durable state decides what runs, so a missed poke can never strand work
 * (the lane re-scans on every tick).
 *
 * [cancel] drops the lane's tracked child job for the session — the runner then ends at its
 * next cancellable boundary — while the repository's `CANCELLED` write stays the durable
 * half; a session whose run is not in flight simply drops out of the next scan. Other
 * sessions' runs are untouched.
 *
 * `scheduleRestore` always answers [RestoreEnqueue.ENQUEUED]: the durable intent is the work,
 * and the lane's scan consumes it — there is no platform queue that could keep or drop it, so
 * a decline answer would be a lie. `hasRestoreWork` reports the lane's in-flight restore plus
 * any durable `PREVIEW` backup intent, which covers a request the lane has not claimed yet.
 *
 * [poker] is a `Provider` — the runner holds the lanes and the lane holds this scheduler, so
 * the poker edge must resolve lazily (the `DesktopRefreshScheduler` pattern).
 *
 * `DesktopDataBindings` binds this with two `internal` providers (the `exportRepository`
 * shape): the lane injects the concrete type for its run hooks while every other caller sees
 * `ImportWorkScheduler`, and a contribution cannot give both — `@ExposeImplBinding` requires
 * a public class, which cannot implement the `internal` port. One singleton either way.
 */
internal class LaneImportWorkScheduler(
    private val db: NeutrodyneDatabase,
    private val poker: Provider<JobLanePoker>,
) : ImportWorkScheduler {
    /**
     * In-flight fetch and restore runs, so [cancel] can stop one at its next
     * durable/cancellable boundary. One job per session — a session is either a `PREVIEW`
     * restore or a `COMMITTED`/`FETCHING` import, never both.
     */
    private val inFlight = ConcurrentHashMap<Long, Job>()

    /** The session id of the lane's in-flight restore; [NONE] means none running. */
    private val restoreInFlight = AtomicLong(NONE)

    override suspend fun schedule(
        sessionId: Long,
        policy: ImportWorkPolicy,
    ) {
        poker().poke(DesktopImportBackupLane.NAME)
    }

    override suspend fun cancel(sessionId: Long) {
        inFlight[sessionId]?.cancel()
        poker().poke(DesktopImportBackupLane.NAME)
    }

    override suspend fun hasRestoreWork(): Boolean =
        restoreInFlight.get() != NONE ||
            db.importDao().backupPreviewSessions().any { it.hasRestoreIntent() }

    override suspend fun scheduleRestore(
        sessionId: Long,
        auto: Boolean,
    ): RestoreEnqueue {
        poker().poke(DesktopImportBackupLane.NAME)
        return RestoreEnqueue.ENQUEUED
    }

    // The lane's run hooks — same object, same module, never part of the port.

    internal fun trackJob(
        sessionId: Long,
        job: Job,
    ) {
        inFlight[sessionId] = job
    }

    internal fun untrackJob(
        sessionId: Long,
        job: Job,
    ) {
        inFlight.remove(sessionId, job)
    }

    internal fun restoreStarted(sessionId: Long) {
        restoreInFlight.set(sessionId)
    }

    internal fun restoreFinished(sessionId: Long) {
        restoreInFlight.compareAndSet(sessionId, NONE)
    }

    private companion object {
        const val NONE = -1L
    }
}
