// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.tools.bmgrdriver

import android.os.SystemClock
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File

/**
 * dumpsys + UiAutomator glue for the external bgstart denial leg (M4 P6b). Every assertion
 * is re-verified by the strict aggregate checker on the captured `bgstart-*.txt` files —
 * this class only surfaces framework state, performs the two real UI taps (media-card Play,
 * notification Resume) and saves evidence. Bounded waits only; a missing surface fails.
 */
internal class BgProbe(
    private val device: UiDevice,
    private val vault: File,
) {
    /** Parsed state for one capture point; the strict asserts in [BgStartTest] read these. */
    data class Stage(
        val label: String,
        val uidState: Int?,
        val serviceRecord: Boolean,
        val isForeground: Boolean?,
        val noBinding: String?,
        val byBindings: String?,
        val infos: List<BgStartSurface.AllowInfo>,
        val tempAllowObserved: Boolean,
        val tempAllowViolation: Boolean,
        val notification4001: Boolean,
        val notification4000: Boolean,
        val apcStarted: Boolean,
        val playback: BgStartSurface.PlaybackSample?,
    ) {
        /** Reasons a record tied to the target's own attempt would falsify the denial. */
        fun selfViolations(appUid: Int): List<String> =
            infos.filter { it.selfAttempt(appUid) }.mapNotNull { info ->
                when {
                    info.hasBfslCapability -> {
                        "BFSL capability in $info"
                    }

                    info.tempAllowListed -> {
                        "temp allowlist in $info"
                    }

                    info.uidState != null && info.uidState !in BgStartSurface.UIDSTATE_BELOW_BFGS -> {
                        "uidState ${info.uidState} above BFGS in $info"
                    }

                    else -> {
                        null
                    }
                }
            }
    }

    fun shell(command: String): String = device.executeShellCommand(command)

    fun write(
        name: String,
        content: String,
    ): File = File(vault, name).apply { writeText(content) }

    fun dumpHierarchy(name: String) {
        runCatching { device.dumpWindowHierarchy(File(vault, "ui-bgstart-$name.xml")) }
    }

    fun await(
        description: String,
        timeoutMs: Long = WAIT_MS,
        periodMs: Long = 500,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            if (condition()) return
            check(SystemClock.elapsedRealtime() < deadline) { "timed out waiting for $description" }
            Thread.sleep(periodMs)
        }
    }

    fun tryAwait(
        timeoutMs: Long,
        periodMs: Long = 600,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            Thread.sleep(periodMs)
        }
        return condition()
    }

    fun appUid(): Int =
        BgStartSurface.parseAppUid(shell("cmd package list packages -U ${BmgrArming.TARGET_PACKAGE}"))
            ?: error("target uid did not parse from the package listing")

    fun uidState(uid: Int): Int? = BgStartSurface.parseUidState(shell("cmd activity get-uid-state $uid"))

    fun servicesDump(): String = shell("dumpsys -t 20 activity services ${BgStartSurface.SERVICE_COMPONENT}")

    /** A bare background start: shell `am` must never produce a foreground service. */
    fun startTargetService() {
        val out = shell("am startservice -n ${BgStartSurface.SERVICE_COMPONENT}")
        check("Error" !in out && "Exception" !in out) { "am startservice failed: $out" }
    }

    /**
     * Captures every surface for one stage into `bgstart-*-<label>.txt` vault files and
     * returns the parsed stage. `uid` may be null when it could not be resolved yet.
     */
    fun capture(
        label: String,
        uid: Int?,
    ): Stage {
        val uidText = shell("cmd activity get-uid-state ${uid ?: -1}")
        val services = servicesDump()
        val processes =
            shell(
                "dumpsys -t 20 activity processes | grep -B2 -A80 '${BmgrArming.TARGET_PACKAGE}'",
            )
        val notification =
            shell("dumpsys -t 20 notification | grep 'pkg=${BmgrArming.TARGET_PACKAGE}'")
        val mediaSession = shell("dumpsys -t 20 media_session")
        val audio =
            shell("dumpsys -t 20 audio | grep -E 'AudioPlaybackConfiguration|uid:|state:'")
        write("bgstart-uid-state-$label.txt", uidText)
        write("bgstart-services-$label.txt", services)
        write("bgstart-processes-$label.txt", processes)
        write("bgstart-notification-$label.txt", notification)
        write("bgstart-media-session-$label.txt", mediaSession)
        write("bgstart-audio-$label.txt", audio)
        return Stage(
            label = label,
            uidState = BgStartSurface.parseUidState(uidText),
            serviceRecord = BgStartSurface.serviceRecordPresent(services),
            isForeground = BgStartSurface.isForegroundValue(services),
            noBinding = BgStartSurface.noBindingVerdict(services),
            byBindings = BgStartSurface.byBindingsVerdict(services),
            infos = BgStartSurface.allowInfos(services),
            tempAllowObserved =
                BgStartSurface.tempAllowListObserved(services) ||
                    BgStartSurface.tempAllowListObserved(processes),
            tempAllowViolation =
                BgStartSurface.tempAllowListViolation(services) ||
                    BgStartSurface.tempAllowListViolation(processes),
            notification4001 =
                BgStartSurface.hasNotification(
                    notification,
                    BmgrArming.TARGET_PACKAGE,
                    BgStartSurface.RESUME_NOTIFICATION,
                    BgStartSurface.RESUME_CHANNEL,
                ),
            notification4000 =
                BgStartSurface.hasNotification(
                    notification,
                    BmgrArming.TARGET_PACKAGE,
                    BgStartSurface.PLAYBACK_NOTIFICATION,
                    BgStartSurface.PLAYBACK_CHANNEL,
                ),
            apcStarted = uid?.let { BgStartSurface.apcStarted(audio, it) } == true,
            playback = BgStartSurface.mediaSessionPlayback(mediaSession, BmgrArming.TARGET_PACKAGE),
        )
    }

    /** `logcat -d` filtered to the FGS-denial surface; record-only evidence. */
    fun captureLogcat() {
        write(
            "bgstart-fssnae-logcat.txt",
            shell(
                "logcat -d -v threadtime | grep -iE 'ForegroundServiceStartNotAllowed|" +
                    "startForegroundService|neutrodyne'",
            ),
        )
    }

    /** Notification shade: notification panel first, quick settings as the API31 fallback. */
    fun openShade(): Boolean = device.openNotification() || device.openQuickSettings()

    /**
     * The SystemUI media-controls Play button on the paused restored session. Scoped to
     * `com.android.systemui` so a notification's own transport buttons can never stand in;
     * a null return means no trusted trigger exists and the lane must fail.
     */
    fun awaitCardPlay(timeoutMs: Long): UiObject2? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val play =
                device.findObject(By.pkg(BgStartSurface.SYSTEMUI_PACKAGE).descContains("Play"))
                    ?: device.findObject(By.pkg(BgStartSurface.SYSTEMUI_PACKAGE).text("Play"))
            if (play != null) return play
            Thread.sleep(400)
        }
        return null
    }

    /** The target's recovery-notification "Resume" action — a real tap, never the PendingIntent. */
    fun tapResume(timeoutMs: Long) {
        val node =
            device.wait(Until.findObject(By.text("Resume")), timeoutMs)
                ?: device.wait(Until.findObject(By.descContains("Resume")), 5_000)
                ?: error("the notification Resume action never resolved")
        node.click()
        device.pressBack()
    }

    /**
     * Two media-session dumps ~1.5 s apart, written to `bgstart-positions.txt` with the
     * speed-projected live position of each, so the checker can recompute the advance.
     */
    fun positionAdvance(): Long? {
        val elapsed1 = SystemClock.elapsedRealtime()
        val dump1 = shell("dumpsys -t 20 media_session")
        val sample1 = BgStartSurface.mediaSessionPlayback(dump1, BmgrArming.TARGET_PACKAGE)
        Thread.sleep(1_500)
        val elapsed2 = SystemClock.elapsedRealtime()
        val dump2 = shell("dumpsys -t 20 media_session")
        val sample2 = BgStartSurface.mediaSessionPlayback(dump2, BmgrArming.TARGET_PACKAGE)
        val live1 = sample1?.livePosition(elapsed1)
        val live2 = sample2?.livePosition(elapsed2)
        write("bgstart-media-session-pos2.txt", dump2)
        write(
            "bgstart-positions.txt",
            buildString {
                appendLine(
                    "sample=1 elapsed=$elapsed1 state=${sample1?.state} " +
                        "position=${sample1?.position} speed=${sample1?.speed} " +
                        "updated=${sample1?.updatedElapsed} live=$live1",
                )
                appendLine(
                    "sample=2 elapsed=$elapsed2 state=${sample2?.state} " +
                        "position=${sample2?.position} speed=${sample2?.speed} " +
                        "updated=${sample2?.updatedElapsed} live=$live2",
                )
                appendLine("delta=${if (live1 != null && live2 != null) live2 - live1 else -1}")
            },
        )
        return if (live1 != null && live2 != null) live2 - live1 else null
    }

    companion object {
        const val WAIT_MS = 30_000L
    }
}
