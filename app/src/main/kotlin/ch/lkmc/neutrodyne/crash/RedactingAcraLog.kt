// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.crash

import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.Redactor
import org.acra.log.ACRALog

/**
 * ACRA's own logger, routed through [Log] so its messages and throwables are redacted like every other line
 * (01 Logging and redaction). ACRA's default logger prints the original uncaught exception before
 * `CrashReportRedactor` runs, which would put a private feed URL into Logcat (review 2026-10-06).
 */
internal object RedactingAcraLog : ACRALog {
    private const val LOGGED = 0

    override fun v(
        tag: String,
        msg: String,
    ): Int = debug(tag, msg, null)

    override fun v(
        tag: String,
        msg: String,
        tr: Throwable,
    ): Int = debug(tag, msg, tr)

    override fun d(
        tag: String,
        msg: String,
    ): Int = debug(tag, msg, null)

    override fun d(
        tag: String,
        msg: String,
        tr: Throwable,
    ): Int = debug(tag, msg, tr)

    override fun i(
        tag: String,
        msg: String,
    ): Int = info(tag, msg, null)

    override fun i(
        tag: String,
        msg: String,
        tr: Throwable,
    ): Int = info(tag, msg, tr)

    override fun w(
        tag: String,
        msg: String,
    ): Int = warn(tag, msg, null)

    override fun w(
        tag: String,
        msg: String,
        tr: Throwable,
    ): Int = warn(tag, msg, tr)

    override fun w(
        tag: String,
        tr: Throwable,
    ): Int = warn(tag, "", tr)

    override fun e(
        tag: String,
        msg: String,
    ): Int = error(tag, msg, null)

    override fun e(
        tag: String,
        msg: String,
        tr: Throwable,
    ): Int = error(tag, msg, tr)

    override fun getStackTraceString(tr: Throwable): String = Redactor.text(tr.stackTraceToString())

    private fun debug(
        tag: String,
        msg: String,
        tr: Throwable?,
    ): Int {
        Log.d(tag) { withStack(msg, tr) }
        return LOGGED
    }

    private fun info(
        tag: String,
        msg: String,
        tr: Throwable?,
    ): Int {
        Log.i(tag) { withStack(msg, tr) }
        return LOGGED
    }

    private fun warn(
        tag: String,
        msg: String,
        tr: Throwable?,
    ): Int {
        Log.w(tag, tr) { msg }
        return LOGGED
    }

    private fun error(
        tag: String,
        msg: String,
        tr: Throwable?,
    ): Int {
        Log.e(tag, tr) { msg }
        return LOGGED
    }

    /** Debug and info calls have no throwable slot in [Log]; the stack is appended to the text (redacted there). */
    private fun withStack(
        msg: String,
        tr: Throwable?,
    ): String =
        if (tr ==
            null
        ) {
            msg
        } else {
            msg + "\n" + tr.stackTraceToString()
        }
}
