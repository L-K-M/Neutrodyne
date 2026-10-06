// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

/** Severity handed to installed [LogSink]s. */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * A logging destination installed at start-up (01 Logging and redaction): Logcat on Android,
 * the rolling file writer + stdout on desktop, the diagnostics overlay in debug builds.
 *
 * Sinks receive text only: [Log] renders a throwable into [message] and redacts it first, because a
 * platform logger printing the original throwable would print its messages, causes and suppressed
 * exceptions unredacted (`IOException("GET https://u:password@host/feed?token=…")`).
 */
fun interface LogSink {
    fun log(
        level: LogLevel,
        tag: String,
        message: String,
    )
}

/**
 * The single logging entry point. Callers pass a lazy `msg` — with no sinks installed it is never
 * evaluated. Every emitted message is run through [Redactor.text] so a forgotten `url.redacted()`
 * at a call site is still safe. A throwing sink never takes the app down.
 */
object Log {
    private const val STACK_SEPARATOR = "\n"

    @Volatile
    private var sinks: List<LogSink> = emptyList()

    /** Replaces all sinks; the shells call this once per process. `install()` disables logging. */
    fun install(vararg sinks: LogSink) {
        this.sinks = sinks.toList()
    }

    fun d(
        tag: String,
        msg: () -> String,
    ) = emit(LogLevel.DEBUG, tag, null, msg)

    fun i(
        tag: String,
        msg: () -> String,
    ) = emit(LogLevel.INFO, tag, null, msg)

    fun w(
        tag: String,
        t: Throwable? = null,
        msg: () -> String,
    ) = emit(LogLevel.WARN, tag, t, msg)

    fun e(
        tag: String,
        t: Throwable? = null,
        msg: () -> String,
    ) = emit(LogLevel.ERROR, tag, t, msg)

    private fun emit(
        level: LogLevel,
        tag: String,
        t: Throwable?,
        msg: () -> String,
    ) {
        val current = sinks
        if (current.isEmpty()) return

        val text = if (t == null) msg() else msg() + STACK_SEPARATOR + t.stackTraceToString()
        val message = Redactor.text(text)
        for (sink in current) {
            runCatching { sink.log(level, tag, message) }
        }
    }
}
