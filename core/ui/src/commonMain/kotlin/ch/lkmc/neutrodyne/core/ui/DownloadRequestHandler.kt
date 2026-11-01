// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.WaitReason
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.downloads_reject_gone
import ch.lkmc.neutrodyne.core.ui.resources.downloads_reject_no_file
import ch.lkmc.neutrodyne.core.ui.resources.downloads_reject_stream
import ch.lkmc.neutrodyne.core.ui.resources.downloads_skipped
import ch.lkmc.neutrodyne.core.ui.resources.playback_queue_unavailable
import ch.lkmc.neutrodyne.core.ui.resources.playback_queue_youtube
import ch.lkmc.neutrodyne.download.api.DeleteReason
import ch.lkmc.neutrodyne.download.api.DownloadController
import ch.lkmc.neutrodyne.download.api.DownloadStatus
import ch.lkmc.neutrodyne.download.api.RejectReason
import ch.lkmc.neutrodyne.download.api.RequestResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

public enum class DownloadCommand { PROMOTE, MOBILE, RETRY, RESUME, STORAGE }

/** Exhaustive current wait policy, shared by episode entry points and Downloads rows. */
public fun downloadWaitCommand(
    reason: WaitReason,
    lane: DownloadLane,
): DownloadCommand? =
    when (reason) {
        WaitReason.NONE, WaitReason.SLOT, WaitReason.CHARGING, WaitReason.SYSTEM -> {
            DownloadCommand.PROMOTE
        }

        WaitReason.NETWORK -> {
            null
        }

        WaitReason.UNMETERED_NETWORK -> {
            if (lane ==
                DownloadLane.MANUAL
            ) {
                DownloadCommand.MOBILE
            } else {
                DownloadCommand.PROMOTE
            }
        }

        WaitReason.STORAGE -> {
            DownloadCommand.STORAGE
        }

        WaitReason.BACKOFF -> {
            DownloadCommand.RETRY
        }

        WaitReason.NEEDS_FOREGROUND -> {
            DownloadCommand.RESUME
        }
    }

public data class DownloadConsent(
    val ids: List<Long>,
    val estimate: RequestResult.NeedsMeteredDecision,
)

public enum class DownloadConsentChoice { ONCE, WIFI, ALWAYS }

public data class DownloadDeletion(
    val ids: List<Long>,
    val bytes: Long,
)

/** UI-owned decisions survive configuration changes, but never process death. */
public class DownloadRequestHandler(
    private val scope: CoroutineScope,
    private val controller: DownloadController,
    private val messages: UserMessages,
    private val allowAlways: suspend () -> Unit,
    private val markNotificationPrompted: suspend () -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private val consentState = MutableStateFlow<DownloadConsent?>(null)
    public val consent = consentState.asStateFlow()
    private val notificationState = MutableStateFlow(false)
    public val notificationPrompt = notificationState.asStateFlow()
    private val blockedState = MutableStateFlow(false)
    public val notificationsBlocked = blockedState.asStateFlow()

    public fun notificationResult(granted: Boolean) {
        blockedState.value = !granted
        notificationState.value = !granted
    }

    private val menuState = MutableStateFlow<DownloadStatus?>(null)
    public val menu = menuState.asStateFlow()
    private val deletionState = MutableStateFlow<DownloadDeletion?>(null)
    public val deletion = deletionState.asStateFlow()

    public fun request(ids: List<Long>) {
        run { handle(ids, controller.request(ids, DownloadLane.MANUAL)) }
    }

    /** M6a desktop downloads use the default root only. No folder registration or move. */
    public suspend fun storageFolder(): String? =
        suspendRunCatching {
            controller
                .storageRoots()
                .firstOrNull { it.kind == ch.lkmc.neutrodyne.download.api.RootKind.DESKTOP_DEFAULT }
                ?.path
        }.onFailure(onFailure)
            .getOrNull()

    public fun toggle(id: Long) {
        run {
            val status = controller.observe(id).first()
            when (status?.state) {
                null, DownloadState.MISSING -> {
                    handle(listOf(id), controller.request(listOf(id), DownloadLane.MANUAL))
                }

                DownloadState.QUEUED -> {
                    if (status.waitReason == WaitReason.NEEDS_FOREGROUND) {
                        controller.resume(
                            listOf(id),
                        )
                    } else {
                        menuState.value = status
                    }
                }

                DownloadState.COMPLETED -> {
                    menuState.value = status
                }

                DownloadState.RESOLVING -> {
                    controller.cancel(listOf(id))
                }

                DownloadState.DOWNLOADING -> {
                    controller.pause(listOf(id))
                }

                DownloadState.PAUSED -> {
                    controller.resume(listOf(id))
                }

                DownloadState.FAILED -> {
                    controller.retry(listOf(id))
                }

                DownloadState.VERIFYING -> {
                    Unit
                }
            }
        }
    }

    public fun command(
        id: Long,
        command: DownloadCommand,
    ) {
        dismissMenu()
        run {
            when (command) {
                DownloadCommand.PROMOTE -> handle(listOf(id), controller.promote(id))
                DownloadCommand.MOBILE -> controller.setAllowMetered(listOf(id), true)
                DownloadCommand.RETRY -> controller.retry(listOf(id))
                DownloadCommand.RESUME -> controller.resume(listOf(id))
                DownloadCommand.STORAGE -> Unit // The route opens platform storage settings.
            }
        }
    }

    public fun cancel(id: Long) {
        dismissMenu()
        run { controller.cancel(listOf(id)) }
    }

    public fun dismissMenu() {
        menuState.value = null
    }

    public fun dismissConsent() {
        consentState.value = null
    }

    public fun answerConsent(choice: DownloadConsentChoice) {
        val pending = consentState.value ?: return
        consentState.value = null
        run {
            if (choice == DownloadConsentChoice.ALWAYS) allowAlways()
            handle(
                pending.ids,
                controller.request(
                    pending.ids,
                    DownloadLane.MANUAL,
                    choice != DownloadConsentChoice.WIFI,
                ),
            )
        }
    }

    public fun askNotifications() {
        notificationState.value = true
    }

    public fun dismissNotifications() {
        notificationState.value = false
        run { markNotificationPrompted() }
    }

    public fun confirmDelete(
        ids: List<Long>,
        bytes: Long,
    ) {
        dismissMenu()
        if (ids.isNotEmpty()) deletionState.value = DownloadDeletion(ids.toList(), bytes)
    }

    public fun dismissDelete() {
        deletionState.value = null
    }

    public fun deleteConfirmed() {
        val pending = deletionState.value ?: return
        deletionState.value = null
        run { controller.delete(pending.ids, DeleteReason.USER) }
    }

    private fun handle(
        ids: List<Long>,
        result: RequestResult,
    ) {
        when (result) {
            is RequestResult.NeedsMeteredDecision -> {
                consentState.value = DownloadConsent(ids.toList(), result)
            }

            is RequestResult.Queued -> {
                notificationState.value = notificationState.value || result.askNotificationPermission
                val reason = result.rejected.values.firstOrNull() ?: return
                val text =
                    when (reason) {
                        RejectReason.EPISODE_GONE -> Res.string.downloads_reject_gone
                        RejectReason.NOT_DOWNLOADABLE -> Res.string.downloads_reject_no_file
                        RejectReason.UNSUPPORTED_STREAM -> Res.string.downloads_reject_stream
                        RejectReason.YOUTUBE_NOT_SUPPORTED -> Res.string.playback_queue_youtube
                        RejectReason.UNAVAILABLE -> Res.string.playback_queue_unavailable
                    }
                messages.post(
                    if (ids.size > 1) {
                        UiText.Plural(
                            Res.plurals.downloads_skipped,
                            result.rejected.size,
                            listOf(result.rejected.size, UiText.Res(text)),
                        )
                    } else {
                        UiText.Res(text)
                    },
                )
            }
        }
    }

    private fun run(block: suspend () -> Unit) {
        scope.launch { suspendRunCatching { block() }.onFailure(onFailure) }
    }
}

/** Decimal media sizes, without JVM formatting or locale-dependent platform APIs. */
public fun downloadSize(bytes: Long): String {
    val units = listOf("B", "kB", "MB", "GB", "TB")
    var scale = 1L
    var unit = 0
    while (unit < units.lastIndex && bytes / scale >= DECIMAL_UNIT_BYTES) {
        scale *= DECIMAL_UNIT_BYTES
        unit++
    }
    val whole = bytes.coerceAtLeast(0) / scale
    val tenth = (bytes.coerceAtLeast(0) % scale) * 10 / scale
    return if (unit == 0) "$whole ${units[unit]}" else "$whole.$tenth ${units[unit]}"
}

private const val DECIMAL_UNIT_BYTES = 1_000L
