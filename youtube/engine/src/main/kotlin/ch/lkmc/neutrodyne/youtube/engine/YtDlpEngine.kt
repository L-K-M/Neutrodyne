// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.engine

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.model.ExternalReason
import ch.lkmc.neutrodyne.youtube.api.BreakerState
import ch.lkmc.neutrodyne.youtube.api.EngineAvailability
import ch.lkmc.neutrodyne.youtube.api.EngineStatus
import ch.lkmc.neutrodyne.youtube.api.EngineUpdateOutcome
import ch.lkmc.neutrodyne.youtube.api.EngineVersionSource
import ch.lkmc.neutrodyne.youtube.api.PlatformLimitation
import ch.lkmc.neutrodyne.youtube.api.PrewarmReason
import ch.lkmc.neutrodyne.youtube.api.TransientKind
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilities
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.youtube.api.YouTubeEngine
import ch.lkmc.neutrodyne.youtube.api.YouTubeHealth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Portable lifecycle only. W4 owns starts/idle timers and ignores late replies after shutdown.
 * M9b: the served version comes from `store.active` (bundled or a verified download); update
 * checks route to [EngineUpdateHooks] and a breaker opening requests `engine-update-now`.
 */
class YtDlpEngine(
    private val transport: YtxTransport,
    private val store: EngineStore,
    private val settings: EngineSettings,
    private val preflight: EnginePlatformPreflight,
    private val identity: CompileMarker,
    private val health: YouTubeHealth,
    private val clock: Clock,
    scope: CoroutineScope,
    private val hooks: EngineUpdateHooks? = null,
) : YouTubeEngine,
    YouTubeCapabilitiesSource {
    private val lock = Any()
    private var loaded = false
    private var enabled = settings.state.value.enabled
    private var failures = scopedFailures()
    private var limitation = preflight.limitation()
    private var previousHost = transport.status.value
    private var startRequested = false
    private var pendingPrewarm: PrewarmReason? = null
    private var acceptReady = transport.status.value.state == HostState.READY
    private var hostUnusable = transport.status.value.state == HostState.UNUSABLE
    private var failureRevision = 0L
    private val writes = Channel<Pair<Long, Int>>(Channel.UNLIMITED)
    private val healthLoaded = CompletableDeferred<Unit>()
    private val mutableStatus = MutableStateFlow(EngineStatus(EngineAvailability.STOPPED, identity.engineVersion))
    override val status = mutableStatus.asStateFlow()

    // Settings adapters publish a default snapshot before awaitLoaded() returns; a persisted
    // opt-out is not yet visible, so capabilities must stay off until the load completes.
    private val mutableCapabilities = MutableStateFlow(capabilitiesFor(ExternalReason.NOT_YET_AVAILABLE))
    override val capabilities = mutableCapabilities.asStateFlow()

    init {
        synchronized(lock) { publish() }
        scope.launch {
            // The consent boundary is the settings load alone: a blocked health load must not
            // freeze capabilities or deafen live toggles, so observation starts right after the
            // settings sync block, independent of the health barrier below.
            settings.awaitLoaded()
            synchronized(lock) {
                enabled = settings.state.value.enabled
                if (failureRevision == 0L) {
                    failures = scopedFailures()
                }
                if (failureRevision == 0L && enabled && limitation == null &&
                    transport.status.value.state == HostState.READY
                ) {
                    acceptReady = true
                    if (failures != 0) {
                        failures = 0
                        recordFailures()
                    }
                }
                loaded = true
                publish()
                if (!enabled) transport.shutdown()
                pendingPrewarm?.let {
                    pendingPrewarm = null
                    prewarm(it)
                }
            }
            scope.launch {
                combine(
                    settings.state,
                    transport.status,
                    store.preparation,
                    store.active,
                ) { prefs, host, _, _ ->
                    prefs.enabled to host
                }.collect { (newEnabled, host) ->
                    synchronized(lock) {
                        if (enabled != newEnabled) {
                            enabled = newEnabled
                            acceptReady = false
                            startRequested = false
                            if (!enabled) transport.shutdown()
                        }
                        observeHost(host)
                        publish()
                    }
                }
            }
            // Health-boundary steps keep their original post-restore ordering: reporting the
            // engine version before the restore CAS would drop persisted breaker state, and the
            // failure identity reset belongs to the same ordering. M9b reports the served
            // version from `store.active`, not the compile identity. The barrier is awaited
            // once; the breaker watcher waits on the shared gate.
            health.awaitLoaded()
            healthLoaded.complete(Unit)
            var reported =
                synchronized(lock) {
                    val served =
                        store.active.value.current
                            .ifEmpty { null }
                    health.reportEngineVersion(served)
                    if (settings.state.value.failureIdentity != identity) recordFailures()
                    served
                }
            // Activations, rollbacks and resets after the barrier change the served version
            // without touching settings or host state, so they report through `store.active`.
            store.active
                .map { it.current.ifEmpty { null } }
                .distinctUntilChanged()
                .collect { version ->
                    if (version == reported) return@collect
                    synchronized(lock) {
                        reported = version
                        health.reportEngineVersion(version)
                    }
                }
        }
        scope.launch {
            healthLoaded.await()
            var opened = health.state.value.breaker != BreakerState.CLOSED
            health.state
                .map { it.breaker }
                .distinctUntilChanged()
                .collect { breaker ->
                    val isOpen = breaker == BreakerState.OPEN
                    if (isOpen && !opened) hooks?.requestImmediateCheck()
                    opened = breaker != BreakerState.CLOSED
                }
        }
        scope.launch {
            settings.awaitLoaded()
            for ((revision, count) in writes) {
                if (synchronized(lock) { revision != failureRevision }) continue
                try {
                    settings.recordStartFailures(identity, count)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.e("YtDlpEngine", error) { "Could not persist engine start failures" }
                }
            }
        }
    }

    override fun prewarm(reason: PrewarmReason) {
        synchronized(lock) {
            publish()
            if (!loaded) {
                pendingPrewarm = reason
                return
            }
            if (mutableCapabilities.value.externalReason != null ||
                store.preparation.value != EnginePreparation.COMPILED
            ) {
                return
            }
            when (transport.status.value.state) {
                HostState.READY -> if (acceptReady) transport.prewarm(reason) else requestStart(reason)
                HostState.STOPPED -> requestStart(reason)
                HostState.STARTING, HostState.COMPILING -> Unit
                HostState.UNUSABLE -> if (!hostUnusable) requestStart(reason)
            }
        }
    }

    private fun requestStart(reason: PrewarmReason) {
        if (startRequested) return
        startRequested = true
        transport.prewarm(reason)
    }

    override fun retryStart() {
        synchronized(lock) {
            limitation = preflight.limitation()
            failures = 0
            hostUnusable = false
            recordFailures()
            startRequested = false
            if (!acceptReady && transport.status.value.state != HostState.STOPPED) transport.shutdown()
            publish()
        }
        prewarm(PrewarmReason.SCREEN)
    }

    override suspend fun checkForUpdate(): EngineUpdateOutcome =
        hooks?.runUpdate(EngineUpdateTrigger.MANUAL)
            ?: EngineUpdateOutcome.Failed(TransientKind.ENGINE_UNAVAILABLE)

    override suspend fun resetToBundled() {
        hooks?.resetToBundled()
    }

    private fun observeHost(host: HostStatus) {
        if (host == previousHost) return
        val previous = previousHost
        previousHost = host
        if (!enabled || limitation != null || !loaded || failures >= 3) return
        when (host.state) {
            HostState.STARTING, HostState.COMPILING -> {
                acceptReady = true
                startRequested = true
            }

            HostState.READY -> {
                if (acceptReady) {
                    startRequested = false
                    if (failures != 0) {
                        failures = 0
                        recordFailures()
                    }
                }
            }

            HostState.STOPPED -> {
                startRequested = false
                acceptReady = false
                if (host.lastExit == HostExit.START_FAILED &&
                    (
                        previous.state == HostState.STARTING || previous.state == HostState.COMPILING ||
                            previous.lastExit != HostExit.START_FAILED
                    )
                ) {
                    // A downloaded active or a staged trial is the monitor's/updater's problem;
                    // the start-failure counter only applies to the bundled engine.
                    val active = store.active.value
                    if (active.source == EngineVersionSource.BUNDLED && active.trial == null) {
                        failures = (failures + 1).coerceAtMost(3)
                        recordFailures()
                    }
                }
            }

            HostState.UNUSABLE -> {
                hostUnusable = true
                startRequested = false
                acceptReady = false
            }
        }
    }

    private fun scopedFailures(): Int {
        val saved = settings.state.value
        if (saved.failureIdentity != identity) return 0
        return saved.startFailures.coerceIn(0, 3)
    }

    private fun recordFailures() {
        check(writes.trySend(++failureRevision to failures).isSuccess) { "Engine settings writer stopped" }
    }

    private fun publish() {
        val host = transport.status.value
        val absent = limitation == PlatformLimitation.NO_ENGINE || limitation == PlatformLimitation.PROCESS_32_BIT
        val failed =
            limitation != null || failures >= 3 || hostUnusable ||
                store.preparation.value == EnginePreparation.FAILED
        val availability =
            when {
                absent -> EngineAvailability.NOT_IN_THIS_APK
                !loaded -> EngineAvailability.STOPPED
                !enabled -> EngineAvailability.DISABLED
                failed -> EngineAvailability.FAILED
                store.preparation.value != EnginePreparation.COMPILED -> EngineAvailability.PREPARING
                host.state == HostState.READY && acceptReady -> EngineAvailability.READY
                host.state == HostState.STARTING || host.state == HostState.COMPILING -> EngineAvailability.STARTING
                else -> EngineAvailability.STOPPED
            }
        val reason =
            when {
                absent -> ExternalReason.NOT_IN_THIS_APK
                !loaded -> ExternalReason.NOT_YET_AVAILABLE
                availability == EngineAvailability.DISABLED -> ExternalReason.DISABLED_BY_USER
                availability == EngineAvailability.FAILED -> ExternalReason.ENGINE_FAILED
                availability == EngineAvailability.NOT_YET_AVAILABLE -> ExternalReason.NOT_YET_AVAILABLE
                else -> null
            }
        val active = store.active.value
        val update = settings.state.value
        mutableStatus.value =
            EngineStatus(
                availability = availability,
                version = active.current.takeIf { it.isNotEmpty() && !absent },
                source = active.source,
                bundledVersion = active.bundled.takeIf { it.isNotEmpty() && !absent },
                updatePolicy = update.updatePolicy,
                lastCheckAtMs = update.lastCheckAtMs,
                lastOutcome = update.lastOutcome,
                approvalsStale =
                    update.heartbeatAtMs?.let { clock.now() - it > STALE_HEARTBEAT_MS } == true,
                // M9b does not ship the JS provider, regardless of a host diagnostic flag.
                platformLimitation = limitation ?: PlatformLimitation.HOST_UNUSABLE.takeIf { hostUnusable },
            )
        mutableCapabilities.value = capabilitiesFor(reason)
    }

    private fun capabilitiesFor(reason: ExternalReason?): YouTubeCapabilities {
        val available = reason == null
        return YouTubeCapabilities(available, available, available, available, available, reason)
    }

    private companion object {
        /** 04 Update flow: a valid heartbeat older than 48 h marks the approval channel stale. */
        const val STALE_HEARTBEAT_MS = 48L * 60 * 60 * 1000
    }
}
