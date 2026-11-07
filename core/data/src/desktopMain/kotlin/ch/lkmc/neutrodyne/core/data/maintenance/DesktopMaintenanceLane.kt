// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.maintenance

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.JobLane
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.DbMaintenance
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.domain.SyncActivity
import ch.lkmc.neutrodyne.core.model.settings.DiagnosticsSettingKeys
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlin.time.Instant

/**
 * The `maintenance` lane (11's lane table): runs the shared [DbMaintenance] pass once per
 * 24 h, never earlier than ten minutes after the runner's first tick. The runner launches
 * every lane on each 60 s tick; this lane returns early until both clocks say so. The lane
 * owns the cadence stamp `diagnostics.maintenance_last_run_at` (written after every pass,
 * failed ones included — a crash-looping pass must not retry every minute; the shared impl
 * writes the same key in its stats record), so a quit and relaunch does not re-run inside
 * the window: only the ten-minute start guard re-applies.
 *
 * The shared pass has no deadline here (02: "the desktop's `maintenance` lane keeps the
 * chunks but has no deadline"). Its [DbMaintenance.run] vacuum blocker is the shared
 * [SyncActivity] flag: while a sync round or link merge is active the vacuum step records
 * `skipped:sync`, and the rest of the pass runs unchanged.
 */
@ContributesIntoSet(AppScope::class)
internal class DesktopMaintenanceLane
    @Inject
    constructor(
        private val maintenance: DbMaintenance,
        private val settings: SettingsRepository,
        private val syncActivity: SyncActivity,
    ) : JobLane {
        override val name: String = NAME

        /** The first tick's instant; approximates "app start" for the ten-minute guard. */
        private var startedAt: Instant? = null

        override suspend fun run(now: Instant) {
            val started = startedAt ?: now.also { startedAt = it }
            if (now - started < START_GUARD) return

            val last =
                suspendRunCatching {
                    settings.get(DiagnosticsSettingKeys.MAINTENANCE_LAST_RUN_AT)
                }.getOrNull() ?: return
            if (now.toEpochMilliseconds() - last < INTERVAL.inWholeMilliseconds) return

            suspendRunCatching {
                maintenance.run(
                    now = now.toEpochMilliseconds(),
                    deadlineElapsedMs = null,
                    vacuumBlocker = { if (syncActivity.active) "sync" else null },
                )
            }.onFailure { Log.w(TAG, it) { "db-maintenance pass failed" } }
            suspendRunCatching {
                settings.set(DiagnosticsSettingKeys.MAINTENANCE_LAST_RUN_AT, now.toEpochMilliseconds())
            }.onFailure { Log.w(TAG, it) { "maintenance stamp write failed" } }
        }

        internal companion object {
            const val NAME = "maintenance"
            private const val TAG = "MaintenanceLane"
            val INTERVAL = kotlin.time.Duration.parse("PT24H")
            val START_GUARD = kotlin.time.Duration.parse("PT10M")
        }
    }
