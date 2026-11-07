// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlpdesktop

import java.io.File

/**
 * Which CPython runs the engine child (11 "Python runtime").
 *
 * The bundled python-build-standalone runtime arrives with the packaging slice; the locator
 * resolves an explicit development path, then the packaged resources (`<resources>/engine/python/`,
 * the tree `trimPythonStandalone` staged), then the dev build's trimmed tree, and probes the
 * interpreter once for its `<major>.<minor>` marker suffix. A null [PythonRuntimeLocator.locate]
 * maps to `HostState.UNUSABLE`, never a crash: the engine reports ENGINE_FAILED and keeps the
 * external capability path. An arbitrary PATH interpreter is never a production candidate
 * ([PythonRuntimeSearch] is the development/test seam).
 */
data class PythonRuntime(
    val executable: File,
    val pythonMinor: String,
) {
    companion object {
        private val MINOR = Regex("""\d+\.\d+""")

        /** Runs `<executable> -I -c` once; null when it cannot run or answer (11's non-executable row). */
        fun probe(executable: File): PythonRuntime? =
            try {
                val process =
                    ProcessBuilder(
                        executable.absolutePath,
                        "-I",
                        "-c",
                        "import sys;print(f'{sys.version_info[0]}.{sys.version_info[1]}')",
                    ).redirectErrorStream(true)
                        .start()
                val answered =
                    process.inputStream.bufferedReader().use { it.readText().trim() }
                if (!process.waitFor(PROBE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    null
                } else if (process.exitValue() != 0 || !MINOR.matches(answered)) {
                    null
                } else {
                    PythonRuntime(executable, answered)
                }
            } catch (_: Exception) {
                null
            }

        private const val PROBE_TIMEOUT_MS = 10_000L
    }
}

fun interface PythonRuntimeLocator {
    /** The probed interpreter, or null when no usable one exists this launch. */
    fun locate(): PythonRuntime?
}

/** One locator for the app's life; tests substitute a fixed runtime or `PythonRuntimeSearch`. */
class DesktopPythonRuntimeLocator(
    private val explicit: () -> File?,
    private val engineResources: () -> File?,
    private val devStandalone: () -> File?,
    private val probe: (File) -> PythonRuntime? = PythonRuntime.Companion::probe,
) : PythonRuntimeLocator {
    @Volatile private var memoized: PythonRuntime? = null

    @Volatile private var memoizedPath: File? = null

    override fun locate(): PythonRuntime? {
        val candidate =
            explicit()?.takeIf(File::isFile)
                ?: engineResources()?.let(::interpreterIn)?.takeIf(File::isFile)
                ?: devStandalone()?.let(::interpreterIn)?.takeIf(File::isFile)
                ?: return null
        // Only a working interpreter is memoized: a broken one is re-probed so that
        // retryStart picks up a binary the user fixed or replaced in place.
        if (candidate == memoizedPath) return memoized
        return probe(candidate)?.also {
            memoized = it
            memoizedPath = candidate
        }
    }

    /** The interpreter inside a staged runtime tree (11 Resources layout): `python/python.exe` on
     * Windows, `python/bin/python3` elsewhere. */
    private fun interpreterIn(resources: File): File =
        if (isWindows()) resources.resolve("python/python.exe")
        else resources.resolve("python/bin/${exeName()}")

    private fun exeName(): String = if (isWindows()) "python.exe" else "python3"

    companion object {
        private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows")

        /** The production chain: explicit dev override, then packaged or dev-build resources. */
        fun standard(
            engineResources: () -> File?,
            rootDir: () -> File?,
        ): DesktopPythonRuntimeLocator =
            DesktopPythonRuntimeLocator(
                explicit = {
                    System.getProperty("neutrodyne.ytx.python")?.takeUnless { it.isBlank() }?.let(::File)
                },
                engineResources = engineResources,
                devStandalone = {
                    rootDir()
                        ?.resolve("youtube/ytdlp-desktop/build/python-standalone")
                        ?.resolve(currentTarget())
                        ?.resolve("trimmed")
                        ?.resolve("python")
                        ?.takeIf(File::isDirectory)
                },
            )

        /** `<os>-<arch>` PBS target id; linux-aarch64 is not a shipped target (11 Build matrix). */
        private fun currentTarget(): String {
            val os =
                when {
                    isWindows() -> "windows"
                    System.getProperty("os.name").let { it.contains("mac") || it.contains("darwin") } -> "macos"
                    else -> "linux"
                }
            val arch = if (System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }) "aarch64" else "x64"
            return "$os-$arch"
        }
    }
}

/**
 * The build machine's interpreter — test/development only. Production never searches PATH:
 * a random user Python is not a supported engine runtime (11 "Python runtime").
 */
object PythonRuntimeSearch {
    fun onPath(env: Map<String, String> = System.getenv()): PythonRuntime? {
        val path = env["PATH"] ?: return null
        val separator = if (System.getProperty("os.name").startsWith("Windows")) ";" else ":"
        val names =
            if (System.getProperty("os.name").startsWith("Windows")) {
                listOf("python3.14.exe", "python3.exe", "python.exe")
            } else {
                listOf("python3.14", "python3", "python")
            }
        for (dir in path.split(separator)) {
            if (dir.isBlank()) continue
            for (name in names) {
                val candidate = File(dir, name)
                if (candidate.isFile) PythonRuntime.probe(candidate)?.let { return it }
            }
        }
        return null
    }
}
