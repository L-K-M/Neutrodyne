// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlp.ytx

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import com.chaquo.python.Python
import java.util.concurrent.Executors

/**
 * Bound service of the `:ytx` engine host (`android:process=":ytx"`, not exported; 04 Process and
 * lifecycle). Its only client is `BinderYtxTransport` in the main process (M9a) and, while S7 is
 * being proven, the instrumented `selftest` smoke.
 *
 * M0a stub: `ping` and `selftest` are answered; every other method fails `EXTRACTION`.
 * `call` returns once the work is queued; the Python call itself runs on one worker thread so
 * `onCreate`/`onBind` never touch the interpreter (no service-start ANR; Chaquopy's first
 * `Python.start` extracts assets).
 */
class YtxService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var ytxPython: YtxPython

    private val binder =
        object : IYtxEngine.Stub() {
            override fun call(
                callId: Long,
                method: String?,
                payloadJson: String?,
                deadlineAtMs: Long,
                cb: IYtxCallback?,
            ) {
                // deadlineAtMs is honoured cooperatively by the shim from M9a; the stub's calls are
                // fast enough to ignore it.
                executor.execute { dispatch(callId, method, cb) }
            }

            override fun cancel(callId: Long) {
                // No call registry in M0a (the stub's calls are not cancellable); M9a wires
                // YtxCallRegistry so cancel stops the OkHttp calls and sets the shim's flag.
            }

            override fun status(): String {
                // Full HostStatus JSON is M9a's; the stub reports the process and interpreter.
                val python =
                    if (Python.isStarted()) {
                        "\"" + Python.getInstance().getModule("platform").callAttr("python_version") + "\""
                    } else {
                        "null"
                    }
                return "{\"pid\":${Process.myPid()},\"python\":$python}"
            }
        }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        ytxPython = YtxPython(this)
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    private fun dispatch(
        callId: Long,
        method: String?,
        cb: IYtxCallback?,
    ) {
        if (cb == null) return
        try {
            when (method) {
                "ping" -> {
                    ytxPython.ensureStarted()
                    cb.onResult(callId, """{"ready":true}""")
                }

                "selftest" -> {
                    cb.onResult(callId, ytxPython.selftest())
                }

                else -> {
                    cb.onError(callId, "EXTRACTION", "unknown method: $method")
                }
            }
        } catch (e: Exception) {
            // A failed Python.start means the host cannot run: ENGINE_UNAVAILABLE (04's transport
            // code); anything raised by the call itself is EXTRACTION.
            val code = if (Python.isStarted()) "EXTRACTION" else "ENGINE_UNAVAILABLE"
            cb.onError(callId, code, e.message)
        }
    }
}
