// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.youtube.api.EngineRejectReason
import ch.lkmc.neutrodyne.youtube.api.EngineUpdateOutcome
import ch.lkmc.neutrodyne.youtube.api.PlatformLimitation
import ch.lkmc.neutrodyne.youtube.api.PrewarmReason
import ch.lkmc.neutrodyne.youtube.engine.CompileMarker
import ch.lkmc.neutrodyne.youtube.engine.Ed25519Verifier
import ch.lkmc.neutrodyne.youtube.engine.EngineActivation
import ch.lkmc.neutrodyne.youtube.engine.EngineActive
import ch.lkmc.neutrodyne.youtube.engine.EngineCompiler
import ch.lkmc.neutrodyne.youtube.engine.EngineHttp
import ch.lkmc.neutrodyne.youtube.engine.EngineHttpException
import ch.lkmc.neutrodyne.youtube.engine.EnginePlatformPreflight
import ch.lkmc.neutrodyne.youtube.engine.EnginePreparation
import ch.lkmc.neutrodyne.youtube.engine.EngineSettings
import ch.lkmc.neutrodyne.youtube.engine.EngineSettingsSnapshot
import ch.lkmc.neutrodyne.youtube.engine.EngineStore
import ch.lkmc.neutrodyne.youtube.engine.EngineStorePaths
import ch.lkmc.neutrodyne.youtube.engine.EngineUpdateScheduler
import ch.lkmc.neutrodyne.youtube.engine.HostKind
import ch.lkmc.neutrodyne.youtube.engine.HostState
import ch.lkmc.neutrodyne.youtube.engine.HostStatus
import ch.lkmc.neutrodyne.youtube.engine.PreparedEngine
import ch.lkmc.neutrodyne.youtube.engine.RejectedEngine
import ch.lkmc.neutrodyne.youtube.engine.YtxCallException
import ch.lkmc.neutrodyne.youtube.engine.YtxDeadline
import ch.lkmc.neutrodyne.youtube.engine.YtxMethod
import ch.lkmc.neutrodyne.youtube.engine.YtxTestArming
import ch.lkmc.neutrodyne.youtube.engine.YtxTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import okio.Buffer
import okio.Path
import okio.Path.Companion.toPath
import okio.Source

/** Scripts transport outcomes without pretending to provide admission/timeout/host policy (W4/W5). */
class FakeYtxTransport : YtxTransport {
    private var nextId = 0L

    data class Call(
        val id: Long,
        val method: YtxMethod,
        val json: String,
        val deadline: YtxDeadline,
    ) {
        override fun toString(): String = "Call(method=$method)"
    }

    override val status = MutableStateFlow(HostStatus(HostState.STOPPED, HostKind.CHAQUOPY, 2))
    val calls = mutableListOf<Call>()
    val cancelled = mutableListOf<Long>()
    val prewarms = mutableListOf<PrewarmReason>()
    var shutdowns = 0
    var answer: suspend (Call) -> String = { throw YtxCallException("ENGINE_UNAVAILABLE") }

    override suspend fun call(
        method: YtxMethod,
        json: String,
        deadline: YtxDeadline,
    ): String {
        val call = Call(++nextId, method, json, deadline)
        calls += call
        try {
            return answer(call)
        } catch (cancelled: CancellationException) {
            cancel(call.id)
            throw cancelled
        }
    }

    override fun cancel(callId: Long) {
        cancelled += callId
    }

    override fun prewarm(reason: PrewarmReason) {
        prewarms += reason
    }

    override fun shutdown() {
        shutdowns++
    }
}

class FakeEngineStorePaths : EngineStorePaths {
    override var root: Path = "/engine-test/ytdlp".toPath()
    override var ytDlpCacheDir: Path = "/engine-test/cache".toPath()
    override var pythonMinor: String = "3.14"
    var zip = byteArrayOf()
    var metadata = "{}"
    var manifestKeys = "# test manifest keys\n"
    var upstreamKey = ""

    override fun openBundledZip(): Source = Buffer().write(zip)

    override fun bundledJson(): String = metadata

    override fun manifestKeysText(): String = manifestKeys

    override fun upstreamKeyText(): String = upstreamKey
}

class FakeEngineStore : EngineStore {
    override val preparation = MutableStateFlow(EnginePreparation.UNPREPARED)
    override val active = MutableStateFlow(EngineActive("", "", null, 0))
    var loads = 0
    var preparations = 0
    var trialPrepared: PreparedEngine? = null
    var libDir: Path? = null
    var prepare: suspend () -> PreparedEngine = { error("FakeEngineStore.prepare not armed") }
    var stage: suspend (String, ByteArray, String?, Long) -> PreparedEngine =
        { v, _, _, _ -> PreparedEngine("/engine-test/ytdlp/versions/$v/lib".toPath(), CompileMarker(1, v, "3.14")) }
    val rollbacks = mutableListOf<Pair<EngineRejectReason, Long>>()
    var resets = 0
    var cleanups = 0
    val trials = mutableListOf<String>()
    val aborted = mutableListOf<String>()
    var commits = 0

    override suspend fun loadStatus() {
        loads++
    }

    override suspend fun prepareBundled(): PreparedEngine {
        preparations++
        return prepare()
    }

    override fun hostPrepared(): PreparedEngine? =
        trialPrepared ?: libDir?.let { PreparedEngine(it, CompileMarker(1, active.value.current, "3.14")) }

    override suspend fun cleanStaging() {
        cleanups++
    }

    override suspend fun stageVersion(
        version: String,
        archive: ByteArray,
        ejsVersion: String?,
        sequence: Long,
    ): PreparedEngine {
        val candidate = stage(version, archive, ejsVersion, sequence)
        active.value = active.value.copy(staged = version)
        return candidate
    }

    override suspend fun recordManifestSequence(sequence: Long) {
        active.value = active.value.copy(lastManifestSequence = maxOf(active.value.lastManifestSequence, sequence))
    }

    override suspend fun beginTrial(version: String): PreparedEngine {
        trials += version
        val candidate =
            trialPrepared
                ?: PreparedEngine(
                    "/engine-test/ytdlp/versions/$version/lib".toPath(),
                    CompileMarker(1, version, "3.14"),
                )
        trialPrepared = candidate
        active.value = active.value.copy(trial = candidate)
        return candidate
    }

    override suspend fun abortTrial(
        version: String,
        reason: EngineRejectReason,
        sequence: Long,
        atMs: Long,
    ) {
        aborted += version
        trialPrepared = null
        active.value =
            active.value.copy(
                trial = null,
                staged = null,
                rejected = active.value.rejected + RejectedEngine(version, atMs, sequence, reason),
            )
    }

    override suspend fun commitTrial(
        atMs: Long,
        previousLastSuccessAtMs: Long?,
        breakerWasOpen: Boolean,
    ) {
        commits++
        val candidate = trialPrepared ?: error("No trial")
        trialPrepared = null
        active.value =
            active.value.copy(
                current = candidate.marker.engineVersion,
                previous = active.value.current,
                staged = null,
                trial = null,
                activation = EngineActivation(atMs, previousLastSuccessAtMs, breakerWasOpen),
            )
    }

    override suspend fun clearTrial() {
        trialPrepared = null
        active.value = active.value.copy(trial = null)
    }

    override suspend fun rollback(
        reason: EngineRejectReason,
        atMs: Long,
    ): EngineActive {
        rollbacks += reason to atMs
        trialPrepared = null
        val before = active.value
        active.value =
            before.copy(
                current = before.previous ?: before.bundled,
                previous = null,
                trial = null,
                staged = null,
                activation = null,
                rejected = before.rejected + RejectedEngine(before.current, atMs, before.lastManifestSequence, reason),
            )
        return active.value
    }

    override suspend fun resetToBundled() {
        resets++
        active.value = active.value.copy(current = active.value.bundled, activation = null)
    }
}

class FakeEngineSettings(
    override val state: MutableStateFlow<EngineSettingsSnapshot> = MutableStateFlow(EngineSettingsSnapshot()),
) : EngineSettings {
    val writes = mutableListOf<Pair<CompileMarker, Int>>()
    val checks = mutableListOf<Pair<Long, EngineUpdateOutcome>>()
    val heartbeats = mutableListOf<Long>()
    var loads = 0

    override suspend fun awaitLoaded() {
        loads++
    }

    override suspend fun recordStartFailures(
        identity: CompileMarker,
        count: Int,
    ) {
        writes += identity to count
        state.update { it.copy(startFailures = count, failureIdentity = identity) }
    }

    override suspend fun recordCheck(
        atMs: Long,
        outcome: EngineUpdateOutcome,
    ) {
        checks += atMs to outcome
        state.update { it.copy(lastCheckAtMs = atMs, lastOutcome = outcome) }
    }

    override suspend fun recordHeartbeat(issuedAtMs: Long) {
        heartbeats += issuedAtMs
        state.update { it.copy(heartbeatAtMs = issuedAtMs) }
    }

    /** Test-side durable enable write; `EngineSettings` exposes no enabled setter to production. */
    fun setEnabled(enabled: Boolean) {
        state.update { it.copy(enabled = enabled) }
    }
}

class FakeEngineHttp : EngineHttp {
    val gets = mutableListOf<String>()
    val downloads = mutableListOf<String>()
    val redirects = mutableListOf<String>()
    var getAnswer: suspend (String) -> ByteArray = { throw EngineHttpException(404) }
    var downloadAnswer: suspend (String) -> ByteArray = { throw EngineHttpException(404) }
    var redirectAnswer: suspend (String) -> String? = { null }

    override suspend fun get(
        url: String,
        maxBytes: Long,
    ): ByteArray {
        gets += url
        return getAnswer(url)
    }

    override suspend fun redirectLocation(url: String): String? {
        redirects += url
        return redirectAnswer(url)
    }

    override suspend fun download(
        url: String,
        maxBytes: Long,
    ): ByteArray {
        downloads += url
        return downloadAnswer(url)
    }
}

class FakeEd25519Verifier : Ed25519Verifier {
    var answer: (ByteArray, ByteArray, ByteArray) -> Boolean = { _, _, _ -> false }
    val calls = mutableListOf<Int>()

    override fun verify(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean {
        calls += message.size
        return answer(publicKey, message, signature)
    }
}

class FakeEngineUpdateScheduler : EngineUpdateScheduler {
    val scheduled = mutableListOf<Boolean>()
    var immediate = 0

    override fun scheduleDaily(enabled: Boolean) {
        scheduled += enabled
    }

    override fun requestImmediateCheck() {
        immediate++
    }
}

class FakeEngineCompiler : EngineCompiler {
    val compiled = mutableListOf<Path>()

    override suspend fun compile(lib: Path) {
        compiled += lib
    }
}

class FakeEnginePlatformPreflight : EnginePlatformPreflight {
    var result: PlatformLimitation? = null
    var checks = 0

    override fun limitation(): PlatformLimitation? {
        checks++
        return result
    }
}

class FakeYtxTestArming : YtxTestArming {
    var directory: Path? = null
        private set

    override fun arm(replayDirectory: Path) {
        directory = replayDirectory
    }

    override fun clear() {
        directory = null
    }
}
