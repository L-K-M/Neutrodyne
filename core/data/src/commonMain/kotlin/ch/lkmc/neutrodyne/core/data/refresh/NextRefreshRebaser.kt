// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.refresh

import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.database.FetchStateBatcher
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope

/**
 * The `nextRefreshAt` rebase of 03 Periodic tick step 2 (`:core:data` internal): after an interval
 * or membership change every healthy row moves to `target = COALESCE(lastSuccessAt, subscribedAt)
 * + I` (`NEVER` when the interval is "Manual only"). Writes go through 02's batched fetch-state
 * update, and only rows that actually change are written — forced (`nextRefreshAt = 0`), failing,
 * `gone` and `needsCredentials` rows are left alone.
 */
@Inject
internal class NextRefreshRebaser(
    private val db: NeutrodyneDatabase,
    private val settings: SettingsRepository,
    private val clock: Clock,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    suspend fun rebase() {
        val intervalMinutes =
            RefreshPolicy.effectiveIntervalMinutes(settings.get(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES))
        val dao = db.podcastDao()
        val batcher = FetchStateBatcher(dao, clock, appScope)
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
            if (shouldWrite) batcher.add(candidate.fetchState(target))
        }
        batcher.flush()
    }

    private companion object {
        const val FORCED_DUE = 0L
        const val MINUTE_MS = 60_000L
    }
}
