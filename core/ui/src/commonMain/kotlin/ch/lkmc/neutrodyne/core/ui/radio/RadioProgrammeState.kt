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
                save = {
                    listOf(
                        it.chooserOpen.toString(),
                        it.playlistId?.toString().orEmpty(),
                        it.restoreFocus.toString(),
                    )
                },
                restore = { saved ->
                    RadioProgrammeState().apply {
                        chooserOpen = saved.getOrNull(0)?.toBoolean() ?: false
                        playlistId = saved.getOrNull(1)?.toLongOrNull()
                        restoreFocus = saved.getOrNull(2)?.toBoolean() ?: false
                    }
                },
            )
    }
}
