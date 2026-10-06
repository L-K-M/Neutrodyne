// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.log

import ch.lkmc.neutrodyne.core.common.LogLevel
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.junit.Test

/**
 * [RollingFileSink] per 11 Logs and rotation: the level filter, the 2-MiB rotation keeping five
 * files (`neutrodyne.log` + `neutrodyne.1.log` … `neutrodyne.4.log`) and redacted lines — `Log`
 * redacts messages, the sink redacts throwable stacks.
 */
class RollingFileSinkTest {
    private val logsDir: Path = Files.createTempDirectory("nd-logs")
    private val fixedInstant = Instant.parse("2026-10-06T00:00:00Z")

    @Test
    fun `entries below the minimum level are not written`() {
        val sink = RollingFileSink(
            logsDir,
            minLevel = LogLevel.INFO,
            maxFileBytes = LARGE_CAP,
            timeSource = { fixedInstant },
        )
        sink.use {
            it.log(LogLevel.DEBUG, "Tag", "hidden", null)
            it.log(LogLevel.INFO, "Tag", "shown", null)
        }
        val content = Files.readString(logsDir.resolve(RollingFileSink.CURRENT_NAME))
        assertThat(content).contains("shown")
        assertThat(content).doesNotContain("hidden")
        assertThat(content).startsWith("2026-10-06T00:00:00.000Z INFO Tag shown")
    }

    @Test
    fun `rotation keeps five files and drops the oldest content`() {
        val sink = RollingFileSink(
            logsDir,
            minLevel = LogLevel.DEBUG,
            maxFileBytes = SMALL_CAP,
            timeSource = { fixedInstant },
        )
        sink.use {
            repeat(LINE_COUNT) { index -> it.log(LogLevel.INFO, "T", "line $index", null) }
        }

        assertThat(Files.exists(logsDir.resolve(RollingFileSink.CURRENT_NAME))).isTrue()
        for (index in 1..RollingFileSink.ROTATED_FILES) {
            assertThat(Files.exists(logsDir.resolve(RollingFileSink.rotatedName(index)))).isTrue()
        }
        // Five files in total: no sixth appears.
        assertThat(Files.exists(logsDir.resolve("neutrodyne.5.log"))).isFalse()

        // One line is 40 bytes (39 characters + newline), so a 120-byte cap rotates every third
        // write. With 29 writes: nine rotations (after lines 2, 5, …, 26); the current file holds
        // 27 and 28, `.1` the last complete group (24–26), `.4` the sixth group (15–17).
        val current = Files.readString(logsDir.resolve(RollingFileSink.CURRENT_NAME))
        assertThat(current).contains("line ${LINE_COUNT - 1}")
        val newestRotation = Files.readString(logsDir.resolve(RollingFileSink.rotatedName(1)))
        assertThat(newestRotation).contains("line ${LINE_COUNT - 5}")
        assertThat(newestRotation).doesNotContain("line ${LINE_COUNT - 1}")
        // Only four rotated files survive nine rotations, so everything before line 15 is gone.
        val oldest = Files.readString(logsDir.resolve(RollingFileSink.rotatedName(RollingFileSink.ROTATED_FILES)))
        assertThat(oldest).contains("line 15")
        assertThat(oldest).doesNotContain("line 14")
    }

    @Test
    fun `throwable stacks are written redacted`() {
        val sink = RollingFileSink(
            logsDir,
            minLevel = LogLevel.INFO,
            maxFileBytes = LARGE_CAP,
            timeSource = { fixedInstant },
        )
        sink.use {
            it.log(
                LogLevel.ERROR,
                "Fetch",
                "enclosure failed",
                RuntimeException("https://user:secret@example.org/rss/a8F3kq09ZpLm2xQ?auth=zebra"),
            )
        }
        val content = Files.readString(logsDir.resolve(RollingFileSink.CURRENT_NAME))
        assertThat(content).contains("enclosure failed")
        assertThat(content).contains("RuntimeException")
        // User-info, token-shaped path segments and query values never reach the file.
        assertThat(content).doesNotContain("user:secret")
        assertThat(content).doesNotContain("a8F3kq09ZpLm2xQ")
        assertThat(content).doesNotContain("zebra")
    }

    private companion object {
        // A line is 40 bytes (39 characters + newline); a 120-byte cap rotates every third write.
        const val SMALL_CAP = 120L
        const val LARGE_CAP = 1024L * 1024
        const val LINE_COUNT = 29
    }
}
