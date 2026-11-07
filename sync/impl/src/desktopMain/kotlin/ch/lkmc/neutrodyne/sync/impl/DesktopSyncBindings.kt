// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.JobLane
import ch.lkmc.neutrodyne.core.common.JobLanePoker
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.common.PowerEvent
import ch.lkmc.neutrodyne.core.common.PowerMonitor
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.sync.api.SyncRoundReason
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The desktop half of 10 Scheduling on the desktop: the shared `SyncScheduler`'s hooks push to
 * the `sync` lane's reasoned poke, the lane joins the runner's `JobLane` multibinding, and
 * `DesktopSyncTriggers` feeds the events the runner cannot see — window focus and a power
 * resume (the runner's own wake poke runs the lane too; the reasoned one keeps "resume → a
 * round" instead of the bare 15-min check).
 */
@ContributesTo(AppScope::class)
@BindingContainer
object DesktopSyncBindings {
    @Provides
    @SingleIn(AppScope::class)
    internal fun desktopSyncLane(
        db: NeutrodyneDatabase,
        engine: SyncEngine,
        network: NetworkMonitor,
        clock: Clock,
        poker: Provider<JobLanePoker>,
    ): DesktopSyncLane = DesktopSyncLane(db, { reason -> engine.round(reason) }, network, clock, poker)

    @Provides
    @IntoSet
    internal fun syncLaneAsJobLane(lane: DesktopSyncLane): JobLane = lane

    @Provides
    internal fun syncScheduleHooks(lane: DesktopSyncLane): SyncScheduleHooks = DesktopSyncScheduleHooks(lane)

    /** The window's focus callback — resolving the lane reaches the database, so it stays lazy. */
    @Provides
    internal fun syncFocusPoke(triggers: Provider<DesktopSyncTriggers>): SyncFocusPoke =
        SyncFocusPoke { triggers().onWindowFocus() }
}

/**
 * The window-refocus trigger's callback type. Metro treats a parameter-less `() -> Unit` as a
 * provider type, so the poked surface is a named interface instead (the window adapts it with
 * a method reference).
 */
fun interface SyncFocusPoke {
    fun poke()
}

/** The desktop [SyncScheduleHooks]: every deferred push is a reasoned poke on the `sync` lane. */
class DesktopSyncScheduleHooks(
    private val lane: DesktopSyncLane,
) : SyncScheduleHooks {
    /** The desktop never rounds in the collector — its push path is the lane itself. */
    override fun interactive(): Boolean = false

    override fun schedulePush(reason: SyncRoundReason) = lane.poke(reason)

    /** The runner tick already covers the periodic pull; a link arms nothing extra. */
    override suspend fun onLinked() = Unit

    /** Unlink needs no work cancellation — only the in-memory lane state guards R7.1. */
    override suspend fun onUnlinked() = Unit
}

/**
 * The desktop triggers the runner has no event for (10 Scheduling on the desktop): a window
 * refocus after more than 60 s away pokes a `FOREGROUND` round, and a power resume does the
 * same so the pull happens instead of waiting out the 15-min check.
 */
@SingleIn(AppScope::class)
class DesktopSyncTriggers
    @Inject
    constructor(
        private val lane: Provider<DesktopSyncLane>,
        private val power: PowerMonitor,
        private val clock: Clock,
        @param:ApplicationScope private val scope: CoroutineScope,
    ) {
        @Volatile
        private var started = false
        private var lastFocusMs: Long? = null

        /**
         * 10 Live updates: the events stream's gate — the SSE connection stays open while the
         * machine is awake; `Suspending` closes it and `Resumed` re-opens it (beside the
         * existing resume round below).
         */
        internal val awake = MutableStateFlow(true)

        /** Starts the power-resume collector once (the initializer calls it). */
        fun start() {
            if (started) return
            started = true
            scope.launch {
                power.events.collect { event ->
                    when (event) {
                        PowerEvent.Suspending -> {
                            awake.value = false
                        }

                        PowerEvent.Resumed -> {
                            awake.value = true
                            pokeLane()
                        }
                    }
                }
            }
        }

        /**
         * AWT focus callback — runs on the event thread, so it only pokes. Resolving the lane
         * reaches the database: before the band-100 open (or after a failed one) the trigger
         * simply has nothing to poke — an unlinked device skips the round the same way.
         */
        fun onWindowFocus() {
            val now = clock.elapsedRealtime()
            val last = lastFocusMs
            if (last != null && now - last < FOCUS_COOLDOWN_MS) return
            lastFocusMs = now
            pokeLane()
        }

        private fun pokeLane() {
            runCatching { lane().poke(SyncRoundReason.FOREGROUND) }
        }

        private companion object {
            /** 10 Scheduling on the desktop: a refocus counts only after > 60 s away. */
            const val FOCUS_COOLDOWN_MS = 60_000L
        }
    }

/**
 * The desktop sync-scheduling start-up initializer (01 Application start-up, band 210 — after
 * the database open at 100, beside the runner's own start): launches `SyncScheduler`'s
 * collectors and the power-resume listener. Nothing is armed while unlinked — the collectors
 * gate per trigger and the lane itself re-checks the link.
 */
@ContributesIntoSet(AppScope::class)
class DesktopSyncSchedulingInitializer
    @Inject
    constructor(
        // Lazy (01 DI rule 7): the scheduler's dependencies reach the database (band 100).
        private val scheduler: Provider<SyncScheduler>,
        private val triggers: Provider<DesktopSyncTriggers>,
        private val events: Provider<SyncEventsClient>,
    ) : AppInitializer {
        override val order: Int = 210

        override suspend fun run() {
            scheduler().start()
            triggers().start()
            // 10 Live updates: the SSE gate — open while the app runs and the machine is awake.
            events().start(triggers().awake, SSE_HEARTBEAT_DESKTOP_SECONDS)
        }
    }
