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
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import java.util.concurrent.atomic.AtomicLong
import ch.lkmc.neutrodyne.core.ui.resources.Res as UiRes

/**
 * 10 Scheduling on the desktop's held-batch notice and 08's "Sync is waiting for you" row:
 * while the main window is hidden a held batch of the mass-change guard posts
 * `NotificationKind.SYNC_HELD` through the bound [DesktopNotifier]; the click applies
 * `neutrodyne://open/settings/sync`, the same destination as the banner's Review.
 *
 * The holder lives in the [AppScope] so the dedupe survives window recreation (11: the
 * shell may recompose its window); the posted state itself is never persisted. The single
 * [ID] slot follows the other posters' replace/cancel contract. [onSyncState] marks
 * delivery only after [DesktopNotifier.post] returns — a thrown post leaves the batch
 * unnotified so a later emission retries.
 */
@SingleIn(AppScope::class)
class DesktopSyncHeldNotifier
    @Inject
    internal constructor(
        private val notifier: DesktopNotifier,
    ) {
        /** The notice the [ID] slot currently shows, per this holder's best knowledge. */
        private var shown: HeldNotice? = null

        /**
         * Owners with a [DesktopNotifier.post] attempt whose outcome is unknown (cancelled
         * or failed mid-call). [clear] retracts the slot for them because the attempt may
         * have raced actual delivery.
         */
        private val attempted = mutableSetOf<Long>()
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
        ) {
            val profile = (status as? SyncStatus.Linked)?.let(::HeldProfile)
            val ids = prompts.mapTo(LinkedHashSet()) { it.id }
            val stale = shown
            if (stale != null && (profile == null || stale.profile != profile || ids.isEmpty())) {
                // Unlink, a profile switch or a fully resolved batch withdraws the notice.
                notifier.cancel(ID)
                shown = null
            }
            if (profile == null || ids.isEmpty() || !windowHidden) return
            val current = shown
            if (current != null && current.profile == profile && current.ids == ids) {
                // Same batch set as posted: adopt the still-shown notice so an older
                // generation's clear() cannot retract it, and never repost.
                shown = current.copy(owner = owner)
                return
            }
            attempted += owner
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
                attempted -= owner
                // An older generation's late post must not overwrite a newer record.
                if (owner >= (shown?.owner ?: Long.MIN_VALUE)) {
                    shown = HeldNotice(owner, profile, ids)
                }
            }
        }

        /**
         * End of one collector generation (gate close or window dispose): withdraw the
         * notice this generation posted — or may still be posting — without touching a
         * newer generation's notice.
         */
        internal fun clear(owner: Long) {
            val posted = shown?.owner == owner
            val inFlight = shown == null && owner in attempted
            attempted -= owner
            if (posted) shown = null
            if (posted || inFlight) notifier.cancel(ID)
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
 * combined change of `status` + `heldChanges` + window visibility into the holder. A gate
 * close cancels the inner collection, and its `finally` — under [NonCancellable] — joins
 * the cleanup so no orphan job or stale notice survives a database close.
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
                .collect { (status, prompts, windowHidden) ->
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
