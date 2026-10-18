// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.model.ImportItemStatus

/**
 * The import report notification port (05 §8): once a fetch session reaches `DONE` the runner
 * posts a single "N added, M need attention" notice whose click opens
 * `neutrodyne://open/import/{sessionId}`. Implemented by the Android notification-channel
 * poster (channel `import_backup`, ID 3001) and the desktop `DesktopNotifier` call site;
 * each suppresses or downgrades it per its own rules (permission, visible screen). In
 * `:core:domain` beside the other platform ports.
 */
interface ImportReportPoster {
    /**
     * Called once, after the session's `DONE` commit. [counts] maps every `ImportItemStatus`
     * present on the session's items to its tally — the same shape `ImportSessionView.counts`
     * carries.
     */
    suspend fun postDone(
        sessionId: Long,
        counts: Map<ImportItemStatus, Int>,
    )
}
