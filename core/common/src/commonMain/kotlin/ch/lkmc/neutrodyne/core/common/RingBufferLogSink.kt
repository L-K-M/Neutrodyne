// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Instant

/**
 * A bounded ring of the last 500 formatted log lines (01's `RingBufferLogSink`): the
 * Diagnostics screen's LOG section and `DiagnosticsRepository.observeLogLines` read it.
 * Installed as one of the shells' `LogSink`s, so lines arrive already redacted.
 *
 * [minLevel] is INFO normally and DEBUG while `diagnostics.verbose_log_until` is in the
 * future ("Detailed log for 24 hours"); the level observer writes it, [log] reads it.
 */
class RingBufferLogSink(
    private val clock: Clock,
    private val capacity: Int = DEFAULT_CAPACITY,
) : LogSink {
    @Volatile
    var minLevel: LogLevel = LogLevel.INFO

    private val lock = Any()
    private val lines = ArrayDeque<String>(capacity)

    private val _lines = MutableStateFlow<List<String>>(emptyList())

    /** Newest-last snapshot, updated on every kept line. */
    val linesFlow: StateFlow<List<String>> = _lines.asStateFlow()

    override fun log(
        level: LogLevel,
        tag: String,
        message: String,
    ) {
        if (level.ordinal < minLevel.ordinal) return
        val at = Instant.fromEpochMilliseconds(clock.now())
        val line = "$at $level $tag $message"
        synchronized(lock) {
            if (lines.size == capacity) lines.removeFirst()
            lines.addLast(line)
            _lines.value = lines.toList()
        }
    }

    /** Drops the buffer — tests and process teardown only. */
    fun clear() {
        synchronized(lock) {
            lines.clear()
            _lines.value = emptyList()
        }
    }

    companion object {
        /** 09's LOG section: the last 500 redacted log lines. */
        const val DEFAULT_CAPACITY = 500
    }
}
