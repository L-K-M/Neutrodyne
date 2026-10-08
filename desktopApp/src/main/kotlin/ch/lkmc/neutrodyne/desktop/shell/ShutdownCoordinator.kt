// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.shell

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.desktop.log.RollingFileSink
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The clean shutdown (11 Shutdown, the M0b subset): stop the hand-off server and the
 * application scope, write `session.json` with `cleanExit = true`, release the
 * single-instance lock and flush the log. Playback, lanes and the sync push join with their
 * milestones.
 *
 * [startedAtMs] is the session's real start instant, passed in by the caller that wrote the
 * session file at start-up — `session.json` keeps one meaning for the field whether the exit
 * was clean or not.
 *
 * Idempotent: the window-close path on the main thread and the SIGTERM shutdown hook both end
 * here — only the first call runs, so the exit stays clean whichever arrives second. Every
 * step after the service stop is guarded, so a full disk or a bad lock release cannot skip the
 * log flush.
 */
internal class ShutdownCoordinator(
    private val dirs: AppDirs,
    private val versionName: String,
    private val lock: SingleInstanceLock,
    private val fileSink: RollingFileSink,
    private val stopServices: () -> Unit,
    private val startedAtMs: Long,
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
        } catch (e: Exception) {
            // A broken stop must not cost the session file, the lock release or the log flush.
            Log.w(TAG, e) { "service stop failed; continuing the clean shutdown" }
        }

        runCatching {
            SessionFile.write(
                dirs.state,
                SessionState(
                    pid = ProcessHandle.current().pid(),
                    startedAtMs = startedAtMs,
                    versionName = versionName,
                    cleanExit = true,
                ),
            )
        }.onFailure { Log.w(TAG, it) { "session file write failed" } }
        runCatching { lock.close() }
            .onFailure { Log.w(TAG, it) { "instance lock release failed" } }
        fileSink.close()
    }

    private companion object {
        const val TAG = "Shutdown"
    }
}
