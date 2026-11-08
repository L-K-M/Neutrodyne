// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.engine

import ch.lkmc.neutrodyne.youtube.api.EngineRejectReason
import ch.lkmc.neutrodyne.youtube.api.EngineUpdateOutcome
import ch.lkmc.neutrodyne.youtube.api.EngineUpdatePolicy
import ch.lkmc.neutrodyne.youtube.api.EngineVersionSource
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

    /** `keys/engine-manifest-ed25519.pub`: base64 raw Ed25519 keys, one per line, `#` comments. */
    fun manifestKeysText(): String

    /** `keys/yt-dlp-release-key.asc`: the armored pinned upstream release key. */
    fun upstreamKeyText(): String
}

/** W2 owns atomic active.json, bounded safe extraction and IO, and the M9b version/staging records.
 * hostPrepared is null until prepared; playback returns transient preparing, never compiles inline.
 * During a self-test trial it returns the staged candidate; every other bind serves [EngineActive.current].
 */
interface EngineStore {
    val preparation: StateFlow<EnginePreparation>

    val active: StateFlow<EngineActive>

    suspend fun loadStatus()

    suspend fun prepareBundled(): PreparedEngine

    fun hostPrepared(): PreparedEngine?

    fun hostLibDir(): Path? = hostPrepared()?.libDir

    /** Removes any `staging/` residue; every update run starts with a clean staging directory. */
    suspend fun cleanStaging()

    /**
     * Writes the verified archive into `staging/`, extracts `lib/` and moves the result to
     * `versions/<version>`, then records the manifest sequence it was approved under. A version
     * directory that already exists (staged by a run that died or ran out of idle time) is reused
     * unchanged: nothing else writes it, so it still carries the checks its staging run passed.
     */
    suspend fun stageVersion(
        version: String,
        archive: ByteArray,
        ejsVersion: String?,
        sequence: Long,
    ): PreparedEngine

    /** Points [hostPrepared] at `versions/<version>` so the next bind serves the trial candidate. */
    suspend fun beginTrial(version: String): PreparedEngine

    /** Records the newest processed manifest `sequence` for replay protection. */
    suspend fun recordManifestSequence(sequence: Long)

    /** Ends the trial, deletes `versions/<version>` and records it in `rejected[]`. */
    suspend fun abortTrial(
        version: String,
        reason: EngineRejectReason,
        sequence: Long,
        atMs: Long,
    )

    /** Atomic switch: `current = trial`, `previous = old current`, activation recorded for the watch. */
    suspend fun commitTrial(
        atMs: Long,
        previousLastSuccessAtMs: Long?,
        breakerWasOpen: Boolean,
    )

    /**
     * Ends an unfinished trial with no verdict: a cancelled or failed run never proved the
     * candidate bad. `hostPrepared` serves `current` again, the staged tree stays
     * resumable, and `rejected[]` is untouched. Only a failed self-test takes [abortTrial].
     */
    suspend fun clearTrial()

    /** Rejects the current version and falls back to `previous`, else bundled. */
    suspend fun rollback(
        reason: EngineRejectReason,
        atMs: Long,
    ): EngineActive

    /** `current = bundled` at the next idle bind; downloaded versions stay on disk. */
    suspend fun resetToBundled()
}

enum class EnginePreparation { UNPREPARED, PREPARING, COMPILED, FAILED }

/** Snapshot of `active.json`: what a bind may serve and what an update run may touch. */
data class EngineActive(
    val current: String,
    val bundled: String,
    val previous: String?,
    val lastManifestSequence: Long,
    val rejected: List<RejectedEngine> = emptyList(),
    val activation: EngineActivation? = null,
    val trial: PreparedEngine? = null,
    /** A `versions/` directory that is neither current nor previous — a kept, unactivated stage. */
    val staged: String? = null,
) {
    val source: EngineVersionSource
        get() = if (current == bundled) EngineVersionSource.BUNDLED else EngineVersionSource.UPDATED
}

data class RejectedEngine(
    val version: String,
    val atMs: Long,
    /** `sequence` of the manifest that named this version when it was rejected (0: no manifest). */
    val sequence: Long,
    val reason: EngineRejectReason,
)

/** What a freshly activated version must remember for `EngineRollbackMonitor`'s 30-min watch. */
data class EngineActivation(
    val atMs: Long,
    val previousLastSuccessAtMs: Long?,
    val breakerWasOpen: Boolean,
)

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

    /** `youtube.engine_last_check_at`/`engine_last_outcome`, device-bound. */
    suspend fun recordCheck(
        atMs: Long,
        outcome: EngineUpdateOutcome,
    )

    /** `youtube.engine_heartbeat_at` — the canary heartbeat's own timestamp, device-bound. */
    suspend fun recordHeartbeat(issuedAtMs: Long)
}

data class EngineSettingsSnapshot(
    val enabled: Boolean = true,
    val startFailures: Int = 0,
    val failureIdentity: CompileMarker? = null,
    val updatePolicy: EngineUpdatePolicy = EngineUpdatePolicy.APPROVED,
    val lastCheckAtMs: Long? = null,
    val lastOutcome: EngineUpdateOutcome? = null,
    val heartbeatAtMs: Long? = null,
)

/** W4 checks ABI/API before binding; x86_64 API26-27 returns X86_64_API_26_27, never launches Python.
 * Retry repeats this check. NOT_IN_THIS_APK wins over disabled, then unusable => ENGINE_FAILED.
 */
interface EnginePlatformPreflight {
    fun limitation(): PlatformLimitation?
}

/**
 * Detached-Ed25519 check for the signed engine manifest and heartbeat (04 Trust chain). JDK 15+
 * and Android API 33+ use `Signature("Ed25519")` ([JdkEd25519Verifier]); older Android hosts use
 * `TinkEd25519Verifier` in `:youtube:ytdlp`. Returns false for any malformed input; never throws.
 */
interface Ed25519Verifier {
    fun verify(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean
}

object JdkEd25519Verifier : Ed25519Verifier {
    // SubjectPublicKeyInfo DER prefix for a raw 32-byte Ed25519 key (RFC 8410).
    private val spkiPrefix =
        byteArrayOf(0x30, 0x2A, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x70, 0x03, 0x21, 0x00)

    override fun verify(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        return try {
            val key =
                java.security.KeyFactory
                    .getInstance("Ed25519")
                    .generatePublic(java.security.spec.X509EncodedKeySpec(spkiPrefix + publicKey))
            java.security.Signature
                .getInstance("Ed25519")
                .apply {
                    initVerify(key)
                    update(message)
                }.verify(signature)
        } catch (invalid: java.security.GeneralSecurityException) {
            false
        }
    }
}

/** HTTP access for update runs through 01's client family; plain `get` uses the API client. */
interface EngineHttp {
    /** GETs [url] over HTTPS, failing on non-2xx or a body beyond [maxBytes]. */
    suspend fun get(
        url: String,
        maxBytes: Long,
    ): ByteArray

    /** The redirect target of [url] without following it, or null when the response is final. */
    suspend fun redirectLocation(url: String): String?

    /** Like [get] but on the DOWNLOAD client; the only call that may move the 10 MB engine blob. */
    suspend fun download(
        url: String,
        maxBytes: Long,
    ): ByteArray
}

/** Non-2xx update responses; transport-level failures surface as `IOException`. */
class EngineHttpException(
    val code: Int,
) : Exception("Engine update request failed") {
    override fun toString(): String = "EngineHttpException(code=$code)"
}

/** Host-side scheduling port for `engine-update` (daily) and `engine-update-now` (breaker/manual). */
interface EngineUpdateScheduler {
    fun scheduleDaily(enabled: Boolean)

    fun requestImmediateCheck()
}

/** Compiles `versions/<version>/lib` before the self-test; a no-op on Android (the host compiles). */
interface EngineCompiler {
    suspend fun compile(lib: Path)
}

object NoOpEngineCompiler : EngineCompiler {
    override suspend fun compile(lib: Path) = Unit
}

enum class EngineUpdateTrigger { SCHEDULED, BREAKER, MANUAL }

/** What `YouTubeEngine` and `EngineUpdateWorker` need from one in-process `EngineUpdater`. */
interface EngineUpdateHooks {
    suspend fun runUpdate(trigger: EngineUpdateTrigger): EngineUpdateOutcome

    suspend fun resetToBundled()

    fun requestImmediateCheck()
}
