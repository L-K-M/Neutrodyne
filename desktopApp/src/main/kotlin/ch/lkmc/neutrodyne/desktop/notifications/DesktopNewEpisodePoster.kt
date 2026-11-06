// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.notifications

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.DesktopNotification
import ch.lkmc.neutrodyne.core.common.DesktopNotifier
import ch.lkmc.neutrodyne.core.common.NotificationKind
import ch.lkmc.neutrodyne.core.domain.NewEpisodePoster
import ch.lkmc.neutrodyne.core.model.NewEpisodeLine
import ch.lkmc.neutrodyne.core.model.NewEpisodeNotice
import ch.lkmc.neutrodyne.desktop.resources.Res
import ch.lkmc.neutrodyne.desktop.resources.notification_more_groups
import ch.lkmc.neutrodyne.desktop.resources.notification_new_episodes_title
import ch.lkmc.neutrodyne.desktop.resources.notification_other_podcasts
import dev.zacsweers.metro.Inject
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString

/**
 * 03's desktop new-episode poster (MD2: "new episodes per refresh grouped by group"): the
 * run's notices merge in memory with everything announced since the main window last had
 * focus (newest 50; a window focus or a notification click clears the set, a quit forgets
 * it) and post as one notification under the stable id `new-episodes`, which every back end
 * replaces in place (11 Notifications).
 *
 * One episode shows podcast and episode title and routes to the episode; several show "N new
 * episodes" with one body line per group in notice order, at most [MAX_GROUP_LINES] plus an
 * "and N more" tail, and route to the group when every episode belongs to one group's
 * notice, else nowhere (a click just shows the window). No artwork and no actions: the back
 * ends show the app icon, and the settings' per-group and per-podcast switches have already
 * filtered what arrives here. A denied or missing notification service is not this class's
 * concern — the banner fallback shows the same event.
 */
class DesktopNewEpisodePoster
    @Inject
    internal constructor(
        private val notifier: DesktopNotifier,
    ) : NewEpisodePoster {
    private val lock = Any()

    /** Episode id → its line and the group notice that claimed it; insertion-ordered. */
    private val unseen = LinkedHashMap<Long, SeenLine>()

    override suspend fun post(notices: List<NewEpisodeNotice>) {
        if (notices.isEmpty()) return
        val merged =
            synchronized(lock) {
                for (notice in notices) {
                    for (line in notice.episodes) {
                        unseen[line.episodeId] = SeenLine(line, notice.groupUuid, notice.groupName)
                    }
                }
                trimToNewest()
                unseen.values.toList()
            }
        if (merged.isEmpty()) return
        notifier.post(build(merged))
    }

    override suspend fun removePodcasts(podcastIds: List<Long>) {
        if (podcastIds.isEmpty()) return
        val remaining =
            synchronized(lock) {
                unseen.values.removeIf { it.line.podcastId in podcastIds }
                unseen.values.toList()
            }
        if (remaining.isEmpty()) {
            notifier.cancel(ID)
        } else {
            notifier.post(build(remaining))
        }
    }

    /**
     * The "seen" clear of 03: the main window gained focus, or a notification was clicked.
     * Called from the AWT thread and the click collector; never blocks.
     */
    fun clearUnseen() {
        synchronized(lock) { unseen.clear() }
    }

    /** Keeps the newest [MAX_UNSEEN] lines; older announcements fall off the front. */
    private fun trimToNewest() {
        val overflow = unseen.size - MAX_UNSEEN
        if (overflow > 0) {
            val iterator = unseen.entries.iterator()
            repeat(overflow) {
                iterator.next()
                iterator.remove()
            }
        }
    }

    private suspend fun build(lines: List<SeenLine>): DesktopNotification {
        if (lines.size == 1) {
            val only = lines.single().line
            return DesktopNotification(
                id = ID,
                kind = NotificationKind.NEW_EPISODES,
                title = only.podcastTitle,
                body = only.episodeTitle,
                route = "$ROUTE_OPEN/episode/${only.episodeId}",
            )
        }
        val groups = groupLines(lines)
        val body = buildString {
            groups.take(MAX_GROUP_LINES).forEachIndexed { index, group ->
                if (index > 0) append('\n')
                append(groupLine(group))
            }
            val hidden = groups.size - MAX_GROUP_LINES
            if (hidden > 0) {
                if (isNotEmpty()) append('\n')
                append(getString(Res.string.notification_more_groups, hidden))
            }
        }
        return DesktopNotification(
            id = ID,
            kind = NotificationKind.NEW_EPISODES,
            title = getPluralString(Res.plurals.notification_new_episodes_title, lines.size, lines.size),
            body = body,
            route = groups.singleOrNull()?.let { group -> group.uuid?.let { "$ROUTE_OPEN/group/$it" } },
        )
    }

    /** `"tech: 3 — Podcast A, Podcast B"`; the default channel reads "Other podcasts". */
    private suspend fun groupLine(group: GroupLines): String {
        val name = group.name ?: getString(Res.string.notification_other_podcasts)
        val podcasts = group.lines.distinctBy { it.line.podcastId }.joinToString(", ") { it.line.podcastTitle }
        return "$name: ${group.lines.size} — $podcasts"
    }

    /** Groups by notice, in first-seen order (the notices themselves arrive in orderKey). */
    private fun groupLines(lines: List<SeenLine>): List<GroupLines> =
        buildList {
            for (candidate in lines) {
                val index = indexOfFirst { it.uuid == candidate.groupUuid }
                if (index >= 0) {
                    this[index] = this[index].withLine(candidate)
                } else {
                    add(GroupLines(candidate.groupUuid, candidate.groupName, mutableListOf(candidate)))
                }
            }
        }

    private data class SeenLine(
        val line: NewEpisodeLine,
        val groupUuid: String?,
        val groupName: String?,
    )

    private class GroupLines(
        val uuid: String?,
        val name: String?,
        val lines: MutableList<SeenLine>,
    ) {
        fun withLine(extra: SeenLine): GroupLines =
            GroupLines(uuid, name, lines.apply { add(extra) })
    }

    private companion object {
        const val ID = "new-episodes"
        const val ROUTE_OPEN = "neutrodyne://open"
        const val MAX_UNSEEN = 50
        const val MAX_GROUP_LINES = 4
    }
}
