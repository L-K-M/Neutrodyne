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
        val exe =
            dir
                .resolve("python")
                .apply { mkdirs() }
                .resolve(if (isWindows()) "python.exe" else "python3")
                .apply { writeBytes(byteArrayOf()) }
        val locator =
            DesktopPythonRuntimeLocator(
                explicit = { null },
                engineResources = { dir },
                devStandalone = { null },
                probe = { PythonRuntime(it, "3.14") },
            )
        assertEquals(exe, locator.locate()?.executable)
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

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows")
}
