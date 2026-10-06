// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlp.ytx

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/**
 * The one CPython interpreter of the `:ytx` process (04 Process and lifecycle).
 *
 * M0a stub while S7 is go: starts Chaquopy, imports the shared `neutrodyne_ytx` shim and calls its
 * `selftest`. M9a turns this into `YtxPython` proper (sys.path over `EngineStore.hostLibDir()`,
 * `compileall` on first import, `NeutrodyneOkHttpRH`, one `YoutubeDL` per worker and `hl`).
 */
internal class YtxPython(
    context: Context,
) {
    private val appContext = context.applicationContext

    @Volatile
    private var python: Python? = null

    /**
     * Starts CPython once per process and verifies the shim package imports. Off the main thread
     * (the caller runs on a worker) because Chaquopy's first start extracts assets.
     */
    @Synchronized
    fun ensureStarted(): Python {
        python?.let { return it }
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(appContext))
        }
        val started = Python.getInstance()
        started.getModule(SHIM_PACKAGE) // throws PyException if the packaged shim is absent

        // Cache only after the shim imported, so a failed import is retried, never reported ready
        python = started
        return started
    }

    /** `selftest` as the shim returns it: a JSON string (04 methods). */
    fun selftest(): String =
        ensureStarted().getModule("$SHIM_PACKAGE.selftest").callAttr("selftest").toJava(String::class.java)

    private companion object {
        const val SHIM_PACKAGE = "neutrodyne_ytx"
    }
}
