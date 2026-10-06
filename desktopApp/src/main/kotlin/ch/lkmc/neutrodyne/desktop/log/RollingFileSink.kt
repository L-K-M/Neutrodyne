// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.log

import ch.lkmc.neutrodyne.core.common.LogLevel
import ch.lkmc.neutrodyne.core.common.LogSink
import java.io.BufferedWriter
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant

/**
 * The desktop log destination (11 Logs and rotation): `<logs>/neutrodyne.log`, UTF-8, rotation at
 * [maxFileBytes] keeping [ROTATED_FILES] older files (`neutrodyne.1.log` … `neutrodyne.4.log`, so
 * five files in total). Messages arrive already redacted through [Log]; the throwable stack is
 * redacted here. Errors of the sink itself are swallowed — logging must never take the app down.
 *
 * The level filter is the caller's business (INFO in packaged builds, DEBUG for
 * `BuildInfo.debug`, 01 Logging and redaction).
 */
internal class RollingFileSink(
    logsDir: Path,
    private val minLevel: LogLevel,
    private val maxFileBytes: Long = MAX_FILE_BYTES,
    private val timeSource: () -> Instant = Instant::now,
) : LogSink,
    AutoCloseable {
    private val currentFile = logsDir.resolve(CURRENT_NAME)
    private val lock = Any()

    private var writer: BufferedWriter?

    init {
        Files.createDirectories(logsDir)
        writer = openWriter()
    }

    override fun log(
        level: LogLevel,
        tag: String,
        message: String,
    ) {
        if (level.ordinal < minLevel.ordinal) return
        val line = formatLogLine(level, tag, message, timeSource())
        synchronized(lock) {
            val sink = writer ?: return
            try {
                sink.write(line)
                sink.newLine()
                sink.flush()
                if (Files.size(currentFile) >= maxFileBytes) rotate()
            } catch (_: IOException) {
                // Disk full or the file vanished: drop the line and retry with a fresh writer next time.
                closeWriter()
                writer = openWriter()
            }
        }
    }

    /** `neutrodyne.3.log` → `.4`, …, `neutrodyne.log` → `.1`, then a fresh current file. */
    private fun rotate() {
        val logsDir = currentFile.parent
        closeWriter()
        for (index in ROTATED_FILES downTo 2) {
            val from = logsDir.resolve(rotatedName(index - 1))
            val to = logsDir.resolve(rotatedName(index))
            if (Files.exists(from)) {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        Files.move(currentFile, logsDir.resolve(rotatedName(1)), StandardCopyOption.REPLACE_EXISTING)
        writer = openWriter()
    }

    private fun openWriter(): BufferedWriter? =
        try {
            Files.newBufferedWriter(
                currentFile,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        } catch (_: IOException) {
            null
        }

    private fun closeWriter() {
        try {
            writer?.close()
        } catch (_: IOException) {
            // Ignored: rotation continues with a fresh writer either way.
        }
        writer = null
    }

    override fun close() {
        synchronized(lock) {
            closeWriter()
        }
    }

    companion object {
        /** 11 Logs and rotation: rotate at 2 MiB. */
        const val MAX_FILE_BYTES = 2L * 1024 * 1024
        const val ROTATED_FILES = 4
        const val CURRENT_NAME = "neutrodyne.log"

        fun rotatedName(index: Int): String = "neutrodyne.$index.log"
    }
}
