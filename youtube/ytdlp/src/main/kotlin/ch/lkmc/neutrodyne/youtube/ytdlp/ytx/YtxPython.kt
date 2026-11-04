// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlp.ytx

import android.content.Context
import ch.lkmc.neutrodyne.youtube.engine.EngineCompileMarkers
import ch.lkmc.neutrodyne.youtube.engine.PreparedEngine
import ch.lkmc.neutrodyne.youtube.engine.YtxCallException
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/** One interpreter per engine process, started and compiled only by the host workers. */
internal class YtxPython(
    context: Context,
) {
    private val appContext = context.applicationContext

    @Volatile private var python: Python? = null

    @Volatile private var host: PyObject? = null

    @Synchronized
    fun ensureStarted(): Python {
        python?.let { return it }
        if (!Python.isStarted()) Python.start(AndroidPlatform(appContext))
        return Python.getInstance().also {
            it.getModule(SHIM_PACKAGE)
            python = it
        }
    }

    @Synchronized
    fun configure(
        prepared: PreparedEngine,
        http: PyHttp,
        replay: String?,
    ) {
        if (host != null) return
        val runtime = ensureStarted()
        val preparation = runtime.getModule("$SHIM_PACKAGE.prepare")
        // A compiled marker is written only after compilation and an actual import both succeed.
        val marker = prepared.libDir.toFile().resolve(".compiled-${prepared.marker.pythonMinor}")
        if (!marker.isFile) preparation.callAttr("compile_bundle", prepared.libDir.toString())
        preparation.callAttr("import_path", prepared.libDir.toString())
        val adapter = runtime.getModule("$SHIM_PACKAGE.host_chaquopy")
        adapter.callAttr("configure", appContext.cacheDir.resolve("yt-dlp").path, http, replay)
        if (!marker.isFile) EngineCompileMarkers.write(prepared)
        host = adapter
    }

    fun call(
        method: String,
        payload: String,
        remainingMs: Long,
        id: Long,
    ): String {
        val reply = checkNotNull(host).callAttr("wire_call", method, payload, remainingMs, id).asList()
        reply[1]?.let { throw YtxCallException(it.toJava(String::class.java)) }
        return checkNotNull(reply[0]).toJava(String::class.java)
    }

    fun crash() {
        ensureStarted().getModule("os").callAttr("_exit", 70)
    }

    fun cancel(id: Long) {
        host?.callAttr("cancel", id)
    }

    fun selftest(): String =
        ensureStarted().getModule("$SHIM_PACKAGE.selftest").callAttr("selftest").toJava(String::class.java)

    private companion object {
        const val SHIM_PACKAGE = "neutrodyne_ytx"
    }
}
