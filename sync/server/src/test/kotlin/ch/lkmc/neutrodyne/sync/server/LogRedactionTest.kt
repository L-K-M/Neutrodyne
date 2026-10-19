// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.dto.InviteRedeemRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.LinkApproveRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.LinkConfirmRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.LinkTokenRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.LoginRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogRedactionTest {
    @Test fun `credentials codes private URLs payloads and hostile errors never reach logs`() {
        val directory = tempDataDir()
        val output = directory.resolve("logs.txt")
        val process =
            ProcessBuilder(
                Path
                    .of(System.getProperty("java.home"), "bin", "java")
                    .toString(),
                "-cp",
                System.getProperty("neutrodyne.server.testClasspath"),
                LogRedactionProbe::class.java.name,
                directory.toString(),
            ).redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue(), Files.readString(output))
        val logs = Files.readString(output)
        assertTrue(logs.contains("Redaction scenario completed"))
        for (secret in Files.readAllLines(directory.resolve("secrets.txt"))) {
            assertFalse(logs.contains(secret), "Sensitive value appeared in child logs")
        }
    }
}

/** Separate JVM prevents cached slf4j-simple streams from bypassing the capture. */
internal object LogRedactionProbe {
    @JvmStatic fun main(args: Array<String>) =
        runBlocking {
            AuthFixture().use { f ->
                val password = "password-REDACTION-7391"
                val account = f.account(password = password)
                val code = f.services.invites.create(account.id)
                val setup = Files.readString(f.services.setup.initialize()!!).trim()
                val grant = f.services.invites.redeem(code, deviceInfo())
                val web = f.services.auth.loginWeb("alice", password, null)
                val link = f.services.links.start(deviceInfo(), "127.0.0.1")
                val url = "https://private-feed-REDACTION.example/secret.xml"
                val payload = "payload-REDACTION-8236"
                Files.write(
                    Path.of(args.single(), "secrets.txt"),
                    listOf(
                        password,
                        code,
                        AuthSecrets.normalizeCode(code),
                        setup,
                        AuthSecrets.normalizeCode(setup),
                        grant.token,
                        web,
                        link.userCode,
                        AuthSecrets.normalizeCode(link.userCode),
                        link.deviceCode,
                        url,
                        payload,
                    ),
                )
                testApplication {
                    application {
                        f.install(this)
                        routing { get("/hostile/{secret}") { error("$payload $url ${grant.token}") } }
                    }
                    client.get("/hostile/$payload?token=${grant.token}") {
                        header("X-Request-Id", grant.token + "/bad")
                    }
                    client.postApi("/api/v1/auth/login", LoginRequest("alice", password + "wrong", deviceInfo()))
                    client.postApi("/api/v1/auth/invite/redeem", InviteRedeemRequest(code, deviceInfo()))
                    client.postApi("/api/v1/auth/link/token", LinkTokenRequest(link.deviceCode))
                    client.postApi("/api/v1/auth/link/approve", LinkApproveRequest(link.userCode), grant.token)
                    client.postApi(
                        "/api/v1/auth/link/confirm",
                        LinkConfirmRequest(link.userCode, false),
                        grant.token,
                    )
                    client.post("/api/v1/sync") {
                        api(grant.token)
                        contentType(ContentType.Application.Json)
                        setBody("{\"$payload\":\"$url\"")
                    }
                }
                LoggerFactory.getLogger("redaction-probe").info("Redaction scenario completed")
            }
        }
}
