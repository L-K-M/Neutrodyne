// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.PROTOCOL_VERSION
import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * `GET /.well-known/neutrodyne-sync` (10 Protocol › Discovery): unauthenticated, revealing only
 * the display name, the version and the protocol range. The `DiscoveryDocument` DTO arrives in
 * `:sync:protocol` with MS0; until then the document is assembled here with the same shape.
 */
internal class WellKnownRoutes(
    private val serverVersion: String,
    private val clock: Clock,
) {

    fun install(routing: Routing) {
        routing.get(WELL_KNOWN_PATH) {
            val document = buildJsonObject {
                put("name", SERVER_DISPLAY_NAME)
                put("serverVersion", serverVersion)
                put("protocol", buildJsonObject {
                    put("min", PROTOCOL_VERSION)
                    put("max", PROTOCOL_VERSION)
                })
                // `sse`, `link` and `password` feature flags arrive with MS1 (10 Discovery).
                put("features", JsonArray(emptyList()))
                put("maxBatch", DiscoveryNumbers.MAX_BATCH)
                put("maxSkewMs", DiscoveryNumbers.MAX_SKEW_MS)
                put("serverTime", serverTime())
            }
            call.respondText(document.toString(), ContentType.Application.Json)
        }
    }

    /** ISO-8601 UTC with milliseconds, truncated so the fraction never exceeds three digits. */
    private fun serverTime(): String =
        Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds()).toString()

    internal companion object {
        const val WELL_KNOWN_PATH = "/.well-known/neutrodyne-sync"
        const val SERVER_DISPLAY_NAME = "Neutrodyne Sync"
    }
}

/** Wire numbers of the discovery document; `ProtocolLimits` in `:sync:protocol` takes over in MS0. */
internal object DiscoveryNumbers {
    const val MAX_BATCH = 1_000
    const val MAX_SKEW_MS = 300_000L
}
