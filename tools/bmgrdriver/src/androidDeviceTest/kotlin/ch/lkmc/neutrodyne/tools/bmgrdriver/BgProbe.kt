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
        val serviceRunning: Boolean,
        val isForeground: Boolean?,
        val noBinding: String?,
        val byBindings: String?,
        val bindingClients: List<String>,
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

                    info.granted -> {
                        "allow code ${info.code} in $info"
                    }

                    else -> {
                        null
                    }
                }
            }

        /** Any record — self or binding — whose verdict grants an FGS start. */
        fun grantViolations(): List<String> =
            infos.mapNotNull { info ->
                when {
                    info.bindingGrant != null -> {
                        "bindFromPackage ${info.bindingGrant} grants FGS in $info"
                    }

                    info.granted -> {
                        "allow code ${info.code} in $info"
                    }

                    else -> {
                        null
                    }
                }
            }

        /** Self-attempt records that prove a real evaluation ended in DENIED. */
        fun deniedAttempts(appUid: Int): List<BgStartSurface.AllowInfo> =
            infos.filter { it.selfAttempt(appUid) && it.code == "DENIED" }
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
            pokeActor()
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
            pokeActor()
            if (condition()) return true
            Thread.sleep(periodMs)
        }
        return condition()
    }

    fun appUid(): Int =
        BgStartSurface.parseAppUid(
            shell("cmd package list packages -U ${BmgrArming.TARGET_PACKAGE}"),
            BmgrArming.TARGET_PACKAGE,
        ) ?: error("target uid did not parse from the package listing")

    /**
     * Grants the `:tools:bgtrigger` package real trusted-controller membership:
     * `cmd notification allow_listener` on supported builds, else the durable
     * `enabled_notification_listeners` secure setting (existing entries
     * preserved). The membership is verified on the trigger's own surface —
     * `getActiveSessions` throws until the uid is genuinely trusted — never by
     * assuming the command took. Every attempt is written to
     * `bgstart-listener.txt`.
     */
    fun enableTrigger() {
        val log = StringBuilder()
        log.appendLine(
            "allow_listener -> " +
                shell("cmd notification allow_listener ${BgStartSurface.TRIGGER_LISTENER}").trim(),
        )
        if (!tryAwait(TRUST_WAIT_MS) { triggerSessionVisible() }) {
            val current = shell("settings get secure enabled_notification_listeners").trim()
            val merged =
                when {
                    current.isBlank() || current == "null" -> BgStartSurface.TRIGGER_LISTENER
                    BgStartSurface.TRIGGER_LISTENER in current -> current
                    else -> "$current:${BgStartSurface.TRIGGER_LISTENER}"
                }
            log.appendLine(
                "settings put -> " +
                    shell("settings put secure enabled_notification_listeners $merged").trim(),
            )
            tryAwait(TRUST_WAIT_MS) { triggerSessionVisible() }
        }
        write("bgstart-listener.txt", log.toString())
    }

    /**
     * A synchronous binder transaction into the trigger's exported provider —
     * the same thaw mechanism as [pokeActor] — carrying the trusted-command
     * vocabulary (`status`/`prepare`/`play`). The provider answers via its
     * returned Bundle, printed by `content` as `result=<k=v>`.
     */
    fun triggerCall(method: String): String =
        shell("content call --uri content://${BgStartSurface.TRIGGER_PROVIDER} --method $method")

    /**
     * The trusted trigger's view of the session: `status` reports
     * `trusted=1 target=1` only when the listener membership is real AND the
     * target's restored session is visible through MediaSessionManager —
     * evidence that a genuine trusted controller issued the later command.
     */
    fun triggerSessionVisible(): Boolean {
        val out = triggerCall("status")
        write("bgstart-trigger-status.txt", out)
        return "trusted=1" in out && "target=1" in out
    }

    /** The trigger uid's own procstate + its listener membership, per stage. */
    fun captureTrigger(label: String) {
        val uid =
            BgStartSurface.parseAppUid(
                shell("cmd package list packages -U ${BgStartSurface.TRIGGER_PACKAGE}"),
                BgStartSurface.TRIGGER_PACKAGE,
            )
        write(
            "bgstart-trigger-$label.txt",
            "uid=${uid ?: -1}\n" +
                "uidState=${shell("cmd activity get-uid-state ${uid ?: -1}").trim()}\n" +
                "enabledListeners=" +
                shell("settings get secure enabled_notification_listeners").trim() +
                "\n",
        )
    }

    fun uidState(uid: Int): Int? = BgStartSurface.parseUidState(shell("cmd activity get-uid-state $uid"))

    fun servicesDump(): String = shell("dumpsys -t 20 activity services ${BgStartSurface.SERVICE_COMPONENT}")

    /**
     * The external cold start is a BIND, not a start: the standalone
     * `:tools:bgactor` APK (a separate uid that never gains an instrumentation
     * TOP) launches a throwaway activity — `am start` is exempt from the
     * bg-start restriction that denies `am startservice` on a cold package —
     * whose service binds the target's exported media-library action only once
     * its own uid has demoted, mirroring the in-app `MediaController.connect()`.
     * A bind never auto-promotes the service to foreground the way
     * `am startservice` did on the target.
     */
    fun startActor() {
        val out = shell("am start -n ${BgStartSurface.ACTOR_COMPONENT}")
        check("Error" !in out && "Exception" !in out) { "actor activity failed: $out" }
    }

    fun stopActor() {
        shell("am stopservice -n ${BgStartSurface.ACTOR_SERVICE}")
    }

    /**
     * A synchronous binder transaction into the actor process through its inert
     * exported provider. The cached-app freezer ices the actor ~10 s in (api36
     * froze it inside the activity-starter grace wait both rounds), which stops
     * its demotion poll entirely — an incoming sync binder is the platform's
     * own thaw path ("sync unfroze" in logcat), so poking here lets the gate's
     * deadline-elapsed check and the demoted bind actually run.
     */
    fun pokeActor() {
        runCatching {
            shell(
                "content call --uri content://${BgStartSurface.ACTOR_PROVIDER} --method wake",
            )
        }
    }

    /**
     * The committee-mandated settle: the target uid must stay at or below SERVICE and the
     * service non-foreground for [settleMs] continuously, inside a [budgetMs] window.
     * Every sample is written to `bgstart-settle.txt` for the strict checker; a breach
     * restarts the window, an exhausted budget fails loudly with the samples on disk.
     */
    fun awaitDemotedSettled(
        uid: Int,
        settleMs: Long = BgStartSurface.SETTLE_MS,
        budgetMs: Long = WAIT_MS * 4,
    ) {
        val started = SystemClock.elapsedRealtime()
        val deadline = started + budgetMs
        val rows = mutableListOf<String>()
        var demotedSince: Long? = null
        try {
            while (true) {
                val elapsed = SystemClock.elapsedRealtime() - started
                pokeActor()
                val state = uidState(uid)
                val fg = "isForeground=true" in servicesDump()
                rows += "t=$elapsed state=${state ?: -1} fg=$fg"
                val demoted = state != null && state >= BgStartSurface.PROCSTATE_SERVICE && !fg
                if (!demoted) {
                    demotedSince = null
                } else if (demotedSince == null) {
                    demotedSince = SystemClock.elapsedRealtime()
                } else if (SystemClock.elapsedRealtime() - demotedSince >= settleMs) {
                    return
                }
                check(SystemClock.elapsedRealtime() < deadline) {
                    "target uid never settled at/below SERVICE non-foreground for ${settleMs}ms"
                }
                Thread.sleep(1_000)
            }
        } finally {
            write("bgstart-settle.txt", rows.joinToString("\n", postfix = "\n"))
        }
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
        val lru =
            shell("dumpsys -t 20 activity lru | grep -B3 -A6 '${BmgrArming.TARGET_PACKAGE}'")
        write("bgstart-uid-state-$label.txt", uidText)
        write("bgstart-services-$label.txt", services)
        write("bgstart-processes-$label.txt", processes)
        write("bgstart-notification-$label.txt", notification)
        write("bgstart-media-session-$label.txt", mediaSession)
        write("bgstart-audio-$label.txt", audio)
        write("bgstart-lru-$label.txt", lru)
        return Stage(
            label = label,
            uidState = BgStartSurface.parseUidState(uidText),
            serviceRecord = BgStartSurface.serviceRecordPresent(services),
            serviceRunning = BgStartSurface.serviceRunning(services),
            isForeground = BgStartSurface.isForegroundValue(services),
            noBinding = BgStartSurface.noBindingVerdict(services),
            byBindings = BgStartSurface.byBindingsVerdict(services),
            bindingClients = BgStartSurface.bindingClients(services),
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
            pokeActor()
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

        /**
         * Listener enablement plus the trigger's trusted view of the session —
         * bounded like every other wait; NMS observes the settings row in well
         * under this on a fresh image.
         */
        const val TRUST_WAIT_MS = 20_000L

        /**
         * The prepare→PAUSED probe. If the trusted prepare ever lands, the
         * session republishes PAUSED well inside this window; a miss only means
         * the card probe below will honestly report no card again.
         */
        const val PREPARED_WAIT_MS = 20_000L

        /**
         * The bound stage's budget. The actor deliberately waits out the ~10 s
         * activity-starter FGS grace before binding (a bind inside it stamps
         * code:ACTIVITY_STARTER on the binding record), and on api36 the
         * demotion gate itself can take ~60 s (r8 reached IMPORTANCE_SERVICE only
         * at the deadline) plus the cold target process spawn — a pacing bound,
         * not a semantic guard.
         */
        const val BOUND_WAIT_MS = 120_000L
    }
}
