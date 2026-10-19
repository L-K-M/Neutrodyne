// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProcessLeaseTest {
    @Test fun `serve excludes another serve and restore but permits live admin in another process`() {
        val dir = tempDataDir()
        ProcessLease.acquire(dir, ProcessMode.SERVE).use {
            assertEquals("busy", child(dir, "SERVE").lineAndExit())
            assertEquals("busy", child(dir, "RESTORE").lineAndExit())
            assertEquals("acquired", child(dir, "LIVE_ADMIN").lineAndExit())
        }
        ProcessLease.acquire(dir, ProcessMode.RESTORE).use {
            assertEquals("busy", child(dir, "SERVE").lineAndExit())
            assertEquals("busy", child(dir, "LIVE_ADMIN").lineAndExit())
        }
        assertEquals("acquired", child(dir, "RESTORE").lineAndExit())
        assertFalse(Files.exists(dir.resolve(ServerStorage.CLEAN_MARKER)))
    }

    @Test fun `SIGKILL leaves marker absent and next serve checks database and rotates epoch`() =
        runBlocking<Unit> {
            val dir = tempDataDir()
            ServerStorage.open(dir).use { storage ->
                accountFixture(dir)
                storage.finishGracefully()
            }
            val process = child(dir, "crash")
            try {
                assertEquals("ready", process.inputStream.bufferedReader().readLine())
                process.destroyForcibly()
                assertTrue(process.waitFor(10, TimeUnit.SECONDS))
                assertFalse(Files.exists(dir.resolve(ServerStorage.CLEAN_MARKER)))
                ServerStorage.open(dir).use { storage ->
                    assertNotEquals("00000001", storage.store.read(1) { account.cursor.epoch })
                    storage.finishGracefully()
                }
            } finally {
                process.destroyForcibly()
            }
        }

    @Test
    fun `live admin backup coexists with serve without writing clean marker`() =
        runBlocking<Unit> {
            val dir = tempDataDir()
            ServerStorage.open(dir).use { storage ->
                accountFixture(dir)
                storage.store.write(1) { put(fixtureRecord("a", nextSequence())) }
                assertEquals("backed-up", child(dir, "backup").lineAndExit())
                assertTrue(Files.exists(dir.resolve("admin-backup.db")))
                assertFalse(Files.exists(dir.resolve(ServerStorage.CLEAN_MARKER)))
                storage.finishGracefully()
            }
        }

    @Test
    fun `CLI SIGTERM writes clean marker but failed bind does not`() {
        val dir = tempDataDir()
        val port = java.net.ServerSocket(0).use { it.localPort }
        val process = child(dir, "serve", port)
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            var ready = false
            while (!ready && process.isAlive && System.nanoTime() < deadline) {
                ready =
                    runCatching {
                        val connection =
                            java.net
                                .URI("http://127.0.0.1:$port/readyz")
                                .toURL()
                                .openConnection()
                        connection.connectTimeout = 200
                        connection.readTimeout = 200
                        connection.getInputStream().use { it.readBytes().toString(Charsets.UTF_8) == "ok" }
                    }.getOrDefault(false)
                if (!ready) Thread.sleep(50)
            }
            assertTrue(ready, "CLI did not become ready")
            val failedDir = dir.resolve("failed-start")
            val failed = child(failedDir, "serve", port)
            try {
                assertTrue(failed.waitFor(15, TimeUnit.SECONDS))
                assertNotEquals(0, failed.exitValue())
                assertFalse(Files.exists(failedDir.resolve(ServerStorage.CLEAN_MARKER)))
            } finally {
                failed.destroyForcibly()
            }
            process.destroy()
            assertTrue(process.waitFor(15, TimeUnit.SECONDS))
            assertTrue(Files.exists(dir.resolve(ServerStorage.CLEAN_MARKER)))
        } finally {
            process.destroyForcibly()
        }
    }

    private fun child(
        dir: Path,
        mode: String,
        port: Int? = null,
    ): Process {
        val classpath = checkNotNull(System.getProperty("neutrodyne.server.testClasspath"))
        return ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin/java").toString(),
            "-cp",
            classpath,
            "ch.lkmc.neutrodyne.sync.server.ProcessFixture",
            dir.toString(),
            mode,
        ).redirectError(ProcessBuilder.Redirect.INHERIT)
            .apply {
                environment()[ServerEnv.DATA] = dir.toString()
                environment()[ServerEnv.UPDATE_CHECK] = "false"
                if (port != null) environment()[ServerEnv.LISTEN] = "127.0.0.1:$port"
            }.start()
    }

    private fun Process.lineAndExit(): String {
        assertTrue(waitFor(10, TimeUnit.SECONDS))
        assertEquals(0, exitValue())
        return inputStream.bufferedReader().readLine()
    }
}

/** Real OS locks and SIGKILL cannot be exercised by two overlapping locks in one JVM. */
object ProcessFixture {
    @JvmStatic fun main(args: Array<String>) {
        val dir = Path.of(args[0])
        if (args[1] == "serve") {
            ch.lkmc.neutrodyne.sync.server
                .main(arrayOf("serve"))
            return
        }
        if (args[1] == "backup") {
            ProcessLease.acquire(dir, ProcessMode.LIVE_ADMIN).use {
                SqliteSyncStore(dir).use { store -> runBlocking { store.backup(dir.resolve("admin-backup.db")) } }
            }
            println("backed-up")
            return
        }
        if (args[1] == "crash") {
            ServerStorage.open(dir).use {
                println("ready")
                System.out.flush()
                Thread.sleep(Long.MAX_VALUE)
            }
            return
        }
        try {
            ProcessLease.acquire(dir, ProcessMode.valueOf(args[1])).use { println("acquired") }
        } catch (_: IllegalStateException) {
            println("busy")
        }
    }
}
