// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.youtube

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.data.playback.markEpisodesPlayed
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.domain.YouTubeExternalOpen
import ch.lkmc.neutrodyne.core.domain.YouTubeOpenContext
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.settings.YouTubeSettingKeys
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
internal class YouTubeExternalOpenImpl(
    private val db: NeutrodyneDatabase,
    private val settings: SettingsRepository,
    private val clock: Clock,
) : YouTubeExternalOpen {
    override suspend fun onOpened(
        episodeId: Long,
        context: YouTubeOpenContext,
    ): Boolean {
        if (context == YouTubeOpenContext.PLAYABLE_OVERFLOW ||
            !settings.get(YouTubeSettingKeys.MARK_PLAYED_ON_OPEN)
        ) {
            return false
        }

        return db.withWriteTransaction {
            val episode = db.episodeDao().byId(episodeId) ?: return@withWriteTransaction false
            val podcast = db.podcastDao().byId(episode.podcastId) ?: return@withWriteTransaction false
            if (podcast.sourceType != SourceType.YOUTUBE_CHANNEL ||
                db.episodeStateDao().byEpisode(episodeId)?.playedAt != null
            ) {
                return@withWriteTransaction false
            }

            db.markEpisodesPlayed(listOf(episodeId), clock.now())
            true
        }
    }

    override suspend fun undo(episodeId: Long) {
        db.episodeStateDao().markUnplayed(listOf(episodeId), clock.now())
    }
}
