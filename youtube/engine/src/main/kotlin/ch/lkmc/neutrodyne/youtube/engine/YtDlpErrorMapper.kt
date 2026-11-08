// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.engine

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.youtube.api.ResolveResult
import ch.lkmc.neutrodyne.youtube.api.TransientKind
import kotlinx.coroutines.CancellationException

/** One app-scoped classifier shared by engine operations. It reports no health itself. */
class YtDlpErrorMapper(
    private val clock: Clock,
) {
    private val unavailable = LinkedHashMap<String, Long>()

    @Synchronized fun map(
        code: String,
        videoId: String?,
    ): ResolveResult {
        if (code == "CANCELLED") throw CancellationException("YouTube operation cancelled")
        val reason =
            when (code) {
                "AGE_RESTRICTED" -> Availability.AGE_RESTRICTED
                "MEMBERS_ONLY" -> Availability.MEMBERS_ONLY
                "PRIVATE" -> Availability.PRIVATE
                "REGION_BLOCKED" -> Availability.REGION_BLOCKED
                "UPCOMING" -> Availability.UPCOMING
                "LIVE" -> Availability.LIVE
                "KIDS_ONLY" -> Availability.KIDS_ONLY
                "UNAVAILABLE" -> Availability.UNAVAILABLE
                else -> null
            }
        if (reason != null) {
            if (code != "UNAVAILABLE" || videoId == null ||
                !unavailableCluster(videoId)
            ) {
                return ResolveResult.Unavailable(reason)
            }
        }
        val kind =
            when (code) {
                "NETWORK" -> TransientKind.NETWORK
                "TIMEOUT" -> TransientKind.TIMEOUT
                "ENGINE_UNAVAILABLE" -> TransientKind.ENGINE_UNAVAILABLE
                "RATE_LIMITED" -> TransientKind.RATE_LIMITED
                else -> TransientKind.EXTRACTION
            }
        return transient(kind)
    }

    private fun unavailableCluster(videoId: String): Boolean {
        val now = clock.elapsedRealtime()
        unavailable.entries.removeAll { now - it.value >= 10 * 60_000L || now < it.value }
        val others = unavailable.keys.count { it != videoId }
        unavailable.remove(videoId)
        unavailable[videoId] = now
        // Only the presence of two other distinct videos matters; bound even a hostile stream.
        while (unavailable.size > 64) unavailable.remove(unavailable.keys.first())
        return others >= 2
    }

    companion object {
        fun transient(kind: TransientKind): ResolveResult.Transient =
            ResolveResult.Transient(YtxCallException(kind.name), kind)
    }
}
