// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import java.io.IOException
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.Path

/**
 * The `neutrodyne-server` command line (10 CLI). M0b implements `serve` and `--version`; the
 * account, backup, restore, migrate and doctor commands arrive with MS1. Unknown or missing
 * commands print the usage and exit 2; configuration problems print one line per error and
 * exit 1.
 */
internal class ServerCli(
    private val args: Array<String>,
    private val environment: Map<String, String>,
    private val serverStarter: (ServerConfig) -> Unit = ::serve,
    private val out: PrintStream = System.out,
    private val err: PrintStream = System.err,
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

            else -> {
                err.println("unknown command '$command'")
                usage()
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
                    err.println("serve failed: ${e.message}")
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

            The account, backup, restore, migrate and doctor commands arrive with MS1.
            """.trimIndent() + "\n"
    }
}

/** Process exit codes: success, failure (configuration or runtime), usage error. */
internal object ExitCodes {
    const val OK = 0
    const val ERROR = 1
    const val USAGE = 2
}
