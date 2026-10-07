// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.refresh

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.ConnectionPoolEvictor
import ch.lkmc.neutrodyne.core.common.JobLane
import ch.lkmc.neutrodyne.core.common.JobLanePoker
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.common.PowerEvent
import ch.lkmc.neutrodyne.core.common.PowerMonitor
import ch.lkmc.neutrodyne.core.common.Redactor
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ExposeImplBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A lane's diagnostics row (11 Runner diagnostics): the Diagnostics screen lists one per lane. All
 * instants are wall-clock (`Clock.now`).
 */
data class LaneStatus(
    val running: Boolean,
    val lastStartAt: Instant?,
    val lastEndAt: Instant?,
    val lastError: String?,
    val runs: Long,
    val failures: Long,
    val backoffUntil: Instant?,
)

/**
 * The desktop's in-process scheduler (11 Background work): no OS integration exists, so lanes run
 * on a 60-s tick while the app runs and nothing runs while it is quit — the persisted schedule
 * (`nextRefreshAt` and friends) is the only state, so restarts lose nothing. Pokes coalesce by
 * name, a running lane records a single rerun, failures back off `min(2^(failures-1) min, 30 min)`,
 * and a `Resumed` event or a >90 s wall/monotonic drift counts as a wake: pools are evicted, the
 * network is awaited (≤ 60 s), every lane is poked.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
// The initializer starts the concrete runner, not the `JobLanePoker` the binding contributes;
// `@ExposeImplBinding` requires the public type — the desktop app's graph is not a friend module.
@ExposeImplBinding
class DesktopJobRunner
    @Inject
    constructor(
        lanes: Set<JobLane>,
        private val clock: Clock,
        private val power: PowerMonitor,
        private val network: NetworkMonitor,
        private val poolEvictor: ConnectionPoolEvictor,
        @param:ApplicationScope private val scope: CoroutineScope,
    ) : JobLanePoker {
        private val orderedLanes: List<JobLane> =
            lanes.sortedWith(
                compareBy({ LANE_ORDER.indexOf(it.name).let { i -> if (i < 0) LANE_ORDER.size else i } }, { it.name }),
            )
        private val lanesByName = lanes.associateBy { it.name }

        private val states = lanes.associate { it.name to LaneState() }
        private val pendingPokes = ConcurrentHashMap.newKeySet<String>()
        private val tickSignal = Channel<Unit>(Channel.CONFLATED)

        private val _status =
            MutableStateFlow(orderedLanes.associate { it.name to LaneStatus(false, null, null, null, 0, 0, null) })

        /** Per-lane diagnostics (11 Runner diagnostics). */
        val status: StateFlow<Map<String, LaneStatus>> = _status.asStateFlow()

        private val _lastWakeAt = MutableStateFlow<Instant?>(null)

        /** The last detected wake (11 Runner diagnostics). */
        val lastWakeAt: StateFlow<Instant?> = _lastWakeAt.asStateFlow()

        private var runnerJob: Job? = null
        private var lastWallAt = Long.MIN_VALUE
        private var lastMonotonicAt = Long.MIN_VALUE

        /** Band-200 entry point (11 Tick algorithm): 5 s of settle time, then the tick loop. */
        fun start() {
            check(runnerJob == null) { "DesktopJobRunner.start() called twice" }
            runnerJob =
                scope.launch {
                    launch {
                        power.events.collect { event ->
                            if (event == PowerEvent.Resumed) wake("power-event")
                        }
                    }
                    delay(START_SETTLE_MS)
                    while (isActive) {
                        tick()
                        withTimeoutOrNull(TICK_INTERVAL_MS) { tickSignal.receive() }
                    }
                }
        }

        /**
         * "Run [name] soon" (11 Runner contract): coalesced by name; unknown names are a
         * programming error. Safe to call from any thread.
         */
        override fun poke(name: String) {
            require(name in lanesByName) { "unknown lane '$name'" }
            pendingPokes.add(name)
            tickSignal.trySend(Unit)
        }

        /** `ShutdownCoordinator` (11 Quitting): cancels every lane, waiting up to [grace]. */
        suspend fun stop(grace: Duration) {
            runnerJob?.cancel()
            withTimeoutOrNull(grace) { states.values.mapNotNull { it.job }.joinAll() }
        }

        private suspend fun tick() {
            detectWallClockWake()
            val now = Instant.fromEpochMilliseconds(clock.now())
            // Snapshot then remove only the snapshotted names: a poke landing between the two
            // survives the clear and is delivered next tick.
            val pokes = pendingPokes.toSet()
            pendingPokes.removeAll(pokes)
            for (lane in orderedLanes) {
                val state = states.getValue(lane.name)
                val poked = lane.name in pokes
                when {
                    state.running -> if (poked) state.rerun = true
                    !poked && state.backoffUntil?.let { it > now } == true -> Unit
                    else -> launchLane(lane, state)
                }
            }
        }

        private fun launchLane(
            lane: JobLane,
            state: LaneState,
        ) {
            state.running = true
            state.job =
                scope.launch(laneDispatcher(lane.name)) {
                    try {
                        updateStatus(lane.name) {
                            it.copy(running = true, lastStartAt = Instant.fromEpochMilliseconds(clock.now()))
                        }
                        suspendRunCatching { lane.run(Instant.fromEpochMilliseconds(clock.now())) }
                            .fold(
                                onSuccess = {
                                    state.failures = 0
                                    state.backoffUntil = null
                                    updateStatus(lane.name) {
                                        it.copy(
                                            running = false,
                                            lastEndAt = Instant.fromEpochMilliseconds(clock.now()),
                                            lastError = null,
                                            runs = it.runs + 1,
                                            backoffUntil = null,
                                        )
                                    }
                                },
                                onFailure = { throwable ->
                                    if (throwable is CancellationException) throw throwable
                                    state.failures += 1
                                    val backoffMinutes =
                                        min(
                                            BACKOFF_CAP_MINUTES.toDouble(),
                                            BACKOFF_BASE_MINUTES.toDouble() *
                                                (1 shl (state.failures - 1).coerceIn(0, 5)),
                                        ).toLong()
                                    state.backoffUntil =
                                        Instant.fromEpochMilliseconds(clock.now()) + backoffMinutes.minutes
                                    Log.e(TAG, throwable) { "lane ${lane.name} failed" }
                                    updateStatus(lane.name) {
                                        it.copy(
                                            running = false,
                                            lastEndAt = Instant.fromEpochMilliseconds(clock.now()),
                                            lastError =
                                                Redactor.text(
                                                    throwable.message ?: throwable::class.simpleName ?: "error",
                                                ),
                                            runs = it.runs + 1,
                                            failures = it.failures + 1,
                                            backoffUntil = state.backoffUntil,
                                        )
                                    }
                                },
                            )
                    } finally {
                        state.running = false
                        if (state.rerun) {
                            state.rerun = false
                            launchLane(lane, state)
                        }
                    }
                }
        }

        /** 11's fairness rule: I/O lanes ride `IO`; `artwork`'s colour work gets a bounded `Default`. */
        private fun laneDispatcher(name: String): CoroutineDispatcher =
            when (name) {
                "artwork" -> Dispatchers.Default.limitedParallelism(ARTWORK_PARALLELISM)
                else -> Dispatchers.IO
            }

        /** A missed suspend notice shows as wall time outrunning the monotonic clock (11 Wake). */
        private suspend fun detectWallClockWake() {
            val wall = clock.now()
            val monotonic = clock.elapsedRealtime()
            val previousWall = lastWallAt
            val previousMonotonic = lastMonotonicAt
            lastWallAt = wall
            lastMonotonicAt = monotonic
            if (previousWall == Long.MIN_VALUE) return
            if (wall - previousWall - (monotonic - previousMonotonic) > WAKE_DRIFT_MS) wake("clock-drift")
        }

        private suspend fun wake(reason: String) {
            _lastWakeAt.value = Instant.fromEpochMilliseconds(clock.now())
            Log.i(TAG) { "wake detected ($reason): evicting pools and poking lanes" }
            suspendRunCatching { poolEvictor.evict() }
            withTimeoutOrNull(ONLINE_WAIT_MS) { network.status.first { it.isConnected } }
            pendingPokes.addAll(lanesByName.keys)
            tickSignal.trySend(Unit)
        }

        private fun updateStatus(
            name: String,
            block: (LaneStatus) -> LaneStatus,
        ) {
            _status.update { current -> current + (name to block(current.getValue(name))) }
        }

        /** Read across the tick and lane coroutines, so every shared field is volatile. */
        private class LaneState {
            @Volatile var job: Job? = null

            @Volatile var running: Boolean = false

            @Volatile var rerun: Boolean = false

            @Volatile var failures: Int = 0

            @Volatile var backoffUntil: Instant? = null
        }

        internal companion object {
            private const val TAG = "JobRunner"
            val TICK_INTERVAL_MS = 60.seconds
            val START_SETTLE_MS = 5.seconds
            val ONLINE_WAIT_MS = 60.seconds
            const val WAKE_DRIFT_MS = 90_000L
            const val BACKOFF_BASE_MINUTES = 1
            const val BACKOFF_CAP_MINUTES = 30
            const val ARTWORK_PARALLELISM = 2

            /** 11's fixed per-tick lane order; unknown lanes append by name for determinism. */
            val LANE_ORDER =
                listOf(
                    "sync",
                    "refresh",
                    "import-backup",
                    "downloads-manual",
                    "downloads-auto",
                    "downloads-move",
                    "artwork",
                    "app-update-check",
                    "engine-update",
                    "maintenance",
                )
        }
    }
