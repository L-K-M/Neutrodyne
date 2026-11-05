// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.engine

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.youtube.api.BreakerState
import ch.lkmc.neutrodyne.youtube.api.EngineRejectReason
import ch.lkmc.neutrodyne.youtube.api.EngineUpdateOutcome
import ch.lkmc.neutrodyne.youtube.api.EngineUpdatePolicy
import ch.lkmc.neutrodyne.youtube.api.ExtractionOutcome
import ch.lkmc.neutrodyne.youtube.api.TransientKind
import ch.lkmc.neutrodyne.youtube.api.YouTubeHealth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * One serialized update run (04 Update flow): every check is re-validated; the active engine is
 * never touched before checks 1–10 pass and the staged candidate survives an on-host self-test.
 * The host runner (`EngineUpdateWorker` on Android) only calls [runUpdate]; everything else —
 * throttling, staging, the idle window, the trial and rollback records — lives here and in
 * [EngineRollbackMonitor].
 */
class EngineUpdater(
    private val store: EngineStore,
    private val settings: EngineSettings,
    private val http: EngineHttp,
    private val keys: EngineKeys,
    private val verifier: Ed25519Verifier,
    private val scheduler: EngineUpdateScheduler,
    private val transport: YtxTransport,
    private val client: YtDlpClient,
    private val compiler: EngineCompiler,
    private val preflight: EnginePlatformPreflight,
    private val health: YouTubeHealth,
    private val monitor: EngineRollbackMonitor,
    private val clock: Clock,
    private val manifestUrl: String,
    private val control: () -> YtxControlRequest,
    // The fixture server's base in tests; production always uses GitHub Releases.
    private val releaseBaseUrl: String = RELEASES,
) : EngineUpdateHooks {
    private val mutex = Mutex()
    private val heartbeatUrl = manifestUrl.substringBeforeLast('/') + "/ytdlp-heartbeat.json"
    private val runner = EngineSelfTestRunner(store, transport, compiler, clock, control)

    override suspend fun runUpdate(trigger: EngineUpdateTrigger): EngineUpdateOutcome =
        mutex.withLock {
            try {
                runLocked(trigger)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cap: EngineSizeCapException) {
                finish(EngineUpdateOutcome.Rejected(null, EngineRejectReason.SIZE_CAP))
            } catch (http: EngineHttpException) {
                Log.w(TAG, http) { "Engine update request failed" }
                finish(EngineUpdateOutcome.Failed(TransientKind.NETWORK))
            } catch (io: IOException) {
                Log.w(TAG, io) { "Engine update request failed" }
                finish(EngineUpdateOutcome.Failed(TransientKind.NETWORK))
            }
        }

    override suspend fun resetToBundled() {
        mutex.withLock {
            store.resetToBundled()
            if (awaitIdle()) transport.shutdown()
        }
    }

    override fun requestImmediateCheck() = scheduler.requestImmediateCheck()

    private suspend fun runLocked(trigger: EngineUpdateTrigger): EngineUpdateOutcome {
        settings.awaitLoaded()
        val snapshot = settings.state.value
        if (!snapshot.enabled || preflight.limitation() != null) return EngineUpdateOutcome.UpToDate
        if (snapshot.updatePolicy == EngineUpdatePolicy.OFF) return EngineUpdateOutcome.UpToDate
        if (trigger == EngineUpdateTrigger.BREAKER) {
            val last = snapshot.lastCheckAtMs
            if (last != null && clock.now() - last < BREAKER_THROTTLE_MS) return EngineUpdateOutcome.UpToDate
        }
        store.cleanStaging()
        return when (snapshot.updatePolicy) {
            EngineUpdatePolicy.APPROVED -> approvedRun()
            EngineUpdatePolicy.UPSTREAM_STABLE -> upstreamRun()
            EngineUpdatePolicy.OFF -> EngineUpdateOutcome.UpToDate
        }
    }

    private suspend fun approvedRun(): EngineUpdateOutcome {
        val manifestBytes = http.get(manifestUrl, MAX_MANIFEST_BYTES)
        val signature =
            try {
                http.get("$manifestUrl.sig", MAX_SIGNATURE_BYTES).decodeToString()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (missing: Exception) {
                return finish(EngineUpdateOutcome.Rejected(null, EngineRejectReason.MANIFEST_SIGNATURE))
            }
        recordHeartbeat()
        val manifest =
            EngineManifestVerifier.verify(manifestBytes, signature, keys, verifier)
                ?: return finish(EngineUpdateOutcome.Rejected(null, EngineRejectReason.MANIFEST_SIGNATURE))
        val active = store.active.value
        if (manifest.sequence < active.lastManifestSequence) {
            return finish(EngineUpdateOutcome.Rejected(manifest.version, EngineRejectReason.MANIFEST_REPLAYED))
        }
        if (EngineManifestVerifier.SHIM_API_VERSION !in manifest.shimApi.min..manifest.shimApi.max) {
            return finish(EngineUpdateOutcome.Rejected(manifest.version, EngineRejectReason.SHIM_INCOMPATIBLE))
        }
        val revokedActive = active.current in manifest.revoked
        if (manifest.sequence == active.lastManifestSequence && active.staged == null) {
            if (revokedActive) return finish(rollbackNow(EngineRejectReason.REVOKED, manifest.version))
            return finish(EngineUpdateOutcome.UpToDate)
        }
        if (manifest.version == active.current) {
            return finish(
                if (revokedActive) {
                    rollbackNow(EngineRejectReason.REVOKED, manifest.version)
                } else {
                    recordSequence(manifest.sequence)
                    EngineUpdateOutcome.UpToDate
                },
            )
        }
        val blocked = active.rejected.firstOrNull { it.version == manifest.version }
        if (blocked != null && clock.now() - blocked.atMs < REJECT_MS && manifest.sequence <= blocked.sequence) {
            if (revokedActive) rollbackRecord(EngineRejectReason.REVOKED)
            return finish(EngineUpdateOutcome.Rejected(manifest.version, blocked.reason))
        }
        if (compareVersions(manifest.version, active.bundled) < 0) {
            if (revokedActive) rollbackRecord(EngineRejectReason.REVOKED)
            return finish(EngineUpdateOutcome.Rejected(manifest.version, EngineRejectReason.BELOW_BUNDLED))
        }
        if (manifest.ytdlp.repo != RELEASE_REPO || manifest.ytdlp.asset != RELEASE_ASSET) {
            if (revokedActive) rollbackRecord(EngineRejectReason.REVOKED)
            return finish(EngineUpdateOutcome.Rejected(manifest.version, EngineRejectReason.ORIGIN))
        }
        return stageAndTry(
            version = manifest.version,
            tag = manifest.ytdlp.tag,
            expectedSha256 = manifest.ytdlp.sha256,
            ejsVersion = manifest.ytdlp.ejsVersion,
            sequence = manifest.sequence,
            revokedActive = revokedActive,
        )
    }

    private suspend fun upstreamRun(): EngineUpdateOutcome {
        val location =
            http.redirectLocation("$releaseBaseUrl/latest")
                ?: return finish(EngineUpdateOutcome.Failed(TransientKind.NETWORK))
        val tag = location.substringAfterLast('/')
        if (!VERSION_PATTERN.matches(tag)) {
            return finish(EngineUpdateOutcome.Rejected(tag, EngineRejectReason.ORIGIN))
        }
        val active = store.active.value
        if (tag == active.current || compareVersions(tag, active.current) <= 0) {
            return finish(EngineUpdateOutcome.UpToDate)
        }
        if (compareVersions(tag, active.bundled) < 0) {
            return finish(EngineUpdateOutcome.Rejected(tag, EngineRejectReason.BELOW_BUNDLED))
        }
        return stageAndTry(tag, tag, expectedSha256 = null, ejsVersion = null, sequence = 0, revokedActive = false)
    }

    /**
     * Checks 7–11: verify the upstream signature over `SHA2-256SUMS`, match the archive hash,
     * extract under `versions/`, wait for engine idle, self-test the candidate in a fresh host
     * and only then switch `current` atomically. A kept `versions/<version>` resumes at the
     * idle wait — nothing re-downloads what a previous run already verified.
     */
    private suspend fun stageAndTry(
        version: String,
        tag: String,
        expectedSha256: String?,
        ejsVersion: String?,
        sequence: Long,
        revokedActive: Boolean,
    ): EngineUpdateOutcome {
        val archive: ByteArray? =
            if (store.active.value.staged == version) {
                null
            } else {
                val base = "$releaseBaseUrl/download/$tag"
                val sums = http.get("$base/SHA2-256SUMS", MAX_SUMS_BYTES)
                val sumsSignature =
                    try {
                        http.get("$base/SHA2-256SUMS.sig", MAX_SUMS_BYTES)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (missing: Exception) {
                        return rejected(version, EngineRejectReason.UPSTREAM_SIGNATURE, revokedActive)
                    }
                val entries =
                    UpstreamReleaseVerifier.verify(sums, sumsSignature, keys.upstream)
                        ?: return rejected(version, EngineRejectReason.UPSTREAM_SIGNATURE, revokedActive)
                val lineHash =
                    entries[RELEASE_ASSET]
                        ?: return rejected(version, EngineRejectReason.ORIGIN, revokedActive)
                if (expectedSha256 != null && lineHash != expectedSha256) {
                    return rejected(version, EngineRejectReason.HASH_MISMATCH, revokedActive)
                }
                val blob = http.download("$base/$RELEASE_ASSET", BundledZip.MAX_ZIP_BYTES)
                if (UpstreamReleaseVerifier.sha256(blob) != lineHash) {
                    return rejected(version, EngineRejectReason.HASH_MISMATCH, revokedActive)
                }
                blob
            }
        val candidate =
            try {
                store.stageVersion(version, archive ?: byteArrayOf(), ejsVersion, sequence)
            } catch (invalid: IllegalArgumentException) {
                return rejected(version, EngineRejectReason.ORIGIN, revokedActive)
            }
        if (!awaitIdle()) return finish(EngineUpdateOutcome.Staged(version))
        if (!runner.run(candidate)) {
            transport.shutdown()
            store.abortTrial(version, EngineRejectReason.SELFTEST_FAILED, sequence, clock.now())
            if (revokedActive) rollbackRecord(EngineRejectReason.REVOKED)
            return finish(EngineUpdateOutcome.Rejected(version, EngineRejectReason.SELFTEST_FAILED))
        }
        val basis = monitor.activationBasis()
        store.commitTrial(clock.now(), basis.lastSuccessAtMs, basis.breakerOpen)
        health.reportEngineVersion(version)
        return finish(EngineUpdateOutcome.Activated(version))
    }

    private suspend fun rejected(
        version: String,
        reason: EngineRejectReason,
        revokedActive: Boolean,
    ): EngineUpdateOutcome {
        if (revokedActive) rollbackRecord(EngineRejectReason.REVOKED)
        return finish(EngineUpdateOutcome.Rejected(version, reason))
    }

    /** Rolls the record back without touching the flow's reported outcome shape. */
    private suspend fun rollbackNow(
        reason: EngineRejectReason,
        version: String?,
    ): EngineUpdateOutcome {
        rollbackRecord(reason)
        return EngineUpdateOutcome.Rejected(version, reason)
    }

    private suspend fun rollbackRecord(reason: EngineRejectReason) {
        val active = store.rollback(reason, clock.now())
        transport.shutdown()
        health.reportEngineVersion(active.current)
    }

    private suspend fun recordSequence(sequence: Long) {
        store.recordManifestSequence(sequence)
    }

    private suspend fun awaitIdle(): Boolean =
        withTimeoutOrNull(IDLE_WAIT_MS) {
            combine(client.pendingOperations, transport.status) { pending, host ->
                pending == 0 && host.inFlight.isEmpty() &&
                    host.state != HostState.STARTING && host.state != HostState.COMPILING
            }.first { it }
            true
        } ?: false

    private suspend fun recordHeartbeat() {
        try {
            val document = http.get(heartbeatUrl, MAX_HEARTBEAT_BYTES)
            val signature = http.get("$heartbeatUrl.sig", MAX_SIGNATURE_BYTES).decodeToString()
            val issued =
                EngineManifestVerifier.verifyHeartbeat(document, signature, keys, verifier) ?: return
            settings.recordHeartbeat(issued)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (ignored: Exception) {
            // The heartbeat only reports canary liveness; it never blocks an update.
        }
    }

    private suspend fun finish(outcome: EngineUpdateOutcome): EngineUpdateOutcome {
        settings.recordCheck(clock.now(), outcome)
        return outcome
    }

    companion object {
        private const val TAG = "EngineUpdater"
        private const val RELEASE_REPO = "yt-dlp/yt-dlp"
        private const val RELEASE_ASSET = "yt-dlp"
        private const val RELEASES = "https://github.com/yt-dlp/yt-dlp/releases"
        private const val MAX_MANIFEST_BYTES = 64L * 1024
        private const val MAX_SIGNATURE_BYTES = 4L * 1024
        private const val MAX_SUMS_BYTES = 64L * 1024
        private const val MAX_HEARTBEAT_BYTES = 4L * 1024
        private const val IDLE_WAIT_MS = 5L * 60 * 1000
        private const val BREAKER_THROTTLE_MS = 3L * 60 * 60 * 1000
        private const val REJECT_MS = 72L * 60 * 60 * 1000
        private val VERSION_PATTERN = Regex("[0-9]{4}\\.[0-9]{2}\\.[0-9]{2}")
    }
}

/**
 * The fresh-host self-test of check 11: Android compiles on first host import, so compile is a
 * no-op here; `transport.shutdown()` plus `beginTrial` makes the next bind serve the candidate's
 * `versions/<version>/lib`, and `selftest` then probes every shim-used symbol without network.
 */
class EngineSelfTestRunner(
    private val store: EngineStore,
    private val transport: YtxTransport,
    private val compiler: EngineCompiler,
    private val clock: Clock,
    private val control: () -> YtxControlRequest,
) {
    suspend fun run(candidate: PreparedEngine): Boolean =
        try {
            compiler.compile(candidate.libDir)
            store.beginTrial(candidate.marker.engineVersion)
            transport.shutdown()
            val reply =
                transport.call(
                    YtxMethod.SELFTEST,
                    control().toJson(),
                    YtxDeadline(clock.elapsedRealtime() + SELFTEST_MS),
                )
            val result = Json.decodeFromString<YtxSelftest>(reply)
            result.ok && result.missing.isEmpty()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            false
        }

    private companion object {
        const val SELFTEST_MS = 150_000L
    }
}

/**
 * Watches `YouTubeHealth` outcomes and failed host starts after an activation (04 Rollback):
 * ≥ 3 `ParseFailure`s on distinct videos within 30 min of the activation and no `Success` roll
 * back to `previous` — but only when the previous version had a `Success` within the 24 h before
 * activation and the breaker was closed then (otherwise the fallback is not known to work).
 * A downloaded version that fails to start rolls back immediately. All records live in the store;
 * a restarted process re-reads the activation window from `active.json`.
 */
class EngineRollbackMonitor(
    private val store: EngineStore,
    private val transport: YtxTransport,
    private val health: YouTubeHealth,
    private val clock: Clock,
    scope: CoroutineScope,
) {
    private val lock = Any()
    private var lastSuccessAtMs: Long? = null
    private val failures = linkedSetOf<String?>()
    private val rollbackMutex = Mutex()

    init {
        scope.launch {
            health.awaitLoaded()
            health.outcomes.collect { onOutcome(it) }
        }
        scope.launch {
            var previous = transport.status.value
            transport.status.collect { status ->
                val before = previous
                previous = status
                onHostTransition(before, status)
            }
        }
    }

    /** What an activation must remember: the old version's last `Success` and the breaker state. */
    suspend fun activationBasis(): EngineActivationBasis {
        health.awaitLoaded()
        return EngineActivationBasis(
            synchronized(lock) { lastSuccessAtMs },
            health.state.value.breaker != BreakerState.CLOSED,
        )
    }

    private suspend fun onOutcome(outcome: ExtractionOutcome) {
        when (outcome) {
            is ExtractionOutcome.Success -> {
                synchronized(lock) {
                    lastSuccessAtMs = clock.now()
                    failures.clear()
                }
            }

            is ExtractionOutcome.ParseFailure -> {
                val now = clock.now()
                val activation = store.active.value.activation ?: return
                if (now - activation.atMs > WATCH_MS) return
                val previousWorked =
                    activation.previousLastSuccessAtMs != null &&
                        activation.atMs - activation.previousLastSuccessAtMs < PREVIOUS_WINDOW_MS
                if (!previousWorked || activation.breakerWasOpen) return
                val count =
                    synchronized(lock) {
                        failures += outcome.videoId
                        failures.size
                    }
                if (count >= DISTINCT_FAILURES) rollbackNow(EngineRejectReason.ROLLED_BACK)
            }

            else -> {
                Unit
            }
        }
    }

    private suspend fun onHostTransition(
        before: HostStatus,
        status: HostStatus,
    ) {
        if (status.state != HostState.STOPPED || status.lastExit != HostExit.START_FAILED) return
        if (before.state != HostState.STARTING && before.state != HostState.COMPILING &&
            before.lastExit == HostExit.START_FAILED
        ) {
            return
        }
        val active = store.active.value
        if (active.trial != null || active.source != ch.lkmc.neutrodyne.youtube.api.EngineVersionSource.UPDATED) {
            return
        }
        rollbackNow(EngineRejectReason.ROLLED_BACK)
    }

    private suspend fun rollbackNow(reason: EngineRejectReason) {
        rollbackMutex.withLock {
            val active = store.rollback(reason, clock.now())
            transport.shutdown()
            health.reportEngineVersion(active.current)
        }
    }

    private companion object {
        const val WATCH_MS = 30L * 60 * 1000
        const val PREVIOUS_WINDOW_MS = 24L * 60 * 60 * 1000
        const val DISTINCT_FAILURES = 3
    }
}

data class EngineActivationBasis(
    val lastSuccessAtMs: Long?,
    val breakerOpen: Boolean,
)

/** `youtube.engine_last_outcome` and work-output encoding: one line, no secrets or URLs. */
object EngineUpdateOutcomes {
    fun encode(outcome: EngineUpdateOutcome): String =
        when (outcome) {
            is EngineUpdateOutcome.UpToDate -> "upToDate"
            is EngineUpdateOutcome.Staged -> "staged:${outcome.version}"
            is EngineUpdateOutcome.Activated -> "activated:${outcome.version}"
            is EngineUpdateOutcome.Rejected -> "rejected:${outcome.version ?: ""}:${outcome.reason}"
            is EngineUpdateOutcome.Failed -> "failed:${outcome.kind}"
        }

    fun decode(text: String): EngineUpdateOutcome? {
        val parts = text.split(':', limit = 3)
        return when (parts[0]) {
            "upToDate" -> {
                EngineUpdateOutcome.UpToDate
            }

            "staged" -> {
                parts.getOrNull(1)?.let { EngineUpdateOutcome.Staged(it) }
            }

            "activated" -> {
                parts.getOrNull(1)?.let { EngineUpdateOutcome.Activated(it) }
            }

            "rejected" -> {
                parts.getOrNull(2)?.let {
                    EngineUpdateOutcome.Rejected(
                        parts[1].ifEmpty { null },
                        runCatching { EngineRejectReason.valueOf(it) }.getOrNull() ?: return null,
                    )
                }
            }

            "failed" -> {
                parts.getOrNull(1)?.let {
                    EngineUpdateOutcome.Failed(
                        runCatching { TransientKind.valueOf(it) }.getOrNull() ?: return null,
                    )
                }
            }

            else -> {
                null
            }
        }
    }
}
