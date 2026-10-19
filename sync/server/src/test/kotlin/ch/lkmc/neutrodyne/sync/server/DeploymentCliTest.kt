// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeploymentCliTest {
    @Test fun offlineDoctorLoadsJniWithoutUsingTheProcessTempDirectory() {
        val directory = tempDataDir()
        SqliteSyncStore(directory).close()
        val java = Path.of(System.getProperty("java.home"), "bin", "java")
        val classpath = checkNotNull(System.getProperty("neutrodyne.server.testClasspath"))
        val child =
            ProcessBuilder(
                java.toString(),
                "-Djava.io.tmpdir=${directory.resolve("missing-tmp")}",
                "-cp",
                classpath,
                "ch.lkmc.neutrodyne.sync.server.MainKt",
                "doctor",
            ).apply {
                environment()["NEUTRODYNE_SERVER_DATA"] = directory.toString()
                environment()["NEUTRODYNE_SERVER_UPDATE_CHECK"] = "false"
            }.redirectErrorStream(true)
                .start()
        try {
            assertTrue(child.waitFor(30, TimeUnit.SECONDS), "Doctor did not terminate")
            val output = child.inputStream.bufferedReader().readText()
            assertEquals(0, child.exitValue(), output)
            assertTrue(output.contains("Database integrity and schema: ok"))
        } finally {
            if (child.isAlive) child.destroyForcibly()
        }
    }

    @Test fun databaseBackupOutsideThePrivateDataDirectoryIsOwnerOnly() =
        runBlocking {
            val directory = tempDataDir()
            val destination = Files.createTempFile("nd-cli-backup-", ".db")
            try {
                SqliteSyncStore(directory).use { it.backup(destination) }
                assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(destination))
            } finally {
                Files.deleteIfExists(destination)
            }
        }
}
