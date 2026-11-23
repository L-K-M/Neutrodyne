// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import java.text.DecimalFormatSymbols

internal class MediaButtons(
    private val prefs: PlaybackPrefs,
    private val context: Context,
    private val currentSpeed: () -> Float = { 1f },
) {
    fun current(speed: Float = currentSpeed()): List<CommandButton> {
        val skipBackSeconds = (prefs.current.value.skipBackMs / 1000).toInt()
        val skipForwardSeconds = (prefs.current.value.skipForwardMs / 1000).toInt()
        return listOf(
            CommandButton
                .Builder(backIcon(prefs.current.value.skipBackMs))
                .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                .setSlots(CommandButton.SLOT_BACK)
                .setDisplayName(seconds(R.plurals.media_skip_back, skipBackSeconds))
                .build(),
            CommandButton
                .Builder(forwardIcon(prefs.current.value.skipForwardMs))
                .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                .setSlots(CommandButton.SLOT_FORWARD)
                .setDisplayName(seconds(R.plurals.media_skip_forward, skipForwardSeconds))
                .build(),
            CommandButton
                .Builder(speedIcon(speed))
                .setSessionCommand(CustomCommands.command(CustomCommands.SPEED_CYCLE))
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .setDisplayName(context.getString(R.string.media_speed, formatSpeed(speed)))
                .build(),
            CommandButton
                .Builder(CommandButton.ICON_NEXT)
                .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .setDisplayName(context.getString(R.string.media_next_episode))
                .build(),
        )
    }

    private fun seconds(
        label: Int,
        count: Int,
    ): String = context.resources.getQuantityString(label, count, count)

    /**
     * The speed reaches the resource as a pre-formatted string (`%1$s`), so its decimal separator comes from
     * the locale the resources resolved for, as the rest of the label does. `Float.toString` always uses `.`;
     * swapping only that separator keeps every digit as it was, so the English label is unchanged.
     */
    private fun formatSpeed(speed: Float): String {
        val separator = DecimalFormatSymbols.getInstance(context.resources.configuration.locales[0]).decimalSeparator
        return speed.toString().replace('.', separator)
    }

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
