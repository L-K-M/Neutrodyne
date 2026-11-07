// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlpdesktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PythonRuntimeLocatorTest {
    @Test fun probeFindsTheRunnerInterpreter() {
        val found = runnerPython()
        assertTrue(found.executable.isFile)
        assertTrue(found.pythonMinor.matches(Regex("""\d+\.\d+""")))
    }

    @Test fun explicitPathWinsOverResourceAndDevCandidates() {
        val found = runnerPython()
        val locator =
            DesktopPythonRuntimeLocator(
                explicit = { found.executable },
                engineResources = { File("/nonexistent") },
                devStandalone = { File("/nonexistent") },
            )
        val runtime = locator.locate()
        assertNotNull(runtime)
        assertEquals(found.pythonMinor, runtime.pythonMinor)
    }

    @Test fun engineResourcePythonIsUsedWhenNoExplicitPath() {
        val dir = tempRoot("res")
        val exe = interpreterIn(dir).apply { writeBytes(byteArrayOf()) }
        val locator =
            DesktopPythonRuntimeLocator(
                explicit = { null },
                engineResources = { dir },
                devStandalone = { null },
                probe = { PythonRuntime(it, "3.14") },
            )
        assertEquals(exe, locator.locate()?.executable)
    }

    @Test fun bundledResourcesWinOverTheDevStandaloneTree() {
        val bundled = tempRoot("bundled")
        val bundledExe = interpreterIn(bundled).apply { writeBytes(byteArrayOf()) }
        val dev = tempRoot("dev")
        interpreterIn(dev).apply { writeBytes(byteArrayOf()) }
        val locator =
            DesktopPythonRuntimeLocator(
                explicit = { null },
                engineResources = { bundled },
                devStandalone = { dev },
                probe = { PythonRuntime(it, "3.14") },
            )
        assertEquals(bundledExe, locator.locate()?.executable)
    }

    @Test fun devStandaloneTreeIsUsedWhenResourcesCarryNoInterpreter() {
        val resources = tempRoot("res")
        val dev = tempRoot("dev")
        val devExe = interpreterIn(dev).apply { writeBytes(byteArrayOf()) }
        val locator =
            DesktopPythonRuntimeLocator(
                explicit = { null },
                engineResources = { resources },
                devStandalone = { dev },
                probe = { PythonRuntime(it, "3.14") },
            )
        assertEquals(devExe, locator.locate()?.executable)
    }

    @Test fun missingInterpreterYieldsNullNotAnException() {
        val locator =
            DesktopPythonRuntimeLocator(
                explicit = { null },
                engineResources = { null },
                devStandalone = { null },
            )
        assertNull(locator.locate())
    }

    @Test fun aWorkingInterpreterIsMemoized() {
        val found = runnerPython()
        var probes = 0
        val locator =
            DesktopPythonRuntimeLocator(
                explicit = { found.executable },
                engineResources = { null },
                devStandalone = { null },
                probe = {
                    probes++
                    PythonRuntime(it, "9.9")
                },
            )
        val first = locator.locate()
        assertSame(first, locator.locate())
        assertEquals(1, probes)
    }

    @Test fun aFailedProbeIsNotMemoized() {
        val broken = tempRoot("py").resolve("python3").apply { writeBytes(byteArrayOf()) }
        var probes = 0
        val locator =
            DesktopPythonRuntimeLocator(
                explicit = { broken },
                engineResources = { null },
                devStandalone = { null },
                probe = {
                    probes++
                    null
                },
            )
        assertNull(locator.locate())
        assertNull(locator.locate())
        assertEquals(2, probes)
    }

    private fun interpreterIn(resources: java.io.File): java.io.File {
        val exe =
            if (isWindows()) {
                resources.resolve("python/python.exe")
            } else {
                resources.resolve("python/bin/python3")
            }
        exe.parentFile.mkdirs()
        return exe
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows")
}
