// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.tools.bmgrdriver

import android.os.SystemClock
import androidx.test.uiautomator.UiDevice
import java.io.File

/**
 * dumpsys + UiAutomator glue for the external bgstart impossibility leg (M4 P6b). Every
 * assertion is re-verified by the strict aggregate checker on the captured
 * `bgstart-*.txt` files — this class only surfaces framework state and saves evidence.
 * Bounded waits only; a missing surface fails.
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

        /**
         * The impossibility stamp: a record where the trusted trigger's own bind
         * carries the FGS capability grant — `code:PROC_STATE_BFGS` with
         * `startForegroundCount:0` means the binding itself, before any
         * startForeground call, already exempted the target.
         */
        fun triggerGrantStamps(): List<BgStartSurface.AllowInfo> =
            infos.filter {
                it.callingPackage == BgStartSurface.TRIGGER_PACKAGE &&
                    it.code == "PROC_STATE_BFGS" &&
                    it.startForegroundCount == "0"
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
     * its Media3 connect lands a real controller whose command set stays empty
     * until the uid is genuinely trusted — never by assuming the command took.
     * Every attempt is written to `bgstart-listener.txt`.
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
     * the same thaw mechanism as [pokeActor] — carrying the `status` probe that
     * performs the provider-side Media3 connect and reports the granted command
     * set. The provider answers via its returned Bundle, printed by `content`
     * as `result=<k=v>`.
     */
    fun triggerCall(method: String): String =
        shell("content call --uri content://${BgStartSurface.TRIGGER_PROVIDER} --method $method")

    /**
     * The trusted trigger's view of the session: `status` reports
     * `trusted=1 target=1` only when its Media3 connect landed AND the session
     * granted a real command set (an untrusted connect has none) —
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

    companion object {
        const val WAIT_MS = 30_000L

        /**
         * Listener enablement plus the trigger's trusted view of the session —
         * bounded like every other wait; NMS observes the settings row in well
         * under this on a fresh image.
         */
        const val TRUST_WAIT_MS = 20_000L

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
