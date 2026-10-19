// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

/**
 * The OS-notification port (11 Runner contract): `OsDesktopNotifier` in `:desktop:system` is
 * the MD2 implementation; until then `DesktopAppGraph` binds the documented in-app banner
 * fallback (`DesktopBannerNotifier` in `:desktopApp`, 11 Notifications' fallback column — no
 * OS-centre delivery exists before MD2). The owners' desktop notifiers (03's new-episode
 * poster, 07's download notices, 09's update notice, 04's engine alert, 10's held change,
 * 05's import report) call this so none of them depends on `:desktop:system`. The desktop has
 * no notification channels — content, grouping and per-kind switches live in the owners'
 * modules.
 */
interface DesktopNotifier {
    suspend fun post(n: DesktopNotification)

    fun cancel(id: String)
}

/**
 * One OS notification. [route] is a `neutrodyne://open/…` deep link the shell applies on click;
 * `null` = no navigation.
 */
data class DesktopNotification(
    val id: String,
    val kind: NotificationKind,
    val title: String,
    val body: String,
    val route: String?,
)

enum class NotificationKind {
    NEW_EPISODES,
    DOWNLOAD_FAILED,
    STORAGE_FULL,
    APP_UPDATE,
    ENGINE_ALERT,
    SYNC_HELD,

    /** 05 §8's "import finished" report (Android channel `import_backup`). */
    IMPORT_BACKUP,
}
