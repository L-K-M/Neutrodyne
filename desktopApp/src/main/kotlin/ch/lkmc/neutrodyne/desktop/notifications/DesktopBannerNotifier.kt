// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.notifications

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.DesktopNotification
import ch.lkmc.neutrodyne.core.common.DesktopNotifier
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.navigation.IntentRouter
import ch.lkmc.neutrodyne.core.navigation.Route
import ch.lkmc.neutrodyne.core.navigation.RouteInput
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.UserMessage
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_open
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

/**
 * The documented in-app banner fallback for [DesktopNotifier] (11 Notifications: no OS
 * notification back end exists before MD2's `OsDesktopNotifier` — "in-app banners only" is
 * each OS row's fallback). Every posted notification becomes a [UserMessage] the window
 * feeds the root snackbar through `RootSlots.userMessages` — serial presentation, the
 * notification's [DesktopNotification.route] re-offered as the "Open" action and applied
 * through [IntentRouter] plus the shell's [routeDispatch] on tap.
 *
 * The queue is bounded ([MAX_PENDING]); the newest notification wins past the cap. Posting a
 * second notification under the same id replaces the pending entry — `replaces_id` semantics.
 * [cancel] retracts a notification that has not been shown yet; an already-displayed banner
 * cannot be retracted, like a shown toast. This class carries no OS-centre delivery — MD2
 * replaces the binding, not the port.
 *
 * Thread-safety: [post] is called from lane/IO threads; [userMessages] is collected on the
 * composition's main scope; [messageAction] runs on the main thread. All mutable state is
 * guarded by [lock].
 */
@SingleIn(AppScope::class)
class DesktopBannerNotifier
    @Inject
    internal constructor() : DesktopNotifier {
        private val lock = Any()
        private val pending = MutableStateFlow<ImmutableList<Entry>>(persistentListOf())
        private val byMessageId = HashMap<Long, DesktopNotification>()
        private var nextMessageId = 0L

        /**
         * Where a tapped banner's route goes — the window attaches
         * `DesktopMenuActions.applyRoute` (navigation state lives on the AWT thread).
         */
        @Volatile
        var routeDispatch: (Route) -> Boolean = { false }

        /** What the window feeds `RootSlots.userMessages` — the serial banner stream. */
        val userMessages: Flow<UserMessage> =
            flow {
                while (true) {
                    val head = pending.first { it.isNotEmpty() }.first()
                    // `emit` returns after the snackbar is dismissed and its action, if
                    // tapped, has already run `messageAction` — the entry must still be
                    // readable then, so removal happens after, not before, the show.
                    emit(head.message)
                    synchronized(lock) {
                        byMessageId -= head.message.id
                        pending.value = (pending.value - head).toImmutableList()
                    }
                }
            }

        override suspend fun post(n: DesktopNotification) {
            synchronized(lock) {
                val message =
                    UserMessage(
                        id = nextMessageId++,
                        text = UiText.Raw(listOf(n.title, n.body).filter { it.isNotBlank() }.joinToString(" — ")),
                        action = n.route?.let { UiText.Res(Res.string.action_open) },
                    )
                byMessageId[message.id] = n
                // Same id = replace the pending entry (notification `replaces_id`).
                val entries = (pending.value.filterNot { it.notificationId == n.id } + Entry(n.id, message))
                val dropped = entries.size - MAX_PENDING
                if (dropped > 0) {
                    entries.take(dropped).forEach { byMessageId -= it.message.id }
                    Log.w(TAG) { "dropping $dropped pending notification(s) — queue cap $MAX_PENDING" }
                }
                pending.value = entries.takeLast(MAX_PENDING).toImmutableList()
            }
        }

        override fun cancel(id: String) {
            synchronized(lock) {
                val removed = pending.value.filter { it.notificationId == id }
                removed.forEach { byMessageId -= it.message.id }
                pending.value = pending.value.filterNot { it.notificationId == id }.toImmutableList()
            }
        }

        /** `RootActions.userMessageAction`: an "Open" tap applies the notification's route. */
        fun messageAction(id: Long) {
            val notification = synchronized(lock) { byMessageId[id] } ?: return
            val uri = notification.route ?: return
            val route =
                IntentRouter.route(RouteInput(RouteInput.Action.VIEW, uri = uri, internal = true))
            if (route != Route.None && !routeDispatch(route)) {
                Log.w(TAG) { "notification route dropped: no dispatcher attached" }
            }
        }

        /** A queued banner: the notification id survives for [cancel] and replaces. */
        private class Entry(
            val notificationId: String,
            val message: UserMessage,
        )

        private companion object {
            const val TAG = "BannerNotifier"

            /** One banner at a time drains slowly; a runaway poster cannot pile up forever. */
            const val MAX_PENDING = 8
        }
    }
