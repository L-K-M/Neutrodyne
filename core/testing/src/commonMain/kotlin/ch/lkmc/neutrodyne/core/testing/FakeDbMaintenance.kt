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
    )

    val calls = mutableListOf<Call>()
    var result: Result<Unit> = Result.success(Unit)

    override suspend fun run(
        now: Long,
        deadlineElapsedMs: Long?,
        vacuumBlocker: (suspend () -> String?)?,
    ) {
        calls += Call(now, deadlineElapsedMs, vacuumBlocker != null)
        result.getOrThrow()
    }
}
