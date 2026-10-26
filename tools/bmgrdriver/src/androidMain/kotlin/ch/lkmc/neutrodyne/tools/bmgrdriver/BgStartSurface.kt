// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.tools.bmgrdriver

/**
 * Pure dump parsers for the `bgstart` lane (M4 P6b). Everything here is JVM-only so the
 * same surfaces the device run asserts can be unit tested on the host and re-parsed by
 * the strict aggregate checker on the pulled evidence files.
 *
 * The formats are AOSP dumpsys text: `cmd activity get-uid-state`, `dumpsys activity
 * services <component>` (`mAllowStart_*`, `infoAllowStartForeground`, `isForeground`),
 * `dumpsys notification` (`NotificationRecord` header lines), `dumpsys audio`
 * (`AudioPlaybackConfiguration` items) and `dumpsys media_session` (`PlaybackState`).
 */
internal object BgStartSurface {
    const val PLAYBACK_NOTIFICATION = 4000
    const val RESUME_NOTIFICATION = 4001
    const val PLAYBACK_CHANNEL = "playback"
    const val RESUME_CHANNEL = "alerts"
    const val SERVICE_CLASS = "ch.lkmc.neutrodyne.playback.impl.NeutrodynePlaybackService"
    const val SERVICE_COMPONENT = "ch.lkmc.neutrodyne/$SERVICE_CLASS"
    const val SYSTEMUI_PACKAGE = "com.android.systemui"
    const val PROCSTATE_SERVICE = 10
    const val POSITION_ADVANCE_MS = 200L

    /**
     * Process-state names that are numerically below (worse than) BFGS(5). A record whose
     * uidState is any of these cannot have started a foreground service from procstate
     * alone; PER/PERU/TOP/BTOP/FGS/BFGS would falsify the denial.
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
    private val APP_UID = Regex("""uid[:=]\s*(\d+)""")
    private val IS_FOREGROUND = Regex("""isForeground=(true|false)""")
    private val NO_BINDING = Regex("""mAllowStart_noBinding=(\S+)""")
    private val BY_BINDINGS = Regex("""mAllowStart_byBindings=(\S+)""")

    // The record body itself carries one-level brackets (`uidBFSL: [BFSL]`); match the
    // balanced outer pair so the record never truncates at the first inner `]`.
    private val ALLOW_INFO = Regex("""infoAllowStartForeground=\[((?:[^\[\]]|\[[^\]]*\])*)\]""")
    private val TEMP_ALLOW = Regex("""tempAllowListReason\s*[:=]\s*([^;\]\s]+)""")
    private val FIELD = Regex("""([a-zA-Z]+)\s*:\s*([^;]*)""")

    // The dump tail `bindFromPackage:<pkg>: isBindService:<bool>` is not `;`-separated,
    // so isBindService and tempAllowListReason get dedicated ungreedy readers.
    private val IS_BIND = Regex("""isBindService\s*:\s*(true|false)""")
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

    /** `pm list packages -U` prints `package:<pkg> uid:<int>`; also accepts `uid=`. */
    fun parseAppUid(text: String): Int? =
        APP_UID
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
        val isBindService: Boolean?,
    ) {
        /** A non-bind attempt from the target itself (a genuine FGS start request). */
        fun selfAttempt(appUid: Int): Boolean = isBindService == false || callingUid == appUid

        val hasBfslCapability: Boolean get() = uidBfsl?.contains("BFSL") == true

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
                    isBindService =
                        IS_BIND
                            .find(match.groupValues[1])
                            ?.groupValues
                            ?.get(1)
                            ?.toBooleanStrictOrNull(),
                )
            }.toList()

    /**
     * `tempAllowListReason` appears in both `activity services` records and
     * `activity processes`/`lru` process blocks; any non-null value anywhere means the
     * framework granted a temporary allowlist, which would falsify the denial.
     */
    fun tempAllowListViolation(text: String): Boolean =
        TEMP_ALLOW.findAll(text).any {
            val value = it.groupValues[1]
            value != "<null>" && value != "null"
        }

    fun tempAllowListObserved(text: String): Boolean = TEMP_ALLOW.containsMatchIn(text)

    /**
     * A `dumpsys notification` NotificationRecord header carries pkg, id and channel on
     * one line, e.g. `NotificationRecord(0x..: pkg=ch.lkmc.neutrodyne .. id=4001 ..
     * Notification(channel=alerts ..`.
     */
    fun hasNotification(
        text: String,
        pkg: String,
        id: Int,
        channel: String,
    ): Boolean =
        text.lineSequence().any {
            it.contains("pkg=$pkg") && it.contains("id=$id") && it.contains("channel=$channel")
        }

    fun hasNotificationId(
        text: String,
        pkg: String,
        id: Int,
    ): Boolean = text.lineSequence().any { it.contains("pkg=$pkg") && it.contains("id=$id") }

    /**
     * `dumpsys audio` AudioPlaybackConfiguration items are lines like
     * `- piid:123 uid:10150 state:started ...`; require state and uid on the same line.
     */
    fun apcStarted(
        text: String,
        uid: Int,
    ): Boolean =
        text.lineSequence().any { line ->
            line.contains("state:started") &&
                Regex("""uid[:=]\s*$uid\b""").containsMatchIn(line)
        }

    /** A `dumpsys media_session` PlaybackState sample for one session block. */
    data class PlaybackSample(
        val state: Int?,
        val position: Long?,
        val speed: Float?,
        val updatedElapsed: Long?,
    ) {
        val playing: Boolean get() = state == 3
        val paused: Boolean get() = state == 2

        /** Position projected to `elapsedNow` using the snapshot's speed+anchor. */
        fun livePosition(elapsedNow: Long): Long? =
            position?.let { pos ->
                updatedElapsed?.let { pos + ((speed ?: 0f) * (elapsedNow - it)).toLong() } ?: pos
            }
    }

    /**
     * Finds the PlaybackState closest to the session block mentioning `pkg` (media_session
     * prints the owning package above each session's state); null when the format is absent.
     */
    fun mediaSessionPlayback(
        text: String,
        pkg: String,
    ): PlaybackSample? {
        val lines = text.lines()
        val pkgIndex = lines.indexOfFirst { it.contains(pkg) }
        if (pkgIndex < 0) return null
        val stateIndex =
            lines.indices
                .filter { PLAYBACK_LINE.containsMatchIn(lines[it]) }
                .minByOrNull { kotlin.math.abs(it - pkgIndex) }
                ?.takeIf { kotlin.math.abs(it - pkgIndex) <= 80 }
                ?: return null
        val line = lines[stateIndex]
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
