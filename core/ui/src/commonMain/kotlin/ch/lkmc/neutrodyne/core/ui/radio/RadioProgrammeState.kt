// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.radio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue

/** Kept above the responsive branches so a resize cannot discard a modal draft or list position. */
internal class RadioProgrammeState {
    var mode by mutableStateOf<RadioInsert?>(null)
    var name by mutableStateOf("")
    var chosen by mutableStateOf(emptySet<Long>())
    var launcher = RadioInsert.CHOOSER
    var creationBaseline: Long? = null
    var playlistId: Long? = null
    var restoreFocus by mutableStateOf(false)

    fun open(
        next: RadioInsert,
        createdPlaylistId: Long?,
    ) {
        if (mode == null) launcher = next
        creationBaseline = createdPlaylistId
        if (next == RadioInsert.CREATE && mode != next) {
            name = ""
            chosen = emptySet()
        }
        mode = next
    }

    fun close() {
        mode = null
        restoreFocus = true
    }

    companion object {
        val Saver =
            listSaver<RadioProgrammeState, String>(
                save = {
                    listOf(
                        it.mode?.name.orEmpty(),
                        it.name,
                        it.chosen.joinToString(","),
                        it.launcher.name,
                        it.creationBaseline?.toString().orEmpty(),
                        it.playlistId?.toString().orEmpty(),
                    )
                },
                restore = { saved ->
                    RadioProgrammeState().apply {
                        mode = saved[0].takeIf { it.isNotEmpty() }?.let(RadioInsert::valueOf)
                        name = saved[1]
                        chosen = saved[2].split(',').mapNotNull { it.toLongOrNull() }.toSet()
                        launcher = RadioInsert.valueOf(saved[3])
                        creationBaseline = saved[4].toLongOrNull()
                        playlistId = saved[5].toLongOrNull()
                    }
                },
            )
    }
}
