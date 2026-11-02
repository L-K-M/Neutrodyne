// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.model.DownloadAllEstimate
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.download_operation_failed
import ch.lkmc.neutrodyne.core.ui.resources.download_queued
import ch.lkmc.neutrodyne.core.ui.resources.download_rejected
import ch.lkmc.neutrodyne.download.api.DownloadController
import ch.lkmc.neutrodyne.download.api.RequestResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

public data class DownloadAllState(
    val source: FeedSource,
    val estimate: DownloadAllEstimate? = null,
    val metered: RequestResult.NeedsMeteredDecision? = null,
    val busy: Boolean = true,
    val error: UiText? = null,
)

/** Only a confirmed snapshot creates MANUAL rows. Dismissing a read never queues work. */
public class DownloadAllController(
    private val scope: CoroutineScope,
    private val estimate: suspend (FeedSource) -> DownloadAllEstimate,
    private val downloads: DownloadController,
    private val onNotificationsRequired: () -> Unit,
) {
    private val mutableState = MutableStateFlow<DownloadAllState?>(null)
    public val state = mutableState.asStateFlow()
    public val messages: UserMessages = UserMessages()
    private var read: Job? = null

    public fun open(source: FeedSource) {
        if (state.value?.busy == true) return
        require(source is FeedSource.Group || source is FeedSource.Podcast)
        mutableState.value = DownloadAllState(source)
        read =
            scope.launch {
                suspendRunCatching { estimate(source).also { ensureActive() } }
                    .onSuccess {
                        mutableState.value =
                            DownloadAllState(
                                source,
                                it,
                                busy = false,
                            )
                    }.onFailure {
                        mutableState.value =
                            DownloadAllState(
                                source,
                                busy = false,
                                error = UiText.Res(Res.string.download_operation_failed),
                            )
                    }
            }
    }

    public fun dismiss() {
        // A durable command must finish before the dialog disappears.
        if (state.value?.busy == true && state.value?.estimate != null) return
        read?.cancel()
        mutableState.value = null
    }

    public fun confirm() = request(null)

    public fun confirmMetered() = request(true)

    private fun request(allowMetered: Boolean?) {
        val current = state.value ?: return
        val selection = current.estimate ?: return
        if (current.busy || selection.episodeIds.isEmpty()) return
        if (allowMetered == true && current.metered == null) return
        mutableState.value =
            current.copy(
                busy = true,
                error = null,
            )
        scope.launch {
            suspendRunCatching {
                downloads.request(
                    selection.episodeIds,
                    DownloadLane.MANUAL,
                    allowMetered,
                )
            }.onSuccess { result ->
                when (result) {
                    is RequestResult.NeedsMeteredDecision -> {
                        mutableState.value =
                            current.copy(
                                metered = result,
                                busy = false,
                            )
                    }

                    is RequestResult.Queued -> {
                        mutableState.value = null
                        if (result.rejected.isNotEmpty()) {
                            messages.post(
                                UiText.Res(
                                    Res.string.download_rejected,
                                    listOf(result.rejected.size),
                                ),
                            )
                        } else {
                            messages.post(
                                UiText.Res(
                                    Res.string.download_queued,
                                    listOf(result.queued),
                                ),
                            )
                        }
                        if (result.askNotificationPermission) onNotificationsRequired()
                    }
                }
            }.onFailure {
                mutableState.value =
                    current.copy(
                        busy = false,
                        error = UiText.Res(Res.string.download_operation_failed),
                    )
            }
        }
    }
}
