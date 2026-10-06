// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.shell

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.desktop.log.RollingFileSink
import ch.lkmc.neutrodyne.desktop.platform.DesktopClock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The clean shutdown (11 Shutdown, the M0b subset): stop the hand-off server and the
 * application scope, write `session.json` with `cleanExit = true`, release the
 * single-instance lock and flush the log. Playback, lanes and the sync push join with their
 * milestones.
 *
 * Idempotent: the window-close path on the main thread and the SIGTERM shutdown hook both end
 * here — only the first call runs, so the exit stays clean whichever arrives second.
 */
internal class ShutdownCoordinator(
    private val dirs: AppDirs,
    private val versionName: String,
    private val lock: SingleInstanceLock,
    private val fileSink: RollingFileSink,
    private val stopServices: () -> Unit,
) {
    private val done = AtomicBoolean(false)

    fun shutdown(reason: String) {
        if (!done.compareAndSet(false, true)) return
        Log.i(TAG) { "clean shutdown ($reason)" }

        try {
            stopServices()
        } catch (e: InterruptedException) {
            // The JVM is tearing down anyway; restore the flag and continue the clean path.
            Thread.currentThread().interrupt()
        }

        SessionFile.write(
            dirs.state,
            SessionState(
                pid = ProcessHandle.current().pid(),
                startedAtMs = DesktopClock.now(),
                versionName = versionName,
                cleanExit = true,
            ),
        )
        lock.close()
        fileSink.close()
    }

    private companion object {
        const val TAG = "Shutdown"
    }
}
