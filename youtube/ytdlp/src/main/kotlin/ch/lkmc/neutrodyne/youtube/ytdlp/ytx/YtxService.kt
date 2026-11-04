// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlp.ytx

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import ch.lkmc.neutrodyne.core.common.YtxGraphHolder
import ch.lkmc.neutrodyne.core.common.YtxScope
import ch.lkmc.neutrodyne.youtube.engine.CompileMarker
import ch.lkmc.neutrodyne.youtube.engine.PreparedEngine
import ch.lkmc.neutrodyne.youtube.engine.YtxCallException
import ch.lkmc.neutrodyne.youtube.engine.YtxLimits
import ch.lkmc.neutrodyne.youtube.ytdlp.YtxTestHooks
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** No interpreter work on Binder or main threads. The main process owns the prepared directory. */
class YtxService : Service() {
    @Inject lateinit var http: PyHttp
    private val executor = Executors.newFixedThreadPool(WORKERS)
    private val canceller = Executors.newSingleThreadExecutor()
    private val running = AtomicInteger()
    private lateinit var python: YtxPython
    private val nextCallId = AtomicLong()

    @Volatile private var ready = false

    private fun binder(
        configuration: PreparedEngine?,
        replay: Pair<String, YtxTestHooks.Fault>?,
    ): IBinder =
        object : IYtxEngine.Stub() {
            private val calls = ConcurrentHashMap<Long, Long>()

            override fun call(
                id: Long,
                method: String?,
                payload: String?,
                deadlineAtMs: Long,
                callback: IYtxCallback?,
            ) {
                if (callback == null) return
                if (method == null || payload == null ||
                    !BinderPayloads.fits(payload, YtxLimits.PAYLOAD_BYTES)
                ) {
                    runCatching { callback.onError(id, "EXTRACTION", null) }
                    return
                }
                val engineId = nextCallId.incrementAndGet()
                if (calls.putIfAbsent(id, engineId) != null) {
                    runCatching { callback.onError(id, "EXTRACTION", null) }
                    return
                }
                if (::http.isInitialized) http.begin(engineId)
                executor.execute {
                    running.incrementAndGet()
                    try {
                        if (deadlineAtMs > 0 && SystemClock.elapsedRealtime() >= deadlineAtMs) {
                            callback.onError(id, "TIMEOUT", null)
                            return@execute
                        }
                        val result =
                            if (configuration == null) {
                                when (method) {
                                    "ping" -> {
                                        python.ensureStarted()
                                        """{"ready":true}"""
                                    }

                                    "selftest" -> {
                                        python.selftest()
                                    }

                                    else -> {
                                        throw IllegalStateException("Engine not prepared")
                                    }
                                }
                            } else {
                                python.configure(configuration, http, replay?.first)
                                ready = true
                                if (method != "ping") {
                                    when (replay?.second) {
                                        YtxTestHooks.Fault.HANG -> Thread.sleep(TEST_HANG_MS)
                                        YtxTestHooks.Fault.PYTHON_CRASH -> python.crash()
                                        else -> Unit
                                    }
                                }
                                python.call(
                                    method,
                                    payload,
                                    (deadlineAtMs - SystemClock.elapsedRealtime()).coerceAtLeast(0),
                                    engineId,
                                )
                            }
                        if (!BinderPayloads.fits(result, YtxLimits.RESULT_BYTES)) {
                            callback.onError(id, "EXTRACTION", null)
                        } else {
                            callback.onResult(id, result)
                        }
                    } catch (failure: Exception) {
                        val code =
                            (failure as? YtxCallException)?.code
                                ?: if (ready) "EXTRACTION" else "ENGINE_UNAVAILABLE"
                        runCatching { callback.onError(id, code, null) }
                    } finally {
                        calls.remove(id, engineId)
                        if (::http.isInitialized) http.end(engineId)
                        running.decrementAndGet()
                    }
                }
            }

            override fun cancel(id: Long) {
                val engineId = calls[id] ?: return
                if (::http.isInitialized) http.cancel(engineId)
                // Cancellation must not wait on a worker that might be hung in Python.
                canceller.execute { runCatching { python.cancel(engineId) } }
            }

            override fun status(): String =
                buildJsonObject {
                    put("pid", Process.myPid())
                    put("running", running.get())
                    put("ready", ready)
                }.toString()
        }

    override fun onCreate() {
        super.onCreate()
        val holder = applicationContext as? YtxGraphHolder
        if (holder != null) (holder.engineGraph as YtxServiceInjector).inject(this)
        python = YtxPython(this)
    }

    override fun onBind(intent: Intent?): IBinder {
        val replay = intent?.let { YtxTestHooks.read(this, it) }
        val lib = intent?.getStringExtra(LIB_DIR)
        val prepared =
            if (lib != null) {
                val directory = java.io.File(lib).canonicalFile
                val root = noBackupFilesDir.canonicalFile.resolve("ytdlp/versions")
                require(directory.path.startsWith(root.path + "/") && directory.name == "lib")
                PreparedEngine(
                    directory.path.toPath(),
                    CompileMarker(
                        intent.getIntExtra(
                            APP_VERSION,
                            0,
                        ),
                        requireNotNull(intent.getStringExtra(ENGINE_VERSION)),
                        "3.14",
                    ),
                )
            } else {
                null
            }
        return binder(prepared, replay)
    }

    override fun onDestroy() {
        executor.shutdownNow()
        canceller.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val ENGINE_BIND = "ch.lkmc.neutrodyne.youtube.ENGINE"
        const val LIB_DIR = "ytx.lib"
        const val APP_VERSION = "ytx.appVersion"
        const val ENGINE_VERSION = "ytx.engineVersion"
        private const val WORKERS = 2
        private const val TEST_HANG_MS = 10 * 60_000L
    }
}

@ContributesTo(YtxScope::class)
interface YtxServiceInjector {
    fun inject(service: YtxService)
}
