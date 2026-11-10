// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.notifications

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.DesktopNotification
import ch.lkmc.neutrodyne.core.common.DesktopNotifier
import ch.lkmc.neutrodyne.core.common.NotificationKind
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.ui.resources.group_count
import ch.lkmc.neutrodyne.core.ui.resources.held_changes_message
import ch.lkmc.neutrodyne.core.ui.resources.held_changes_unknown_device
import ch.lkmc.neutrodyne.core.ui.resources.podcast_count
import ch.lkmc.neutrodyne.desktop.resources.Res
import ch.lkmc.neutrodyne.desktop.resources.notification_sync_held_title
import ch.lkmc.neutrodyne.sync.api.MassChangePrompt
import ch.lkmc.neutrodyne.sync.api.SyncController
import ch.lkmc.neutrodyne.sync.api.SyncStatus
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import java.util.concurrent.atomic.AtomicLong
import ch.lkmc.neutrodyne.core.ui.resources.Res as UiRes

/**
 * 10 Scheduling on the desktop's held-batch notice and 08's "Sync is waiting for you" row:
 * while the main window is hidden a held batch of the mass-change guard posts
 * `NotificationKind.SYNC_HELD` through the bound [DesktopNotifier]; the click applies
 * `neutrodyne://open/settings/sync`, 08's route for Settings › Sync (`SyncSettingsKey`).
 *
 * The holder lives in the [AppScope] so the dedupe survives window recreation (11: the
 * shell may recompose its window); the posted state itself is never persisted. The single
 * [ID] slot follows the other posters' replace/cancel contract.
 *
 * Generations: a window collector takes a [newOwner] serial per gate-open iteration, and
 * every decision plus the notifier effects behind it is serialized by [lock] — the narrow
 * local mutex the shared slot needs because `LaunchedEffect` cancellation does not join a
 * superseded collector before the next one starts. The lock guards in-process calls only
 * (`OsDesktopNotifier` and the banner fallback — no network), so post, record and retract
 * are atomic across generations: a torn-down [clear] either runs before a newer post
 * reserved or after it committed, never in between; a superseded [clear] never retracts at
 * all. A post attempt reserves its slot content up front because `post()` is the delivery
 * boundary (the OS channel and the fallback report delivery on return): a cancelled or
 * failed attempt may still have landed, so only a `settled` post may be adopted, and a
 * failed one frees its reservation so a later emission retries.
 */
@SingleIn(AppScope::class)
class DesktopSyncHeldNotifier
    @Inject
    internal constructor(
        private val notifier: DesktopNotifier,
    ) {
        private val lock = Mutex()

        /** Owner -> notice that may occupy the [ID] slot, in attempt order. */
        private val posted = LinkedHashMap<Long, HeldNotice>()

        /** Owners whose post call returned (success or failure); unset = outcome unknown. */
        private val settled = HashSet<Long>()
        private val nextOwner = AtomicLong()

        /**
         * A fresh owner for one gate-open collector generation. The window takes one per
         * [observeSyncHeldNotifications] iteration so a torn-down generation cannot
         * retract a newer generation's notice.
         */
        internal fun newOwner(): Long = nextOwner.incrementAndGet()

        /** One combined emission of the controller's public sync state plus window visibility. */
        internal suspend fun onSyncState(
            owner: Long,
            status: SyncStatus,
            prompts: List<MassChangePrompt>,
            windowHidden: Boolean,
        ) = lock.withLock {
            val profile = (status as? SyncStatus.Linked)?.let(::HeldProfile)
            val ids = prompts.mapTo(LinkedHashSet()) { it.id }
            val stale = posted.values.lastOrNull()
            if (stale != null && (profile == null || stale.profile != profile || ids.isEmpty())) {
                // Unlink, a profile switch or a fully resolved batch withdraws the notice;
                // retracting the slot covers every reserved-or-delivered attempt so far.
                notifier.cancel(ID)
                posted.clear()
            }
            if (profile == null || ids.isEmpty() || !windowHidden) return@withLock
            val current = posted.values.lastOrNull()
            if (current != null && current.owner in settled && current.profile == profile && current.ids == ids) {
                // Same batch set as a confirmed post: adopt the still-shown notice so an
                // older generation's clear() leaves it alone, and never repost.
                posted.remove(current.owner)
                posted[owner] = current.copy(owner = owner)
                // The adopted record describes a confirmed delivery: the new owner
                // inherits that marker or its next identical emission reposts.
                settled += owner
                return@withLock
            }
            val notice = HeldNotice(owner, profile, ids)
            // Reserve the slot content before suspending: a post cancelled past the point of
            // no return may still have delivered, and the record keeps it retractable.
            posted[owner] = notice
            suspendRunCatching {
                notifier.post(
                    DesktopNotification(
                        id = ID,
                        kind = NotificationKind.SYNC_HELD,
                        title = getString(Res.string.notification_sync_held_title),
                        body = heldBody(prompts.first()),
                        route = ROUTE,
                    ),
                )
            }.onSuccess {
                settled += owner
            }.onFailure {
                // A non-cancellation failure means no delivery was reported: free the
                // reservation so the next emission retries.
                settled += owner
                posted.remove(owner, notice)
            }
        }

        /**
         * End of one collector generation (gate close or window dispose): withdraw whatever
         * this slot may show — a committed post or a reserved attempt whose delivery is
         * uncertain. A superseded owner retracts nothing: the live generation owns the slot
         * now and re-derives its content from its own emissions.
         */
        internal suspend fun clear(owner: Long) =
            lock.withLock {
                if (owner != nextOwner.get() || posted.isEmpty()) return@withLock
                notifier.cancel(ID)
                posted.clear()
            }

        private suspend fun heldBody(prompt: MassChangePrompt): String {
            val device = prompt.deviceName ?: getString(UiRes.string.held_changes_unknown_device)
            val podcasts =
                getPluralString(UiRes.plurals.podcast_count, prompt.podcastTitles.size, prompt.podcastTitles.size)
            val groups = getPluralString(UiRes.plurals.group_count, prompt.groupNames.size, prompt.groupNames.size)
            return getString(UiRes.string.held_changes_message, device, podcasts, groups)
        }

        private data class HeldProfile(
            val serverUrl: String,
            val accountId: String,
            val deviceId: String,
        ) {
            constructor(
                linked: SyncStatus.Linked,
            ) : this(linked.serverUrl, linked.accountId, linked.deviceId)
        }

        private data class HeldNotice(
            val owner: Long,
            val profile: HeldProfile,
            val ids: Set<Long>,
        )

        private companion object {
            /** The one stable slot every held-batch notice posts under. */
            const val ID = "sync-held"
            const val ROUTE = "neutrodyne://open/settings/sync"
        }
    }

/**
 * The window's owned collector: waits for the database gate before touching the lazy
 * [syncController] or [notifier] (both are database-backed constructions), then feeds every
 * combined change of `status` + `heldChanges` + window visibility into the holder. New
 * emissions cancel the in-flight one ([collectLatest]) so an unlink or a profile switch
 * retracts a suspended old-profile post instead of queuing behind it; a gate close cancels
 * the inner collection, and its `finally` — under [NonCancellable] — joins the cleanup so
 * no orphan job or stale notice survives a database close.
 */
internal suspend fun observeSyncHeldNotifications(
    gate: Flow<Boolean>,
    hidden: Flow<Boolean>,
    syncController: () -> SyncController,
    notifier: () -> DesktopSyncHeldNotifier,
) {
    gate.collectLatest { open ->
        if (!open) return@collectLatest
        val controller = syncController()
        val held = notifier()
        val owner = held.newOwner()
        try {
            combine(controller.status, controller.heldChanges, hidden, ::HeldInputs)
                .collectLatest { (status, prompts, windowHidden) ->
                    held.onSyncState(owner, status, prompts, windowHidden)
                }
        } finally {
            withContext(NonCancellable) { held.clear(owner) }
        }
    }
}

private data class HeldInputs(
    val status: SyncStatus,
    val prompts: List<MassChangePrompt>,
    val windowHidden: Boolean,
)
