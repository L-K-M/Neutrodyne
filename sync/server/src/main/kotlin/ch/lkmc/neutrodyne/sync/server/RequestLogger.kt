// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.path
import io.ktor.util.AttributeKey
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** No raw URI, client ID, headers, error message, body or credential enters the event formatter. */
internal class RequestLogger(
    private val format: LogFormat,
    private val metrics: ServerMetrics,
    private val output: (String) -> Unit,
) {
    fun install(application: Application) {
        application.install(
            createApplicationPlugin("SafeRequestLogger") {
                onCall { call -> call.attributes.put(STARTED, System.nanoTime()) }
                on(ResponseSent) { call ->
                    val route = template(call.request.path())
                    val status = call.response.status()?.value ?: 200
                    metrics.request(route, status)
                    // Hash even syntactically valid client IDs: a token may itself be a valid identifier.
                    val id = AuthSecrets.hash(call.callId ?: UUID.randomUUID().toString()).take(24)
                    val pushed = call.attributes.getOrNull(PUSHED) ?: 0
                    val returned = call.attributes.getOrNull(RETURNED) ?: 0
                    val started = call.attributes.getOrNull(STARTED) ?: System.nanoTime()
                    val duration = (System.nanoTime() - started).coerceAtLeast(0) / 1_000_000
                    val event =
                        buildJsonObject {
                            put(
                                "time",
                                java.time.Instant
                                    .now()
                                    .toString(),
                            )
                            put("request_id", id)
                            call.attributes.getOrNull(RateLimits.PRINCIPAL)?.let { put("account_id", it.accountId) }
                            put("route", route)
                            put("status", status)
                            put("duration_ms", duration)
                            put("changes_pushed", pushed)
                            put("records_returned", returned)
                        }
                    output(
                        if (format ==
                            LogFormat.JSON
                        ) {
                            SyncJson.encodeToString(
                                kotlinx.serialization.json.JsonObject
                                    .serializer(),
                                event,
                            )
                        } else {
                            event.entries.joinToString(" ") { "${it.key}=${it.value}" }
                        },
                    )
                }
            },
        )
    }

    companion object {
        private val STARTED = AttributeKey<Long>("request-start-nanos")
        val PUSHED = AttributeKey<Int>("request-changes-count")
        val RETURNED = AttributeKey<Int>("response-record-count")
        private val FIXED =
            setOf(
                "/healthz",
                "/readyz",
                "/.well-known/neutrodyne-sync",
                "/api/v1/auth/login",
                "/api/v1/auth/invite/redeem",
                "/api/v1/auth/link/start",
                "/api/v1/auth/link/token",
                "/api/v1/auth/link/approve",
                "/api/v1/auth/link/confirm",
                "/api/v1/auth/logout",
                "/api/v1/devices",
                "/api/v1/sync",
                "/api/v1/changes",
                "/api/v1/events",
                "/api/v1/account",
                "/api/v1/account/reset",
                "/",
                "/setup",
                "/style.css",
                "/login",
                "/login/code",
                "/logout",
                "/link",
                "/link/preview",
                "/link/confirm",
                "/devices",
                "/devices/rename",
                "/devices/revoke",
                "/sessions/revoke",
                "/library",
                "/account",
                "/account/export",
                "/api/v1/account/export",
                "/account/password",
                "/account/delete",
                "/admin/accounts",
                "/admin/accounts/create",
                "/admin/accounts/action",
                "/admin/server",
            )
        val TEMPLATES = FIXED + setOf("/api/v1/devices/{id}", "unmatched")

        fun template(path: String): String =
            when {
                path in FIXED -> path

                path.startsWith(
                    "/api/v1/devices/",
                ) && '/' !in path.removePrefix("/api/v1/devices/") -> "/api/v1/devices/{id}"

                else -> "unmatched"
            }
    }
}
