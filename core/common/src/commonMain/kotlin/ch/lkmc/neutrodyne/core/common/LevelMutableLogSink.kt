// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

/**
 * A [LogSink] whose minimum level can change at runtime — "Detailed log for 24 hours" (09)
 * lowers every member of the `Set<LevelMutableLogSink>` multibinding to `DEBUG`: the
 * diagnostics ring on both platforms and the desktop's rolling file sink.
 */
interface LevelMutableLogSink : LogSink {
    var minLevel: LogLevel
}
