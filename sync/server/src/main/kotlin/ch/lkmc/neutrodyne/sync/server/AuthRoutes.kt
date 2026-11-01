// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.dto.InviteRedeemRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.LinkApproveRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.LinkConfirmRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.LinkStartRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.LinkTokenRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.LoginRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.OAuthErrorDto
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

internal class AuthRoutes(
    private val services: ServerServices,
    private val config: ServerConfig,
) {
    fun authenticate(application: Application) {
        application.intercept(ApplicationCallPipeline.Plugins) {
            val path = call.request.path()
            if (!path.startsWith("/api/v1/") || path in PUBLIC_AUTH) return@intercept
            val authorization =
                call.request.headers
                    .getAll(HttpHeaders.Authorization)
                    ?.singleOrNull() ?: unauthorized()
            if (!authorization.startsWith("Bearer ")) unauthorized()
            val principal = services.tokens.authenticate(authorization.removePrefix("Bearer "), TokenKind.DEVICE)
            call.attributes.put(RateLimits.PRINCIPAL, principal)
        }
    }

    fun install(route: Route) {
        route.rateLimit(RateLimits.LOGIN) {
            post("/api/v1/auth/login") {
                val request = call.receive<LoginRequest>()
                call.respond(
                    services.auth.login(request.username, request.password, request.device, call.clientIp(config)),
                )
            }
            post("/api/v1/auth/invite/redeem") {
                val request = call.receive<InviteRedeemRequest>()
                call.respond(services.invites.redeem(request.inviteCode, request.device))
            }
        }
        route.rateLimit(RateLimits.LINK_START) {
            post("/api/v1/auth/link/start") {
                call.respond(services.links.start(call.receive<LinkStartRequest>().device, call.clientIp(config)))
            }
        }
        route.post("/api/v1/auth/link/token") {
            when (val result = services.links.pollDevice(call.receive<LinkTokenRequest>().deviceCode)) {
                is LinkPoll.Device -> call.respond(result.response)
                is LinkPoll.Error -> call.respond(HttpStatusCode.BadRequest, OAuthErrorDto(result.code))
                is LinkPoll.Web -> error("Device endpoint returned a browser credential")
            }
        }
        // Only failed entries consume the approval budget; successful preview/confirm pairs do not.
        route.post("/api/v1/auth/link/approve") {
            call.respond(
                services.links.preview(
                    call.principal(),
                    call.receive<LinkApproveRequest>().userCode,
                    call.clientIp(config),
                ),
            )
        }
        route.post("/api/v1/auth/link/confirm") {
            val request = call.receive<LinkConfirmRequest>()
            services.links.confirm(call.principal(), request.userCode, request.approve)
            call.respond(HttpStatusCode.NoContent)
        }
        route.rateLimit(RateLimits.ACCOUNT) {
            post("/api/v1/auth/logout") {
                services.tokens.logout(call.principal())
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }

    private companion object {
        val PUBLIC_AUTH =
            setOf(
                "/api/v1/auth/login",
                "/api/v1/auth/invite/redeem",
                "/api/v1/auth/link/start",
                "/api/v1/auth/link/token",
            )
    }
}

internal fun ApplicationCall.principal(): AccountPrincipal = attributes[RateLimits.PRINCIPAL]

internal fun ApplicationCall.clientIp(config: ServerConfig): String =
    ClientAddress(config.trustedProxies)
        .resolve(
            request.local.remoteHost,
            request.headers.getAll(HttpHeaders.XForwardedFor).orEmpty(),
            request.headers.getAll(HttpHeaders.XForwardedProto).orEmpty(),
        ).address
