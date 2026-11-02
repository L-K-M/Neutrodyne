// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.nio.file.Path

/**
 * `/healthz` and `/readyz` (10 Protocol › Account and health endpoints): liveness answers `200 ok`
 * while the process serves; readiness requires free disk space and a successful rolled-back
 * database write, and answers `503` otherwise, with no details in the body.
 */
internal class HealthRoutes(
    private val dataDir: Path,
    private val usableSpace: (Path) -> Long = { directory -> Files.getFileStore(directory).usableSpace },
    private val writeProbe: suspend () -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val probeLock = Mutex()
    private var probeCachedAtMs: Long? = null
    private var probeResult = false

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

    /** Both storage capacity and the actual JDBC write path must work. */
    internal suspend fun isReady(): Boolean =
        try {
            Files.isDirectory(dataDir) && usableSpace(dataDir) >= MIN_FREE_DISK_BYTES && probe()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            false
        }

    // The real write probe serializes on the single writer; anonymous /readyz bursts must
    // not turn into a writer queue, so one completed probe covers a short window.
    private suspend fun probe(): Boolean =
        probeLock.withLock {
            val cachedAt = probeCachedAtMs
            if (cachedAt != null && now() - cachedAt < PROBE_CACHE_MS) return@withLock probeResult
            probeResult = writeProbe()
            probeCachedAtMs = now()
            probeResult
        }

    internal companion object {
        const val HEALTH_PATH = "/healthz"
        const val READY_PATH = "/readyz"
        const val BODY_OK = "ok"

        /** 10 Server failure modes: not ready below 50 MB free on the data volume. */
        const val MIN_FREE_DISK_BYTES = 50L * 1024 * 1024

        /** One completed write probe stays valid this long; readiness lags recovery by at most this. */
        const val PROBE_CACHE_MS = 5_000L

        val PATHS = setOf(HEALTH_PATH, READY_PATH)
    }
}
