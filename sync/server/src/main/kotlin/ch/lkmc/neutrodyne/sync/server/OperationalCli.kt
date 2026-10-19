// SPDX-License-Identifier: Unlicense
@file:Suppress("ktlint:standard:max-line-length")

package ch.lkmc.neutrodyne.sync.server

import kotlinx.coroutines.runBlocking
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant

internal class OperationalCli(
    private val args: Array<String>,
    private val environment: Map<String, String>,
    private val out: PrintStream,
    private val err: PrintStream,
) {
    fun run(): Int {
        val tokens = args.drop(1).toMutableList()
        val index = tokens.indexOf("--config")
        val file =
            if (index <
                0
            ) {
                null
            } else {
                tokens.getOrNull(index + 1)?.takeUnless { it.startsWith("-") } ?: return usage()
            }
        if (index >= 0) {
            tokens.removeAt(index + 1)
            tokens.removeAt(index)
        }
        val command = args.first()
        val valid =
            when (command) {
                "backup", "restore" -> tokens.size == 1 && !tokens.single().startsWith("-")
                "migrate" -> tokens == listOf("--check") || tokens.isEmpty()
                "doctor" -> tokens.isEmpty()
                else -> false
            }
        if (!valid) return usage()
        try {
            val properties = file?.let { ServerConfigLoader.readPropertiesFile(Path.of(it)) } ?: emptyMap()
            val result = ServerConfigLoader.load(environment, properties, ServeFlags())
            if (result !is ServerConfigResult.Valid) {
                err.println("Invalid configuration or listen rule")
                return ExitCodes.ERROR
            }
            val config = result.config
            val mode =
                if (command == "restore" ||
                    (command == "migrate" && tokens.isEmpty())
                ) {
                    ProcessMode.RESTORE
                } else {
                    ProcessMode.LIVE_ADMIN
                }
            ProcessLease.acquire(config.dataDir, mode).use {
                val database = config.dataDir.resolve(SqliteSyncStore.DATABASE_FILE)
                if (command == "restore") {
                    val replaced = runBlocking { ServerRecovery(config.dataDir).restore(Path.of(tokens.single())) }
                    out.println("Restored; retained replaced database: $replaced. Every device must resync.")
                    return ExitCodes.OK
                }
                if (command == "migrate" && tokens.isNotEmpty()) {
                    val version =
                        if (Files.exists(
                                database,
                            )
                        ) {
                            ServerRecovery.open(database).use(Migrator::version)
                        } else {
                            0
                        }
                    out.println(
                        "Schema $version; target ${Migrator.SCHEMA_VERSION}; pending ${Migrator.SCHEMA_VERSION - version}",
                    )
                    return if (version == Migrator.SCHEMA_VERSION) ExitCodes.OK else ExitCodes.ERROR
                }
                if (command == "doctor") return doctor(config, database)
                SqliteSyncStore(config.dataDir).use { store ->
                    if (command == "backup") {
                        runBlocking { store.backup(Path.of(tokens.single())) }
                        out.println("Database backup validated and saved")
                    } else {
                        out.println("Migrated to schema ${Migrator.SCHEMA_VERSION}")
                    }
                }
            }
            return ExitCodes.OK
        } catch (_: Exception) {
            err.println(
                "Operation failed; check the file, schema, storage permissions and process lease. Restore requires a readable current identity ledger; otherwise recover it or create a fresh server and re-link all devices.",
            )
            return ExitCodes.ERROR
        }
    }

    private fun doctor(
        config: ServerConfig,
        database: Path,
    ): Int {
        var okay = true

        fun check(
            name: String,
            test: () -> Boolean,
        ) {
            val passed = runCatching(test).getOrDefault(false)
            out.println("$name: ${if (passed) "ok" else "FAILED"}")
            if (!passed) okay = false
        }
        check("Java 21+") { Runtime.version().feature() >= 21 }
        check("Database integrity and schema") {
            ServerRecovery.open(database).use(ServerRecovery::validate)
            true
        }
        check("Private data directory") {
            Files.getPosixFilePermissions(config.dataDir) == PosixFilePermissions.fromString("rwx------") &&
                Files.getOwner(config.dataDir) == Files.getOwner(config.dataDir.resolve("maintenance.lock"))
        }
        check("Free space (50 MiB)") {
            Files.getFileStore(config.dataDir).usableSpace >=
                HealthRoutes.MIN_FREE_DISK_BYTES
        }
        val backups = config.dataDir.resolve("backups")
        val latest =
            if (!Files.isDirectory(backups)) {
                null
            } else {
                Files.list(backups).use { paths ->
                    paths
                        .filter { it.fileName.toString().matches(Regex("neutrodyne-server-\\d{4}-\\d{2}-\\d{2}\\.db")) }
                        .mapToLong { Files.getLastModifiedTime(it).toMillis() }
                        .max()
                        .orElse(0)
                        .takeIf { it > 0 }
                }
            }
        out.println(
            "Last nightly backup: ${latest?.let {
                Instant.ofEpochMilli(
                    it,
                )
            } ?: "none; run backup and copy it off-machine"}",
        )
        out.println("System time: ${Instant.now()}; keep NTP enabled and verify this clock")
        out.println("Configuration and listen rule: ok")
        return if (okay) ExitCodes.OK else ExitCodes.ERROR
    }

    private fun usage(): Int {
        err.print(ServerCli.USAGE_TEXT)
        return ExitCodes.USAGE
    }
}
