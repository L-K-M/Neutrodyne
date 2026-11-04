// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.engine

import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.model.ExternalReason
import ch.lkmc.neutrodyne.youtube.api.EngineAvailability
import ch.lkmc.neutrodyne.youtube.api.EngineStatus
import ch.lkmc.neutrodyne.youtube.api.PlatformLimitation
import ch.lkmc.neutrodyne.youtube.api.PrewarmReason
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilities
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.youtube.api.YouTubeEngine
import ch.lkmc.neutrodyne.youtube.api.YouTubeHealth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Portable lifecycle only. W4 owns starts/idle timers and ignores late replies after shutdown.
 * The bundled compile identity is fixed for this app-scoped instance; M9a has no activation API.
 */
class YtDlpEngine(
    private val transport: YtxTransport,
    private val store: EngineStore,
    private val settings: EngineSettings,
    private val preflight: EnginePlatformPreflight,
    private val identity: CompileMarker,
    private val health: YouTubeHealth,
    scope: CoroutineScope,
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
    private val mutableStatus = MutableStateFlow(EngineStatus(EngineAvailability.STOPPED, identity.engineVersion))
    override val status = mutableStatus.asStateFlow()
    private val mutableCapabilities = MutableStateFlow(capabilitiesFor(null))
    override val capabilities = mutableCapabilities.asStateFlow()

    init {
        synchronized(lock) { publish() }
        scope.launch {
            settings.awaitLoaded()
            health.awaitLoaded()
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
                health.reportEngineVersion(identity.engineVersion)
                if (settings.state.value.failureIdentity != identity) recordFailures()
                publish()
                if (!enabled) transport.shutdown()
                pendingPrewarm?.let {
                    pendingPrewarm = null
                    prewarm(it)
                }
            }
            combine(settings.state, transport.status, store.preparation) { prefs, host, preparation ->
                Triple(prefs.enabled, host, preparation)
            }.collect { (newEnabled, host, _) ->
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
                    failures = (failures + 1).coerceAtMost(3)
                    recordFailures()
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
                !enabled -> EngineAvailability.DISABLED
                failed -> EngineAvailability.FAILED
                store.preparation.value != EnginePreparation.COMPILED -> EngineAvailability.PREPARING
                host.state == HostState.READY && acceptReady -> EngineAvailability.READY
                host.state == HostState.STARTING || host.state == HostState.COMPILING -> EngineAvailability.STARTING
                else -> EngineAvailability.STOPPED
            }
        val reason =
            when (availability) {
                EngineAvailability.NOT_IN_THIS_APK -> ExternalReason.NOT_IN_THIS_APK
                EngineAvailability.DISABLED -> ExternalReason.DISABLED_BY_USER
                EngineAvailability.FAILED -> ExternalReason.ENGINE_FAILED
                else -> null
            }
        mutableStatus.value =
            EngineStatus(
                availability = availability,
                version = identity.engineVersion.takeUnless { absent },
                // M9a does not ship the JS provider, regardless of a host diagnostic flag.
                platformLimitation = limitation ?: PlatformLimitation.HOST_UNUSABLE.takeIf { hostUnusable },
            )
        mutableCapabilities.value = capabilitiesFor(reason)
    }

    private fun capabilitiesFor(reason: ExternalReason?): YouTubeCapabilities {
        val available = reason == null
        return YouTubeCapabilities(available, available, available, available, available, reason)
    }
}
