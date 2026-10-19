// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.os.Bundle
import androidx.media3.session.SessionResult
import ch.lkmc.neutrodyne.core.model.SleepTimerMode
import ch.lkmc.neutrodyne.playback.api.PlaybackSessionCommands as Wire

/** Strict wire types: Bundle getters otherwise silently substitute zero for malformed values. */
internal object SleepTimerCommands {
    val names = listOf(Wire.SLEEP_SET, Wire.SLEEP_EXTEND)

    fun encode(mode: SleepTimerMode): Bundle =
        Bundle().apply {
            putString(
                Wire.MODE,
                when (mode) {
                    SleepTimerMode.Off -> Wire.MODE_OFF
                    SleepTimerMode.EndOfEpisode -> Wire.MODE_END_OF_EPISODE
                    is SleepTimerMode.Minutes -> Wire.MODE_MINUTES
                },
            )
            if (mode is SleepTimerMode.Minutes) putInt(Wire.MINUTES, mode.minutes)
        }

    fun handle(
        name: String,
        args: Bundle,
        bridge: ServiceBridge,
    ): SessionResult? {
        when (name) {
            Wire.SLEEP_SET -> bridge.setSleepTimer(decode(args))
            Wire.SLEEP_EXTEND -> bridge.extendSleepTimer(minutes(args))
            else -> return null
        }
        return SessionResult(SessionResult.RESULT_SUCCESS)
    }

    @Suppress("DEPRECATION")
    private fun decode(args: Bundle): SleepTimerMode =
        when (args.get(Wire.MODE)) {
            Wire.MODE_OFF -> SleepTimerMode.Off
            Wire.MODE_END_OF_EPISODE -> SleepTimerMode.EndOfEpisode
            Wire.MODE_MINUTES -> SleepTimerMode.Minutes(minutes(args))
            else -> throw IllegalArgumentException("Invalid sleep mode")
        }

    @Suppress("DEPRECATION")
    private fun minutes(args: Bundle): Int {
        val value = args.get(Wire.MINUTES)
        require(value is Int && SleepTimerMode.isValidMinutes(value)) { "Minutes must be an Int in 1–240" }
        return value
    }
}
