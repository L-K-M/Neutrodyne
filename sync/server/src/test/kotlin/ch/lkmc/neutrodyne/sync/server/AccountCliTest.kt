// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountCliTest {
    private val directory = tempDataDir()
    private val environment = mapOf(ServerEnv.DATA to directory.toString())

    private data class Result(
        val exit: Int,
        val out: String,
        val err: String,
    )

    private fun command(
        vararg args: String,
        prompt: () -> CharArray? = { null },
    ): Result {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val result =
            ServerCli(
                arrayOf(*args),
                environment,
                out = PrintStream(out),
                err = PrintStream(err),
                passwordPrompt = prompt,
            ).run()
        return Result(result, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    @Test fun `create admin add list invite and disable persist without clean marker`() {
        val created = command("admin", "create", "admin")
        assertEquals(0, created.exit, created.err)
        assertTrue(created.out.contains("Invite (24 hours, shown once):"))
        assertEquals(0, command("user", "add", "bob").exit)
        val list = command("user", "list")
        assertEquals(0, list.exit)
        assertTrue(list.out.contains("admin admin enabled devices=0 last_activity=never"))
        assertTrue(list.out.contains("bob user enabled"))
        assertTrue(command("user", "invite", "bob").out.contains("Invite"))
        assertEquals(0, command("user", "disable", "bob").exit)
        assertTrue(command("user", "list").out.contains("bob user disabled"))
        assertFalse(Files.exists(directory.resolve(ServerStorage.CLEAN_MARKER)))
        assertFalse(Files.exists(directory.resolve("setup-token")))
    }

    @Test fun `password is console only cleared after hashing and absent from output`() {
        val password = "private password".toCharArray()
        val result = command("user", "add", "bob", "--admin", "--password", prompt = { password })
        assertEquals(0, result.exit, result.err)
        assertTrue(password.all { it == '\u0000' })
        assertFalse(result.out.contains("Invite"))
        assertFalse((result.out + result.err).contains("private password"))
        SqliteSyncStore(directory).use { store ->
            runBlocking {
                val services = ServerServices(store, loadValidConfig().copy(dataDir = directory), EventBus({ true }))
                assertTrue(services.auth.loginWeb("bob", "private password", null).startsWith("ndw_"))
            }
        }
    }

    @Test fun `no console is an error and argv passwords are rejected before prompting`() {
        assertEquals(1, command("user", "add", "bob", "--password").exit)
        assertEquals(2, command("user", "add", "bob", "--password", "secret-argv").exit)
        assertEquals(2, command("admin", "create", "bob", "--password=secret-argv").exit)
        assertEquals(2, command("user", "list", "bob").exit)
        assertEquals(2, command("user", "invite").exit)
        assertEquals(2, command("admin", "list").exit)
    }

    @Test fun `account failures are generic and leave previous accounts intact`() {
        assertEquals(0, command("user", "add", "bob").exit)
        assertEquals(1, command("user", "add", "bob").exit)
        val failed = command("user", "invite", "secret-invalid-username!")
        assertEquals(1, failed.exit)
        assertFalse((failed.out + failed.err).contains("secret-invalid"))
        assertTrue(command("user", "list").out.contains("bob"))
    }

    @Test fun `CLI uses properties and preserves an existing clean marker`() {
        val marker = directory.resolve(ServerStorage.CLEAN_MARKER)
        Files.writeString(marker, "unchanged")
        val config = directory.resolve("server.properties")
        Files.writeString(config, "data.dir=$directory\n")
        assertEquals(0, command("user", "add", "bob", "--config", config.toString()).exit)
        assertEquals("unchanged", Files.readString(marker))
    }

    @Test fun `same OS user can administer live server and revoke existing tokens`() =
        runBlocking {
            ServerStorage.open(directory).use { storage ->
                val services =
                    ServerServices(storage.store, loadValidConfig().copy(dataDir = directory), EventBus({ true }))
                val owner = services.auth.createAccount("bob", AccountRole.USER)
                val device = services.invites.redeem(services.invites.create(owner.id), deviceInfo())
                val log = directory.resolve("cli.log")
                val process =
                    ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty("neutrodyne.server.testClasspath"),
                        AccountCliProbe::class.java.name,
                        directory.toString(),
                    ).redirectErrorStream(true).redirectOutput(log.toFile()).start()
                assertTrue(process.waitFor(30, TimeUnit.SECONDS))
                assertEquals(0, process.exitValue(), Files.readString(log))
                assertTrue(storage.store.readAuth { account(owner.id)!!.disabledAt != null })
                assertFalse(
                    services.tokens.valid(
                        AccountPrincipal(
                            owner.id,
                            owner.role,
                            TokenKind.DEVICE,
                            AuthSecrets.hash(device.token),
                            device.deviceId,
                        ),
                    ),
                )
                assertFalse(Files.exists(directory.resolve(ServerStorage.CLEAN_MARKER)))
            }
        }
}

internal object AccountCliProbe {
    @JvmStatic fun main(args: Array<String>) {
        kotlin.system.exitProcess(
            ServerCli(arrayOf("user", "disable", "bob"), mapOf(ServerEnv.DATA to args.single())).run(),
        )
    }
}
