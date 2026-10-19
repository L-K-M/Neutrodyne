// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.desktop.notifications

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.DesktopNotification
import ch.lkmc.neutrodyne.core.common.DesktopNotifier
import ch.lkmc.neutrodyne.core.common.NotificationKind
import ch.lkmc.neutrodyne.core.data.update.UpdateNotificationAdapter
import ch.lkmc.neutrodyne.core.data.update.UpdatePostResult
import ch.lkmc.neutrodyne.core.model.update.UpdateInfo
import ch.lkmc.neutrodyne.desktop.resources.Res
import ch.lkmc.neutrodyne.desktop.resources.notification_app_update_title
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import org.jetbrains.compose.resources.getString

/** Uses the existing banner backend until MD2 supplies native OS notification delivery. */
@ContributesBinding(AppScope::class)
@Inject
internal class DesktopUpdateNotifier(
    private val notifier: DesktopNotifier,
) : UpdateNotificationAdapter {
    override suspend fun post(info: UpdateInfo): UpdatePostResult {
        notifier.post(
            DesktopNotification(
                id = ID,
                kind = NotificationKind.APP_UPDATE,
                title = getString(Res.string.notification_app_update_title, info.versionName),
                body =
                    info.notes
                        .lineSequence()
                        .firstOrNull()
                        .orEmpty(),
                route = "neutrodyne://open/settings/updates",
            ),
        )
        return UpdatePostResult.ACCEPTED
    }

    override suspend fun cancel() = notifier.cancel(ID)

    private companion object {
        const val ID = "app-update"
    }
}
