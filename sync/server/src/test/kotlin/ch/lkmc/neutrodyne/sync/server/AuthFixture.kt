// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import ch.lkmc.neutrodyne.sync.protocol.dto.DeviceInfoDto
import ch.lkmc.neutrodyne.sync.protocol.dto.TokenResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.application.Application
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.UUID

internal class AuthFixture(
    val config: ServerConfig = loadValidConfig().copy(dataDir = tempDataDir()),
) : AutoCloseable {
    @Volatile var now = FIXED_INSTANT.toEpochMilliseconds()
    private val secure = productionInputs()
    val inputs = ServerInputs({ now }, secure.randomBytes)
    val services: ServerServices
    val events = EventBus({ services.tokens.valid(it) })
    val store = SqliteSyncStore(config.dataDir, events::publish)

    init {
        services = ServerServices(store, config, events, inputs)
    }

    suspend fun account(
        name: String = "alice",
        password: String? = null,
        role: AccountRole = AccountRole.USER,
    ): AuthAccount = services.auth.createAccount(name, role, password)

    suspend fun grant(
        account: AuthAccount,
        device: DeviceInfoDto = deviceInfo(),
    ): TokenResponse = services.invites.redeem(services.invites.create(account.id), device)

    suspend fun principal(grant: TokenResponse): AccountPrincipal =
        services.tokens.authenticate(grant.token, TokenKind.DEVICE)

    fun install(application: Application) {
        ServerModule(config, TEST_SERVER_VERSION, FIXED_CLOCK, store::writeProbe, services).install(application)
    }

    override fun close() {
        events.close()
        store.close()
    }
}

internal fun deviceInfo(name: String = "Test phone"): DeviceInfoDto =
    DeviceInfoDto(UUID.randomUUID().toString(), name, "android", "0.test", "1111111111111111")

internal fun HttpRequestBuilder.api(token: String? = null) {
    header(RequestGuards.PROTOCOL_HEADER, "1")
    if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
}

internal suspend inline fun <reified T> HttpClient.postApi(
    path: String,
    body: T,
    token: String? = null,
): HttpResponse =
    post(path) {
        api(token)
        contentType(ContentType.Application.Json)
        setBody(SyncJson.encodeToString(body))
    }

internal fun authServices(test: suspend (AuthFixture) -> Unit) {
    kotlinx.coroutines.runBlocking { AuthFixture().use { test(it) } }
}

internal fun authApplication(test: suspend io.ktor.server.testing.ApplicationTestBuilder.(AuthFixture) -> Unit) {
    AuthFixture().use { fixture ->
        io.ktor.server.testing.testApplication {
            application { fixture.install(this) }
            test(fixture)
        }
    }
}

internal suspend inline fun <reified T> HttpResponse.decoded(): T = SyncJson.decodeFromString(bodyAsText())

internal fun HttpResponse.expect(status: io.ktor.http.HttpStatusCode) {
    kotlin.test.assertEquals(status, this.status)
}
