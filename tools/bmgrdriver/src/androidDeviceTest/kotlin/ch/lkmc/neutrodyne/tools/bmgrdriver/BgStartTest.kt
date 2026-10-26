// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.tools.bmgrdriver

import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket

/**
 * The armed bgstart driver phases (M4 P6b): `bgseed` restores a durable paused session
 * through the real production import UI, then `bgstart` drives the UNINSTRUMENTED target
 * from outside — a bind-only external cold start (the standalone `:tools:bgactor` APK
 * binds the exported media-library action, never a `startService`), a verified
 * demoted-and-settled window, the SystemUI media card's Play button (a trusted
 * STATUS_BAR_SERVICE controller) and the real notification "Resume" tap. The strict
 * aggregate re-verifies every saved `bgstart-*.txt` file.
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
            // The grant would leak a read path past the import; revoke it so the denial
            // leg observes the same surfaces an ordinary install would.
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
    fun deniedBackgroundStartOffersWorkingResume() {
        val nonce =
            BmgrArming.requireArmed(
                "bgstart",
                "deniedBackgroundStartOffersWorkingResume",
                InstrumentationRegistry.getArguments(),
            )
        val recorder = StatusRecorder("bgstart", nonce, context.packageName)
        val vault = SeedVault.dir(context)
        val probe = BgProbe(device, vault)
        var uid: Int? = null
        var server: MockWebServer? = null
        try {
            val sidecar = BgStartSidecar.read(File(vault, "bgstart.expected.json"))
            check(sidecar.nonce == nonce) { "bgstart sidecar nonce ${sidecar.nonce} != armed $nonce" }
            check(sidecar.api == Build.VERSION.SDK_INT) {
                "stale bgstart sidecar: api ${sidecar.api} on ${Build.VERSION.SDK_INT}"
            }
            recorder.step("sidecar", "port=${sidecar.port}")
            val body = FixtureAudio.pcm(FixtureAudio.STREAM_SECONDS)
            server =
                MockWebServer().apply {
                    dispatcher = RangeAudio(body)
                    start(InetAddress.getByName("127.0.0.1"), sidecar.port)
                }
            recorder.step("served", "bytes=${body.size}")
            device.wakeUp()
            probe.shell("wm dismiss-keyguard")
            val appUid = probe.appUid()
            uid = appUid
            val cold = probe.uidState(appUid)
            check(cold == null || cold >= BgStartSurface.PROCSTATE_SERVICE) {
                "target uid $cold above the service threshold before the attempt"
            }
            probe.startActor()
            recorder.step("actor")
            probe.await("bound live service record") {
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
            check(probe.openShade()) { "the SystemUI shade never opened" }
            probe.dumpHierarchy("shade")
            val play = probe.awaitCardPlay(BgProbe.WAIT_MS)
            // The decisive BFSL probe: after the card renders (and binds, if it binds),
            // the target must still sit demoted with no FGS grant on any record.
            val card = probe.capture("card", uid)
            requireGuarded(card, appUid)
            check(card.grantViolations().isEmpty()) {
                "a binding granted FGS capability once the card rendered: " +
                    "${card.grantViolations()} — no genuine denial is reachable"
            }
            recorder.step("card", "uidState=${card.uidState} clients=${card.bindingClients}")
            if (play == null) {
                // No trusted trigger exists on this image; the fallback is out of scope
                // until this absence is proven — fail with the hierarchy as evidence.
                probe.dumpHierarchy("no-card")
                error("SystemUI media card Play never resolved")
            }
            play.click()
            recorder.step("play-tapped")
            check(
                probe.tryAwait(BgProbe.WAIT_MS) {
                    notificationDumpHas(
                        probe,
                        BgStartSurface.RESUME_NOTIFICATION,
                        BgStartSurface.RESUME_CHANNEL,
                    )
                },
            ) { "recovery notification 4001 never posted after the Play tap" }
            val denied = probe.capture("denied", uid)
            requireDenied(denied, appUid)
            probe.captureLogcat()
            recorder.step("denied", "uidState=${denied.uidState}")
            check(probe.openShade()) { "the shade could not be reopened for Resume" }
            probe.tapResume(BgProbe.WAIT_MS)
            recorder.step("resume-tapped")
            check(
                probe.tryAwait(BgProbe.WAIT_MS) {
                    BgStartSurface.isForegroundValue(probe.servicesDump()) == true
                },
            ) { "the service never became foreground after the Resume tap" }
            val resumed = probe.capture("resumed", uid)
            requireResumed(resumed)
            val advance = probe.positionAdvance()
            check(advance != null && advance > BgStartSurface.POSITION_ADVANCE_MS) {
                "position advance ${advance ?: -1}ms did not exceed ${BgStartSurface.POSITION_ADVANCE_MS}ms"
            }
            check(server.requestCount >= 1) { "the enclosure request never reached the driver" }
            recorder.step("resumed", "posAdvance=${advance}ms requests=${server.requestCount}")
            finishDriverPhase(context, "bgstart", nonce, recorder, "ok")
        } catch (t: Throwable) {
            runCatching { probe.capture("fail", uid) }
            probe.dumpHierarchy("fail")
            finishDriverPhase(context, "bgstart", nonce, recorder, "error:${t.javaClass.simpleName}:${t.message}", t)
        } finally {
            runCatching { probe.stopActor() }
            server?.close()
        }
    }

    /**
     * Pre-tap gate: a bound, live, non-foreground service with nothing granting FGS.
     * A bound service's dump carries no `isForeground=` line (ServiceRecord.dump prints
     * it only when foreground or a stale foregroundId survives), so "non-foreground" is
     * proven by the record existing while `isForeground=true` is absent. The
     * `mAllowStart_*` fields print only on newer dumps; when present they must be DENIED.
     */
    private fun requireGuarded(
        stage: BgProbe.Stage,
        uid: Int,
    ) {
        check(stage.serviceRecord) { "no ServiceRecord for the bound service" }
        check(stage.serviceRunning) { "ServiceRecord has no live process (app=null phantom)" }
        check(stage.uidState != null && stage.uidState >= BgStartSurface.PROCSTATE_SERVICE) {
            "target uid ${stage.uidState} above the service threshold before the tap"
        }
        check(stage.isForeground != true) { "service already foreground before the tap" }
        check(stage.noBinding == null || stage.noBinding == "DENIED") {
            "mAllowStart_noBinding=${stage.noBinding} before the tap — an allow source exists"
        }
        check(stage.byBindings == null || stage.byBindings == "DENIED") {
            "mAllowStart_byBindings=${stage.byBindings} before the tap — a binding elevated the target"
        }
        check(!stage.tempAllowViolation) { "temp allowlist present before the tap" }
        check(stage.selfViolations(uid).isEmpty()) {
            "self records show FGS capability before the tap: ${stage.selfViolations(uid)}"
        }
    }

    /** Post-tap denial: real FSSNAE evidence — no service, no allowlist, session paused, 4001 up. */
    private fun requireDenied(
        stage: BgProbe.Stage,
        uid: Int,
    ) {
        check(stage.uidState != null && stage.uidState >= BgStartSurface.PROCSTATE_SERVICE) {
            "target uid ${stage.uidState} elevated after the tap"
        }
        check(stage.isForeground != true) { "service became foreground despite the denial" }
        check(stage.noBinding == null || stage.noBinding == "DENIED") {
            "mAllowStart_noBinding=${stage.noBinding} after the tap"
        }
        check(stage.byBindings == null || stage.byBindings == "DENIED") {
            "mAllowStart_byBindings=${stage.byBindings} after the tap"
        }
        check(stage.deniedAttempts(uid).isNotEmpty()) {
            "no startForeground evaluation recorded a DENIED verdict for the target"
        }
        check(stage.grantViolations().isEmpty()) {
            "a record grants an FGS start despite the denial: ${stage.grantViolations()}"
        }
        check(stage.tempAllowObserved) {
            "tempAllowListReason absent from all dumps — cannot prove no allowlist"
        }
        check(!stage.tempAllowViolation) { "a temp allowlist granted the denied attempt" }
        check(stage.selfViolations(uid).isEmpty()) {
            "self records falsify the denial: ${stage.selfViolations(uid)}"
        }
        check(stage.notification4001) { "recovery notification 4001 absent from the denied stage" }
        check(!stage.apcStarted) { "audio is active for the target uid despite the denial" }
        check(stage.playback != null) { "media_session has no PlaybackState for the target" }
        check(stage.playback.paused) { "session state ${stage.playback.state} is not paused" }
    }

    /** Post-resume: real playback — foreground service, 4000 up, 4001 gone, audio started. */
    private fun requireResumed(stage: BgProbe.Stage) {
        check(stage.isForeground == true) { "service not foreground after Resume" }
        check(stage.notification4000) { "playback notification 4000 absent after Resume" }
        check(!stage.notification4001) { "recovery notification 4001 not cancelled" }
        check(stage.apcStarted) { "no active playback configuration for the target uid" }
        check(stage.playback?.playing == true) {
            "session state ${stage.playback?.state} is not playing"
        }
    }

    private fun notificationDumpHas(
        probe: BgProbe,
        id: Int,
        channel: String,
    ): Boolean =
        BgStartSurface.hasNotification(
            probe.shell("dumpsys -t 15 notification | grep 'pkg=${BmgrArming.TARGET_PACKAGE}'"),
            BmgrArming.TARGET_PACKAGE,
            id,
            channel,
        )

    /** An ephemeral port released for the bgstart leg's HTTP server to rebind. */
    private fun reservePort(): Int = ServerSocket(0).use { it.localPort }
}

/** Serves the fixture WAV with byte ranges so the resume leg's seek to ~750 s works. */
internal class RangeAudio(
    private val body: ByteArray,
) : Dispatcher() {
    override fun dispatch(request: RecordedRequest): MockResponse {
        val range =
            request.headers["Range"]
                ?: return MockResponse
                    .Builder()
                    .code(200)
                    .addHeader("Content-Type", "audio/wav")
                    .addHeader("Accept-Ranges", "bytes")
                    .body(Buffer().write(body))
                    .build()
        val match = RANGE.find(range) ?: return MockResponse.Builder().code(400).build()
        val start = match.groupValues[1].toInt()
        if (start >= body.size) return MockResponse.Builder().code(416).build()
        val end = match.groupValues[2].toIntOrNull()?.coerceAtMost(body.size - 1) ?: (body.size - 1)
        return MockResponse
            .Builder()
            .code(206)
            .addHeader("Content-Type", "audio/wav")
            .addHeader("Content-Range", "bytes $start-$end/${body.size}")
            .body(Buffer().write(body.copyOfRange(start, end + 1)))
            .build()
    }

    private companion object {
        val RANGE = Regex("""bytes=(\d+)-(\d*)""")
    }
}
