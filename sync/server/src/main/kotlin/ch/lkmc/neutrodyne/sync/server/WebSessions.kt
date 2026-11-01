// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.csrf.CSRF
import io.ktor.server.routing.Route
import java.net.URI

/** W8 owns HTML handlers. This service owns cookie, session and synchronizer-token policy.
 * CSRF state is derived from a domain-separated cookie hash; no raw session is stored in SQL.
 */
internal class WebSessions(
    private val tokens: TokenService,
    private val config: ServerConfig,
    private val inputs: ServerInputs,
) {
    private val secrets = AuthSecrets(inputs)

    suspend fun authenticate(call: ApplicationCall): AccountPrincipal {
        val raw = call.request.cookies[COOKIE] ?: unauthorized()
        return tokens.authenticate(raw, TokenKind.WEB_SESSION).also { setCookie(call, raw) }
    }

    fun installFormProtection(route: Route) {
        route.install(CSRF) {
            originMatchesHost()
            onFailure { forbidden() }
            config.publicUrl?.let { allowOrigin(URI(it.scheme, null, it.host, it.port, null, null, null).toString()) }
        }
    }

    fun setCookie(
        call: ApplicationCall,
        raw: String,
    ) {
        call.response.cookies.append(cookie(COOKIE, raw, (TokenService.SESSION_MS / 1000).toInt()))
    }

    fun clearCookie(call: ApplicationCall) {
        call.response.cookies.append(cookie(COOKIE, "", 0))
    }

    /** Anonymous sign-in/setup forms use a separate cookie and the same Origin/synchronizer checks. */
    fun preAuth(call: ApplicationCall): String {
        val existing = call.request.cookies[PRE_AUTH]
        val raw = existing?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{43}")) } ?: secrets.secret()
        call.response.cookies.append(cookie(PRE_AUTH, raw, 3600))
        return csrf(raw)
    }

    fun csrf(raw: String): String = AuthSecrets.hash("neutrodyne-csrf-v1$raw")

    fun requireForm(
        call: ApplicationCall,
        formToken: String,
        kind: FormSession = FormSession.AUTHENTICATED,
    ) {
        val raw = call.request.cookies[if (kind == FormSession.AUTHENTICATED) COOKIE else PRE_AUTH] ?: forbidden()
        if (!AuthSecrets.same(csrf(raw), formToken)) forbidden()
        val origin =
            call.request.headers
                .getAll(HttpHeaders.Origin)
                ?.singleOrNull() ?: forbidden()
        val parsed = runCatching { URI(origin) }.getOrNull() ?: forbidden()
        if (parsed.rawUserInfo != null || parsed.rawQuery != null || parsed.rawFragment != null ||
            !parsed.rawPath.isNullOrEmpty()
        ) {
            forbidden()
        }
        val allowed =
            config.publicUrl?.let { URI(it.scheme, null, it.host, it.port, null, null, null).toString() }
                ?: "${call.request.local.scheme}://${call.request.headers[HttpHeaders.Host]}"
        if (origin != allowed) forbidden()
    }

    suspend fun requireAdmin(call: ApplicationCall): AccountPrincipal =
        authenticate(call).also {
            if (it.role != AccountRole.ADMIN) {
                forbidden()
            }
        }

    private fun cookie(
        name: String,
        value: String,
        age: Int,
    ): Cookie =
        Cookie(
            name,
            value,
            CookieEncoding.RAW,
            maxAge = age,
            path = "/",
            secure = !config.insecureLan,
            httpOnly = true,
            extensions =
                mapOf("SameSite" to "Strict"),
        )

    companion object {
        const val COOKIE = "nds_session"
        private const val PRE_AUTH = "nds_form"
    }
}

internal enum class FormSession { AUTHENTICATED, ANONYMOUS }
