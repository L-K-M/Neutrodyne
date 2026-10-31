// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.playback.api

/**
 * M5 wire contract. Bundle types: mode String, minutes Int. SET requires mode; minutes is required
 * only for MINUTES. EXTEND requires minutes. Chapter commands have no payload. Reject missing,
 * wrong-typed, unknown modes or out-of-range minutes with SessionError.ERROR_BAD_VALUE
 * before mutation; never clamp invalid input. Valid no-ops return RESULT_SUCCESS. Unimplemented
 * commands return SessionError.ERROR_NOT_SUPPORTED. Mutations require the existing trusted-controller
 * policy, execute on main, and are serialized with transport. Void UI calls report no success.
 */
object PlaybackSessionCommands {
    const val SLEEP_SET = "nd.SLEEP_SET"
    const val SLEEP_EXTEND = "nd.SLEEP_EXTEND"
    const val CHAPTER_NEXT = "nd.CHAPTER_NEXT"
    const val CHAPTER_PREV = "nd.CHAPTER_PREV"
    const val MODE = "mode"
    const val MINUTES = "minutes"
    const val MODE_OFF = "off"
    const val MODE_MINUTES = "minutes"
    const val MODE_END_OF_EPISODE = "end_of_episode"
}
