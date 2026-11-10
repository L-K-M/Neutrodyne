// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlpdesktop

import ch.lkmc.neutrodyne.core.testing.EngineUpdateFixtures
import ch.lkmc.neutrodyne.youtube.engine.BundledEngineStore
import ch.lkmc.neutrodyne.youtube.engine.CompileMarker
import ch.lkmc.neutrodyne.youtube.engine.EngineCompileMarkers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The real compile boundary: the packaged interpreter in isolated mode, `compile_bundle`'s
 * legacy .pyc + strip-.py semantics, the compile marker pinning, and owned child lifetime.
 * Failure, timeout and cancellation are proven against a live subprocess, not a fake.
 */
class DesktopEngineCompilerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val fixtures = EngineUpdateFixtures()
    private val python by lazy { runnerPython() }

    private fun fixture(): Pair<DesktopEngineStorePaths, BundledEngineStore> {
        val fixture = StdioFixture(temporary.newFolder(), python)
        return fixture.paths to BundledEngineStore(fixture.paths, 1, RealClock)
    }

    private fun compiler(
        paths: DesktopEngineStorePaths,
        timeoutMs: Long = 180_000,
        spawn: ((List<String>, Map<String, String>, File) -> Process)? = null,
    ) = DesktopEngineCompiler(
        runtime = { python },
        paths = paths,
        appVersionCode = 1,
        clock = RealClock,
        compileTimeoutMs = timeoutMs,
        spawn = spawn ?: { command, env, dir -> realSpawn(command, env, dir) },
    )

    private fun realSpawn(
        command: List<String>,
        environment: Map<String, String>,
        workDir: File,
    ): Process =
        ProcessBuilder(command)
            .directory(workDir)
            .apply {
                this.environment().clear()
                this.environment().putAll(environment)
            }.start()

    @Test fun stagedTreeCompilesToMarkedReadOnlyPycLayout() =
        runBlocking {
            val (paths, store) = fixture()
            val prepared = store.stageVersion(NEW, fixtures.archive(NEW), "0.8.0", 7)
            val lib = prepared.libDir.toFile()
            compiler(paths).compile(prepared.libDir)
            val expected = CompileMarker(1, NEW, paths.pythonMinor)
            // The same record the store's `downloadedUsable` gate reads before serving.
            assertEquals(expected, EngineCompileMarkers.stored(lib.path.toPath(), paths.pythonMinor))
            for (file in listOf(
                "yt_dlp/__init__.pyc",
                "yt_dlp/version.pyc",
                "yt_dlp_ejs/__init__.pyc",
                "yt_dlp_ejs/_version.pyc",
                "__main__.pyc",
            )) {
                assertTrue(lib.resolve(file).isFile, "missing $file")
            }
            assertTrue(lib.walkTopDown().none { it.extension == "py" })
            assertFalse(lib.resolve("yt_dlp/version.pyc").canWrite())
        }

    @Test fun realBundledTreeCompilesTheSameWay() =
        runBlocking {
            val (paths, store) = fixture()
            val prepared = store.prepareBundled()
            compiler(paths).compile(prepared.libDir)
            val lib = prepared.libDir.toFile()
            val expected = CompileMarker(1, BUNDLED_VERSION, paths.pythonMinor)
            assertEquals(expected, EngineCompileMarkers.stored(lib.path.toPath(), paths.pythonMinor))
            assertTrue(lib.walkTopDown().none { it.extension == "py" })
            store.loadStatus()
            assertEquals(ch.lkmc.neutrodyne.youtube.engine.EnginePreparation.COMPILED, store.preparation.value)
        }

    @Test fun brokenSourceFailsAndLeavesNoMarker() =
        runBlocking {
            val (paths, store) = fixture()
            val prepared = store.stageVersion(NEW, brokenArchive(NEW), "0.8.0", 7)
            assertFailsWith<EngineCompileException> {
                compiler(paths).compile(prepared.libDir)
            }
            assertEquals(null, EngineCompileMarkers.stored(prepared.libDir, paths.pythonMinor))
        }

    @Test fun deadlineKillsTheCompileChild() =
        runBlocking {
            val (paths, store) = fixture()
            val prepared = store.prepareBundled()
            var child: Process? = null
            val error =
                assertFailsWith<EngineCompileException> {
                    compiler(
                        paths,
                        timeoutMs = 1,
                        spawn = { command, env, dir ->
                            realSpawn(command, env, dir).also { child = it }
                        },
                    ).compile(prepared.libDir)
                }
            assertTrue(error.message!!.contains("exceeded"), error.message)
            child!!.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            assertFalse(child!!.isAlive)
        }

    @Test fun cancellationKillsTheCompileChild() =
        runBlocking {
            val (paths, store) = fixture()
            val prepared = store.prepareBundled()
            var child: Process? = null
            val run =
                async {
                    compiler(
                        paths,
                        spawn = { command, env, dir ->
                            realSpawn(command, env, dir).also { child = it }
                        },
                    ).compile(prepared.libDir)
                }
            // The full yt-dlp tree keeps the child alive long enough to cancel inside it.
            while (child == null) delay(20)
            delay(200)
            run.cancel()
            assertFailsWith<CancellationException> { run.await() }
            child!!.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            assertFalse(child!!.isAlive)
        }

    @Test fun missingInterpreterReportsUnavailable() =
        runBlocking {
            val fixture = StdioFixture(temporary.newFolder(), python)
            val paths = DesktopEngineStorePaths(fixture.dirs, { engineResourcesDir() }, { null })
            val store = BundledEngineStore(paths, 1, RealClock)
            val prepared = store.stageVersion(NEW, fixtures.archive(NEW), "0.8.0", 7)
            assertFailsWith<PythonUnavailableException> {
                DesktopEngineCompiler({ null }, paths, 1, RealClock).compile(prepared.libDir)
            }
            Unit
        }

    @Test fun nonVersionLibTreeIsRejected() =
        runBlocking {
            val (paths, _) = fixture()
            val stray = temporary.newFolder("lib").path.toPath()
            assertFailsWith<EngineCompileException> {
                compiler(paths).compile(stray)
            }
            Unit
        }

    @Test fun argvIsShellFreeAndTheEnvironmentCarriesNoAmbientVars() =
        runBlocking {
            val (paths, store) = fixture()
            val prepared = store.stageVersion(NEW, fixtures.archive(NEW), "0.8.0", 7)
            var command: List<String> = emptyList()
            var environment: Map<String, String> = emptyMap()
            compiler(
                paths,
                spawn = { c, e, d ->
                    command = c
                    environment = e
                    realSpawn(c, e, d)
                },
            ).compile(prepared.libDir)
            assertEquals(
                listOf(python.executable.absolutePath, "-I", "-X", "utf8", "-B", "-c"),
                command.take(6),
            )
            assertTrue(command[6].contains("compile_bundle"))
            assertEquals(prepared.libDir.toFile().absolutePath, command.last())
            // Cleared env: PATH is the interpreter dir only, no PYTHON*/credential leakage.
            assertEquals(python.executable.parentFile.absolutePath, environment["PATH"])
            assertTrue(environment.keys.none { it.startsWith("PYTHON") })
            assertTrue(
                environment.keys
                    .all {
                        it in
                            setOf("PATH", "HOME", "TMPDIR", "TMP", "TEMP", "USERPROFILE", "SYSTEMROOT", "SSL_CERT_FILE")
                    },
                "unexpected env keys ${environment.keys}",
            )
        }

    /** Same inventory as the fixture archive, but `yt_dlp/__init__.py` does not parse. */
    private fun brokenArchive(version: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(
                name: String,
                content: String,
            ) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
            put("__main__.py", "import yt_dlp\nyt_dlp.main()\n")
            put("yt_dlp/__init__.py", "def broken(:\n")
            put("yt_dlp/version.py", "ORIGIN = 'yt-dlp/yt-dlp'\nCHANNEL = 'stable'\n__version__ = '$version'\n")
            put("yt_dlp_ejs/__init__.py", "")
            put("yt_dlp_ejs/_version.py", "__version__ = version = '0.8.0'\n")
        }
        return out.toByteArray()
    }

    private companion object {
        const val NEW = EngineUpdateFixtures.NEW_VERSION
    }
}
