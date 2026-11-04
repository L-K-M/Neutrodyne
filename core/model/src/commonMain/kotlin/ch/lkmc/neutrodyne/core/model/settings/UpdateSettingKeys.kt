// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model.settings

/** 09 Settings: only CHECK_ENABLED travels in backups; none of these keys sync. */
object UpdateSettingKeys {
    const val ABSENT: Long = -1L

    val CHECK_ENABLED = SettingKey.Bool("updates.check_enabled", default = true)
    val LAST_CHECK_AT = SettingKey.Int64("updates.last_check_at", ABSENT, SettingsFile.DEVICE)
    val SKIPPED_VERSION_CODE = SettingKey.Int64("updates.skipped_version_code", ABSENT, SettingsFile.DEVICE)
    val NOTIFIED_VERSION_CODE = SettingKey.Int64("updates.notified_version_code", ABSENT, SettingsFile.DEVICE)
    val FIRST_RUN_CHOICE_DONE = SettingKey.Bool("updates.first_run_choice_done", false, SettingsFile.DEVICE)
    val VERIFICATION_NOTICE_SHOWN_AT =
        SettingKey.Int64(
            "updates.verification_notice_shown_at",
            ABSENT,
            SettingsFile.DEVICE,
        )

    val ALL: List<SettingKey<*>> =
        listOf(
            CHECK_ENABLED,
            LAST_CHECK_AT,
            SKIPPED_VERSION_CODE,
            NOTIFIED_VERSION_CODE,
            FIRST_RUN_CHOICE_DONE,
            VERIFICATION_NOTICE_SHOWN_AT,
        )

    /** Domain/store use null; DataStore retains its existing non-null Long representation. */
    fun nullableValue(stored: Long): Long? = stored.takeIf { it >= 0L }

    fun storedValue(value: Long?): Long {
        require(value == null || value >= 0L)
        return value ?: ABSENT
    }

    internal fun acceptsRange(
        key: SettingKey<*>,
        value: Any,
    ): Boolean =
        when (key.name) {
            LAST_CHECK_AT.name, VERIFICATION_NOTICE_SHOWN_AT.name -> {
                value is Long && value >= ABSENT
            }

            SKIPPED_VERSION_CODE.name, NOTIFIED_VERSION_CODE.name -> {
                value is Long && (value == ABSENT || value > 0L)
            }

            else -> {
                true
            }
        }
}
