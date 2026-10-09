// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.refresh

import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import dev.zacsweers.metro.Inject

/**
 * The `nextRefreshAt` rebase of 03 Periodic tick step 2 (`:core:data` internal): after an interval
 * or membership change every healthy row moves to `target = COALESCE(lastSuccessAt, subscribedAt)
 * + I` (`NEVER` when the interval is "Manual only"). Only rows that actually change are written —
 * forced (`nextRefreshAt = 0`), failing, `gone` and `needsCredentials` rows are left alone.
 *
 * Each write is conditional on the snapshot's `lastAttemptAt` (03's rule, sharpened 2026-10-07): a
 * fetch commit between the read and the write moves `lastAttemptAt`, the UPDATE then matches no
 * row and the fresher fetch state wins — the rebase cannot roll back columns it never read.
 */
@Inject
internal class NextRefreshRebaser(
    private val db: NeutrodyneDatabase,
    private val settings: SettingsRepository,
) {
    suspend fun rebase() {
        val intervalMinutes =
            RefreshPolicy.effectiveIntervalMinutes(settings.get(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES))
        val dao = db.podcastDao()
        for (candidate in dao.rebaseCandidates()) {
            val stored = candidate.nextRefreshAt
            if (stored == FORCED_DUE) continue
            val target =
                if (intervalMinutes == null) {
                    RefreshPolicy.NEVER
                } else {
                    (candidate.lastSuccessAt ?: candidate.subscribedAt) + intervalMinutes * MINUTE_MS
                }
            val shouldWrite =
                if (intervalMinutes == null) {
                    stored != RefreshPolicy.NEVER
                } else {
                    stored == null || stored == RefreshPolicy.NEVER || stored > target
                }
            if (shouldWrite) {
                dao.rebaseNextRefreshAt(candidate.id, target, candidate.lastAttemptAt ?: NO_ATTEMPT)
            }
        }
    }

    private companion object {
        const val FORCED_DUE = 0L
        const val MINUTE_MS = 60_000L

        /** The `lastAttemptAt` sentinel matching the SQL's `COALESCE(lastAttemptAt, -1)`. */
        const val NO_ATTEMPT = -1L
    }
}
