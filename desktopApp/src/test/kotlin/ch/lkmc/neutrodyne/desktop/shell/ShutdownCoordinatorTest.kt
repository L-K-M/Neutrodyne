// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.shell

import ch.lkmc.neutrodyne.core.common.LogLevel
import ch.lkmc.neutrodyne.desktop.log.RollingFileSink
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The clean-shutdown path a window close ends in (11 Shutdown): `session.json` flips to
 * `cleanExit = true`, the single-instance lock is free again, the log file exists — and the
 * second caller (the SIGTERM hook racing the window close) changes nothing.
 */
class ShutdownCoordinatorTest {
    @Test
    fun `shutdown writes cleanExit releases the lock flushes the log and runs once`() {
        val dirs = tempAppDirs("nd-shutdown").also { it.ensureCreated() }
        val lock = SingleInstanceLock(dirs, versionName = VERSION_NAME)
        assertThat(lock.tryAcquire()).isEqualTo(SingleInstanceLock.Acquire.Acquired)
        val sink = RollingFileSink(dirs.logs, LogLevel.DEBUG)
        var stops = 0
        val coordinator =
            ShutdownCoordinator(
                dirs = dirs,
                versionName = VERSION_NAME,
                lock = lock,
                fileSink = sink,
                stopServices = { stops++ },
            )

        coordinator.shutdown(REASON_ONE)
        coordinator.shutdown(REASON_TWO)

        assertThat(stops).isEqualTo(1)

        val session = SessionFile.read(dirs.state)
        assertThat(session).isNotNull()
        assertThat(session!!.cleanExit).isTrue()
        assertThat(session.versionName).isEqualTo(VERSION_NAME)

        // The lock is free for the next start, and the log file was written out.
        val next = SingleInstanceLock(dirs)
        assertThat(next.tryAcquire()).isEqualTo(SingleInstanceLock.Acquire.Acquired)
        next.close()
        assertThat(
            dirs.logs
                .resolve("neutrodyne.log")
                .toFile()
                .isFile,
        ).isTrue()
    }

    private companion object {
        const val VERSION_NAME = "0.1.0-test"
        const val REASON_ONE = "window closed"
        const val REASON_TWO = "signal"
    }
}
