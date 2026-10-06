// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.crash

import ch.lkmc.neutrodyne.core.common.CrashKey
import ch.lkmc.neutrodyne.core.common.LogLevel
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.desktop.buildinfo.BuildInfoLoader
import ch.lkmc.neutrodyne.desktop.log.RecentLogBuffer
import ch.lkmc.neutrodyne.desktop.shell.tempAppDirs
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.regex.Pattern
import org.junit.Test

/**
 * [DesktopCrashReporter] per 11 Crash files and the email dialog / 09 Crash reporting: the crash
 * file's allow-listed field set, the redaction of stack, context and log lines, the 3-file cap
 * for background threads and the `hs_err` summary path.
 */
class DesktopCrashReporterTest {
    private val dirs = tempAppDirs("nd-crash").also { Files.createDirectories(it.state) }
    private val clock = TestClock()
    private val recentLogs = RecentLogBuffer(timeSource = { Instant.parse("2026-10-06T00:00:00Z") })
    private val buildInfo = BuildInfoLoader.load()
    private val reporter = DesktopCrashReporter(dirs, buildInfo, clock, recentLogs)

    @Test
    fun `an uncaught exception writes the field set, redacted`() {
        recentLogs.log(LogLevel.INFO, "Feed", "refresh of https://casts.example.org/episodes/feed.xml done", null)
        reporter.put(CrashKey.SCREEN, "PodcastsTab/PodcastKey")
        reporter.put(CrashKey.SYNC, "linked, https://sync.example.net:8443/devices?token=nd_s3cret")

        reporter.recordUnhandled(
            Thread.currentThread(),
            IllegalStateException("broken https://user:secret@example.org/rss/a8F3kq09ZpLm2xQ?auth=zebra"),
        )

        val file = singleCrashFile()
        assertThat(file.fileName.toString()).matches(CRASH_NAME.pattern())
        val content = Files.readString(file)

        assertThat(content).contains("version=${buildInfo.versionName}")
        assertThat(content).contains("versionCode=${buildInfo.versionCode}")
        assertThat(content).contains("os=")
        assertThat(content).contains("arch=")
        assertThat(content).contains("installKind=")
        assertThat(content).contains("runtime=")
        assertThat(content).contains("uptime=")
        assertThat(content).contains("thread=")
        assertThat(content).contains("SCREEN=PodcastsTab/PodcastKey")

        // Stack, context values and log lines all leave redacted only.
        assertThat(content).contains("IllegalStateException")
        assertThat(content).doesNotContain("user:secret")
        assertThat(content).doesNotContain("a8F3kq09ZpLm2xQ")
        assertThat(content).doesNotContain("zebra")
        assertThat(content).doesNotContain("nd_s3cret")
        assertThat(content).contains("refresh of https://casts.example.org")
    }

    @Test
    fun `reportNonFatal writes a file carrying where`() {
        reporter.reportNonFatal(IllegalStateException("database recovery needed"), where = "startup-gate")
        val content = Files.readString(singleCrashFile())
        assertThat(content).contains("where=startup-gate")
        assertThat(content).contains("database recovery needed")
    }

    @Test
    fun `background threads write at most three files per session`() {
        repeat(BEYOND_THE_CAP) { index ->
            val worker = Thread { }
            worker.name = "worker-$index"
            reporter.recordUnhandled(worker, RuntimeException("failure $index"))
        }
        assertThat(crashFiles()).hasSize(MAX_BACKGROUND_FILES)
    }

    @Test
    fun `a jvm crash builds a report from the hs_err summary`() {
        val hsErr = dirs.state.resolve("hs_err_pid123.log")
        Files.writeString(
            hsErr,
            """
            #
            # A fatal error has been detected by the Java Runtime Environment:
            #
            #  SIGSEGV (0xb) at pc=0x00007f4021c0e410, pid=123, tid=124
            #
            # J 123.4 c.l.k.n.d.p.SomeKt.decode(SomeKt.kt:31)
            #
            # Problematic frame:
            # C  0x00007f4021c0e410  nd_out_write+0x14
            #
            Stack: 0x00007ffd
            C  0x00007f4021ab12c0  libavcodec.so.63+0x3212c0
            """.trimIndent(),
        )
        reporter.recordJvmCrash(hsErr)

        val content = Files.readString(singleCrashFile())
        assertThat(content).contains("where=jvm-crash")
        assertThat(content).contains("hs_err_pid123.log")
        assertThat(content).contains("Problematic frame")
        assertThat(content).contains("nd_out_write")
    }

    private fun crashFiles(): List<Path> =
        Files.list(dirs.state).use { stream ->
            stream.filter { name -> name.fileName.toString().startsWith("crash-") }.sorted().toList()
        }

    private fun singleCrashFile(): Path {
        val files = crashFiles()
        assertThat(files).hasSize(1)
        return files.single()
    }

    private companion object {
        val CRASH_NAME = Pattern.compile("crash-\\d{8}-\\d{6}(-\\d+)?\\.txt")
        const val MAX_BACKGROUND_FILES = 3
        const val BEYOND_THE_CAP = 5
    }
}
