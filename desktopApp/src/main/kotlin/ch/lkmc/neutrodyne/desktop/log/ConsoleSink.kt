// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.log

import ch.lkmc.neutrodyne.core.common.LogLevel
import ch.lkmc.neutrodyne.core.common.LogSink
import java.time.Instant

/**
 * Stdout sink for development runs (01 Logging and redaction: "ConsoleSink only under
 * `:desktopApp:run`"). M0b installs it whenever `BuildInfo.debug` — `DEV` covers `run` and the
 * tests, and distinguishing them needs a marker Gradle does not pass (noted in 11).
 */
internal object ConsoleSink : LogSink {
    override fun log(
        level: LogLevel,
        tag: String,
        message: String,
    ) {
        println(formatLogLine(level, tag, message, Instant.now()))
    }
}
