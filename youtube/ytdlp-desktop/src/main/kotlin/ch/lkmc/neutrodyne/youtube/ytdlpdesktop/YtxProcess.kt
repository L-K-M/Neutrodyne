// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlpdesktop

import ch.lkmc.neutrodyne.core.common.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The interpreter could not be located or probed; the engine reports HOST_UNUSABLE, not a crash. */
class PythonUnavailableException : Exception()

/** Spawning failed (resources missing, exec refused); one START_FAILED strike, the host may retry. */
class YtxSpawnException(
    cause: Throwable?,
) : Exception(cause)

/** The transport's spawn seam: [YtxProcess] in production, a scripted child in tests. */
interface YtxSpawner {
    fun start(
        libDir: File,
        engineVersion: String,
        replayDir: File?,
        debugHooks: Boolean,
        listener: YtxChild.Listener,
    ): YtxChild
}

/**
 * Spawns and owns one CPython child per generation (11 "Process model").
 *
 * The child runs `python -I -X utf8 -B <shim>/bootstrap.py` against the active prepared lib,
 * on a cleared environment (PATH to the interpreter's directory only, HOME/TMP into the app
 * cache, SYSTEMROOT on Windows, SSL_CERT_FILE once the packaged cacert ships). stdio carries
 * the documented JSON-lines wire; stderr is drained to the redacted log so a chatty library
 * can never stall the child.
 */
class YtxProcess(
    private val runtime: PythonRuntimeLocator,
    private val paths: DesktopEngineStorePaths,
    private val jvmPid: Long = ProcessHandle.current().pid(),
    private val stderrSink: (String) -> Unit = { Log.i(TAG) { it } },
    private val spawn: (command: List<String>, environment: Map<String, String>, workDir: File) -> Process =
        ::defaultSpawn,
) : YtxSpawner {
    override fun start(
        libDir: File,
        engineVersion: String,
        replayDir: File?,
        debugHooks: Boolean,
        listener: YtxChild.Listener,
    ): YtxChild {
        val interpreter = runtime.locate() ?: throw PythonUnavailableException()
        val shim = paths.shimPackageDir() ?: throw YtxSpawnException(null)
        val home = paths.childHome.apply { mkdirs() }
        val tmp = paths.childTmp.apply { mkdirs() }
        val environment = childEnvironment(interpreter, home, tmp)
        val command =
            buildList {
                add(interpreter.executable.absolutePath)
                add("-I")
                add("-X")
                add("utf8")
                add("-B")
                add(File(shim, "bootstrap.py").absolutePath)
                add("--lib")
                add(libDir.absolutePath)
                add("--shim")
                add(shim.absolutePath)
                add("--parent-pid")
                add(jvmPid.toString())
                add("--engine-version")
                add(engineVersion)
                add("--cache")
                add(paths.ytDlpCacheDir.toString())
                replayDir?.let {
                    add("--replay")
                    add(it.absolutePath)
                }
                if (debugHooks) add("--debug-hooks")
            }
        val process =
            try {
                spawn(command, environment, home)
            } catch (failure: IOException) {
                throw YtxSpawnException(failure)
            }
        return YtxChild(process, stderrSink, listener)
    }

    private fun childEnvironment(
        interpreter: PythonRuntime,
        home: File,
        tmp: File,
    ): Map<String, String> =
        buildMap {
            // A cleared environment: nothing user-set (proxies, SSL overrides, PYTHON*) leaks in.
            put("PATH", interpreter.executable.parentFile?.absolutePath ?: "")
            put("TMP", tmp.absolutePath)
            put("TEMP", tmp.absolutePath)
            put("TMPDIR", tmp.absolutePath)
            put("HOME", home.absolutePath)
            if (isWindows()) {
                put("USERPROFILE", home.absolutePath)
                System.getenv("SYSTEMROOT")?.let { put("SYSTEMROOT", it) }
            }
            paths.cacertFile()?.let { put("SSL_CERT_FILE", it.absolutePath) }
        }

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows")

    private companion object {
        const val TAG = "YtxProcess"

        fun defaultSpawn(
            command: List<String>,
            environment: Map<String, String>,
            workDir: File,
        ): Process {
            val builder = ProcessBuilder(command).directory(workDir)
            val env = builder.environment()
            env.clear()
            env.putAll(environment)
            return builder.start()
        }
    }
}

/**
 * One live child generation: framed writes under a lock, bounded line reads on a daemon
 * thread, exit observation for the transport's crash mapping. All listener calls arrive on
 * the child's reader thread; the transport owns the lock ordering.
 */
class YtxChild internal constructor(
    private val process: Process,
    stderrSink: (String) -> Unit,
    private val listener: Listener,
) {
    interface Listener {
        /** A complete, parsed protocol line (hello/ok/err/status/…). */
        fun onMessage(message: JsonObject)

        /** A line that was not a bounded JSON object; the transport must kill this child. */
        fun onProtocolViolation(reason: String)

        /** The process died (any cause), after stdout reached EOF. */
        fun onExit(code: Int)
    }

    val pid: Long get() = process.pid()
    val isAlive: Boolean get() = process.isAlive

    private val writeLock = Any()

    /** Writes one framed JSON object; false when the child's stdin is already gone. */
    fun send(message: JsonObject): Boolean = send(message.toString())

    fun send(text: String): Boolean =
        try {
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (bytes.size + 1 > LINE_BYTES) {
                false
            } else {
                synchronized(writeLock) {
                    process.outputStream.write(bytes)
                    process.outputStream.write('\n'.code)
                    process.outputStream.flush()
                }
                true
            }
        } catch (_: IOException) {
            false
        }

    fun closeStdin() {
        try {
            process.outputStream.close()
        } catch (_: IOException) {
            // Already closed by a dead child.
        }
    }

    fun destroyForcibly() {
        val exited =
            try {
                process.destroyForcibly()
                process.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        if (!exited) {
            // Shutdown callers must still complete slots and keep status collectors alive.
            // Callers requiring proof of exit also inspect isAlive/awaitExit themselves.
            Log.w(TAG) { "the engine child did not exit within ${EXIT_WAIT_MS}ms after forced shutdown" }
        }
    }

    fun awaitExit(timeoutMs: Long): Int? =
        if (process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) process.exitValue() else null

    private val reader =
        Thread(
            {
                readLoop()
            },
            "nd-ytx-out",
        ).apply {
            isDaemon = true
            start()
        }

    private val stderr =
        Thread(
            {
                drainStderr(stderrSink)
            },
            "nd-ytx-err",
        ).apply {
            isDaemon = true
            start()
        }

    /** A malformed frame or oversized line is a protocol violation, never a dispatch (11).
     * A violation reports only [Listener.onProtocolViolation]; the transport owns the kill. */
    private fun readLoop() {
        val code =
            try {
                while (true) {
                    val line = readLineBounded(process.inputStream) ?: break
                    val message =
                        try {
                            Json.parseToJsonElement(line) as? JsonObject
                        } catch (_: Exception) {
                            null
                        }
                    if (message == null) {
                        listener.onProtocolViolation("unparseable protocol line")
                        break
                    }
                    listener.onMessage(message)
                }
                process.waitFor()
            } catch (_: IOException) {
                runCatching { process.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS) }
                    .getOrNull()
                    ?.let { process.exitValue() }
                    ?: -1
            }
        listener.onExit(code)
    }

    private fun readLineBounded(stream: java.io.InputStream): String? {
        val line = ByteArrayOutputStream(READ_CHUNK)
        while (true) {
            val next = stream.read()
            // A partial trailing frame belongs to a dead writer; the exit code reports the crash.
            if (next < 0) return null
            if (next == '\n'.code) return line.toString(Charsets.UTF_8)
            line.write(next)
            if (line.size() >= LINE_BYTES) {
                listener.onProtocolViolation("protocol line beyond $LINE_BYTES bytes")
                return null
            }
        }
    }

    private fun drainStderr(sink: (String) -> Unit) {
        var logged = 0L
        try {
            process.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { text ->
                    if (logged < STDERR_LOG_BYTES) {
                        logged += text.length
                        sink(redact(text))
                    } else if (logged >= 0) {
                        logged = -1
                        sink("stderr beyond ${STDERR_LOG_BYTES / 1024} KiB; further lines suppressed")
                    }
                    // Lines are still drained past the cap: a blocked stderr would wedge the child.
                }
            }
        } catch (_: IOException) {
            // The pipe dies with the process; nothing further to log.
        }
    }

    private companion object {
        const val TAG = "YtxChild"

        /** One framed line inbound or outbound, per 11 "Stdio protocol". */
        const val LINE_BYTES = 256 * 1024
        const val READ_CHUNK = 8 * 1024
        const val STDERR_LOG_BYTES = 1024 * 1024L
        const val EXIT_WAIT_MS = 2_000L
        val URL = Regex("https?://\\S+")

        fun redact(line: String): String = URL.replace(line, "<url>")
    }
}
