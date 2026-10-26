// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.tools.bmgrdriver

import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket

/**
 * The armed bgstart driver phases (M4 P6b): `bgseed` restores a durable paused session
 * through the real production import UI, then `bgstart` measures the UNINSTRUMENTED
 * target from outside — the impossibility matrix. A bind-only external cold start
 * (the standalone `:tools:bgactor` APK binds the exported media-library action, never
 * a `startService`) and a verified demoted-and-settled window set up the two measured
 * rows: the untrusted bound controller (the actor's demoted bind plus the trigger's
 * pre-listener Media3 connect — real bind, `code:DENIED`, no elevation, and a command
 * set with no COMMAND_PLAY_PAUSE) and the trusted bound controller (the
 * `:tools:bgtrigger` uid's enabled NotificationListenerService makes its Media3
 * connect trusted — and that very bind stamps `code:PROC_STATE_BFGS` with
 * `startForegroundCount:0`, the platform's capability grant arriving BEFORE any
 * foreground start could be attempted). With every commanding surface exempt by
 * binding and every non-exempt surface unable to command, the leg records
 * `verdict=impossible`. The strict aggregate re-verifies every saved
 * `bgstart-*.txt` file.
 */
@RunWith(AndroidJUnit4::class)
class BgStartTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val device
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun seedPausedSession() {
        val nonce =
            BmgrArming.requireArmed("bgseed", "seedPausedSession", InstrumentationRegistry.getArguments())
        val recorder = StatusRecorder("bgseed", nonce, context.packageName)
        try {
            val port = reservePort()
            val enclosureBase = "http://127.0.0.1:$port"
            val (content, seed) =
                SeedFixture.seedZip(
                    nonce,
                    File(SeedVault.dir(context), "bgseed.zip"),
                    enclosureBase,
                )
            val sha = SeedFixture.sha256(seed)
            BgStartSidecar.write(
                File(SeedVault.dir(context), "bgstart.expected.json"),
                BgStartSidecar(
                    nonce = nonce,
                    api = Build.VERSION.SDK_INT,
                    seedSha256 = sha,
                    seedBytes = seed.length(),
                    port = port,
                    enclosureUrl = "$enclosureBase/ep2.mp3",
                    fixture = content,
                ),
            )
            recorder.step("built", sha)
            val uri = UiRestore.importViaProductionUi(seed)
            recorder.step("launched")
            UiRestore.awaitPreview()
            recorder.step("preview")
            // The grant would leak a read path past the import; revoke it so the
            // bgstart leg observes the same surfaces an ordinary install would.
            context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            recorder.step("revoked", uri.toString())
            UiRestore.selectMerge()
            UiRestore.setCategory(UiRestore.CAT_HISTORY, true)
            UiRestore.setCategory(UiRestore.CAT_UP_NEXT, true)
            UiRestore.setCategory(UiRestore.CAT_SETTINGS, true)
            recorder.step("categories", "MERGE+{HISTORY,UP_NEXT,SETTINGS}")
            UiRestore.requestRestore()
            recorder.step("requested")
            finishDriverPhase(context, "bgseed", nonce, recorder, "ok")
        } catch (t: Throwable) {
            finishDriverPhase(context, "bgseed", nonce, recorder, "error:${t.javaClass.simpleName}:${t.message}", t)
        }
    }

    @Test
    fun externalBackgroundStartDenialIsUnreachable() {
        val nonce =
            BmgrArming.requireArmed(
                "bgstart",
                "externalBackgroundStartDenialIsUnreachable",
                InstrumentationRegistry.getArguments(),
            )
        val recorder = StatusRecorder("bgstart", nonce, context.packageName)
        val vault = SeedVault.dir(context)
        val probe = BgProbe(device, vault)
        var uid: Int? = null
        try {
            val sidecar = BgStartSidecar.read(File(vault, "bgstart.expected.json"))
            check(sidecar.nonce == nonce) { "bgstart sidecar nonce ${sidecar.nonce} != armed $nonce" }
            check(sidecar.api == Build.VERSION.SDK_INT) {
                "stale bgstart sidecar: api ${sidecar.api} on ${Build.VERSION.SDK_INT}"
            }
            recorder.step("sidecar", "port=${sidecar.port}")
            val appUid = probe.appUid()
            uid = appUid
            val cold = probe.uidState(appUid)
            check(cold == null || cold >= BgStartSurface.PROCSTATE_SERVICE) {
                "target uid $cold above the service threshold before the attempt"
            }
            probe.startActor()
            recorder.step("actor")
            probe.await("bound live service record", timeoutMs = BgProbe.BOUND_WAIT_MS) {
                // The api36 freezer ices the actor inside its grace wait; a sync
                // binder poke is the platform's own thaw so the poll keeps running.
                probe.pokeActor()
                val dump = probe.servicesDump()
                BgStartSurface.serviceRecordPresent(dump) && BgStartSurface.serviceRunning(dump)
            }
            probe.await("restored media session") {
                BgStartSurface.mediaSessionPlayback(
                    probe.shell("dumpsys -t 20 media_session"),
                    BmgrArming.TARGET_PACKAGE,
                ) != null
            }
            recorder.step("bound")
            probe.awaitDemotedSettled(appUid)
            recorder.step("settled")
            val before = probe.capture("before", uid)
            requireGuarded(before, appUid)
            recorder.step("guarded", "uidState=${before.uidState}")
            // Matrix row 1 — the untrusted bound controller. The actor's demoted
            // bind left `code:DENIED` records and elevated nothing; an untrusted
            // Media3 connect (the trigger before its listener is enabled) lands
            // the same real bind but is granted no COMMAND_PLAY_PAUSE — it can
            // never command playback. The trigger's own bind is measured in the
            // same stage: a granting record here would refute the row honestly.
            val untrustedStatus = probe.triggerCall("status")
            probe.write("bgstart-trigger-status-untrusted.txt", untrustedStatus)
            check("target=1" in untrustedStatus) {
                "the untrusted trigger connect never landed: $untrustedStatus"
            }
            check("trusted=0" in untrustedStatus) {
                "an untrusted controller holds player commands: $untrustedStatus"
            }
            probe.captureTrigger("untrusted")
            val untrusted = probe.capture("untrusted", uid)
            requireGuarded(untrusted, appUid)
            recorder.step("untrusted", "uidState=${untrusted.uidState}")
            // Matrix row 2 — the trusted bound controller. The enabled
            // notification listener makes this uid trusted for media control —
            // the same membership a real companion app carries — but its Media3
            // connect bind must stamp `code:PROC_STATE_BFGS` with
            // `startForegroundCount:0`: the capability grant arrives with the
            // binding, before any foreground start could be attempted or denied.
            probe.enableTrigger()
            check(probe.tryAwait(BgProbe.TRUST_WAIT_MS) { probe.triggerSessionVisible() }) {
                "the trusted trigger never saw the session"
            }
            val trustedStatus = probe.triggerCall("status")
            probe.write("bgstart-trigger-status-trusted.txt", trustedStatus)
            check("trusted=1" in trustedStatus) {
                "the trusted connect holds no player commands: $trustedStatus"
            }
            probe.captureTrigger("trusted")
            val trusted = probe.capture("trusted", uid)
            requireGrantStamped(trusted)
            recorder.step("trusted", "uidState=${trusted.uidState} clients=${trusted.bindingClients}")
            // Matrix row 3 — the binding-free framework relay — is documented
            // historical evidence (r13: judged untrusted target-side, no
            // elevation); nothing here wires it. With no surface both able to
            // command and non-exempt, the denial is unreachable by construction.
            probe.write("bgstart-verdict.txt", "verdict=impossible\n")
            recorder.step("verdict", "impossible")
            probe.captureLogcat()
            finishDriverPhase(context, "bgstart", nonce, recorder, "ok")
        } catch (t: Throwable) {
            runCatching { probe.capture("fail", uid) }
            probe.dumpHierarchy("fail")
            finishDriverPhase(context, "bgstart", nonce, recorder, "error:${t.javaClass.simpleName}:${t.message}", t)
        } finally {
            runCatching { probe.stopActor() }
        }
    }

    /**
     * A measured stage where the target must be bound, live, demoted and non-foreground
     * with nothing granting FGS. A bound service's dump carries no `isForeground=` line
     * (ServiceRecord.dump prints it only when foreground or a stale foregroundId
     * survives), so "non-foreground" is proven by the record existing while
     * `isForeground=true` is absent. The `mAllowStart_*` fields print only on newer
     * dumps; when present they must be DENIED.
     */
    private fun requireGuarded(
        stage: BgProbe.Stage,
        uid: Int,
    ) {
        check(stage.serviceRecord) { "no ServiceRecord for the bound service at ${stage.label}" }
        check(stage.serviceRunning) {
            "ServiceRecord has no live process (app=null phantom) at ${stage.label}"
        }
        check(stage.uidState != null && stage.uidState >= BgStartSurface.PROCSTATE_SERVICE) {
            "target uid ${stage.uidState} above the service threshold at ${stage.label}"
        }
        check(stage.isForeground != true) { "service already foreground at ${stage.label}" }
        check(stage.noBinding == null || stage.noBinding == "DENIED") {
            "mAllowStart_noBinding=${stage.noBinding} at ${stage.label} — an allow source exists"
        }
        check(stage.byBindings == null || stage.byBindings == "DENIED") {
            "mAllowStart_byBindings=${stage.byBindings} at ${stage.label} — a binding elevated the target"
        }
        check(!stage.tempAllowViolation) { "temp allowlist present at ${stage.label}" }
        check(stage.selfViolations(uid).isEmpty()) {
            "self records show FGS capability at ${stage.label}: ${stage.selfViolations(uid)}"
        }
    }

    /**
     * The impossibility row's positive stamp: the trusted trigger's connect bind must
     * carry `code:PROC_STATE_BFGS` with `startForegroundCount:0` — the binding itself
     * granted the FGS capability before any startForeground attempt existed to deny.
     */
    private fun requireGrantStamped(stage: BgProbe.Stage) {
        check(stage.serviceRecord) { "no ServiceRecord for the bound service at ${stage.label}" }
        check(stage.serviceRunning) { "ServiceRecord has no live process at ${stage.label}" }
        check(stage.triggerGrantStamps().isNotEmpty()) {
            "the trusted bind stamped no PROC_STATE_BFGS/startForegroundCount:0 grant " +
                "at ${stage.label} — the matrix row did not measure the exemption"
        }
    }

    /** An ephemeral port the bgseed leg's sidecar records for the restored enclosure. */
    private fun reservePort(): Int = ServerSocket(0).use { it.localPort }
}
