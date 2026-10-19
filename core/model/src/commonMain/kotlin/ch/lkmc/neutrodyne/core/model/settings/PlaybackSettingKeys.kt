// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model.settings

import ch.lkmc.neutrodyne.core.model.SleepTimerMode
import kotlinx.collections.immutable.persistentListOf
import kotlin.math.abs
import kotlin.math.roundToInt

/** 06 "Streaming on mobile data": the Android metered-network gate's three policies. */
enum class StreamOnMetered {
    ALLOW,
    ASK,
    NEVER,
}

/** How hardware next/previous buttons route; explicit episode commands ignore this setting. */
enum class HardwareButtons { EPISODE, SKIP }

/** Portable playback keys (06); synced keys follow "Sync playback settings". */
object PlaybackSettingKeys {
    val SPEED: SettingKey.Float32 = SettingKey.Float32("playback.speed", DEFAULT_SPEED, synced = true)

    val SKIP_SILENCE: SettingKey.Bool =
        SettingKey.Bool("playback.skip_silence", false, synced = true)

    val SPEED_PRESETS: SettingKey.Text =
        SettingKey.Text("playback.speed_presets", DEFAULT_SPEED_PRESETS, synced = true)

    val SKIP_BACK_MS: SettingKey.Int64 =
        SettingKey.Int64("playback.skip_back_ms", 10_000L, synced = true)

    val SKIP_FORWARD_MS: SettingKey.Int64 =
        SettingKey.Int64("playback.skip_forward_ms", 30_000L, synced = true)

    val PAUSE_FOR_NAVIGATION: SettingKey.Bool =
        SettingKey.Bool("playback.pause_for_navigation", true)

    val STREAM_ON_METERED: SettingKey.Choice<StreamOnMetered> =
        SettingKey.Choice(
            "playback.stream_on_metered",
            StreamOnMetered.ALLOW,
            persistentListOf(StreamOnMetered.ALLOW, StreamOnMetered.ASK, StreamOnMetered.NEVER),
        )

    val STREAM_CACHE_MB: SettingKey.Int32 = SettingKey.Int32("playback.stream_cache_mb", 500)

    val HARDWARE_BUTTONS: SettingKey.Choice<HardwareButtons> =
        SettingKey.Choice(
            "playback.hardware_buttons",
            HardwareButtons.EPISODE,
            persistentListOf(HardwareButtons.EPISODE, HardwareButtons.SKIP),
        )

    val SMART_RESUME: SettingKey.Bool = SettingKey.Bool("playback.smart_resume", true, synced = true)

    val SLEEP_LAST_MINUTES: SettingKey.Int32 = SettingKey.Int32("playback.sleep_last_minutes", 30, synced = true)

    val ALL: List<SettingKey<*>> =
        listOf(
            SPEED,
            SKIP_SILENCE,
            SPEED_PRESETS,
            SKIP_BACK_MS,
            SKIP_FORWARD_MS,
            PAUSE_FOR_NAVIGATION,
            STREAM_ON_METERED,
            STREAM_CACHE_MB,
            HARDWARE_BUTTONS,
            SMART_RESUME,
            SLEEP_LAST_MINUTES,
        )

    const val SPEED_MIN = 0.5f
    const val SPEED_MAX = 3.0f
    const val SPEED_STEP = 0.05f
    const val DEFAULT_SPEED = 1.0f
    const val DEFAULT_SPEED_PRESETS = "0.8,1.0,1.2,1.5,1.8,2.0"
    const val MIN_PRESETS = 2
    const val MAX_PRESETS = 8

    /** The settings screens' choices, in milliseconds or MB (06 Settings). */
    val SKIP_BACK_MS_VALUES: Set<Long> = setOf(5_000L, 10_000L, 15_000L, 20_000L, 30_000L, 45_000L, 60_000L)
    val SKIP_FORWARD_MS_VALUES: Set<Long> =
        setOf(10_000L, 15_000L, 20_000L, 30_000L, 45_000L, 60_000L, 90_000L)
    val STREAM_CACHE_MB_VALUES: Set<Int> = setOf(100, 250, 500, 1_000, 2_000)

    // Integer twentieths avoid accumulating float drift when snapping stored preset values.
    private const val SPEED_STEPS_PER_UNIT = 20f
    private const val SPEED_EPSILON = 0.000_01f

    fun isValidSpeed(speed: Float): Boolean {
        if (!speed.isFinite() || speed !in SPEED_MIN..SPEED_MAX) return false
        return abs(speed - normalizeSpeed(speed)) <= SPEED_EPSILON
    }

    /** Clamp finite input to 0.5–3.0 and round to the nearest 0.05 step. */
    fun normalizeSpeed(speed: Float): Float {
        require(speed.isFinite()) { "Playback speed must be finite" }
        return (speed.coerceIn(SPEED_MIN, SPEED_MAX) * SPEED_STEPS_PER_UNIT).roundToInt() / SPEED_STEPS_PER_UNIT
    }

    /** Accept 2–8 valid speeds in any input order; return sorted speeds for cycling. */
    fun parseSpeedPresets(text: String): List<Float>? {
        val tokens = text.split(',', limit = MAX_PRESETS + 1)
        if (tokens.size !in MIN_PRESETS..MAX_PRESETS) return null
        val speeds =
            tokens.map { token ->
                val speed = token.trim().toFloatOrNull()?.takeIf(::isValidSpeed) ?: return null
                normalizeSpeed(speed)
            }
        return speeds.sorted()
    }

    /**
     * The playback keys' declared ranges and formats, applied by [AllSettingKeys.acceptsValue]
     * after its stored-type check; every other key accepts any well-typed value.
     */
    internal fun acceptsRange(
        key: SettingKey<*>,
        value: Any,
    ): Boolean =
        when (key) {
            SLEEP_LAST_MINUTES -> value is Int && SleepTimerMode.isValidMinutes(value)
            SPEED -> value is Float && isValidSpeed(value)
            SPEED_PRESETS -> value is String && parseSpeedPresets(value) != null
            SKIP_BACK_MS -> value is Long && value in SKIP_BACK_MS_VALUES
            SKIP_FORWARD_MS -> value is Long && value in SKIP_FORWARD_MS_VALUES
            STREAM_CACHE_MB -> value is Int && value in STREAM_CACHE_MB_VALUES
            else -> true
        }
}
