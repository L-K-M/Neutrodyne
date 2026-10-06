// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.crash

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.CrashContext
import ch.lkmc.neutrodyne.core.common.CrashKey
import ch.lkmc.neutrodyne.core.common.CrashReporter
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.Redactor
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.desktop.log.RecentLogBuffer
import java.io.IOException
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger

/**
 * The desktop's [CrashReporter] and [CrashContext] (11 Crash files and the email dialog, 09 Crash
 * reporting and diagnostics): every uncaught exception becomes a `crash-<UTC>.txt` file in the
 * state directory with 09's allow-listed field set — app version and code, OS and version,
 * architecture, install kind, runtime vendor and version, uptime, thread, the redacted stack
 * trace or `hs_err` summary, the last 200 redacted log lines and the [CrashKey] context. No
 * device identifiers. `InstallKind.DEV` never asks to send anything.
 *
 * M0b pending, recorded in 11: the email dialog at the next start (it needs the window) and with
 * it the mailbox wiring, so [isAvailable] is `false` until PO-10's mailbox reaches the desktop
 * build. Crash files are written regardless (privacy-off keeps them local, 09).
 */
class DesktopCrashReporter(
    private val dirs: AppDirs,
    private val buildInfo: BuildInfo,
    private val clock: Clock,
    private val recentLogs: RecentLogBuffer,
    private val processStartTimeMs: Long = ManagementFactory.getRuntimeMXBean().startTime,
    private val mainThread: Thread = Thread.currentThread(),
) : CrashReporter, CrashContext {
    override val isAvailable: Boolean = false

    private val context = LinkedHashMap<String, String>()
    private val backgroundFiles = AtomicInteger(0)

    override fun reportNonFatal(t: Throwable, where: String) {
        writeCrashFile(t, threadName = null, where = where)
    }

    override fun put(key: CrashKey, value: String) {
        synchronized(context) { context[key.name] = Redactor.text(value) }
    }

    /** Installs this reporter as the JVM-wide handler (11's start-up step). */
    fun installAsDefaultExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            recordUnhandled(thread, throwable)
        }
    }

    /**
     * One uncaught exception: the trace goes to stderr, a crash file is written, and a
     * main-thread or EDT exception additionally ends the process (11 Shell failure modes — the
     * "has to close" dialog and the quit through `ShutdownCoordinator` arrive with the window).
     * Background threads continue; at most [MAX_BACKGROUND_FILES] such files per session.
     */
    fun recordUnhandled(thread: Thread, t: Throwable) {
        System.err.println("Uncaught exception on thread ${thread.name}")
        t.printStackTrace()

        val fatal = thread === mainThread || thread.name.startsWith(AWT_EDT_PREFIX)
        if (!fatal && backgroundFiles.incrementAndGet() > MAX_BACKGROUND_FILES) {
            Log.w(TAG) { "background crash file cap reached; exception recorded to the log only" }
            return
        }
        writeCrashFile(t, threadName = thread.name, where = null)
    }

    /**
     * The JVM/native crash path (11 Crash files table): the previous session ended with
     * `cleanExit = false` and an `hs_err_pid*.log` exists — build the report from its summary
     * (problematic frame, threads, stacks; never memory dumps).
     */
    fun recordJvmCrash(hsErrFile: Path) {
        writeCrashFile(HsErrSummary.of(hsErrFile), threadName = null, where = "jvm-crash")
    }

    private fun writeCrashFile(t: Throwable, threadName: String?, where: String?) {
        try {
            Files.createDirectories(dirs.state)
            val file = nextCrashFile()
            Files.writeString(file, buildReport(t, threadName, where), Charsets.UTF_8)
            Log.e(TAG, t) { "crash file written: ${file.fileName}" }
        } catch (e: IOException) {
            System.err.println("Neutrodyne could not write a crash file")
            e.printStackTrace()
        }
    }

    private fun buildReport(t: Throwable, threadName: String?, where: String?): String {
        val desktop = checkNotNull(buildInfo.desktop) { "the desktop BuildInfo always carries a desktop block" }
        val report = StringBuilder()
        report.appendLine("Neutrodyne crash report")
        report.appendLine("version=${buildInfo.versionName}")
        report.appendLine("versionCode=${buildInfo.versionCode}")
        report.appendLine("os=${desktop.os.wire} ${System.getProperty("os.version")}")
        report.appendLine("arch=${desktop.arch.wire}")
        report.appendLine("installKind=${desktop.installKind.wire}")
        report.appendLine("runtime=${desktop.runtime}")
        report.appendLine("uptime=${(clock.now() - processStartTimeMs) / 1000}s")
        report.appendLine("time=${nowIso()}")
        if (threadName != null) report.appendLine("thread=$threadName")
        if (where != null) report.appendLine("where=$where")
        synchronized(context) {
            for ((key, value) in context) report.appendLine("$key=$value")
        }
        report.appendLine("--- stack ---")
        report.appendLine(Redactor.text(t.stackTraceToString()))
        report.appendLine("--- recent log ---")
        for (line in recentLogs.snapshot()) report.appendLine(line)
        return report.toString()
    }

    private fun nextCrashFile(): Path {
        val at = Instant.ofEpochMilli(clock.now()).atOffset(ZoneOffset.UTC)
        val base = dirs.state.resolve("crash-${FILE_TIMESTAMP.format(at)}.txt")
        var candidate = base
        var suffix = 2
        // Two crashes within the same second: numbered suffixes instead of overwriting.
        while (Files.exists(candidate)) {
            val name = base.fileName.toString()
            candidate = base.resolveSibling("${name.removeSuffix(".txt")}-$suffix.txt")
            suffix++
        }
        return candidate
    }

    private fun nowIso(): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(clock.now()))

    /** The `hs_err_pid<pid>.log` brief (11): headline lines and the first stack frames, redacted. */
    private object HsErrSummary {
        fun of(file: Path): Throwable {
            val lines = runCatching { Files.readAllLines(file) }.getOrElse { emptyList() }
            val brief = lines.filter { it.startsWith("#") || it.startsWith("Stack:") || stackFrame(it) }
                .take(MAX_SUMMARY_LINES)
            return JvmCrash(
                message = "hs_err file ${file.fileName}\n" +
                    (if (brief.isEmpty()) "no summary lines readable" else Redactor.text(brief.joinToString("\n"))),
            )
        }

        private fun stackFrame(line: String): Boolean = line.startsWith("j  ") || line.startsWith("C  ") || line.startsWith("V  ")

        private const val MAX_SUMMARY_LINES = 40
    }

    private class JvmCrash(message: String) : RuntimeException(message)

    private companion object {
        const val TAG = "Crash"

        /** 11 Crash files: `crash-<UTC yyyyMMdd-HHmmss>.txt`. */
        val FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        const val AWT_EDT_PREFIX = "AWT-EventQueue"

        /** 11: at most 3 background-thread crash files per session. */
        const val MAX_BACKGROUND_FILES = 3
    }
}
