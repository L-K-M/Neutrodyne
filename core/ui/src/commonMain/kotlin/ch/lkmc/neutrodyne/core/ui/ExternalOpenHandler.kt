// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_undo
import ch.lkmc.neutrodyne.core.ui.resources.episode_marked_played
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The route calls opened only after a successful OS handoff. */
public class ExternalOpenHandler(
    private val scope: CoroutineScope,
    private val messages: UserMessages,
    private val mark: suspend (Long) -> Boolean,
    private val undo: suspend (Long) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private val undos = mutableMapOf<Long, Long>()

    public fun opened(
        episodeId: Long,
        mode: YouTubeWatchMode = YouTubeWatchMode.EXTERNAL_OR_UNPLAYABLE,
    ) {
        if (mode == YouTubeWatchMode.PLAYABLE_OVERFLOW) return
        scope.launch {
            suspendRunCatching {
                if (mark(episodeId)) {
                    val message =
                        messages.post(
                            UiText.Res(Res.string.episode_marked_played),
                            UiText.Res(Res.string.action_undo),
                            durationMs = UNDO_DURATION_MS,
                        )
                    undos[message.id] = episodeId
                }
            }.onFailure(onFailure)
        }
    }

    public fun onAction(messageId: Long) {
        val episodeId = undos.remove(messageId) ?: return
        scope.launch { suspendRunCatching { undo(episodeId) }.onFailure(onFailure) }
    }

    public fun shown(messageId: Long) {
        undos.remove(messageId)
    }

    private companion object {
        const val UNDO_DURATION_MS = 5_000L
    }
}
