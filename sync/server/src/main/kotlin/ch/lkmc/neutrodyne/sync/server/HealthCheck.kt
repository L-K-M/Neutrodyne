// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.system.exitProcess

/** Container probe: no shell, no data-directory access, no proxy or redirect, bounded to three seconds. */
object HealthCheck {
    @JvmStatic fun main(args: Array<String>) {
        exitProcess(check(System.getenv()))
    }

    internal fun check(environment: Map<String, String>): Int {
        val listen = ListenAddress.parse(environment[ServerEnv.LISTEN] ?: "127.0.0.1:8787") ?: return ExitCodes.ERROR
        val host = if (listen.host.contains(':')) "::1" else "127.0.0.1"
        return try {
            HttpClient
                .newBuilder()
                .proxy(HttpClient.Builder.NO_PROXY)
                .connectTimeout(
                    Duration.ofSeconds(2),
                ).followRedirects(HttpClient.Redirect.NEVER)
                .build()
                .use { client ->
                    val uri = URI("http", null, host, listen.port, "/readyz", null, null)
                    val response =
                        client.send(
                            HttpRequest
                                .newBuilder(uri)
                                .timeout(Duration.ofSeconds(3))
                                .GET()
                                .build(),
                            HttpResponse.BodyHandlers.discarding(),
                        )
                    if (response.statusCode() == 200) ExitCodes.OK else ExitCodes.ERROR
                }
        } catch (_: Exception) {
            ExitCodes.ERROR
        }
    }
}
