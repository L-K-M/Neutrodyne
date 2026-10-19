// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.youtube.api.PlatformLimitation
import ch.lkmc.neutrodyne.youtube.api.PrewarmReason
import ch.lkmc.neutrodyne.youtube.engine.CompileMarker
import ch.lkmc.neutrodyne.youtube.engine.EnginePlatformPreflight
import ch.lkmc.neutrodyne.youtube.engine.EnginePreparation
import ch.lkmc.neutrodyne.youtube.engine.EngineSettings
import ch.lkmc.neutrodyne.youtube.engine.EngineSettingsSnapshot
import ch.lkmc.neutrodyne.youtube.engine.EngineStore
import ch.lkmc.neutrodyne.youtube.engine.EngineStorePaths
import ch.lkmc.neutrodyne.youtube.engine.HostKind
import ch.lkmc.neutrodyne.youtube.engine.HostState
import ch.lkmc.neutrodyne.youtube.engine.HostStatus
import ch.lkmc.neutrodyne.youtube.engine.PreparedEngine
import ch.lkmc.neutrodyne.youtube.engine.YtxCallException
import ch.lkmc.neutrodyne.youtube.engine.YtxDeadline
import ch.lkmc.neutrodyne.youtube.engine.YtxMethod
import ch.lkmc.neutrodyne.youtube.engine.YtxTestArming
import ch.lkmc.neutrodyne.youtube.engine.YtxTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
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

    override fun openBundledZip(): Source = Buffer().write(zip)

    override fun bundledJson(): String = metadata
}

class FakeEngineStore : EngineStore {
    override val preparation = MutableStateFlow(EnginePreparation.UNPREPARED)
    var loads = 0
    var preparations = 0
    var libDir: Path? = null
    var prepare: suspend () -> PreparedEngine = { error("FakeEngineStore.prepare not armed") }

    override suspend fun loadStatus() {
        loads++
    }

    override suspend fun prepareBundled(): PreparedEngine {
        preparations++
        return prepare()
    }

    override fun hostLibDir(): Path? = libDir
}

class FakeEngineSettings : EngineSettings {
    override val state = MutableStateFlow(EngineSettingsSnapshot())
    val writes = mutableListOf<Pair<CompileMarker, Int>>()
    var loads = 0

    override suspend fun awaitLoaded() {
        loads++
    }

    override suspend fun recordStartFailures(
        identity: CompileMarker,
        count: Int,
    ) {
        writes += identity to count
        state.value = state.value.copy(startFailures = count, failureIdentity = identity)
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
