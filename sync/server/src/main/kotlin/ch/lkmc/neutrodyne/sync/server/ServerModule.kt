// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import kotlin.time.Clock

/**
 * Ktor application module (10 Server architecture, Module and classes): installs the request
 * pipeline's insecure-transport guard and the routes. The M0b skeleton carries health and
 * discovery; the API, web and event routes and the body guards arrive with MS1.
 */
internal class ServerModule(
    private val config: ServerConfig,
    private val serverVersion: String,
    private val clock: Clock = Clock.System,
) {
    fun install(application: Application) {
        with(application) {
            InsecureTransportGuard(config).install(this)
            routing {
                HealthRoutes(config.dataDir).install(this)
                WellKnownRoutes(serverVersion, clock).install(this)
            }
        }
    }
}

/**
 * Request pipeline step 1 (10 Request pipeline): when the public URL is `https://`, plain-HTTP
 * requests from peers that are neither loopback nor a trusted proxy reporting `https` are
 * answered with `421 insecure_transport`. Health routes still answer.
 */
internal class InsecureTransportGuard(
    private val config: ServerConfig,
) {
    fun install(application: Application) {
        application.intercept(ApplicationCallPipeline.Plugins) {
            // A probe path with a stray trailing slash is not routed (Ktor merges it only with
            // IgnoreTrailingSlash installed) but must still escape the 421 guard.
            if (call.request.path().trimEnd('/') in HealthRoutes.PATHS) return@intercept
            if (!config.publicUrlIsHttps) return@intercept

            val resolved =
                ClientAddress(config.trustedProxies).resolve(
                    peerHost = call.request.local.remoteHost,
                    forwardedFor =
                        call.request.headers
                            .getAll(HttpHeaders.XForwardedFor)
                            .orEmpty(),
                    forwardedProto =
                        call.request.headers
                            .getAll(HttpHeaders.XForwardedProto)
                            .orEmpty(),
                )
            if (resolved.secureTransport || isLoopbackHost(resolved.address)) return@intercept

            call.respondText(
                text = PROBLEM_BODY,
                contentType = PROBLEM_JSON,
                status = MISDIRECTED_REQUEST,
            )
            finish()
        }
    }

    internal companion object {
        /** Ktor has no constant for 421; RFC 9110 names it "Misdirected Request". */
        val MISDIRECTED_REQUEST = HttpStatusCode(421, "Misdirected Request")
        val PROBLEM_JSON: ContentType = ContentType("application", "problem+json")
        const val PROBLEM_BODY =
            """{"type":"urn:neutrodyne:sync:insecure_transport","title":"Insecure transport","status":421,"code":"insecure_transport"}"""
    }
}
