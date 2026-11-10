// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.sync.api.DiscoveredServer
import ch.lkmc.neutrodyne.sync.api.SyncProblem
import ch.lkmc.neutrodyne.sync.protocol.PROTOCOL_VERSION

sealed interface DiscoveryResult {
    data class Success(
        val server: DiscoveredServer,
    ) : DiscoveryResult

    data class Failure(
        val problem: SyncProblem,
    ) : DiscoveryResult
}

/**
 * 10 Discovery: normalises the typed address (`https://` assumed, a trailing path kept), fetches
 * the discovery document and rejects a protocol range that excludes this app.
 */
class ServerDiscovery(
    private val client: SyncClient,
    private val diagnosticsRuntime: SyncRuntimeDiagnostics = SyncRuntimeDiagnostics(),
) {
    suspend fun discover(address: String): DiscoveryResult {
        val normalized = normalise(address)
        if (normalized == null) return DiscoveryResult.Failure(SyncProblem.Server(400))
        return try {
            val document = client.discover(normalized)
            diagnosticsRuntime.rememberVersion(normalized, document.serverVersion)
            when {
                PROTOCOL_VERSION < document.protocol.min -> {
                    DiscoveryResult.Failure(SyncProblem.AppTooOld)
                }

                PROTOCOL_VERSION > document.protocol.max -> {
                    DiscoveryResult.Failure(SyncProblem.ServerTooOld)
                }

                else -> {
                    DiscoveryResult.Success(
                        DiscoveredServer(
                            name = document.name,
                            serverVersion = document.serverVersion,
                            serverUrl = normalized,
                            features = document.features,
                            maxBatch = document.maxBatch,
                            maxSkewMs = document.maxSkewMs,
                        ),
                    )
                }
            }
        } catch (failure: SyncTransportException) {
            DiscoveryResult.Failure(SyncProblem.Server(failure.status))
        }
    }

    /** `https://` when no scheme is typed; a trailing path is kept for path-prefix servers. */
    fun normalise(address: String): String? {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return null
        // Canonicalise the scheme alone: `HTTP://host` must reach the preflight checks as plain
        // `http` (so it is refused or confirmed), while the authority's and path's case — a path
        // prefix, a query token — stays exactly as typed.
        val withScheme =
            when {
                trimmed.startsWith("https://", ignoreCase = true) -> {
                    "https://" + trimmed.substring("https://".length)
                }

                trimmed.startsWith("http://", ignoreCase = true) -> {
                    "http://" + trimmed.substring("http://".length)
                }

                else -> {
                    "https://$trimmed"
                }
            }
        val path = withScheme.substringAfter("://", "").dropWhile { it == '/' }
        if (path.isEmpty()) return null
        return withScheme.trimEnd('/')
    }
}
