// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlpdesktop

import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.JobLane
import ch.lkmc.neutrodyne.core.common.JobLanePoker
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.youtube.api.EngineAvailability
import ch.lkmc.neutrodyne.youtube.api.EngineUpdateOutcome
import ch.lkmc.neutrodyne.youtube.api.EngineUpdatePolicy
import ch.lkmc.neutrodyne.youtube.api.YouTubeEngine
import ch.lkmc.neutrodyne.youtube.engine.EnginePlatformPreflight
import ch.lkmc.neutrodyne.youtube.engine.EnginePreparation
import ch.lkmc.neutrodyne.youtube.engine.EngineSettings
import ch.lkmc.neutrodyne.youtube.engine.EngineSettingsSnapshot
import ch.lkmc.neutrodyne.youtube.engine.EngineStore
import ch.lkmc.neutrodyne.youtube.engine.EngineUpdateHooks
import ch.lkmc.neutrodyne.youtube.engine.EngineUpdateScheduler
import ch.lkmc.neutrodyne.youtube.engine.EngineUpdateTrigger
import ch.lkmc.neutrodyne.youtube.engine.YtxControlRequest
import ch.lkmc.neutrodyne.youtube.engine.YtxDeadline
import ch.lkmc.neutrodyne.youtube.engine.YtxLimits
import ch.lkmc.neutrodyne.youtube.engine.YtxMethod
import ch.lkmc.neutrodyne.youtube.engine.YtxTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.time.Instant

/** Thrown when a run ends in `Failed`/`Staged` and the runner's backoff owns the re-arm. */
internal class EngineUpdateLaneRetry : Exception("engine update run will retry")

/**
 * 04's desktop scheduler port without WorkManager (11 Engine updates on the desktop):
 * `scheduleDaily` only arms the persisted-cadence gate the lane reads each tick (the lane
 * *is* the periodic work — there is no second scheduler), and `requestImmediateCheck` is
 * `engine-update-now` — the breaker's keep-run poke, coalesced by `DesktopJobRunner` like
 * every other lane poke. A poke with no tick behind it still lands: the runner's
 * `pendingPokes` survives until the next tick or wake.
 */
class DesktopEngineUpdateScheduler(
    private val poker: () -> JobLanePoker,
) : EngineUpdateScheduler {
    private val pendingImmediate = AtomicBoolean()

    @Volatile private var dailyEnabled = false

    /** Whether the daily cadence may run; the update initializer drives this. */
    internal val isDailyEnabled: Boolean get() = dailyEnabled

    /** An immediate (breaker) check is waiting for a connected tick. */
    internal val immediatePending: Boolean get() = pendingImmediate.get()

    /** The lane consumes the flag only when it is about to dispatch the run. */
    internal fun takeImmediate(): Boolean = pendingImmediate.compareAndSet(true, false)

    override fun scheduleDaily(enabled: Boolean) {
        dailyEnabled = enabled
    }

    override fun requestImmediateCheck() {
        pendingImmediate.set(true)
        poker().poke(DesktopEngineUpdateLane.NAME)
    }
}

/**
 * The desktop `engine-update` lane (11 Lanes), `EngineUpdateWorker`'s analogue: the runner
 * ticks it every 60 s while the app runs and the lane does what is due.
 *
 * - **Initial bundled prepare** (11 Engine store paths): extracted once per app version by
 *   the first lane run finding no compile marker — also the retry backstop for
 *   `DesktopEnginePreparationObserver`'s earlier pass, and skipped the moment a marker is
 *   reported. Never on a resolve path.
 * - **Immediate checks** (breaker open or the "engine-update-now" poke) bypass the daily
 *   gate but not connectivity: offline they stay pending for the next tick.
 * - **Daily checks** require `scheduleDaily(true)` (the initializer's eligibility view of
 *   policy + availability), connectivity, and 24 h + per-install jitter since
 *   `lastCheckAtMs`.
 * - `Failed`/`Staged` re-arm bounded attempts by throwing — the runner's exponential
 *   backoff spaces them — and any verdict clears the counter. Cancellation propagates
 *   unchanged; the updater's contract keeps a cancelled candidate staged, not rejected.
 *
 * "Check for engine update" runs in-process through `EngineUpdateHooks.runUpdate(MANUAL)`;
 * the trust chain, staging, self-test, idle gate and rollback all live in `:youtube:engine`.
 */
class DesktopEngineUpdateLane(
    private val store: EngineStore,
    private val transport: YtxTransport,
    private val settings: EngineSettings,
    private val preflight: EnginePlatformPreflight,
    private val scheduler: DesktopEngineUpdateScheduler,
    private val hooks: EngineUpdateHooks,
    private val network: NetworkMonitor,
    private val clock: Clock,
    private val ping: () -> YtxControlRequest,
    private val jitterSeed: Long,
) : JobLane {
    override val name: String = NAME

    /** In-memory retry counter, like `EngineUpdateWorker`'s `runAttemptCount`. */
    @Volatile private var attempts = 0

    override suspend fun run(now: Instant) {
        settings.awaitLoaded()
        prepareBundledIfNeeded()
        val snapshot = settings.state.value
        if (!snapshot.enabled || preflight.limitation() != null) {
            attempts = 0
            return
        }
        when {
            scheduler.immediatePending -> {
                if (!network.status.value.isConnected) return
                scheduler.takeImmediate()
                runTracked(EngineUpdateTrigger.BREAKER)
            }

            // A bounded retry re-arms regardless of the daily gate: the due time just
            // moved to this run, so a plain due-check would never fire again.
            attempts > 0 -> {
                if (!network.status.value.isConnected) return
                runTracked(EngineUpdateTrigger.SCHEDULED)
            }

            else -> {
                if (!scheduler.isDailyEnabled || !network.status.value.isConnected) return
                if (!due(now.toEpochMilliseconds(), snapshot)) return
                runTracked(EngineUpdateTrigger.SCHEDULED)
            }
        }
    }

    /**
     * The once-per-app-version bundled prepare: the lane's durable retry arm of the
     * startup observer. Idempotent in the store — a marker-bearing tree short-circuits —
     * so each tick only repeats while preparation genuinely needs it.
     */
    private suspend fun prepareBundledIfNeeded() {
        if (!settings.state.value.enabled || preflight.limitation() != null) return
        if (store.preparation.value == EnginePreparation.COMPILED) return
        try {
            store.prepareBundled()
            transport.call(
                YtxMethod.PING,
                ping().toJson(),
                YtxDeadline(clock.elapsedRealtime() + YtxLimits.COMPILE_MS + YtxLimits.START_MS),
            )
            store.loadStatus()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A failed pass leaves the store's own state; the next tick retries.
        }
    }

    private fun due(
        nowMs: Long,
        snapshot: EngineSettingsSnapshot,
    ): Boolean {
        val last = snapshot.lastCheckAtMs ?: return true
        return nowMs - last >= CHECK_INTERVAL_MS + jitterMs()
    }

    /** The per-install offset inside [0, JITTER_MS), stable across restarts. */
    private fun jitterMs(): Long = abs(jitterSeed) % JITTER_MS

    private suspend fun runTracked(trigger: EngineUpdateTrigger) {
        when (hooks.runUpdate(trigger)) {
            is EngineUpdateOutcome.Failed, is EngineUpdateOutcome.Staged -> {
                attempts += 1
                if (attempts < MAX_ATTEMPTS) throw EngineUpdateLaneRetry()
                attempts = 0
            }

            else -> {
                attempts = 0
            }
        }
    }

    companion object {
        const val NAME = "engine-update"
        const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
        const val JITTER_MS = 60L * 60 * 1000
        const val MAX_ATTEMPTS = 6
    }
}

/**
 * The desktop's order-220 update initializer, the same shape as Android's `EngineUpdateInitializer`:
 * keeps the lane's daily gate aligned with the portable update policy while an engine can
 * serve — `NOT_IN_THIS_APK`, `DISABLED` and `NOT_YET_AVAILABLE` unschedule, `FAILED` stays
 * scheduled because an update may be the fix. `distinctUntilChanged` makes restarts the
 * only rewrite of a standing state.
 */
class DesktopEngineUpdateInitializer(
    private val settings: EngineSettings,
    private val engine: YouTubeEngine,
    private val scheduler: EngineUpdateScheduler,
    private val scope: CoroutineScope,
) : AppInitializer {
    override val order = 220

    override suspend fun run() {
        scope.launch {
            settings.awaitLoaded()
            combine(settings.state, engine.status) { snapshot, status ->
                snapshot.updatePolicy != EngineUpdatePolicy.OFF &&
                    status.availability !in INELIGIBLE
            }.distinctUntilChanged().collect { eligible ->
                scheduler.scheduleDaily(eligible)
            }
        }
    }

    private companion object {
        val INELIGIBLE =
            setOf(
                EngineAvailability.NOT_IN_THIS_APK,
                EngineAvailability.DISABLED,
                EngineAvailability.NOT_YET_AVAILABLE,
            )
    }
}
