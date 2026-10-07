// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.neutrodyne.core.testing.ReleaseSmoke
import ch.lkmc.neutrodyne.youtube.ytdlp.ytx.IYtxCallback
import ch.lkmc.neutrodyne.youtube.ytdlp.ytx.IYtxEngine
import ch.lkmc.neutrodyne.youtube.ytdlp.ytx.YtxService
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * S7's device proof at the app level (01 Spikes): `Python.start` plus `selftest` in the `:ytx`
 * process of the real app — `NeutrodyneApplication`, the merged manifest's
 * `android:process=":ytx"` component and, in the release-type runs, the R8-minified APK — not
 * the library's standalone test APK (`YtxSelfTestInstrumentedTest`). Selected by the
 * [ReleaseSmoke] filter on the release legs of `release-build-smoke` (api36) and `api37-16k`;
 * the debug legs and the `ci` group run it alongside the rest of the suite.
 *
 * `ping` answering already proves `NeutrodyneApplication`'s `ProcessRole.YTX` branch survived
 * (it builds `YtxGraph`; a missing or R8-stripped Metro graph class would crash `onCreate`
 * before the binder ever answers). The process name asserted below is the same name
 * `ProcessRole.current()` reads in that process, so `YTX` was classified there and no
 * `AndroidAppGraph` was built (the no-initializer/no-database probes are `YtxProcessStartTest`'s,
 * pending in 01's verification log).
 */
@RunWith(AndroidJUnit4::class)
@ReleaseSmoke
class YtxReleaseSmokeTest {
    private lateinit var context: Context
    private var connection: ServiceConnection? = null

    @Before
    fun setUp() {
        // S7 finding (2026-10-06): libpython 3.14's load-time constructor uses the legacy `open`
        // syscall, which Android 8.0/8.1's seccomp filter forbids for x86_64 apps (SIGSYS before
        // `ping`). AArch64 has no `open` syscall and x86_64 allows it from API 28, so only x86_64
        // on API 26–27 is affected (01 S7) — same skip as the library-level test.
        val x86OnOreo = Build.SUPPORTED_ABIS.first() == X86_64 && Build.VERSION.SDK_INT < Build.VERSION_CODES.P
        assumeFalse("CPython 3.14 cannot load on x86_64 Android 8.x (seccomp)", x86OnOreo)

        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @After
    fun tearDown() {
        connection?.let(context::unbindService)
    }

    @Test
    fun selftestAnswersThroughYtxServiceInTheApp() {
        // Instrumentation runs in the app's process, which classifies itself as MAIN.
        assertThat(ProcessRole.current()).isEqualTo(ProcessRole.MAIN)

        val engine = bind()

        // ping: READY only once CPython has started and the shim package imports (04 host contract).
        val ping = JSONObject(call(engine, "ping"))
        assertThat(ping.getBoolean("ready")).isTrue()

        val selftest = JSONObject(call(engine, "selftest"))
        assertThat(selftest.getBoolean("ok")).isTrue()
        assertThat(selftest.getString("python")).startsWith("3.14.")
        assertThat(selftest.getString("openssl")).startsWith("OpenSSL ")

        // The answer came from a different process than this test (the app's main process)…
        val ytxPid = JSONObject(engine.status()).getInt("pid")
        assertThat(ytxPid).isNotEqualTo(Process.myPid())

        // …specifically the dedicated ":ytx" process. The kernel's cmdline record is exactly what
        // ProcessRole.current() classified there, so the YTX branch ran (YtxGraph, no
        // AndroidAppGraph, no initializers). Same-UID sibling processes are readable under
        // Android's hidepid mount; this is the observable for the app's process wiring.
        val ytxName = File("/proc/$ytxPid/cmdline").readText().substringBefore('\u0000')
        assertThat(ytxName).isEqualTo("${context.packageName}:ytx")
    }

    private fun bind(): IYtxEngine {
        val latch = CountDownLatch(1)
        val binder = AtomicReference<IBinder>()
        val conn =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName,
                    service: IBinder,
                ) {
                    binder.set(service)
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName) = Unit
            }
        val bound = context.bindService(Intent(context, YtxService::class.java), conn, Context.BIND_AUTO_CREATE)
        assertTrue("YtxService did not bind", bound)
        connection = conn
        assertTrue("bind timed out", latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        return IYtxEngine.Stub.asInterface(binder.get())
    }

    private fun call(
        engine: IYtxEngine,
        method: String,
    ): String {
        val latch = CountDownLatch(1)
        val result = AtomicReference<String>()
        val error = AtomicReference<String>()
        engine.call(
            CALL_ID,
            method,
            "{}",
            System.currentTimeMillis() + CALL_TIMEOUT_MS,
            object : IYtxCallback.Stub() {
                override fun onResult(
                    callId: Long,
                    resultJson: String,
                ) {
                    result.set(resultJson)
                    latch.countDown()
                }

                override fun onError(
                    callId: Long,
                    code: String,
                    message: String?,
                ) {
                    error.set("$code: $message")
                    latch.countDown()
                }
            },
        )
        // The first Python.start extracts the runtime's assets; allow the doc's first-compile cap.
        assertTrue("$method timed out", latch.await(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        assertThat(error.get()).isNull()
        return result.get()
    }

    private companion object {
        const val X86_64 = "x86_64"
        const val CALL_ID = 1L
        const val BIND_TIMEOUT_MS = 30_000L
        const val CALL_TIMEOUT_MS = 120_000L // 04's first-compile cap; covers Chaquopy extraction
    }
}
