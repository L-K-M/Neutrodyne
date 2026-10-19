// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.ProtocolException
import ch.lkmc.neutrodyne.sync.protocol.SyncErrorCode
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import ch.lkmc.neutrodyne.sync.protocol.dto.ProblemDto
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.CompressionConfig
import io.ktor.server.plugins.compression.excludeContentType
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import java.util.UUID
import kotlin.time.Clock

/**
 * Ktor application module (10 Server architecture, Module and classes): installs the request
 * pipeline, transport guard, health/discovery, authenticated API, web and event routes.
 * Metrics belong exclusively to the separately configured listener.
 */
internal class ServerModule(
    private val config: ServerConfig,
    private val serverVersion: String,
    private val clock: Clock = Clock.System,
    private val writeProbe: suspend () -> Boolean = { true },
    private val services: ServerServices? = null,
    private val requestLog: ((String) -> Unit)? = null,
) {
    fun install(application: Application) {
        with(application) {
            RequestLogger(config.logFormat, services?.metrics ?: ServerMetrics()) { line ->
                if (requestLog != null) {
                    requestLog.invoke(line)
                } else if (config.logLevel in setOf(LogLevel.TRACE, LogLevel.DEBUG, LogLevel.INFO)) {
                    println(line)
                }
            }.install(this)
            install(CallId) {
                retrieveFromHeader("X-Request-Id")
                generate { UUID.randomUUID().toString() }
                verify { id ->
                    id.length in 1..64 &&
                        id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._-" }
                }
                replyToHeader("X-Request-Id")
            }
            install(DefaultHeaders) {
                header("X-Content-Type-Options", "nosniff")
                header("Referrer-Policy", "no-referrer")
                header("Cache-Control", "no-store")
                header(
                    "Content-Security-Policy",
                    "default-src 'self'; script-src 'none'; style-src 'self'; img-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'",
                )
            }
            install(ContentNegotiation) { json(SyncJson) }
            install(Compression) {
                // RequestGuards owns bounded request decompression.
                mode = CompressionConfig.Mode.CompressResponse
                gzip()
                excludeContentType(ContentType.Text.EventStream)
            }
            install(StatusPages) {
                exception<AuthThrottled> { call, failure ->
                    call.response.headers.append(HttpHeaders.RetryAfter, failure.retrySeconds.toString())
                    call.problem(HttpStatusCode.TooManyRequests, "rate_limited")
                }
                exception<BoundaryException> { call, failure -> call.problem(failure.status, failure.code) }
                exception<ProtocolException> { call, failure ->
                    val status =
                        when (failure.code) {
                            SyncErrorCode.PAYLOAD_TOO_LARGE -> HttpStatusCode.PayloadTooLarge
                            SyncErrorCode.CURSOR_EXPIRED -> HttpStatusCode.Gone
                            SyncErrorCode.PROTOCOL_TOO_OLD -> HttpStatusCode.UpgradeRequired
                            SyncErrorCode.UNAUTHORIZED -> HttpStatusCode.Unauthorized
                            SyncErrorCode.FORBIDDEN -> HttpStatusCode.Forbidden
                            SyncErrorCode.RATE_LIMITED -> HttpStatusCode.TooManyRequests
                            SyncErrorCode.NOT_FOUND -> HttpStatusCode.NotFound
                            SyncErrorCode.INSECURE_TRANSPORT -> InsecureTransportGuard.MISDIRECTED_REQUEST
                            else -> HttpStatusCode.BadRequest
                        }
                    call.problem(status, failure.code.wire)
                }
                exception<BadRequestException> { call, _ -> call.problem(HttpStatusCode.BadRequest, "invalid") }
                exception<UnsupportedMediaTypeException> { call, _ ->
                    call.problem(HttpStatusCode.UnsupportedMediaType, "invalid")
                }
                exception<Throwable> { call, failure ->
                    if (failure is CancellationException) throw failure
                    call.problem(HttpStatusCode.InternalServerError, "internal_error")
                }
                status(HttpStatusCode.TooManyRequests) { call, status -> call.problem(status, "rate_limited") }
            }
            RateLimits(config).install(this)
            InsecureTransportGuard(config).install(this)
            RequestGuards().install(this)
            val authRoutes = services?.let { AuthRoutes(it, config) }
            authRoutes?.authenticate(this)
            if (services != null) monitor.subscribe(ApplicationStopping) { services.events.close() }
            routing {
                HealthRoutes(config.dataDir, writeProbe = writeProbe).install(this)
                rateLimit(RateLimits.PUBLIC) {
                    WellKnownRoutes(
                        serverVersion,
                        clock,
                        services?.let { it::features } ?: { emptyList() },
                    ).install(this)
                }
                if (services != null) {
                    authRoutes?.install(this)
                    DeviceRoutes(services.devices).install(this)
                    SyncRoutes(services.records, services.metrics).install(this)
                    ChangesRoutes(services.records, services.inputs, services.metrics).install(this)
                    AccountRoutes(services.accounts, services.records).install(this)
                    EventsRoutes(services.events, services.metrics, services.tokens::valid).install(this)
                    AdminWeb(services, config, serverVersion).install(this)
                }
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

/** Only fixed status/code text reaches clients; exception messages can contain secrets. */
private suspend fun io.ktor.server.application.ApplicationCall.problem(
    status: HttpStatusCode,
    code: String,
) {
    respondText(
        SyncJson.encodeToString(
            ProblemDto.serializer(),
            ProblemDto("urn:neutrodyne:sync:$code", status.description, status.value, code),
        ),
        InsecureTransportGuard.PROBLEM_JSON,
        status,
    )
}
