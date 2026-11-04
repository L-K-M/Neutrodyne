// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlp

import android.content.Context
import android.content.Intent
import java.io.File
import java.util.UUID

/** Instrumentation-only replay. Neither the app graph nor ordinary service binds arm this seam. */
object YtxTestHooks {
    enum class Fault { NONE, HANG, PYTHON_CRASH }

    private data class Armed(
        val directory: String,
        val token: String,
        val fault: Fault,
    )

    @Volatile private var armed: Armed? = null

    fun arm(
        context: Context,
        directory: File,
        fault: Fault = Fault.NONE,
    ) {
        check(System.getProperty("neutrodyne.instrumentedTest") == "true") { "Replay requires instrumentation" }
        val root = context.noBackupFilesDir.canonicalFile.resolve("ytx-test")
        require(directory.canonicalPath.startsWith(root.path + "/"))
        val token = UUID.randomUUID().toString()
        directory.resolve(".armed").writeText(token)
        armed = Armed(directory.canonicalPath, token, fault)
    }

    fun clear() {
        armed = null
    }

    internal fun attach(intent: Intent) {
        armed?.let {
            intent.putExtra(DIRECTORY, it.directory).putExtra(TOKEN, it.token).putExtra(FAULT, it.fault.name)
        }
    }

    internal fun read(
        context: Context,
        intent: Intent,
    ): Pair<String, Fault>? {
        val path = intent.getStringExtra(DIRECTORY) ?: return null
        val root = context.noBackupFilesDir.canonicalFile.resolve("ytx-test")
        val directory = File(path).canonicalFile
        require(directory.path.startsWith(root.path + "/"))
        require(directory.resolve(".armed").readText() == intent.getStringExtra(TOKEN))
        return directory.path to Fault.valueOf(intent.getStringExtra(FAULT) ?: Fault.NONE.name)
    }

    private const val DIRECTORY = "ytx.replay"
    private const val TOKEN = "ytx.replayToken"
    private const val FAULT = "ytx.fault"
}
