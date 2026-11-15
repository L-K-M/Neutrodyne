// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.radio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue

/** Kept above the responsive branches so a resize cannot close the chooser or lose the list position. */
internal class RadioProgrammeState {
    var chooserOpen by mutableStateOf(false)
    var playlistId: Long? = null
    var restoreFocus by mutableStateOf(false)

    fun open() {
        chooserOpen = true
    }

    fun close() {
        chooserOpen = false
        restoreFocus = true
    }

    companion object {
        val Saver =
            listSaver<RadioProgrammeState, String>(
                save = { listOf(it.chooserOpen.toString(), it.playlistId?.toString().orEmpty()) },
                restore = { saved ->
                    RadioProgrammeState().apply {
                        chooserOpen = saved[0].toBoolean()
                        playlistId = saved[1].toLongOrNull()
                    }
                },
            )
    }
}
