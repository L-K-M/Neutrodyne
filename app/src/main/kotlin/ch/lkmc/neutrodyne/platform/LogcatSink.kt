// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.platform

import android.util.Log
import ch.lkmc.neutrodyne.core.common.LogLevel
import ch.lkmc.neutrodyne.core.common.LogSink

/** Writes already-redacted messages at or above [minLevel] to Logcat (01 Logging and redaction). */
internal class LogcatSink(
    private val minLevel: LogLevel,
) : LogSink {
    override fun log(
        level: LogLevel,
        tag: String,
        message: String,
    ) {
        if (level < minLevel) return

        val tagged = TAG_PREFIX + tag
        when (level) {
            LogLevel.DEBUG -> Log.d(tagged, message)
            LogLevel.INFO -> Log.i(tagged, message)
            LogLevel.WARN -> Log.w(tagged, message)
            LogLevel.ERROR -> Log.e(tagged, message)
        }
    }

    private companion object {
        const val TAG_PREFIX = "Nd/"
    }
}
