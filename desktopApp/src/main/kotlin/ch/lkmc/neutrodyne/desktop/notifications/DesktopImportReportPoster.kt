// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.notifications

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.DesktopNotification
import ch.lkmc.neutrodyne.core.common.DesktopNotifier
import ch.lkmc.neutrodyne.core.common.NotificationKind
import ch.lkmc.neutrodyne.core.domain.ImportReportPoster
import ch.lkmc.neutrodyne.core.model.ImportItemStatus
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.resolve
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.import_done_added
import ch.lkmc.neutrodyne.core.ui.resources.import_done_attention
import ch.lkmc.neutrodyne.core.ui.resources.import_done_title
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * 05 §8's desktop half of "the runner posts one notification": `ImportFetchRunner`'s
 * post-`DONE` report through [DesktopNotifier]. The notification id is the session's
 * `import-{sessionId}` (Android's tag — stable per session, so a repost replaces) and the
 * click route is 01's `neutrodyne://open/import/{sessionId}`, whose `ImportKey` screen is
 * the D1/D2 row.
 *
 * Before MD2 lands `OsDesktopNotifier` the bound notifier is the in-app banner fallback —
 * nothing here claims OS-centre delivery.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
internal class DesktopImportReportPoster
    @Inject
    constructor(
        private val notifier: DesktopNotifier,
    ) : ImportReportPoster {
        override suspend fun postDone(
            sessionId: Long,
            counts: Map<ImportItemStatus, Int>,
        ) {
            val added = counts[ImportItemStatus.SUBSCRIBED] ?: 0
            val attention = counts.filterKeys { it in ATTENTION_STATUSES }.values.sum()
            val body =
                UiText.Joined(
                    parts =
                        listOfNotNull(
                            UiText.Plural(Res.plurals.import_done_added, added, listOf(added)),
                            if (attention > 0) {
                                UiText.Plural(Res.plurals.import_done_attention, attention, listOf(attention))
                            } else {
                                null
                            },
                        ),
                    separator = ", ",
                    suffix = "",
                )
            notifier.post(
                DesktopNotification(
                    id = "$ID_PREFIX$sessionId",
                    kind = NotificationKind.IMPORT_BACKUP,
                    title = UiText.Res(Res.string.import_done_title).resolve(),
                    body = body.resolve(),
                    route = "$ROUTE_PREFIX$sessionId",
                ),
            )
        }

        private companion object {
            /** Android's notification tag for the same report (05 §8). */
            const val ID_PREFIX = "import-"

            /** 01's import route (IntentRouter `open/import/{id}` → `ImportKey`). */
            const val ROUTE_PREFIX = "neutrodyne://open/import/"

            /** The "3 need attention" half — 05 §8's attention buckets. */
            val ATTENTION_STATUSES =
                setOf(
                    ImportItemStatus.NOT_A_FEED,
                    ImportItemStatus.NO_MEDIA,
                    ImportItemStatus.AUTH_REQUIRED,
                    ImportItemStatus.GONE,
                    ImportItemStatus.FETCH_FAILED,
                )
        }
    }
