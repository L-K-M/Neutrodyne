// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import java.nio.file.Files
import java.nio.file.Path

/**
 * `/healthz` and `/readyz` (10 Protocol › Account and health endpoints): liveness answers `200 ok`
 * while the process serves; readiness answers `200` when the data volume has room — the database
 * write check joins with MS1's storage — and `503` otherwise, with no details in the body.
 */
internal class HealthRoutes(
    private val dataDir: Path,
    private val usableSpace: (Path) -> Long = { directory -> Files.getFileStore(directory).usableSpace },
) {

    fun install(routing: Routing) {
        routing.apply {
            get(HEALTH_PATH) {
                call.respondText(BODY_OK, ContentType.Text.Plain, HttpStatusCode.OK)
            }
            get(READY_PATH) {
                if (isReady()) {
                    call.respondText(BODY_OK, ContentType.Text.Plain, HttpStatusCode.OK)
                } else {
                    call.respond(HttpStatusCode.ServiceUnavailable)
                }
            }
        }
    }

    /** Ready when the data volume has room; the database write check joins with MS1's storage. */
    internal fun isReady(): Boolean = runCatching {
        Files.isDirectory(dataDir) && usableSpace(dataDir) >= MIN_FREE_DISK_BYTES
    }.getOrDefault(false)

    internal companion object {
        const val HEALTH_PATH = "/healthz"
        const val READY_PATH = "/readyz"
        const val BODY_OK = "ok"

        /** 10 Server failure modes: not ready below 50 MB free on the data volume. */
        const val MIN_FREE_DISK_BYTES = 50L * 1024 * 1024

        val PATHS = setOf(HEALTH_PATH, READY_PATH)
    }
}
