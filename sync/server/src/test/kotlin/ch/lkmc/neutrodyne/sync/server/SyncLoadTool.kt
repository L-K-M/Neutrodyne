// SPDX-License-Identifier: Unlicense
@file:Suppress("ktlint:standard:max-line-length")

package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.ProtocolLimits
import ch.lkmc.neutrodyne.sync.protocol.RecordId
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeDto
import ch.lkmc.neutrodyne.sync.protocol.dto.DeviceInfoDto
import ch.lkmc.neutrodyne.sync.protocol.dto.FieldValue
import ch.lkmc.neutrodyne.sync.protocol.dto.InviteRedeemRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.SyncRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.SyncResponse
import ch.lkmc.neutrodyne.sync.protocol.dto.TokenResponse
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import kotlin.time.measureTime

/** S16 driver: authenticate, then upload through the public capped/rate-limited HTTP endpoint. */
object SyncLoadTool {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size in 1..2) { "SyncLoadTool <server-url> [records=50000]; set NEUTRODYNE_LOAD_INVITE" }
        val base = URI(args[0].trimEnd('/'))
        require(base.scheme in setOf("https", "http") && base.userInfo == null && base.query == null)
        val count = args.getOrNull(1)?.toInt() ?: DEFAULT_RECORDS
        require(count > 1)
        val invite = checkNotNull(System.getenv("NEUTRODYNE_LOAD_INVITE")) { "NEUTRODYNE_LOAD_INVITE is required" }
        val node =
            NodeId(
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(NodeId.LENGTH),
            )
        val device =
            DeviceInfoDto(UUID.randomUUID().toString(), "SyncLoadTool", "linux", ServerVersion.current(), node.value)
        val podcast = UUID.randomUUID().toString()
        HttpClient
            .newBuilder()
            .connectTimeout(
                Duration.ofSeconds(10),
            ).followRedirects(HttpClient.Redirect.NEVER)
            .build()
            .use { client ->
                val grant =
                    SyncJson.decodeFromString<TokenResponse>(
                        post(
                            client,
                            base.resolve("/api/v1/auth/invite/redeem"),
                            SyncJson.encodeToString(
                                InviteRedeemRequest.serializer(),
                                InviteRedeemRequest(invite, device),
                            ),
                            null,
                        ),
                    )
                val elapsed =
                    measureTime {
                        for (start in 0 until count step ProtocolLimits.MAX_CHANGES) {
                            val clock = Hlc.of(System.currentTimeMillis(), 0, node).toString()
                            val changes =
                                (start until minOf(count, start + ProtocolLimits.MAX_CHANGES)).map { index ->
                                    if (index == 0) {
                                        ChangeDto(
                                            SyncCollection.PODCAST.wire,
                                            JsonPrimitive(podcast),
                                            fields =
                                                mapOf(
                                                    "feedUrl" to
                                                        FieldValue(
                                                            JsonPrimitive("https://example.org/load-$podcast.xml"),
                                                            clock,
                                                        ),
                                                    "subscribed" to FieldValue(JsonPrimitive(true), clock),
                                                ),
                                        )
                                    } else {
                                        ChangeDto(
                                            SyncCollection.EPISODE.wire,
                                            JsonObject(
                                                mapOf(
                                                    "p" to JsonPrimitive(podcast),
                                                    "k" to JsonPrimitive("g:load-$index"),
                                                ),
                                            ),
                                            fields =
                                                mapOf("fav" to FieldValue(JsonPrimitive(true), clock)),
                                        )
                                    }
                                }
                            val body = SyncJson.encodeToString(SyncRequest.serializer(), SyncRequest(null, changes))
                            check(body.toByteArray().size <= ProtocolLimits.MAX_REQUEST_BYTES)
                            val response =
                                SyncJson.decodeFromString<SyncResponse>(
                                    post(client, base.resolve("/api/v1/sync"), body, grant.token),
                                )
                            check(
                                response.results.size == changes.size &&
                                    response.results.none {
                                        it.status == "rejected"
                                    },
                            ) { "Upload rejected a capped batch" }
                        }
                    }
                println("SyncLoadTool: records=$count elapsed=$elapsed clientJava=${Runtime.version()} server=$base")
            }
    }

    private fun post(
        client: HttpClient,
        uri: URI,
        body: String,
        token: String?,
    ): String {
        val builder =
            HttpRequest
                .newBuilder(uri)
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header(RequestGuards.PROTOCOL_HEADER, "1")
                .POST(HttpRequest.BodyPublishers.ofString(body))
        if (token != null) builder.header("Authorization", "Bearer $token")
        while (true) {
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 429) {
                val seconds =
                    response
                        .headers()
                        .firstValue("Retry-After")
                        .orElse("1")
                        .toLong()
                        .coerceIn(1, 60)
                Thread.sleep(seconds * 1000)
                continue
            }
            check(response.statusCode() == 200) { "Load request failed: HTTP ${response.statusCode()}" }
            return response.body()
        }
    }

    private const val DEFAULT_RECORDS = 50_000
}
