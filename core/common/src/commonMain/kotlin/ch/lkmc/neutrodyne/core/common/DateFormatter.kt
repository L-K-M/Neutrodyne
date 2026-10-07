// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

/**
 * Localised date formatting, small per-platform shim (01 Source sets and JVM islands):
 * the JDK's date-time formatter on both targets — the `expect` exists to keep JDK packages out
 * of `commonMain`. Uses the app locale: the test harness sets `user.language=de`,
 * `user.country=DE`, and unit tests assert locale-invariant structure only.
 *
 * Relative times are deliberately absent — desktop lacks `RelativeDateTimeFormatter` and its
 * strings need Compose plural resources (01 Coroutines/UI rule, 11 UI shells).
 */
expect object DateFormatter {
    /** Medium-format localised date for [epochMs] in the device's default zone (`04.10.2026`). */
    fun date(epochMs: Long): String
}
