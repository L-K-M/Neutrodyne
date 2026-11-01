// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OperationalCliTest {
    @Test fun migrationCheckIsReadOnlyAndDoctorDoesNotMarkClean() =
        runBlocking {
            val directory = tempDataDir()
            val output = ByteArrayOutputStream()

            fun command(vararg arguments: String): Int =
                ServerCli(
                    arguments.toList().toTypedArray(),
                    mapOf("NEUTRODYNE_SERVER_DATA" to directory.toString()),
                    out = PrintStream(output),
                    err = PrintStream(output),
                ).run()
            assertEquals(1, command("migrate", "--check"))
            assertFalse(Files.exists(directory.resolve(SqliteSyncStore.DATABASE_FILE)))
            assertEquals(0, command("migrate"))
            assertEquals(0, command("migrate", "--check"))
            assertEquals(0, command("doctor"))
            assertTrue(output.toString().contains("keep NTP enabled"))
            assertFalse(Files.exists(directory.resolve(ServerStorage.CLEAN_MARKER)))
            assertEquals(0, command("backup", directory.resolve("backup.db").toString()))
            assertEquals(0, command("restore", directory.resolve("backup.db").toString()))
            assertEquals(2, command("restore"))
            assertEquals(2, command("migrate", "--unknown"))
        }

    @Test fun restoreCannotAcquireLiveServerLease() {
        val directory = tempDataDir()
        val output = ByteArrayOutputStream()
        ProcessLease.acquire(directory, ProcessMode.SERVE).use {
            val result =
                ServerCli(
                    arrayOf("restore", directory.resolve("backup.db").toString()),
                    mapOf("NEUTRODYNE_SERVER_DATA" to directory.toString()),
                    out = PrintStream(output),
                    err = PrintStream(output),
                ).run()
            assertEquals(1, result)
        }
    }
}
