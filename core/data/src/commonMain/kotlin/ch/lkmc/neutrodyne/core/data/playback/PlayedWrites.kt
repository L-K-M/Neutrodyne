// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.playback

import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase

/** The shared chain for player, episode/bulk actions and import, inside the caller's transaction. */
internal suspend fun NeutrodyneDatabase.markEpisodesPlayed(
    ids: List<Long>,
    now: Long,
) {
    for (chunk in ids.distinct().chunked(PLAYBACK_BIND_CHUNK)) {
        episodeStateDao().ensureAll(chunk, now)
        episodeStateDao().markPlayed(chunk, now)
        positionDao().reset(chunk, now)
        queueDao().removeEpisodes(chunk)
    }
}

internal const val PLAYBACK_BIND_CHUNK = 500
