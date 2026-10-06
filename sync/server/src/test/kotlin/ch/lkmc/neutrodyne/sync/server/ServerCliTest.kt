// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The `serve` / `--version` command line (10 CLI; M0b skeleton). */
class ServerCliTest {

    private class Captured {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val outStream = PrintStream(out, true)
        val errStream = PrintStream(err, true)
        fun outText() = out.toString("UTF-8")
        fun errText() = err.toString("UTF-8")
    }

    private fun run(
        args: List<String>,
        env: Map<String, String> = emptyMap(),
        started: MutableList<ServerConfig> = mutableListOf(),
    ): Pair<Int, Captured> {
        val captured = Captured()
        val cli = ServerCli(args.toTypedArray(), env, serverStarter = { config -> started.add(config) }, out = captured.outStream, err = captured.errStream)
        return cli.run() to captured
    }

    @Test
    fun `no command prints usage and exits 2`() {
        val (exit, captured) = run(emptyList())
        assertEquals(ExitCodes.USAGE, exit)
        assertTrue(captured.errText().contains("Usage: neutrodyne-server"), captured.errText())
    }

    @Test
    fun `an unknown command prints usage and exits 2`() {
        val (exit, captured) = run(listOf("frobnicate"))
        assertEquals(ExitCodes.USAGE, exit)
        assertTrue(captured.errText().contains("unknown command 'frobnicate'"), captured.errText())
        assertTrue(captured.errText().contains("Usage: neutrodyne-server"), captured.errText())
    }

    @Test
    fun `an unknown serve option prints usage and exits 2`() {
        val (exit, captured) = run(listOf("serve", "--turbo"))
        assertEquals(ExitCodes.USAGE, exit)
        assertTrue(captured.errText().contains("unknown option '--turbo'"), captured.errText())
    }

    @Test
    fun `--config without a path prints usage and exits 2`() {
        val (exit, captured) = run(listOf("serve", "--config"))
        assertEquals(ExitCodes.USAGE, exit)
        assertTrue(captured.errText().contains("--config needs a file path"), captured.errText())
    }

    @Test
    fun `--version prints the version and exits 0`() {
        val (exit, captured) = run(listOf(ServerCli.VERSION_FLAG))
        assertEquals(ExitCodes.OK, exit)
        assertEquals(ServerVersion.current(), captured.outText().trim())
    }

    @Test
    fun `serve refuses an invalid configuration, reports the cause and exits 1`() {
        val started = mutableListOf<ServerConfig>()
        val (exit, captured) = run(
            listOf("serve"),
            env = mapOf(ServerEnv.LISTEN to "0.0.0.0:8787"),
            started = started,
        )
        assertEquals(ExitCodes.ERROR, exit)
        assertTrue(captured.errText().contains("refusing to listen on 0.0.0.0:8787"), captured.errText())
        assertTrue(started.isEmpty())
    }

    @Test
    fun `serve with an unreadable config file exits 1`() {
        val (exit, captured) = run(listOf("serve", "--config", "/nonexistent/server.properties"))
        assertEquals(ExitCodes.ERROR, exit)
        assertTrue(captured.errText().contains("--config"), captured.errText())
    }

    @Test
    fun `serve --insecure-lan passes validation and starts`() {
        val started = mutableListOf<ServerConfig>()
        val (exit, _) = run(
            listOf("serve", "--insecure-lan"),
            env = mapOf(ServerEnv.LISTEN to "0.0.0.0:8787"),
            started = started,
        )
        assertEquals(ExitCodes.OK, exit)
        val config = started.single()
        assertEquals("0.0.0.0", config.listen.host)
        assertEquals(8787, config.listen.port)
        assertTrue(config.insecureLan)
    }

    @Test
    fun `serve reads a properties file and the environment overrides it`() {
        val properties = Files.createTempFile("server", ".properties")
        properties.writeText(
            """
            # reference-style server.properties
            listen = 127.0.0.1:9999
            quota.records = 1500
            backup.time = 04:15
            """.trimIndent() + "\n",
        )
        val started = mutableListOf<ServerConfig>()
        val (exit, _) = run(
            listOf("serve", "--config", properties.toString()),
            env = mapOf(ServerEnv.LISTEN to "127.0.0.1:8788"),
            started = started,
        )
        assertEquals(ExitCodes.OK, exit)
        val config = started.single()
        assertEquals(8788, config.listen.port)
        assertEquals(1500L, config.quotaRecords)
        assertEquals("04:15", config.backupTime)
    }

    @Test
    fun `a starter failure exits non-zero`() {
        val captured = Captured()
        val cli = ServerCli(
            arrayOf("serve"),
            emptyMap(),
            serverStarter = { throw IllegalStateException("port already in use") },
            out = captured.outStream,
            err = captured.errStream,
        )
        assertEquals(ExitCodes.ERROR, cli.run())
        assertTrue(captured.errText().contains("port already in use"), captured.errText())
    }
}
