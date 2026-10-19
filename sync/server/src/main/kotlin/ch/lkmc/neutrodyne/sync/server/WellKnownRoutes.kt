// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.PROTOCOL_VERSION
import ch.lkmc.neutrodyne.sync.protocol.ProtocolLimits
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import ch.lkmc.neutrodyne.sync.protocol.dto.DiscoveryDocument
import ch.lkmc.neutrodyne.sync.protocol.dto.ProtocolRange
import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlin.time.Clock
import kotlin.time.Instant

/** Unauthenticated discovery advertises only implemented capabilities (10 Discovery). */
internal class WellKnownRoutes(
    private val serverVersion: String,
    private val clock: Clock,
) {
    fun install(routing: Route) {
        routing.get(WELL_KNOWN_PATH) {
            val document =
                DiscoveryDocument(
                    name = SERVER_DISPLAY_NAME,
                    serverVersion = serverVersion,
                    protocol = ProtocolRange(PROTOCOL_VERSION, PROTOCOL_VERSION),
                    features = emptyList(), // sse, link and password arrive with MS1.
                    maxBatch = ProtocolLimits.MAX_CHANGES,
                    maxSkewMs = ProtocolLimits.MAX_SKEW_MS,
                    serverTime = serverTime(),
                )
            call.respondText(
                SyncJson.encodeToString(DiscoveryDocument.serializer(), document),
                ContentType.Application.Json,
            )
        }
    }

    /** ISO-8601 UTC with milliseconds, truncated so the fraction never exceeds three digits. */
    private fun serverTime(): String = Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds()).toString()

    internal companion object {
        const val WELL_KNOWN_PATH = "/.well-known/neutrodyne-sync"
        const val SERVER_DISPLAY_NAME = "Neutrodyne Sync"
    }
}
