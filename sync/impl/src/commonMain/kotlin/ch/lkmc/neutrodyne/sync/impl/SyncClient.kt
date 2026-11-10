// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.common.HttpClientKind
import ch.lkmc.neutrodyne.core.network.NeutrodyneHttpClients
import ch.lkmc.neutrodyne.sync.protocol.PROTOCOL_VERSION
import ch.lkmc.neutrodyne.sync.protocol.ProtocolException
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import ch.lkmc.neutrodyne.sync.protocol.dto.AccountResetRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.AccountSummary
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeDto
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangesPage
import ch.lkmc.neutrodyne.sync.protocol.dto.DeviceDto
import ch.lkmc.neutrodyne.sync.protocol.dto.DeviceInfoDto
import ch.lkmc.neutrodyne.sync.protocol.dto.DeviceRenameRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.DiscoveryDocument
import ch.lkmc.neutrodyne.sync.protocol.dto.InviteRedeemRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.LinkApproveRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.LinkApproveResponse
import ch.lkmc.neutrodyne.sync.protocol.dto.LinkConfirmRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.LinkStartRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.LinkStartResponse
import ch.lkmc.neutrodyne.sync.protocol.dto.LinkTokenRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.LoginRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.OAuthErrorDto
import ch.lkmc.neutrodyne.sync.protocol.dto.ProblemDto
import ch.lkmc.neutrodyne.sync.protocol.dto.SyncRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.SyncResponse
import ch.lkmc.neutrodyne.sync.protocol.dto.TokenResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** The transport the engine and link flow use; `KtorSyncClient` is the production implementation. */
interface SyncClient {
    /**
     * An isolated view for transports with mutable authentication. Already-bound transports
     * return null, so callers retain the original instance (including decorated test clients).
     */
    fun withCredentials(
        serverUrl: String,
        token: String,
    ): SyncClient? = null

    suspend fun discover(baseUrl: String): DiscoveryDocument

    suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
        device: DeviceInfoDto,
    ): TokenResponse

    suspend fun redeemInvite(
        baseUrl: String,
        code: String,
        device: DeviceInfoDto,
    ): TokenResponse

    suspend fun linkStart(
        baseUrl: String,
        device: DeviceInfoDto,
    ): LinkStartResponse

    suspend fun linkToken(
        baseUrl: String,
        deviceCode: String,
    ): TokenResponse

    suspend fun linkApprove(
        baseUrl: String,
        token: String,
        userCode: String,
    ): LinkApproveResponse

    suspend fun linkConfirm(
        baseUrl: String,
        token: String,
        userCode: String,
        approve: Boolean,
    )

    suspend fun logout()

    suspend fun devices(): List<DeviceDto>

    suspend fun renameDevice(
        deviceId: String,
        name: String,
    ): DeviceDto

    suspend fun revokeDevice(deviceId: String)

    suspend fun account(): AccountSummary

    suspend fun sync(
        since: String?,
        changes: List<ChangeDto>,
    ): SyncResponse

    suspend fun changes(
        since: String?,
        limit: Int,
    ): ChangesPage

    suspend fun reset(
        confirm: String,
        mode: String,
        beforeCursor: String?,
    ): AccountSummary
}

/** A [SyncClient] failure with the server's status and problem code (10 Errors). */
class SyncTransportException(
    val status: Int,
    val code: String?,
    message: String,
    /** `Retry-After` seconds for `429` responses; the events stream obeys it before reconnecting. */
    val retryAfterSeconds: Long? = null,
) : Exception(message)

/**
 * [SyncClient] over the `SYNC` Ktor client (10 Protocol). The bearer token is added per request,
 * never through an interceptor shared with other clients. Bodies are JSON; the server's gzip
 * response compression is handled by the engine's client.
 *
 * [tokenForBase], when bound, resolves the credential for the exact base URL the request will
 * hit: each authenticated call fixes its [AuthContext] once, so a saved-address change between
 * the URL read and the credential read cannot pair one server's URL with another's token.
 */
class KtorSyncClient(
    private val clients: NeutrodyneHttpClients,
    private val tokenProvider: suspend () -> String?,
    private val baseUrlProvider: suspend () -> String,
    private val tokenForBase: (suspend (String) -> String?)? = null,
) : SyncClient {
    private val client: HttpClient = clients.client(HttpClientKind.SYNC)

    override fun withCredentials(
        serverUrl: String,
        token: String,
    ): SyncClient = KtorSyncClient(clients, tokenProvider = { token }, baseUrlProvider = { serverUrl })

    override suspend fun discover(baseUrl: String): DiscoveryDocument =
        getJson(baseUrl.trimEnd('/') + "/.well-known/neutrodyne-sync", auth = false)

    override suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
        device: DeviceInfoDto,
    ): TokenResponse = postJson(baseUrl, "/api/v1/auth/login", LoginRequest(username, password, device), auth = false)

    override suspend fun redeemInvite(
        baseUrl: String,
        code: String,
        device: DeviceInfoDto,
    ): TokenResponse = postJson(baseUrl, "/api/v1/auth/invite/redeem", InviteRedeemRequest(code, device), auth = false)

    override suspend fun linkStart(
        baseUrl: String,
        device: DeviceInfoDto,
    ): LinkStartResponse = postJson(baseUrl, "/api/v1/auth/link/start", LinkStartRequest(device), auth = false)

    override suspend fun linkToken(
        baseUrl: String,
        deviceCode: String,
    ): TokenResponse {
        val response =
            request(baseUrl, "/api/v1/auth/link/token", LinkTokenRequest(deviceCode), auth = false)
        if (response.status == HttpStatusCode.BadRequest) {
            val error = SyncJson.decodeFromString(OAuthErrorDto.serializer(), response.bodyAsText())
            throw SyncProblemException(error.error)
        }
        if (!response.status.isSuccess()) throw problem(response)
        return SyncJson.decodeFromString(TokenResponse.serializer(), response.bodyAsText())
    }

    override suspend fun linkApprove(
        baseUrl: String,
        token: String,
        userCode: String,
    ): LinkApproveResponse = postJson(baseUrl, "/api/v1/auth/link/approve", LinkApproveRequest(userCode), token)

    override suspend fun linkConfirm(
        baseUrl: String,
        token: String,
        userCode: String,
        approve: Boolean,
    ) {
        postNoContent(
            baseUrl,
            "/api/v1/auth/link/confirm",
            LinkConfirmRequest(userCode, approve),
            token,
        )
    }

    override suspend fun logout() {
        val ctx = authContext()
        postNoContent(ctx.baseUrl, "/api/v1/auth/logout", Unit, ctx.token)
    }

    override suspend fun devices(): List<DeviceDto> {
        val ctx = authContext()
        return getJson(ctx.baseUrl.trimEnd('/') + "/api/v1/devices", ctx.token)
    }

    override suspend fun renameDevice(
        deviceId: String,
        name: String,
    ): DeviceDto {
        val ctx = authContext()
        return patchJson(ctx.baseUrl, "/api/v1/devices/$deviceId", DeviceRenameRequest(name), ctx.token)
    }

    override suspend fun revokeDevice(deviceId: String) {
        val ctx = authContext()
        delete("${ctx.baseUrl}/api/v1/devices/$deviceId", ctx.token)
    }

    override suspend fun account(): AccountSummary {
        val ctx = authContext()
        return getJson(ctx.baseUrl.trimEnd('/') + "/api/v1/account", ctx.token)
    }

    override suspend fun sync(
        since: String?,
        changes: List<ChangeDto>,
    ): SyncResponse {
        val ctx = authContext()
        return postJson(ctx.baseUrl, "/api/v1/sync", SyncRequest(since, changes), ctx.token)
    }

    override suspend fun changes(
        since: String?,
        limit: Int,
    ): ChangesPage {
        val ctx = authContext()
        val base = ctx.baseUrl.trimEnd('/')
        return getJson("$base/api/v1/changes?since=${since?.encodeURLParameter() ?: ""}&limit=$limit", ctx.token)
    }

    override suspend fun reset(
        confirm: String,
        mode: String,
        beforeCursor: String?,
    ): AccountSummary {
        val ctx = authContext()
        return postJson(
            ctx.baseUrl,
            "/api/v1/account/reset",
            AccountResetRequest(confirm, mode, beforeCursor),
            ctx.token,
        )
    }

    /**
     * One authenticated request's endpoint and credential, fixed together: the token is loaded
     * for the exact base URL the request targets, so a saved-address switch between the two
     * reads can never pair one server's URL with another server's token.
     */
    private class AuthContext(
        val baseUrl: String,
        val token: String?,
    ) {
        override fun toString(): String = "AuthContext(baseUrl=$baseUrl, token=<redacted>)"
    }

    private suspend fun authContext(): AuthContext {
        val baseUrl = baseUrlProvider()
        // With `tokenForBase` bound the credential is looked up for the URL just read; a null
        // result is a genuinely absent token, never a cue to re-resolve through `tokenProvider`
        // — that re-reads the saved address and could return another host's token. Callers
        // constructed without it (test fixtures) keep the decoupled provider lookup.
        val token =
            if (tokenForBase != null) {
                tokenForBase.invoke(baseUrl)
            } else {
                tokenProvider()
            }
        return AuthContext(baseUrl, token)
    }

    // Endpoints answering a bare 204 must not decode a body; `auth` is explicit — an
    // authenticated call sends the token it was given (the request context's, possibly none),
    // never a refetched one.
    private suspend inline fun <reified B : Any> postNoContent(
        baseUrl: String,
        path: String,
        body: B,
        token: String? = null,
        auth: Boolean = true,
    ) {
        val response = request(baseUrl, path, body, auth, token)
        if (!response.status.isSuccess()) throw problem(response)
    }

    // The body parameter is reified through every caller: `SyncJson.encodeToString` resolves
    // the serializer statically, so a statically `Any`-typed body would find no serializer.
    private suspend inline fun <reified B : Any> request(
        baseUrl: String,
        path: String,
        body: B,
        auth: Boolean,
        token: String? = null,
    ): io.ktor.client.statement.HttpResponse {
        val url = baseUrl.trimEnd('/') + path
        return client.post(url) {
            contentType(ContentType.Application.Json)
            header("Neutrodyne-Sync-Protocol", PROTOCOL_VERSION.toString())
            if (auth) token?.let { header("Authorization", "Bearer $it") }
            setBody(SyncJson.encodeToString(body))
        }
    }

    private suspend inline fun <reified B : Any, reified T> postJson(
        baseUrl: String,
        path: String,
        body: B,
        token: String? = null,
        auth: Boolean = true,
    ): T {
        val response = request(baseUrl, path, body, auth, token)
        if (!response.status.isSuccess()) throw problem(response)
        return SyncJson.decodeFromString<T>(response.bodyAsText())
    }

    private suspend inline fun <reified B : Any, reified T> patchJson(
        baseUrl: String,
        path: String,
        body: B,
        token: String?,
    ): T {
        val response =
            client.patch(baseUrl.trimEnd('/') + path) {
                contentType(ContentType.Application.Json)
                header("Neutrodyne-Sync-Protocol", PROTOCOL_VERSION.toString())
                token?.let { header("Authorization", "Bearer $it") }
                setBody(SyncJson.encodeToString(body))
            }
        if (!response.status.isSuccess()) throw problem(response)
        return SyncJson.decodeFromString<T>(response.bodyAsText())
    }

    private suspend inline fun <reified T> getJson(
        url: String,
        token: String? = null,
        auth: Boolean = true,
    ): T {
        val response =
            client.get(url) {
                header("Neutrodyne-Sync-Protocol", PROTOCOL_VERSION.toString())
                if (auth) token?.let { header("Authorization", "Bearer $it") }
            }
        if (!response.status.isSuccess()) throw problem(response)
        return SyncJson.decodeFromString<T>(response.bodyAsText())
    }

    private suspend fun problem(response: io.ktor.client.statement.HttpResponse): SyncTransportException {
        val text = runCatching { response.bodyAsText() }.getOrDefault("")
        val code =
            runCatching { SyncJson.decodeFromString(ProblemDto.serializer(), text).code }.getOrNull()
        return SyncTransportException(
            response.status.value,
            code,
            "sync request failed: ${response.status}",
            response.headers["Retry-After"]?.toLongOrNull(),
        )
    }

    private suspend fun delete(
        url: String,
        token: String?,
    ) {
        val response =
            client.delete(url) {
                header("Neutrodyne-Sync-Protocol", PROTOCOL_VERSION.toString())
                token?.let { header("Authorization", "Bearer $it") }
            }
        if (!response.status.isSuccess()) throw problem(response)
    }
}

/** An RFC 8628 link-token error (`authorization_pending`, `slow_down`, …). */
class SyncProblemException(
    val error: String,
) : Exception(error)
