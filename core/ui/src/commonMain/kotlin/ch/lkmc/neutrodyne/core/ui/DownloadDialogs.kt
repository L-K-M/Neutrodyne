// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.components.NdActionRole
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialogAction
import ch.lkmc.neutrodyne.core.designsystem.components.NdTextButton
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.SystemSettingsTarget
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_cancel
import ch.lkmc.neutrodyne.core.ui.resources.action_cancel_download
import ch.lkmc.neutrodyne.core.ui.resources.action_delete_download
import ch.lkmc.neutrodyne.core.ui.resources.action_download
import ch.lkmc.neutrodyne.core.ui.resources.action_resume_download
import ch.lkmc.neutrodyne.core.ui.resources.downloads_allow
import ch.lkmc.neutrodyne.core.ui.resources.downloads_delete_body
import ch.lkmc.neutrodyne.core.ui.resources.downloads_delete_confirm
import ch.lkmc.neutrodyne.core.ui.resources.downloads_folder
import ch.lkmc.neutrodyne.core.ui.resources.downloads_manage
import ch.lkmc.neutrodyne.core.ui.resources.downloads_metered_always
import ch.lkmc.neutrodyne.core.ui.resources.downloads_metered_body
import ch.lkmc.neutrodyne.core.ui.resources.downloads_metered_title
import ch.lkmc.neutrodyne.core.ui.resources.downloads_metered_unknown
import ch.lkmc.neutrodyne.core.ui.resources.downloads_mobile
import ch.lkmc.neutrodyne.core.ui.resources.downloads_not_now
import ch.lkmc.neutrodyne.core.ui.resources.downloads_notification_body
import ch.lkmc.neutrodyne.core.ui.resources.downloads_notification_title
import ch.lkmc.neutrodyne.core.ui.resources.downloads_now
import ch.lkmc.neutrodyne.core.ui.resources.downloads_retry_now
import ch.lkmc.neutrodyne.core.ui.resources.downloads_wifi
import ch.lkmc.neutrodyne.core.ui.resources.notifications_blocked
import ch.lkmc.neutrodyne.core.ui.resources.notifications_open_settings
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
public fun DownloadDialogs(
    handler: DownloadRequestHandler,
    modifier: Modifier =
        Modifier,
) {
    val consent by handler.consent.collectAsStateWithLifecycle()
    val notifications by handler.notificationPrompt.collectAsStateWithLifecycle()
    val blocked by handler.notificationsBlocked.collectAsStateWithLifecycle()
    val menu by handler.menu.collectAsStateWithLifecycle()
    val deletion by handler.deletion.collectAsStateWithLifecycle()
    val platform = LocalPlatformActions.current
    val storageAction = rememberDownloadStorageAction(handler)
    when {
        consent != null -> {
            consent?.let { pending ->
                NdDialog(
                    modifier = modifier,
                    onDismissRequest = handler::dismissConsent,
                    title = stringResource(Res.string.downloads_metered_title),
                    dismiss =
                        NdDialogAction(
                            stringResource(Res.string.action_cancel),
                            onClick = handler::dismissConsent,
                        ),
                ) {
                    Column {
                        androidx.compose.material3.Text(
                            pluralStringResource(
                                Res.plurals.downloads_metered_body,
                                pending.estimate.count,
                                pending.estimate.count,
                                downloadSize(pending.estimate.knownBytes),
                            ),
                        )
                        if (pending.estimate.unknownSizeCount > 0) {
                            androidx.compose.material3.Text(
                                pluralStringResource(
                                    Res.plurals.downloads_metered_unknown,
                                    pending.estimate.unknownSizeCount,
                                    pending.estimate.unknownSizeCount,
                                ),
                            )
                        }
                        NdTextButton(stringResource(Res.string.downloads_now), onClick = {
                            handler.answerConsent(DownloadConsentChoice.ONCE)
                        })
                        NdTextButton(
                            stringResource(Res.string.downloads_wifi),
                            onClick =
                                { handler.answerConsent(DownloadConsentChoice.WIFI) },
                        )
                        NdTextButton(
                            stringResource(Res.string.downloads_metered_always),
                            onClick =
                                { handler.answerConsent(DownloadConsentChoice.ALWAYS) },
                        )
                    }
                }
            }
        }

        notifications && platform.notifications != null -> {
            NdDialog(
                modifier = modifier,
                onDismissRequest = handler::dismissNotifications,
                title = stringResource(Res.string.downloads_notification_title),
                text =
                    stringResource(
                        if (blocked) {
                            Res.string.notifications_blocked
                        } else {
                            Res.string.downloads_notification_body
                        },
                    ),
                confirm =
                    NdDialogAction(stringResource(Res.string.downloads_allow)) {
                        handler.dismissNotifications()
                        platform.notifications?.let { requester ->
                            if (blocked) requester.openSettings() else requester.request(handler::notificationResult)
                        }
                    },
                dismiss =
                    NdDialogAction(
                        stringResource(Res.string.downloads_not_now),
                        onClick =
                            handler::dismissNotifications,
                    ),
            )
        }

        menu != null -> {
            menu?.let { status ->
                NdDialog(
                    modifier = modifier,
                    onDismissRequest = handler::dismissMenu,
                    title = stringResource(Res.string.action_download),
                    dismiss = NdDialogAction(stringResource(Res.string.action_cancel), onClick = handler::dismissMenu),
                ) {
                    Column {
                        if (status.state == DownloadState.COMPLETED) {
                            NdTextButton(
                                stringResource(Res.string.action_delete_download),
                                onClick =
                                    {
                                        handler.confirmDelete(
                                            listOf(status.episodeId),
                                            status.totalBytes ?: status.downloadedBytes,
                                        )
                                    },
                            )
                        } else {
                            downloadWaitCommand(status.waitReason, status.lane)?.let { command ->
                                NdTextButton(
                                    downloadCommandText(
                                        command,
                                        LocalPlatformKind.current,
                                    ).asString(),
                                    onClick = {
                                        if (command == DownloadCommand.STORAGE) {
                                            handler.dismissMenu()
                                            storageAction()
                                        } else {
                                            handler.command(status.episodeId, command)
                                        }
                                    },
                                )
                            }
                            NdTextButton(
                                stringResource(Res.string.action_cancel_download),
                                onClick =
                                    { handler.cancel(status.episodeId) },
                            )
                        }
                    }
                }
            }
        }

        deletion != null -> {
            deletion?.let { pending ->
                NdDialog(
                    modifier = modifier,
                    onDismissRequest = handler::dismissDelete,
                    title =
                        pluralStringResource(
                            Res.plurals.downloads_delete_confirm,
                            pending.ids.size,
                            pending.ids.size,
                            downloadSize(pending.bytes),
                        ),
                    text = stringResource(Res.string.downloads_delete_body),
                    confirm =
                        NdDialogAction(
                            stringResource(Res.string.action_delete_download),
                            role = NdActionRole.DESTRUCTIVE,
                            onClick = handler::deleteConfirmed,
                        ),
                    dismiss =
                        NdDialogAction(
                            stringResource(Res.string.action_cancel),
                            onClick = handler::dismissDelete,
                        ),
                )
            }
        }

        else -> {
            Unit
        }
    }
}

public fun downloadCommandText(
    command: DownloadCommand,
    platform: PlatformKind = PlatformKind.ANDROID,
): UiText =
    UiText.Res(
        when (command) {
            DownloadCommand.PROMOTE -> {
                Res.string.downloads_now
            }

            DownloadCommand.MOBILE -> {
                Res.string.downloads_mobile
            }

            DownloadCommand.RETRY -> {
                Res.string.downloads_retry_now
            }

            DownloadCommand.RESUME -> {
                Res.string.action_resume_download
            }

            DownloadCommand.STORAGE -> {
                if (platform == PlatformKind.DESKTOP) {
                    Res.string.downloads_folder
                } else {
                    Res.string.downloads_manage
                }
            }
        },
    )

/** Platform settings on Android; the existing default root in the desktop file manager. */
@Composable
public fun rememberDownloadStorageAction(handler: DownloadRequestHandler): () -> Unit {
    val platform = LocalPlatformActions.current
    val navigator = LocalAppNavigator.current
    val scope = rememberCoroutineScope()
    return {
        if (platform.settings?.open(SystemSettingsTarget.STORAGE) != true) {
            scope.launch {
                val path = handler.storageFolder()
                if (path == null || platform.reveal?.reveal(path) != true) {
                    navigator.pushDetail(SettingsKey(SettingsPage.DOWNLOADS))
                }
            }
        }
    }
}
