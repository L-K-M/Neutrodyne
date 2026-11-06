// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model.settings

/**
 * 09 Diagnostics: the `diagnostics.*` keys M11b registers. All device-local — a quick-check
 * failure timestamp, a maintenance run record or a "verbose log" deadline must never travel in
 * a backup or through sync; only `verbose_log_until` is user-facing (the Diagnostics screen's
 * "Detailed log for 24 hours" switch).
 */
object DiagnosticsSettingKeys {
    /** Diagnostics screen's switch: ring buffer and file log drop to DEBUG until this instant. */
    val VERBOSE_LOG_UNTIL: SettingKey.Int64 =
        SettingKey.Int64(name = "diagnostics.verbose_log_until", default = 0L, file = SettingsFile.DEVICE)

    /** Written when a `PRAGMA quick_check` step returns anything but `ok` (02 step 6). */
    val DB_QUICK_CHECK_FAILED_AT: SettingKey.Int64 =
        SettingKey.Int64(name = "diagnostics.db_quick_check_failed_at", default = 0L, file = SettingsFile.DEVICE)

    /** Start of the last `db-maintenance` run; also the desktop lane's 24 h cadence store. */
    val MAINTENANCE_LAST_RUN_AT: SettingKey.Int64 =
        SettingKey.Int64(name = "diagnostics.maintenance_last_run_at", default = 0L, file = SettingsFile.DEVICE)

    /**
     * JSON snapshot of the last run (02 step 8): per-step durations, deleted counts, row
     * counts, `page_count × page_size`, `freelist_count` and the vacuum decision — rendered
     * by the DATABASE section of the diagnostics screen.
     */
    val MAINTENANCE_STATS: SettingKey.Text =
        SettingKey.Text(name = "diagnostics.maintenance_stats", default = "", file = SettingsFile.DEVICE)

    val ALL: List<SettingKey<*>> =
        listOf(VERBOSE_LOG_UNTIL, DB_QUICK_CHECK_FAILED_AT, MAINTENANCE_LAST_RUN_AT, MAINTENANCE_STATS)
}
