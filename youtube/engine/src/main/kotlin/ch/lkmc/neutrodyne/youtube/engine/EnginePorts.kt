// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.engine

import ch.lkmc.neutrodyne.youtube.api.PlatformLimitation
import ch.lkmc.neutrodyne.youtube.api.PrewarmReason
import kotlinx.coroutines.flow.StateFlow
import okio.Path
import okio.Source

/** Transport allocates IDs, bounds encoded bytes (including Binder UTF-16/framing), ignores late
 * replies, and rethrows caller cancellation after sending cancel. Deadline includes bind/queue wait.
 * Client admission is <= status.workers: PLAYBACK, LOOKUP/SEARCH, BACKGROUND, FIFO within a priority.
 * Each worker owns one YoutubeDL per locale; a cancelled socket belongs to that call ID only.
 * At deadline+5s kill: timed call TIMEOUT, other calls ENGINE_UNAVAILABLE. Idle stop is 3 minutes.
 */
interface YtxTransport {
    val status: StateFlow<HostStatus>

    suspend fun call(
        method: YtxMethod,
        json: String,
        deadline: YtxDeadline,
    ): String

    fun cancel(callId: Long)

    fun prewarm(reason: PrewarmReason)

    fun shutdown()
}

/** Absolute app monotonic elapsed milliseconds, never wall time. Host adapter converts remaining
 * budget to its own monotonic origin on receipt; Python's monotonic origin need not match Kotlin.
 * Conversion cannot reset/extend the caller budget. Epoch seconds in shim results are separate.
 */
data class YtxDeadline(
    val elapsedRealtimeMs: Long,
)

data class HostStatus(
    val state: HostState,
    val host: HostKind,
    val workers: Int,
    val pid: Long? = null,
    val engineVersion: String? = null,
    val ejsVersion: String? = null,
    val shimApi: Int? = null,
    val python: String? = null,
    val jsChallenges: Boolean = false,
    val running: Int = 0,
    val queued: Int = 0,
    val inFlight: List<Long> = emptyList(),
    val lastExit: HostExit? = null,
) {
    init {
        require(workers > 0 && running in 0..workers && queued >= 0)
    }
}

enum class HostState { STOPPED, STARTING, COMPILING, READY, UNUSABLE }

enum class HostKind { CHAQUOPY, STDIO }

enum class HostExit { IDLE_STOP, SHUTDOWN, HANG_KILL, CRASH, START_FAILED, PROTOCOL_VIOLATION }

enum class YtxMethod(
    val wireName: String,
) {
    PING("ping"),
    VERSION("version"),
    SELFTEST("selftest"),
    RESOLVE("resolve"),
    FACTS("facts"),
    LOOKUP("lookup"),
    TAB_OPEN("tab_open"),
    TAB_NEXT("tab_next"),
    SEARCH_OPEN("search_open"),
    SEARCH_NEXT("search_next"),
}

enum class YtxCode {
    RATE_LIMITED,
    AGE_RESTRICTED,
    MEMBERS_ONLY,
    PRIVATE,
    REGION_BLOCKED,
    UPCOMING,
    LIVE,
    KIDS_ONLY,
    UNAVAILABLE,
    EXTRACTION,
    NETWORK,
    TIMEOUT,
    CANCELLED,
    CURSOR_EXPIRED,
    ENGINE_UNAVAILABLE,
}

/** Raw messages may contain signed URLs/cookies. Unknown wire codes map to EXTRACTION in W5. */
class YtxCallException(
    val code: String,
) : Exception("YouTube engine call failed") {
    override fun toString(): String = "YtxCallException(redacted)"
}

object YtxLimits {
    const val PAYLOAD_BYTES = 16 * 1024
    const val RESULT_BYTES = 128 * 1024
    const val HTTP_BODY_BYTES = 8 * 1024 * 1024
    const val DESCRIPTION_UTF8_BYTES = 32 * 1024
    const val TAB_PAGE_SIZE = 30
    const val SEARCH_PAGE_SIZE = 20
    const val CURSORS = 16
    const val CURSOR_IDLE_MS = 10 * 60_000L
    const val HANG_GRACE_MS = 5_000L
    const val IDLE_STOP_MS = 3 * 60_000L

    // Provisional, spike pending. COMPILING suspends START_MS, not the per-call deadline.
    const val START_MS = 15_000L
    const val COMPILE_MS = 120_000L
    const val PLAYBACK_WATCHDOG_MS = 35_000L
}

/** Main process only; paths never carry stream URLs. Bundled ZIP remains for minor-version recovery. */
interface EngineStorePaths {
    val root: Path
    val ytDlpCacheDir: Path
    val pythonMinor: String

    fun openBundledZip(): Source

    fun bundledJson(): String
}

/** W2 owns atomic active.json, bounded safe extraction and IO. No runtime update actions in M9a.
 * hostLibDir is null until prepared; playback returns transient preparing, never compiles inline.
 */
interface EngineStore {
    val preparation: StateFlow<EnginePreparation>

    suspend fun loadStatus()

    suspend fun prepareBundled(): PreparedEngine

    fun hostLibDir(): Path?
}

enum class EnginePreparation { UNPREPARED, PREPARING, COMPILED, FAILED }

data class CompileMarker(
    val appVersionCode: Int,
    val engineVersion: String,
    val pythonMinor: String,
)

data class PreparedEngine(
    val libDir: Path,
    val marker: CompileMarker,
)

/** W4 adapts DataStore in app process; one identity scopes failure counts across restarts/updates. */
interface EngineSettings {
    val state: StateFlow<EngineSettingsSnapshot>

    suspend fun awaitLoaded()

    suspend fun recordStartFailures(
        identity: CompileMarker,
        count: Int,
    )
}

data class EngineSettingsSnapshot(
    val enabled: Boolean = true,
    val startFailures: Int = 0,
    val failureIdentity: CompileMarker? = null,
)

/** W4 checks ABI/API before binding; x86_64 API26-27 returns X86_64_API_26_27, never launches Python.
 * Retry repeats this check. NOT_IN_THIS_APK wins over disabled, then unusable => ENGINE_FAILED.
 */
interface EnginePlatformPreflight {
    fun limitation(): PlatformLimitation?
}
