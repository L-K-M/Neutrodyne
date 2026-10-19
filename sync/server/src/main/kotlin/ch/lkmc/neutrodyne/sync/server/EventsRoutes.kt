// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangedEvent
import io.ktor.http.ContentType
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class EventsRoutes(
    private val events: EventBus,
) {
    fun install(route: Route) {
        route.get("/api/v1/events") {
            val raw = call.request.queryParameters["hb"]
            val heartbeat =
                (
                    raw?.toLongOrNull() ?: if (raw == null) {
                        DEFAULT_HEARTBEAT
                    } else {
                        invalidAuth()
                    }
                ).coerceIn(30, 120)
            // Reserve and recheck before sending headers, so stream-limit failures are real 429s.
            val stream = events.open(call.principal())
            try {
                call.response.headers.append("X-Accel-Buffering", "no")
                call.respondTextWriter(ContentType.Text.EventStream) {
                    stream.use {
                        write("retry: 10000\n\n")
                        flush()
                        withTimeoutOrNull(STREAM_LIFETIME_MS) {
                            coroutineScope {
                                val incoming = stream.events.buffer(Channel.CONFLATED).produceIn(this)
                                try {
                                    var open = true
                                    while (open) {
                                        select<Unit> {
                                            incoming.onReceiveCatching { result ->
                                                val event = result.getOrNull()
                                                if (event == null) {
                                                    open = false
                                                    return@onReceiveCatching
                                                }
                                                if (event is ServerEvent.Changed) {
                                                    val data =
                                                        SyncJson.encodeToString(
                                                            ChangedEvent.serializer(),
                                                            ChangedEvent(event.cursor, event.byDevice),
                                                        )
                                                    write("event: changed\ndata: $data\n\n")
                                                    flush()
                                                }
                                            }
                                            onTimeout(heartbeat * 1000) {
                                                write(": hb\n\n")
                                                flush()
                                            }
                                        }
                                    }
                                } finally {
                                    incoming.cancel()
                                }
                            }
                        }
                    }
                }
            } catch (failure: Throwable) {
                stream.close()
                throw failure
            }
        }
    }

    private companion object {
        const val DEFAULT_HEARTBEAT = 60L
        const val STREAM_LIFETIME_MS = 86_400_000L
    }
}
