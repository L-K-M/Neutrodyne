// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.domain.DbMaintenance

/**
 * A [DbMaintenance] recording every invocation; [result] lets a test throw. No database work —
 * the sweeps are exercised against the real engine in `RetentionTest`/`DbMaintenanceTest`.
 */
class FakeDbMaintenance : DbMaintenance {
    data class Call(
        val now: Long,
        val deadlineElapsedMs: Long?,
        val hadVacuumBlocker: Boolean,
        val blockerReason: String?,
    )

    val calls = mutableListOf<Call>()

    /** How many times the supplied vacuum blocker was consulted by the pass. */
    var blockerEvaluations = 0
        private set

    var result: Result<Unit> = Result.success(Unit)

    override suspend fun run(
        now: Long,
        deadlineElapsedMs: Long?,
        vacuumBlocker: (suspend () -> String?)?,
    ) {
        // The real pass consults the blocker only at its vacuum step; mimic that here so
        // the lane's tests observe the same contract.
        val reason =
            if (vacuumBlocker == null) {
                null
            } else {
                blockerEvaluations++
                vacuumBlocker()
            }
        calls += Call(now, deadlineElapsedMs, vacuumBlocker != null, reason)
        result.getOrThrow()
    }
}
