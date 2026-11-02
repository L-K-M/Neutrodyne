// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.html.ButtonType
import kotlinx.html.FORM
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.head
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.link
import kotlinx.html.meta
import kotlinx.html.nav
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.select
import kotlinx.html.title
import kotlinx.html.ul
import kotlin.time.Instant

/** All untrusted strings use the HTML builder's text/attribute escaping; no unsafe HTML or script. */
internal class AdminWeb(
    private val services: ServerServices,
    private val config: ServerConfig,
    private val version: String,
) {
    fun install(root: Route) {
        root.rateLimit(RateLimits.WEB) {
            get("/style.css") {
                call.respondText(checkNotNull(javaClass.getResource("/admin.css")).readText(), ContentType.Text.CSS)
            }
            get("/") { signIn(call) }
            get("/setup") {
                if (!services.setup.available()) missingAuth()
                val csrf = services.sessions.preAuth(call)
                page(call, "Set up Neutrodyne Sync") {
                    formFor("/setup", csrf, "Create administrator") {
                        field("code", "Setup code")
                        field("username", "Username")
                        field("password", "Password (optional)", InputType.password)
                    }
                }
            }
            get("/link") {
                val csrf = session(call)
                page(
                    call,
                    "Approve a device",
                ) { formFor("/link/preview", csrf, "Check device") { field("code", "Link code") } }
            }
            get("/devices") { devices(call) }
            get("/library") {
                session(call)
                val summary = services.accounts.summary(call.principal())
                val rawAfter = call.request.queryParameters["after"]
                val after = rawAfter?.toLongOrNull() ?: if (rawAfter == null) 0L else invalidAuth()
                val library = services.admin.library(call.principal(), after)
                val lastSync =
                    services.devices
                        .list(call.principal())
                        .mapNotNull { it.lastSeenAt }
                        .maxOrNull()
                page(call, "Library") {
                    p {
                        +(
                            "${summary.records} records; ${summary.counts.podcasts} subscriptions; " +
                                "${summary.counts.groups} groups"
                        )
                    }
                    p { +"Last sync: ${lastSync ?: "never"}" }
                    ul { library.titles.forEach { li { +it } } }
                    library.next?.let { a(href = "/library?after=$it") { +"Next page" } }
                }
            }
            get("/account/export") {
                session(call)
                call.respondAccountBackup(services.backups, call.principal())
            }
            get("/account") {
                val csrf = session(call)
                val summary = services.accounts.summary(call.principal())
                val passwordAt = services.admin.passwordSetAt(call.principal())
                page(call, "Account") {
                    p { +summary.username }
                    passwordAt?.let { p { +"Password set on ${date(it)}" } }
                    a(href = "/devices") { +"Review browser sessions" }
                    a(href = "/account/export") { +"Download my data" }
                    formFor("/account/password", csrf, "Set password") {
                        field("current", "Current password", InputType.password)
                        field("password", "New password (10–256 characters)", InputType.password)
                    }
                    formFor("/account/delete", csrf, "Delete my data") {
                        field("confirm", "Type DELETE to delete your library and revoke all sessions")
                    }
                }
            }
            get("/admin/accounts") { accounts(call) }
            get("/admin/server") { server(call) }
            // Ktor Origin protection complements the mandatory synchronizer/Origin checks in form().
            route("") {
                services.sessions.installFormProtection(this)
                rateLimit(RateLimits.LOGIN) {
                    post("/login") {
                        val form = form(call, FormSession.ANONYMOUS)
                        val raw =
                            services.auth.loginWeb(
                                form.required("username"),
                                form.required("password"),
                                "Web browser",
                                call.clientIp(config),
                            )
                        services.sessions.setCookie(call, raw)
                        call.respondRedirect("/devices")
                    }
                    post("/setup") {
                        val form = form(call, FormSession.ANONYMOUS)
                        val raw =
                            services.setup.createAdminWeb(
                                form.required(
                                    "code",
                                ),
                                form.required("username"),
                                form["password"]?.takeIf {
                                    it.isNotEmpty()
                                },
                            )
                        services.sessions.setCookie(call, raw)
                        call.respondRedirect("/devices")
                    }
                }
                rateLimit(RateLimits.LINK_START) {
                    post("/login/code") {
                        form(call, FormSession.ANONYMOUS)
                        val result = services.links.startWeb(call.clientIp(config))
                        linkCookie(call, LINK, result.deviceCode)
                        linkCookie(call, CODE, result.userCode)
                        linkCookie(call, INTERVAL, "5")
                        call.respondRedirect("/")
                    }
                }
                post("/logout") {
                    form(call)
                    services.tokens.logout(call.principal())
                    services.sessions.clearCookie(call)
                    call.respondRedirect("/")
                }
                post("/link/preview") {
                    val form = form(call)
                    val code = form.required("code")
                    val preview = services.links.preview(call.principal(), code, call.clientIp(config))
                    page(call, "Confirm device") {
                        p { +"${preview.deviceName} (${preview.platform}, ${preview.appVersion})" }
                        p {
                            +(
                                if (preview.purpose ==
                                    "web"
                                ) {
                                    "This signs a browser in to your account."
                                } else {
                                    "This device gets your whole library."
                                }
                            )
                        }
                        if (!preview.sameNetwork) {
                            p(classes = "warning") {
                                +"Requested from a different network. Only approve a device you are holding."
                            }
                        }
                        formFor("/link/confirm", csrf(call), "Approve") {
                            hidden("code", code)
                            button(type = ButtonType.submit, name = "decision") {
                                value = "deny"
                                +"Deny"
                            }
                        }
                    }
                }
                post("/link/confirm") {
                    val form = form(call)
                    services.links.confirm(call.principal(), form.required("code"), form["decision"] != "deny")
                    call.respondRedirect("/devices")
                }
                post("/devices/rename") {
                    val form = form(call)
                    services.devices.rename(call.principal(), form.required("id"), form.required("name"))
                    call.respondRedirect("/devices")
                }
                post("/devices/revoke") {
                    val form = form(call)
                    services.devices.revoke(call.principal(), form.required("id"))
                    call.respondRedirect("/devices")
                }
                post("/sessions/revoke") {
                    val form = form(call)
                    services.admin.revokeSession(call.principal(), form.required("id"))
                    call.respondRedirect("/devices")
                }
                post("/account/password") {
                    val form = form(call)
                    services.auth.changePassword(call.principal(), form["current"], form.required("password"))
                    call.respondRedirect("/account")
                }
                post("/account/delete") {
                    val form = form(call)
                    services.accounts.purge(call.principal(), form.required("confirm"))
                    services.sessions.clearCookie(call)
                    call.respondRedirect("/")
                }
                post("/admin/accounts/create") {
                    val form = form(call, access = WebAccess.ADMIN)
                    val role =
                        when (form["role"]) {
                            "admin" -> AccountRole.ADMIN
                            "user" -> AccountRole.USER
                            else -> invalidAuth()
                        }
                    val owner = services.auth.createAccount(form.required("username"), role, actor = call.principal())
                    invitePage(call, services.invites.create(owner.id, call.principal()))
                }
                post("/admin/accounts/action") {
                    val form = form(call, access = WebAccess.ADMIN)
                    val id = form.required("id").toLongOrNull() ?: invalidAuth()
                    if (form["action"] == "invite") {
                        invitePage(call, services.invites.create(id, call.principal()))
                        return@post
                    }
                    if (form["action"] == "reset_password") {
                        invitePage(call, services.admin.resetPasswordInvite(call.principal(), id))
                        return@post
                    }
                    val action =
                        AccountAction.entries.singleOrNull { it.name.lowercase() == form["action"] } ?: invalidAuth()
                    services.admin.change(id, action, form["confirm"].orEmpty(), call.principal())
                    call.respondRedirect("/admin/accounts")
                }
            }
        }
    }

    private suspend fun signIn(call: ApplicationCall) {
        val pending = call.request.cookies[LINK]
        if (pending != null) {
            when (val result = services.links.pollWeb(pending)) {
                is LinkPoll.Web -> {
                    linkCookie(call, LINK, "", 0)
                    linkCookie(call, CODE, "", 0)
                    linkCookie(call, INTERVAL, "", 0)
                    services.sessions.setCookie(call, result.cookie)
                    call.respondRedirect("/devices")
                }

                is LinkPoll.Error -> {
                    if (result.code in setOf("authorization_pending", "slow_down")) {
                        var interval =
                            call.request.cookies[INTERVAL]
                                ?.toIntOrNull()
                                ?.coerceIn(5, 600) ?: 5
                        if (result.code == "slow_down") interval = (interval + 5).coerceAtMost(600)
                        linkCookie(call, INTERVAL, interval.toString())
                        page(call, "Sign in with a code", refresh = interval) {
                            p { +"In your linked app, open Link another device and enter:" }
                            p { +(call.request.cookies[CODE] ?: "") }
                            p { +"Waiting for approval. This page refreshes automatically." }
                        }
                    } else {
                        linkCookie(call, LINK, "", 0)
                        page(call, "Sign-in code ended") {
                            p { +result.code }
                            a(href = "/") { +"Try again" }
                        }
                    }
                }

                is LinkPoll.Device -> {
                    error("Browser returned device credential")
                }
            }
            return
        }
        val csrf = services.sessions.preAuth(call)
        page(call, "Sign in") {
            formFor("/login", csrf, "Sign in") {
                field("username", "Username")
                field("password", "Password", InputType.password)
            }
            formFor("/login/code", csrf, "Sign in with a code from the app") {}
            a(href = "/setup") { +"Set up the first administrator" }
        }
    }

    private suspend fun devices(call: ApplicationCall) {
        val csrf = session(call)
        val devices = services.devices.list(call.principal())
        val sessions = services.admin.sessions(call.principal())
        page(call, "Devices and browser sessions") {
            devices.forEach { device ->
                h2 { +device.name }
                p {
                    +"${device.platform} ${device.appVersion}"
                    +"; last seen ${device.lastSeenAt ?: "never"}; stale: ${device.stale}"
                }
                formFor("/devices/rename", csrf, "Rename") {
                    hidden("id", device.id)
                    field("name", "Device name")
                }
                formFor("/devices/revoke", csrf, "Revoke device") { hidden("id", device.id) }
            }
            sessions.forEach { browser ->
                p {
                    +"${browser.label}; last used ${date(
                        browser.lastUsed,
                    )}${if (browser.current) " (this browser)" else ""}"
                }
                formFor("/sessions/revoke", csrf, "Sign out browser") { hidden("id", browser.id) }
            }
            formFor("/logout", csrf, "Sign out") {}
        }
    }

    private suspend fun accounts(call: ApplicationCall) {
        val csrf = session(call, access = WebAccess.ADMIN)
        val owners = services.admin.accounts(call.principal())
        page(call, "Accounts") {
            formFor("/admin/accounts/create", csrf, "Create account and invite") {
                field("username", "Username")
                label {
                    +"Role "
                    select {
                        name = "role"
                        option {
                            value = "user"
                            +"User"
                        }
                        option {
                            value = "admin"
                            +"Admin"
                        }
                    }
                }
            }
            owners.forEach { row ->
                h2 { +row.account.username }
                p {
                    +"${row.account.role.name.lowercase()}; ${row.devices} devices; "
                    +"last active ${date(row.lastSeen)}; "
                    +(if (row.account.disabledAt == null) "enabled" else "disabled")
                }
                formFor("/admin/accounts/action", csrf, "Apply") {
                    hidden("id", row.account.id.toString())
                    label {
                        +"Action "
                        select {
                            name = "action"
                            listOf("invite", "disable", "enable", "reset_password", "delete").forEach {
                                option {
                                    value =
                                        it
                                    ; +it
                                }
                            }
                        }
                    }
                    field("confirm", "To delete, type the account username")
                }
            }
        }
    }

    private suspend fun server(call: ApplicationCall) {
        session(call, access = WebAccess.ADMIN)
        val audit = services.audit.recent(call.principal())
        val stats = services.metrics.snapshot()
        val update = services.updates.notice
        page(call, "Server status") {
            p {
                +"Version: $version; database size: ${stats.databaseBytes} bytes; "
                +"accounts: ${stats.accounts}; records: ${stats.records}"
            }
            p {
                +"Retention: tombstones ${config.retentionTombstoneDays} days; "
                +"unsubscribed ${config.retentionUnsubscribedDays} days; "
                +"empty episodes ${config.retentionEmptyEpisodeDays} days"
            }
            p { +"Last backup: ${date(stats.backupAt)}; result: ${stats.backupResult}" }
            update?.let {
                p {
                    +"Neutrodyne Sync ${it.version} is available. "
                    a(href = it.releaseUrl) { +"Open release" }
                }
            }
            h2 { +"Audit log" }
            ul {
                audit.forEach {
                    li {
                        +"${date(
                            it.at,
                        )} account ${it.accountId ?: "anonymous"}: ${it.event} ${it.ip.orEmpty()}"
                    }
                }
            }
        }
    }

    private suspend fun invitePage(
        call: ApplicationCall,
        code: String,
    ) = page(call, "One-time invite") {
        p { +"Shown once, expires in 24 hours:" }
        p { +code }
        a(href = "/admin/accounts") { +"Accounts" }
    }

    private suspend fun session(
        call: ApplicationCall,
        access: WebAccess = WebAccess.ACCOUNT,
    ): String {
        val actor =
            if (access ==
                WebAccess.ADMIN
            ) {
                services.sessions.requireAdmin(call)
            } else {
                services.sessions.authenticate(call)
            }
        call.attributes.put(RateLimits.PRINCIPAL, actor)
        return csrf(call)
    }

    private fun csrf(call: ApplicationCall) =
        services.sessions.csrf(call.request.cookies[WebSessions.COOKIE] ?: unauthorized())

    private suspend fun form(
        call: ApplicationCall,
        kind: FormSession = FormSession.AUTHENTICATED,
        access: WebAccess = WebAccess.ACCOUNT,
    ): Parameters {
        if (kind == FormSession.AUTHENTICATED) session(call, access)
        if (RequestGuards.bodySize(call) > MAX_FORM_BYTES) {
            throw BoundaryException(HttpStatusCode.PayloadTooLarge, "payload_too_large")
        }
        val form = call.receiveParameters()
        // Reject duplicate parameters rather than choosing a different value in two layers.
        if (form.entries().any { it.value.size != 1 }) invalidAuth()
        services.sessions.requireForm(call, form["csrf"].orEmpty(), kind)
        return form
    }

    private suspend fun page(
        call: ApplicationCall,
        title: String,
        refresh: Int? = null,
        content: FlowContent.() -> Unit,
    ) {
        call.respondHtml(HttpStatusCode.OK) {
            attributes["lang"] = "en"
            head {
                meta { charset = "utf-8" }
                meta {
                    name = "viewport"
                    this.content = "width=device-width, initial-scale=1"
                }
                refresh?.let {
                    meta {
                        httpEquiv = "refresh"
                        this.content = it.toString()
                    }
                }
                title { +title }
                link(rel = "stylesheet", href = "/style.css")
            }
            body {
                if (config.insecureLan) p(classes = "warning") { +"This server is not using HTTPS" }
                nav {
                    listOf("/devices", "/library", "/link", "/account", "/admin/accounts", "/admin/server").forEach {
                        a(href = it) { +it.removePrefix("/") }
                        +" "
                    }
                }
                h1 { +title }
                content()
            }
        }
    }

    private fun linkCookie(
        call: ApplicationCall,
        name: String,
        value: String,
        age: Int = 600,
    ) {
        call.response.cookies.append(
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
            ),
        )
    }

    private enum class WebAccess { ACCOUNT, ADMIN }

    private companion object {
        const val MAX_FORM_BYTES = 16_384
        const val LINK = "nds_link"
        const val CODE = "nds_link_code"
        const val INTERVAL = "nds_link_interval"
    }
}

private fun Parameters.required(name: String): String = this[name] ?: invalidAuth()

private fun date(ms: Long?): String = ms?.let { Instant.fromEpochMilliseconds(it).toString() } ?: "never"

private fun FlowContent.formFor(
    action: String,
    csrf: String,
    submit: String,
    fields: FORM.() -> Unit,
) {
    form(action = action, method = FormMethod.post) {
        hidden("csrf", csrf)
        fields()
        button(type = ButtonType.submit) { +submit }
    }
}

private fun FORM.hidden(
    name: String,
    value: String,
) {
    input(type = InputType.hidden, name = name) { this.value = value }
}

private fun FORM.field(
    name: String,
    caption: String,
    type: InputType = InputType.text,
) {
    label {
        +"$caption "
        input(type = type, name = name) {
            if (type ==
                InputType.password
            ) {
                autoComplete = "off"
                attributes["maxlength"] = "256"
            }
        }
    }
}
