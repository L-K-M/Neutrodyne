// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.common

/**
 * Crash reporting as modules see it (09 `CrashReporter` and `CrashContext`). Android binds an ACRA-backed
 * implementation, the desktop its crash-file writer; modules never touch either directly.
 */
interface CrashReporter {
    /** False when no mailbox is configured (PO-10) or the user turned `privacy.crash_reports` off. */
    val isAvailable: Boolean

    /** Offers [t] to the user as a report: ACRA's dialog on Android, a crash file and mail prompt on the desktop. */
    fun reportNonFatal(
        t: Throwable,
        where: String,
    )
}

/** Key-value context attached to the next crash report; every value passes [Redactor.text]. */
interface CrashContext {
    /** Last write wins. */
    fun put(
        key: CrashKey,
        value: String,
    )
}

/** The allow-listed context keys (09); nothing else reaches a report. */
enum class CrashKey { SCREEN, DB_RECOVERY, YOUTUBE_HEALTH, PLAYBACK, RUNNING_WORK, SYNC }
