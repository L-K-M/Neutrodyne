// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlp.ytx

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The library selftest APK's fixture on the host: a bare bind against [YtxService] under the
 * default [android.app.Application] — no `YtxGraphHolder`, so `http` is never injected. The
 * pure control path (no `ytx.lib` extra) must still answer without touching HTTP or Python:
 * a call whose deadline already passed gets TIMEOUT, and an engine method without a prepared
 * engine fails ENGINE_UNAVAILABLE. `YtxSelfTestInstrumentedTest` covers the real ping/selftest
 * on device; these probes never reach the interpreter, so they run CPU-only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class YtxServiceControlPathTest {
    private val context = RuntimeEnvironment.getApplication()
    private var service: ServiceController<YtxService>? = null

    @After
    fun destroyService() {
        service?.destroy()
    }

    @Test
    fun expiredControlCallAnswersTimeoutWithoutAGraph() {
        val engine = bind()
        // The paused shadow clock may still sit at 0; a deadline must be > 0 and already past.
        ShadowSystemClock.advanceBy(Duration.ofMillis(2))
        assertEquals("TIMEOUT", call(engine, "ping", SystemClock.elapsedRealtime() - 1))
    }

    @Test
    fun unconfiguredMethodFailsClearlyWithoutAGraph() {
        val engine = bind()
        assertEquals("ENGINE_UNAVAILABLE", call(engine, "resolve", SystemClock.elapsedRealtime() + 60_000))
    }

    private fun bind(): IYtxEngine {
        val controller = Robolectric.buildService(YtxService::class.java).create()
        service = controller
        return IYtxEngine.Stub.asInterface(
            controller.get().onBind(Intent(context, YtxService::class.java)),
        )
    }

    private fun call(
        engine: IYtxEngine,
        method: String,
        deadlineAtMs: Long,
    ): String {
        val latch = CountDownLatch(1)
        val result = AtomicReference<String>()
        val error = AtomicReference<String>()
        engine.call(
            CALL_ID,
            method,
            "{}",
            deadlineAtMs,
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
                    error.set(code)
                    latch.countDown()
                }
            },
        )
        assertTrue(latch.await(10, TimeUnit.SECONDS), "$method timed out")
        return error.get() ?: "RESULT: ${result.get()}"
    }

    private companion object {
        const val CALL_ID = 1L
    }
}
