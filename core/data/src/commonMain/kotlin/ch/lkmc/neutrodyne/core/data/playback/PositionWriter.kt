// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.playback

import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.database.EpisodePositionEntity
import ch.lkmc.neutrodyne.core.database.EpisodeStateEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.PositionStore
import ch.lkmc.neutrodyne.core.domain.StoredPosition
import ch.lkmc.neutrodyne.core.model.PositionSource
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class PositionWriter(
    private val db: NeutrodyneDatabase,
) : PositionStore {
    override fun observe(episodeId: Long): Flow<StoredPosition?> =
        db.invalidationTracker
            .createFlow("episode_position", "episode_state")
            .map {
                db.withReadTransaction {
                    db.positionDao().byEpisode(episodeId)?.let {
                        StoredPosition(
                            it.episodeId,
                            it.positionMs,
                            it.updatedAt,
                            db.episodeStateDao().byEpisode(episodeId)?.playedAt,
                        )
                    }
                }
            }.distinctUntilChanged()

    override suspend fun save(
        episodeId: Long,
        positionMs: Long,
        durationMs: Long?,
        source: PositionSource,
        now: Long,
        pinStartedAt: Long,
        allowed: () -> Boolean,
    ): Boolean {
        require(positionMs >= 0 && now >= 0 && pinStartedAt >= 0)
        require(durationMs == null || durationMs >= 0)

        return db.withWriteTransaction {
            db.savePosition(episodeId, positionMs, durationMs, source, now, pinStartedAt, allowed)
        }
    }

    override suspend fun reset(
        episodeId: Long,
        now: Long,
    ) {
        require(now >= 0)
        db.withWriteTransaction {
            db.positionDao().reset(listOf(episodeId), now)
            db.episodeStateDao().clearStarted(episodeId, now)
            db.captureEpisodeIntents(listOf(episodeId), EpisodeIntent.RESET_POSITION)
        }
    }

    override suspend fun markPlayed(
        episodeId: Long,
        now: Long,
    ) {
        require(now >= 0)
        db.withWriteTransaction { db.markEpisodesPlayed(listOf(episodeId), now) }
    }

    override suspend fun markUnplayedForReplay(
        episodeId: Long,
        now: Long,
    ) {
        require(now >= 0)
        db.withWriteTransaction {
            db.episodeStateDao().markUnplayed(listOf(episodeId), now)
            db.captureEpisodeIntents(listOf(episodeId), EpisodeIntent.UNPLAYED)
        }
    }
}

/** Also used for departures, within the session writer's transaction so arrival cannot race it. */
internal suspend fun NeutrodyneDatabase.savePosition(
    episodeId: Long,
    positionMs: Long,
    durationMs: Long?,
    source: PositionSource,
    now: Long,
    pinStartedAt: Long,
    allowed: () -> Boolean = { true },
): Boolean {
    if (!allowed()) return false
    if (episodeDao().byId(episodeId) == null) return false
    // The UPDATE guard alone cannot protect a first INSERT after a concurrent mark-played.
    if (episodeStateDao().byEpisode(episodeId)?.playedAt?.let { it >= pinStartedAt } == true) return false
    if (positionMs == 0L && positionDao().byEpisode(episodeId)?.positionMs?.let { it > 0 } == true) return false

    positionDao().insertIfAbsent(EpisodePositionEntity(episodeId, positionMs, durationMs, source, now))
    val written = positionDao().updateGuarded(episodeId, positionMs, durationMs, source, now, pinStartedAt) > 0
    if (written && positionMs > 0) {
        episodeStateDao().ensure(EpisodeStateEntity(episodeId, updatedAt = now))
        episodeStateDao().markStarted(episodeId, now)
    }
    return written
}
