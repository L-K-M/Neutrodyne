// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.ProtocolLimits
import ch.lkmc.neutrodyne.sync.protocol.dto.AccountResetRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangesPage
import ch.lkmc.neutrodyne.sync.protocol.dto.DeviceRenameRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.LibraryCountsDto
import ch.lkmc.neutrodyne.sync.protocol.dto.SyncResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.coroutines.CompletableDeferred
import java.nio.file.Files
import kotlin.time.Instant

internal class DeviceRoutes(
    private val devices: DeviceService,
) {
    fun install(route: Route) {
        route.rateLimit(RateLimits.ACCOUNT) {
            get("/api/v1/devices") { call.respond(devices.list(call.principal())) }
            patch("/api/v1/devices/{id}") {
                call.respond(
                    devices.rename(
                        call.principal(),
                        call.parameters["id"] ?: invalidAuth(),
                        call.receive<DeviceRenameRequest>().name,
                    ),
                )
            }
            delete("/api/v1/devices/{id}") {
                devices.revoke(call.principal(), call.parameters["id"] ?: invalidAuth())
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

internal class SyncRoutes(
    private val writer: StoredRecordWriter,
    private val metrics: ServerMetrics = ServerMetrics(),
) {
    fun install(route: Route) {
        route.rateLimit(RateLimits.SYNC_IP) {
            rateLimit(RateLimits.SYNC_ACCOUNT) {
                post("/api/v1/sync") {
                    val result = writer.push(call.principal(), RequestGuards.syncRequest(call))
                    call.attributes.put(RequestLogger.PUSHED, result.results.size)
                    call.attributes.put(RequestLogger.RETURNED, result.page.records.size + result.page.extras.size)
                    metrics.changes(result.results.map { it.status })
                    metrics.returned(result.page.records.size + result.page.extras.size)
                    call.respond(
                        SyncResponse(
                            result.results,
                            result.page.wireRecords(),
                            result.page.cursor,
                            result.page.hasMore,
                            Instant.fromEpochMilliseconds(result.serverTimeMs).toString(),
                        ),
                    )
                }
            }
        }
    }
}

internal class ChangesRoutes(
    private val writer: StoredRecordWriter,
    private val inputs: ServerInputs,
    private val metrics: ServerMetrics = ServerMetrics(),
) {
    fun install(route: Route) {
        route.rateLimit(RateLimits.SYNC_IP) {
            rateLimit(RateLimits.SYNC_ACCOUNT) {
                get("/api/v1/changes") {
                    val rawLimit = call.request.queryParameters["limit"]
                    val limit =
                        rawLimit?.toIntOrNull() ?: if (rawLimit == null) ProtocolLimits.MAX_RECORDS else invalidAuth()
                    val page =
                        writer.changes(
                            call.principal(),
                            call.request.queryParameters["since"],
                            limit.coerceIn(1, ProtocolLimits.MAX_RECORDS),
                        )
                    call.attributes.put(RequestLogger.RETURNED, page.records.size + page.extras.size)
                    metrics.returned(page.records.size + page.extras.size)
                    call.respond(
                        ChangesPage(
                            page.wireRecords(),
                            page.cursor,
                            page.hasMore,
                            Instant.fromEpochMilliseconds(inputs.wallMs()).toString(),
                        ),
                    )
                }
            }
        }
    }
}

internal class AccountRoutes(
    private val accounts: AccountService,
    private val writer: StoredRecordWriter,
    private val backups: AccountBackups,
) {
    fun install(route: Route) {
        route.rateLimit(RateLimits.ACCOUNT) {
            get("/api/v1/account/export") {
                call.respondAccountBackup(backups, call.principal())
            }
            get("/api/v1/account") { call.respond(accounts.summary(call.principal())) }
            post("/api/v1/account/reset") {
                val request = call.receive<AccountResetRequest>()
                when (request.mode) {
                    "library" -> {
                        writer.resetLibrary(call.principal(), request)
                        call.respond(accounts.summary(call.principal()))
                    }

                    "purge" -> {
                        val before = accounts.summary(call.principal())
                        accounts.purge(call.principal(), request.confirm)
                        call.respond(before.copy(counts = LibraryCountsDto(0, 0, 0, 0, 0), records = 0))
                    }

                    else -> {
                        invalidAuth()
                    }
                }
            }
        }
    }
}

private fun RecordPage.wireRecords() = (records + extras).map { it.raw.toDto(it.key.id, it.seq, it.byDevice) }

/** An engine may schedule the producer after respond returns. Retain the input and account lease. */
internal suspend fun ApplicationCall.respondAccountBackup(
    backups: AccountBackups,
    principal: AccountPrincipal,
) {
    backups.authorizeDownload(principal)
    backups.download(principal) { path ->
        Files.newInputStream(path).use { input ->
            val completed = CompletableDeferred<Unit>()
            response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=neutrodyne-backup.zip")
            respondOutputStream(ContentType.Application.Zip) {
                try {
                    input.copyTo(this)
                } finally {
                    completed.complete(Unit)
                }
            }
            completed.await()
        }
    }
}
