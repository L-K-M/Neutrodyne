// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.util.AttributeKey
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** W5 sets PRINCIPAL after authentication and wraps routes with their account/IP limiters.
 * Account keys never come from a request-supplied ID. Failed-login exponential delay belongs to W5.
 */
internal class RateLimits(
    private val config: ServerConfig,
) {
    fun install(application: Application) {
        application.install(RateLimit) {
            register(PUBLIC) {
                rateLimiter(60, 1.minutes)
                requestKey { address(it) }
            }
            register(WEB) {
                rateLimiter(300, 1.minutes)
                requestKey { address(it) }
            }
            register(LOGIN) {
                rateLimiter(10, 15.minutes)
                requestKey { address(it) }
            }
            register(LINK_START) {
                rateLimiter(20, 1.hours)
                requestKey { address(it) }
            }
            register(LINK_APPROVE) {
                rateLimiter(5, 15.minutes)
                requestKey { account(it) }
            }
            register(SYNC_IP) {
                rateLimiter(600, 1.minutes)
                requestKey { address(it) }
            }
            register(SYNC_ACCOUNT) {
                rateLimiter(300, 1.minutes)
                requestKey { account(it) }
            }
            register(ACCOUNT) {
                rateLimiter(30, 1.minutes)
                requestKey { account(it) }
            }
            register(EXPORT) {
                rateLimiter(6, 1.hours)
                requestKey { account(it) }
            }
        }
    }

    private fun account(call: ApplicationCall): Long = call.attributes[PRINCIPAL].accountId

    private fun address(call: ApplicationCall): String =
        ClientAddress(config.trustedProxies)
            .resolve(
                call.request.local.remoteHost,
                call.request.headers
                    .getAll(HttpHeaders.XForwardedFor)
                    .orEmpty(),
                call.request.headers
                    .getAll(HttpHeaders.XForwardedProto)
                    .orEmpty(),
            ).address

    companion object {
        val PRINCIPAL = AttributeKey<AccountPrincipal>("sync-principal")
        val PUBLIC = RateLimitName("public")
        val WEB = RateLimitName("web")
        val LOGIN = RateLimitName("login")
        val LINK_START = RateLimitName("link-start")
        val LINK_APPROVE = RateLimitName("link-approve")
        val SYNC_IP = RateLimitName("sync-ip")
        val SYNC_ACCOUNT = RateLimitName("sync-account")
        val ACCOUNT = RateLimitName("account")
        val EXPORT = RateLimitName("export")
    }
}
