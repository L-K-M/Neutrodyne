// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlpdesktop

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PythonRuntimeProbeTest {
    @Test fun aSilentInterpreterHonorsTheProbeDeadline() {
        org.junit.Assume.assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val root = tempRoot("probe-deadline")
        val pidFile = root.resolve("probe.pid")
        val quotedPid = "'" + pidFile.absolutePath.replace("'", "'\\''") + "'"
        val executable =
            root.resolve("python-probe").apply {
                writeText("#!/bin/sh\nprintf '%s' \"\$\$\" > $quotedPid\nexec /bin/sleep 60\n")
                assertTrue(setExecutable(true))
            }
        val executor = Executors.newSingleThreadExecutor()
        val result = executor.submit<PythonRuntime?> { PythonRuntime.probe(executable, 200) }
        try {
            assertNull(result.get(5, TimeUnit.SECONDS))
            assertTrue(pidFile.isFile, "The test must launch the hanging interpreter")
            ProcessHandle.of(pidFile.readText().toLong()).ifPresent { child ->
                child.onExit().get(5, TimeUnit.SECONDS)
            }
        } finally {
            if (pidFile.isFile) {
                ProcessHandle.of(pidFile.readText().toLong()).ifPresent { it.destroyForcibly() }
            }
            result.cancel(true)
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            root.deleteRecursively()
        }
    }
}
