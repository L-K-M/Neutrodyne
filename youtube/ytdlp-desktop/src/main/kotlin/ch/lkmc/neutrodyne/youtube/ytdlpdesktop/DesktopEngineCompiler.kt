// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlpdesktop

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.youtube.engine.CompileMarker
import ch.lkmc.neutrodyne.youtube.engine.EngineCompileMarkers
import ch.lkmc.neutrodyne.youtube.engine.EngineCompiler
import ch.lkmc.neutrodyne.youtube.engine.PreparedEngine
import ch.lkmc.neutrodyne.youtube.engine.YtxLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.Path
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A failed update compile is a failed self-test (04's stage→compile→selftest chain), never a cancel. */
class EngineCompileException(
    message: String,
) : Exception(message)

/**
 * The desktop `EngineCompiler` (11 "Engine updates on the desktop"): compiles a staged
 * `versions/<version>/lib` with the exact packaged interpreter — never a PATH search and
 * never a downloaded native binary — in isolated mode (`-I`), running the same
 * `neutrodyne_ytx.prepare.compile_bundle` the serving child's bootstrap runs (legacy `.pyc`
 * layout, then `.py` removal). [EngineCompileMarkers.write] then pins the read-only tree
 * before `beginTrial`, so the trial bind reports COMPILED instead of compiling inline.
 *
 * The compile child is spawned like [YtxProcess]'s: argv list only (no shell), the cleared
 * [childProcessEnvironment] (no SecretStore, credentials or ambient variables), and owned
 * lifetime — coroutine cancellation or the deadline kills it. A timeout is a compile
 * failure, not a cancellation, so a slow run rejects the candidate as SELFTEST_FAILED.
 */
class DesktopEngineCompiler(
    private val runtime: PythonRuntimeLocator,
    private val paths: DesktopEngineStorePaths,
    private val appVersionCode: Int,
    private val clock: Clock,
    private val compileTimeoutMs: Long = YtxLimits.COMPILE_MS,
    private val spawn: (command: List<String>, environment: Map<String, String>, workDir: File) -> Process =
        ::defaultSpawn,
) : EngineCompiler {
    override suspend fun compile(lib: Path): Unit =
        withContext(Dispatchers.IO) {
            val libDir = lib.toFile()
            val version =
                libDir.parentFile?.name?.takeIf { VERSION_PATTERN.matches(it) }
                    ?: throw EngineCompileException("Not a versions/<version>/lib tree")
            require(libDir.isDirectory) { "Engine lib dir missing" }
            val interpreter = runtime.locate() ?: throw PythonUnavailableException()
            // The shim package's parent holds `neutrodyne_ytx`; argv carries it, never a shell.
            val shimParent = paths.shimPackageDir()?.parentFile ?: throw YtxSpawnException(null)
            val home = paths.childHome.apply { mkdirs() }
            val tmp = paths.childTmp.apply { mkdirs() }
            val command =
                listOf(
                    interpreter.executable.absolutePath,
                    "-I",
                    "-X",
                    "utf8",
                    "-B",
                    "-c",
                    COMPILE_PROGRAM,
                    shimParent.absolutePath,
                    libDir.absolutePath,
                )
            val process =
                try {
                    spawn(command, childProcessEnvironment(interpreter, paths, home, tmp), home)
                } catch (failure: IOException) {
                    throw YtxSpawnException(failure)
                }
            val output = Drain(process)
            try {
                val exit = awaitExit(process)
                if (exit != 0) {
                    throw EngineCompileException("compile_bundle exited $exit: ${output.tail()}")
                }
            } finally {
                if (process.isAlive) process.destroyForcibly()
                output.close()
            }
            EngineCompileMarkers.write(
                PreparedEngine(lib, CompileMarker(appVersionCode, version, interpreter.pythonMinor)),
            )
        }

    /**
     * Canceller-friendly exit wait: a poll loop is the only wait that answers coroutine
     * cancellation promptly, and the deadline check turns a wedged interpreter into a
     * compile failure the finally block kills.
     */
    private suspend fun awaitExit(process: Process): Int {
        val deadline = clock.elapsedRealtime() + compileTimeoutMs
        while (true) {
            if (process.waitFor(POLL_MS, TimeUnit.MILLISECONDS)) return process.exitValue()
            currentCoroutineContext().ensureActive()
            if (clock.elapsedRealtime() >= deadline) {
                throw EngineCompileException("compile_bundle exceeded ${compileTimeoutMs}ms")
            }
        }
    }

    /** Drains both pipes to a bounded tail; a blocked pipe would otherwise stall the compile. */
    private class Drain(
        process: Process,
    ) {
        private val tail = ByteArrayOutputStream(TAIL_BYTES)
        private val full =
            java.util.concurrent.atomic
                .AtomicBoolean()
        private val threads =
            listOf(process.inputStream, process.errorStream).map { stream ->
                Thread(
                    {
                        val buffer = ByteArray(8192)
                        try {
                            while (true) {
                                val count = stream.read(buffer)
                                if (count < 0) break
                                record(buffer, count)
                            }
                        } catch (_: IOException) {
                            // The pipe dies with the process; nothing further arrives.
                        }
                    },
                    "nd-ytx-compile",
                ).apply {
                    isDaemon = true
                    start()
                }
            }

        private fun record(
            buffer: ByteArray,
            count: Int,
        ) {
            synchronized(tail) {
                if (full.get()) return
                if (tail.size() + count <= TAIL_BYTES) {
                    tail.write(buffer, 0, count)
                } else {
                    full.set(true)
                }
            }
        }

        fun tail(): String =
            synchronized(tail) {
                tail
                    .toString(Charsets.UTF_8)
                    .takeLast(TAIL_CHARS)
                    .replace(Regex("\\s+"), " ")
                    .trim()
            }

        fun close() = threads.forEach { it.join(JOIN_MS) }

        private companion object {
            const val TAIL_BYTES = 16 * 1024
            const val TAIL_CHARS = 2000
            const val JOIN_MS = 2_000L
        }
    }

    private companion object {
        const val POLL_MS = 100L
        val VERSION_PATTERN = Regex("[0-9]{4}\\.[0-9]{2}\\.[0-9]{2}")

        // Fixed literal: `sys.path[0]` is the shim parent, `sys.argv[1]` the lib. compile_bundle
        // is prepare.py's shared semantics — legacy .pyc next to each .py, verify, then delete .py.
        const val COMPILE_PROGRAM =
            "import sys;" +
                "sys.path.insert(0,sys.argv[1]);" +
                "from neutrodyne_ytx.prepare import compile_bundle;" +
                "compile_bundle(sys.argv[2])"

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
