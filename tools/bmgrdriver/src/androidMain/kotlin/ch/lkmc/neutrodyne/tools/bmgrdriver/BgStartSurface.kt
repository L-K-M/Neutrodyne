// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.tools.bmgrdriver

/**
 * Pure dump parsers for the `bgstart` lane (M4 P6b). Everything here is JVM-only so the
 * same surfaces the device run asserts can be unit tested on the host and re-parsed by
 * the strict aggregate checker on the pulled evidence files.
 *
 * The formats are AOSP dumpsys text: `cmd activity get-uid-state`, `dumpsys activity
 * services <component>` (`mAllowStart_*`, `infoAllowStartForeground`, `isForeground`)
 * and `dumpsys media_session` (`PlaybackState`).
 */
internal object BgStartSurface {
    const val SERVICE_CLASS = "ch.lkmc.neutrodyne.playback.impl.NeutrodynePlaybackService"
    const val SERVICE_COMPONENT = "ch.lkmc.neutrodyne/$SERVICE_CLASS"

    /** The exported action a real external controller binds — never `am startservice`. */
    const val BIND_ACTION = "androidx.media3.session.MediaLibraryService"

    /**
     * The standalone `:tools:bgactor` APK — a separate uid that stays demoted; a bind
     * from the driver's own instrumented uid would stamp a granting TOP/BFSL record.
     */
    const val ACTOR_COMPONENT = "ch.lkmc.neutrodyne.tools.bgactor/.BgActorActivity"
    const val ACTOR_SERVICE = "ch.lkmc.neutrodyne.tools.bgactor/.BgActorService"
    const val ACTOR_PROVIDER = "ch.lkmc.neutrodyne.tools.bgactor.provider"

    /**
     * The standalone `:tools:bgtrigger` APK — a third uid measured twice through
     * its `status` provider method, which performs a native Media3
     * MediaController connect to the target's exported MediaLibraryService and
     * reports the granted command set. Before its NotificationListenerService
     * is enabled the connect is untrusted (a real bind that makes the package
     * visible, but a command set without COMMAND_PLAY_PAUSE); after enablement
     * (`isTrustedForMediaControl` — the same membership a real companion app
     * carries) the connect is trusted, and its own bind is what stamps the
     * `code:PROC_STATE_BFGS`/`startForegroundCount:0` grant — the
     * impossibility row.
     */
    const val TRIGGER_PACKAGE = "ch.lkmc.neutrodyne.tools.bgtrigger"
    const val TRIGGER_PROVIDER = "ch.lkmc.neutrodyne.tools.bgtrigger.provider"
    const val TRIGGER_LISTENER = "$TRIGGER_PACKAGE/.BgTriggerListenerService"

    /** AOSP PROCESS_STATE_BOUND_FOREGROUND_SERVICE is hidden from the SDK. */
    const val PROCSTATE_BOUND_FOREGROUND_SERVICE = 5
    const val PROCSTATE_SERVICE = 10
    const val SETTLE_MS = 15_000L

    /**
     * Process-state names that are numerically below (worse than) BFGS(5). A record whose
     * uidState is any of these cannot have started a foreground service from procstate
     * alone; PER/PERU/TOP/BTOP/FGS/BFGS would falsify a claimed denial.
     */
    val UIDSTATE_BELOW_BFGS =
        setOf(
            "IMPF",
            "IMPB",
            "TRNB",
            "BAK",
            "SVC",
            "RCVR",
            "TPSL",
            "HVY",
            "HOME",
            "LAST",
            "CAC",
            "CCE",
            "CRE",
            "NONE",
            "NEX",
        )

    private val UID_STATE = Regex("""^\s*(-?\d+)\s*\(""", RegexOption.MULTILINE)
    private val IS_FOREGROUND = Regex("""isForeground=(true|false)""")
    private val SERVICE_RUNNING = Regex("""app=ProcessRecord\{""")
    private val NO_BINDING = Regex("""mAllowStart_noBinding=(\S+)""")
    private val BY_BINDINGS = Regex("""mAllowStart_byBindings=(\S+)""")
    private val BINDING_CLIENT =
        Regex("""\* Client AppBindRecord\{[0-9a-f]+ ProcessRecord\{[0-9a-f]+ \d+:(\S+)/u0a(\d+)\}""")

    // The record body itself carries one-level brackets (`uidBFSL: [BFSL]`); match the
    // balanced outer pair so the record never truncates at the first inner `]`.
    private val ALLOW_INFO = Regex("""infoAllowStartForeground=\[((?:[^\[\]]|\[[^\]]*\])*)\]""")
    private val TEMP_ALLOW = Regex("""tempAllowListReason\s*[:=]\s*([^;\]\s]+)""")
    private val FIELD = Regex("""([a-zA-Z]+)\s*:\s*([^;]*)""")

    // The dump tail `bindFromPackage:<pkg>: isBindService:<bool>` is not `;`-separated,
    // so isBindService, bindFromPackage and tempAllowListReason get dedicated readers.
    private val IS_BIND = Regex("""isBindService\s*:\s*(true|false)""")
    private val BIND_FROM = Regex("""bindFromPackage\s*:\s*([^\s:;]+)""")
    private val TEMP_IN_RECORD = Regex("""tempAllowListReason\s*:\s*(<null>|[^;\]]+)""")
    private val SERVICE_RECORD = Regex("""ServiceRecord\{""")
    private val PLAYBACK_LINE = Regex("""PlaybackState\s*\{[^\n]*""")
    private val STATE_FIELD = Regex("""state=(?:[A-Z_]+\()?(-?\d+)\)?""")
    private val POSITION_FIELD = Regex("""(?<!buffered )position=(-?\d+)""")
    private val SPEED_FIELD = Regex("""speed=(-?[\d.]+)""")
    private val UPDATED_FIELD = Regex("""updated=(-?\d+)""")

    /** `get-uid-state` prints `<int> (<NAME>)`; returns null on any other shape. */
    fun parseUidState(text: String): Int? =
        UID_STATE
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()

    /**
     * `pm list packages -U <filter>` prints `package:<pkg> uid:<int>` per matching package;
     * the filter is a substring match, so `ch.lkmc.neutrodyne` also lists the driver
     * package and the emitted order is not stable across API levels. Only the line for
     * `pkg` itself counts.
     */
    fun parseAppUid(
        text: String,
        pkg: String,
    ): Int? =
        Regex("""^package:${Regex.escape(pkg)}\s+uid[:=]\s*(\d+)""", RegexOption.MULTILINE)
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()

    fun isForegroundValue(text: String): Boolean? =
        IS_FOREGROUND
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.toBooleanStrictOrNull()

    fun serviceRecordPresent(text: String): Boolean = SERVICE_RECORD.containsMatchIn(text)

    /**
     * `app=ProcessRecord{...}` marks a live host process; `app=null` is a dead record (a
     * denied `am startservice` leaves exactly that phantom).
     */
    fun serviceRunning(text: String): Boolean = SERVICE_RUNNING.containsMatchIn(text)

    /** `pid:procName/u0aN` tokens of every bound client in a ServiceRecord's Bindings. */
    fun bindingClients(text: String): List<String> =
        BINDING_CLIENT
            .findAll(text)
            .map { "${it.groupValues[1]}/u0a${it.groupValues[2]}" }
            .toList()

    fun noBindingVerdict(text: String): String? = NO_BINDING.find(text)?.groupValues?.get(1)

    fun byBindingsVerdict(text: String): String? = BY_BINDINGS.find(text)?.groupValues?.get(1)

    /** One `infoAllowStartForeground=[...]` record inside a ServiceRecord dump. */
    data class AllowInfo(
        val callingPackage: String?,
        val callingUid: Int?,
        val uidState: String?,
        val uidBfsl: String?,
        val code: String?,
        val tempAllowListReason: String?,
        val bindFromPackage: String?,
        val isBindService: Boolean?,
        val startForegroundCount: String?,
    ) {
        /**
         * A non-bind attempt by the target — a genuine FGS start request. When the
         * record carries `isBindService` it alone decides; sparse records (API 31
         * dumps may omit the field) fall back to the calling uid.
         */
        fun selfAttempt(appUid: Int): Boolean = isBindService?.let { !it } ?: (callingUid == appUid)

        val hasBfslCapability: Boolean get() = uidBfsl?.contains("BFSL") == true

        /** Any verdict other than DENIED means the evaluated attempt would be allowed. */
        val granted: Boolean get() = code != null && code != "DENIED"

        /** The package whose binding propagated FGS capability; `null`/`"null"` = none. */
        val bindingGrant: String?
            get() = bindFromPackage?.takeIf { it.isNotBlank() && it != "null" && it != "<null>" }

        val tempAllowListed: Boolean
            get() =
                tempAllowListReason != null && tempAllowListReason != "<null>" &&
                    tempAllowListReason != "null"
    }

    fun allowInfos(text: String): List<AllowInfo> =
        ALLOW_INFO
            .findAll(text)
            .map { match ->
                val fields =
                    FIELD
                        .findAll(match.groupValues[1])
                        .associate { it.groupValues[1] to it.groupValues[2].trim() }
                AllowInfo(
                    callingPackage = fields["callingPackage"],
                    callingUid = fields["callingUid"]?.toIntOrNull(),
                    uidState = fields["uidState"],
                    uidBfsl = fields["uidBFSL"],
                    code = fields["code"],
                    tempAllowListReason =
                        TEMP_IN_RECORD.find(match.groupValues[1])?.groupValues?.get(1),
                    bindFromPackage =
                        BIND_FROM.find(match.groupValues[1])?.groupValues?.get(1),
                    isBindService =
                        IS_BIND
                            .find(match.groupValues[1])
                            ?.groupValues
                            ?.get(1)
                            ?.toBooleanStrictOrNull(),
                    startForegroundCount = fields["startForegroundCount"],
                )
            }.toList()

    /**
     * `tempAllowListReason` appears in both `activity services` records and
     * `activity processes`/`lru` process blocks; any non-null value anywhere means the
     * framework granted a temporary allowlist — an exemption that would falsify
     * the untrusted row's non-exemptness.
     */
    fun tempAllowListViolation(text: String): Boolean =
        TEMP_ALLOW.findAll(text).any {
            val value = it.groupValues[1]
            value != "<null>" && value != "null"
        }

    fun tempAllowListObserved(text: String): Boolean = TEMP_ALLOW.containsMatchIn(text)

    /** A `dumpsys media_session` PlaybackState sample for one session block. */
    data class PlaybackSample(
        val state: Int?,
        val position: Long?,
        val speed: Float?,
        val updatedElapsed: Long?,
    ) {
        val playing: Boolean get() = state == 3
        val paused: Boolean get() = state == 2
    }

    /**
     * Reads only the exact package's session block, including tag-prefixed AOSP headers.
     * Stop at the next sibling so a missing state cannot borrow another session's sample.
     */
    fun mediaSessionPlayback(
        text: String,
        pkg: String,
    ): PlaybackSample? {
        val header =
            Regex("""^(\s*)(?:\S+\s+)?${Regex.escape(pkg)}/\S*\s+\(userId=\d+\)\s*$""")
        val lines = text.lines()
        val pkgIndex = lines.indexOfFirst { header.containsMatchIn(it) }
        if (pkgIndex < 0) return null
        val indent = header.find(lines[pkgIndex])!!.groupValues[1].length
        var line: String? = null
        for (i in pkgIndex + 1 until lines.size) {
            val candidate = lines[i]
            if (candidate.isNotBlank() &&
                candidate.length - candidate.trimStart().length <= indent
            ) {
                break
            }
            if (PLAYBACK_LINE.containsMatchIn(candidate)) {
                line = candidate
                break
            }
        }
        if (line == null) return null
        return PlaybackSample(
            state =
                STATE_FIELD
                    .find(line)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull(),
            position =
                POSITION_FIELD
                    .find(line)
                    ?.groupValues
                    ?.get(1)
                    ?.toLongOrNull(),
            speed =
                SPEED_FIELD
                    .find(line)
                    ?.groupValues
                    ?.get(1)
                    ?.toFloatOrNull(),
            updatedElapsed =
                UPDATED_FIELD
                    .find(line)
                    ?.groupValues
                    ?.get(1)
                    ?.toLongOrNull(),
        )
    }
}
