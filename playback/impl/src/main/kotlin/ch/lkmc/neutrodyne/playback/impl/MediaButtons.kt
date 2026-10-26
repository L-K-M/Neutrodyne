// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import androidx.media3.common.Player
import androidx.media3.session.CommandButton

internal class MediaButtons(
    private val prefs: PlaybackPrefs,
    private val currentSpeed: () -> Float = { 1f },
) {
    fun current(speed: Float = currentSpeed()): List<CommandButton> =
        listOf(
            CommandButton
                .Builder(backIcon(prefs.current.value.skipBackMs))
                .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                .setSlots(CommandButton.SLOT_BACK)
                .setDisplayName("Back ${prefs.current.value.skipBackMs / 1000} seconds")
                .build(),
            CommandButton
                .Builder(forwardIcon(prefs.current.value.skipForwardMs))
                .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                .setSlots(CommandButton.SLOT_FORWARD)
                .setDisplayName("Forward ${prefs.current.value.skipForwardMs / 1000} seconds")
                .build(),
            CommandButton
                .Builder(speedIcon(speed))
                .setSessionCommand(CustomCommands.command(CustomCommands.SPEED_CYCLE))
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .setDisplayName("Speed $speed×")
                .build(),
            CommandButton
                .Builder(CommandButton.ICON_NEXT)
                .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .setDisplayName("Next episode")
                .build(),
        )

    private fun backIcon(ms: Long): Int =
        when (ms) {
            5_000L -> CommandButton.ICON_SKIP_BACK_5
            10_000L -> CommandButton.ICON_SKIP_BACK_10
            15_000L -> CommandButton.ICON_SKIP_BACK_15
            30_000L -> CommandButton.ICON_SKIP_BACK_30
            else -> CommandButton.ICON_SKIP_BACK
        }

    private fun forwardIcon(ms: Long): Int =
        when (ms) {
            5_000L -> CommandButton.ICON_SKIP_FORWARD_5
            10_000L -> CommandButton.ICON_SKIP_FORWARD_10
            15_000L -> CommandButton.ICON_SKIP_FORWARD_15
            30_000L -> CommandButton.ICON_SKIP_FORWARD_30
            else -> CommandButton.ICON_SKIP_FORWARD
        }

    private fun speedIcon(speed: Float): Int =
        when (speed) {
            0.5f -> CommandButton.ICON_PLAYBACK_SPEED_0_5
            0.8f -> CommandButton.ICON_PLAYBACK_SPEED_0_8
            1f -> CommandButton.ICON_PLAYBACK_SPEED_1_0
            1.2f -> CommandButton.ICON_PLAYBACK_SPEED_1_2
            1.5f -> CommandButton.ICON_PLAYBACK_SPEED_1_5
            1.8f -> CommandButton.ICON_PLAYBACK_SPEED_1_8
            2f -> CommandButton.ICON_PLAYBACK_SPEED_2_0
            else -> CommandButton.ICON_PLAYBACK_SPEED
        }
}
