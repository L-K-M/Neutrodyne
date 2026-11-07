// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.JobLane
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.sync.api.SyncRoundReason
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlin.time.Instant

/**
 * The desktop `sync` lane (10 Scheduling on the desktop): a pull when the runner ticks and the
 * last round is more than 15 min old, and a round on the event-driven pokes (outbox change,
 * playback pause, resume). Offline costs nothing; the runner pokes again on network regain.
 */
@ContributesIntoSet(AppScope::class)
internal class DesktopSyncLane
    @Inject
    constructor(
        private val engine: SyncEngine,
        private val network: NetworkMonitor,
        private val clock: Clock,
    ) : JobLane {
        override val name: String = NAME

        override suspend fun run(now: Instant) {
            if (!network.status.value.isConnected) return
            val last = lastRoundMs
            if (last != null && now.toEpochMilliseconds() - last < PULL_INTERVAL_MS) return
            engine.round(SyncRoundReason.PERIODIC)
            lastRoundMs = now.toEpochMilliseconds()
        }

        /** The event-driven poke (outbox change, playback pause, resume). */
        suspend fun poke(reason: SyncRoundReason) {
            if (!network.status.value.isConnected) return
            engine.round(reason)
            lastRoundMs = clock.now()
        }

        private var lastRoundMs: Long? = null

        private companion object {
            const val NAME = "sync"
            const val PULL_INTERVAL_MS = 15 * 60_000L
        }
    }
