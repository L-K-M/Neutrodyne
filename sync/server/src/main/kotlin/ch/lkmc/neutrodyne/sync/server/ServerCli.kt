// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.Path

/**
 * The `neutrodyne-server` command line (10 CLI): serve, version and account administration.
 * W7 supplies backup, restore, migrate and doctor. Unknown or missing
 * commands print the usage and exit 2; configuration problems print one line per error and
 * exit 1.
 */
internal class ServerCli(
    private val args: Array<String>,
    private val environment: Map<String, String>,
    private val serverStarter: (ServerConfig) -> Unit = ::serve,
    private val out: PrintStream = System.out,
    private val err: PrintStream = System.err,
    private val passwordPrompt: () -> CharArray? = { System.console()?.readPassword("Password: ") },
) {
    fun run(): Int =
        when (val command = args.firstOrNull()) {
            null -> {
                usage()
            }

            VERSION_FLAG -> {
                out.println(ServerVersion.current())
                ExitCodes.OK
            }

            SERVE_COMMAND -> {
                serve()
            }

            "admin", "user" -> {
                accounts()
            }

            else -> {
                err.println("unknown command '$command'")
                usage()
            }
        }

    private fun accounts(): Int {
        val tokens = args.drop(1).toMutableList()
        var configFile: Path? = null
        val configIndex = tokens.indexOf(CONFIG_FLAG)
        if (configIndex >= 0) {
            val file = tokens.getOrNull(configIndex + 1)
            if (file == null || file.startsWith("-")) return usage()
            configFile = Path(file)
            tokens.removeAt(configIndex + 1)
            tokens.removeAt(configIndex)
        }
        val admin = args.first() == "admin"
        val command = tokens.firstOrNull() ?: return usage()
        val expected = if (admin) setOf("create") else setOf("add", "invite", "disable", "list")
        if (command !in expected) return usage()
        val name =
            if (command ==
                "list"
            ) {
                null
            } else {
                tokens.getOrNull(1)?.takeUnless { it.startsWith("-") } ?: return usage()
            }
        val options = tokens.drop(if (command == "list") 1 else 2)
        val allowed =
            if (command in
                setOf("add", "create")
            ) {
                setOf("--password") + if (admin) emptySet() else setOf("--admin")
            } else {
                emptySet()
            }
        if (options.any { it !in allowed } || options.distinct().size != options.size) return usage()
        var password: CharArray? = null
        try {
            if ("--password" in options) {
                password =
                    passwordPrompt()
                        ?: run {
                            err.println("Password needs an interactive console")
                            return ExitCodes.ERROR
                        }
            }
            val properties = configFile?.let(ServerConfigLoader::readPropertiesFile) ?: emptyMap()
            val result = ServerConfigLoader.load(environment, properties, ServeFlags())
            if (result !is ServerConfigResult.Valid) {
                err.println("Account command failed: invalid configuration")
                return ExitCodes.ERROR
            }
            val config = result.config
            ProcessLease.acquire(config.dataDir, ProcessMode.LIVE_ADMIN).use {
                SqliteSyncStore(config.dataDir).use { store ->
                    lateinit var services: ServerServices
                    EventBus({ services.tokens.valid(it) }).use { events ->
                        services = ServerServices(store, config, events)
                        runBlocking {
                            when (command) {
                                "create", "add" -> {
                                    val role =
                                        if (admin ||
                                            "--admin" in options
                                        ) {
                                            AccountRole.ADMIN
                                        } else {
                                            AccountRole.USER
                                        }
                                    val account =
                                        services.auth.createAccount(
                                            checkNotNull(name),
                                            role,
                                            password?.concatToString(),
                                        )
                                    out.println("Created ${account.username} (${role.name.lowercase()})")
                                    if (password ==
                                        null
                                    ) {
                                        out.println(
                                            "Invite (24 hours, shown once): ${services.invites.create(account.id)}",
                                        )
                                    }
                                }

                                "invite" -> {
                                    out.println(
                                        "Invite (24 hours, shown once): ${services.invites.create(
                                            services.admin.accountId(checkNotNull(name)),
                                        )}",
                                    )
                                }

                                "disable" -> {
                                    services.admin.change(
                                        services.admin.accountId(checkNotNull(name)),
                                        AccountAction.DISABLE,
                                    )
                                    out.println("Account disabled; credentials revoked")
                                }

                                "list" -> {
                                    services.admin.accounts().forEach { row ->
                                        out.println(
                                            "${row.account.username} ${row.account.role.name.lowercase()} ${if (row.account.disabledAt == null) "enabled" else "disabled"} devices=${row.devices} last_activity=${row.lastSeen ?: "never"}",
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return ExitCodes.OK
        } catch (_: Exception) {
            // JDBC, validation and filesystem messages may contain user-supplied secrets.
            err.println("Account command failed; check the username, permissions and account state")
            return ExitCodes.ERROR
        } finally {
            password?.fill('\u0000')
        }
    }

    private fun serve(): Int {
        val flags =
            when (val parsed = parseServeFlags(args.drop(1))) {
                is FlagsParseResult.Ok -> {
                    parsed.flags
                }

                is FlagsParseResult.WrongUsage -> {
                    err.println(parsed.message)
                    printUsage(err)
                    return ExitCodes.USAGE
                }
            }

        val properties =
            flags.configFile?.let { configFile ->
                try {
                    ServerConfigLoader.readPropertiesFile(configFile)
                } catch (e: IOException) {
                    err.println("configuration error: --config $configFile: ${e.message}")
                    return ExitCodes.ERROR
                }
            } ?: emptyMap()

        return when (val result = ServerConfigLoader.load(environment, properties, flags)) {
            is ServerConfigResult.Invalid -> {
                result.errors.forEach { message -> err.println("configuration error: $message") }
                ExitCodes.ERROR
            }

            is ServerConfigResult.Valid -> {
                try {
                    serverStarter(result.config)
                    ExitCodes.OK
                } catch (e: Exception) {
                    err.println("serve failed; check configuration, storage and listener availability")
                    ExitCodes.ERROR
                }
            }
        }
    }

    private fun parseServeFlags(tokens: List<String>): FlagsParseResult {
        var insecureLan = false
        var configFile: Path? = null
        var index = 0
        while (index < tokens.size) {
            when (tokens[index]) {
                INSECURE_LAN_FLAG -> {
                    insecureLan = true
                }

                CONFIG_FLAG -> {
                    val value = tokens.getOrNull(index + 1)
                    if (value == null || value.startsWith("-")) {
                        return FlagsParseResult.WrongUsage("$CONFIG_FLAG needs a file path")
                    }
                    configFile = Path(value)
                    index++
                }

                else -> {
                    return FlagsParseResult.WrongUsage("unknown option '${tokens[index]}' for $SERVE_COMMAND")
                }
            }
            index++
        }
        return FlagsParseResult.Ok(ServeFlags(insecureLan, configFile))
    }

    private fun usage(): Int {
        printUsage(err)
        return ExitCodes.USAGE
    }

    private fun printUsage(stream: PrintStream) {
        stream.println(USAGE_TEXT)
    }

    private sealed interface FlagsParseResult {
        data class Ok(
            val flags: ServeFlags,
        ) : FlagsParseResult

        data class WrongUsage(
            val message: String,
        ) : FlagsParseResult
    }

    internal companion object {
        const val SERVE_COMMAND = "serve"
        const val VERSION_FLAG = "--version"
        const val INSECURE_LAN_FLAG = "--insecure-lan"
        const val CONFIG_FLAG = "--config"

        val USAGE_TEXT =
            """
            Usage: neutrodyne-server <command> [options]

            Commands:
              serve [--insecure-lan] [--config <server.properties>]
                  Run the sync server.
              --version
                  Print the version and exit.

              admin create <username> [--password] [--config <server.properties>]
              user add <username> [--admin] [--password] [--config <server.properties>]
              user invite <username> [--config <server.properties>]
              user disable <username> [--config <server.properties>]
              user list [--config <server.properties>]
                  Administer accounts as the server OS user. Passwords are prompted,
                  never accepted as arguments. Backup/restore commands arrive with W7.
            """.trimIndent() + "\n"
    }
}

/** Process exit codes: success, failure (configuration or runtime), usage error. */
internal object ExitCodes {
    const val OK = 0
    const val ERROR = 1
    const val USAGE = 2
}
