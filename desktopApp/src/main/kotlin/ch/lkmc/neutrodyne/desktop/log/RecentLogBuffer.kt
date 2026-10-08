// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.log

import ch.lkmc.neutrodyne.core.common.LogLevel
import ch.lkmc.neutrodyne.core.common.LogSink
import java.time.Instant

/**
 * A bounded ring of the most recent formatted log lines (11 Crash files: "the last 200 redacted
 * log lines"). Installed as one of the process's [LogSink]s — `Log` has already redacted the
 * message, and the throwable stack is redacted while formatting. M11b's shared
 * `RingBufferLogSink` (500 entries, diagnostics screen) will take over the diagnostics half;
 * crash files keep this buffer.
 */
class RecentLogBuffer(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val timeSource: () -> Instant = Instant::now,
) : LogSink {
    private val lines = ArrayDeque<String>(capacity)

    @Synchronized
    override fun log(
        level: LogLevel,
        tag: String,
        message: String,
    ) {
        if (lines.size == capacity) lines.removeFirst()
        lines.addLast(formatLogLine(level, tag, message, timeSource()))
    }

    /** The newest-last snapshot for the next crash file. */
    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    companion object {
        /** 11 Crash files and the email dialog: the crash file quotes the last 200 log lines. */
        const val DEFAULT_CAPACITY = 200
    }
}
