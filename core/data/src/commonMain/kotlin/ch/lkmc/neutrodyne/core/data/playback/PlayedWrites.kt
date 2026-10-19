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

/** Literal actions must survive even when effective columns already hide raw played/progress. */
internal enum class EpisodeIntent(
    val field: String,
    val literal: String,
) {
    UNPLAYED("played", "{\"played\":false,\"playedAt\":null}"),
    RESET_POSITION("pos", "{\"ms\":0,\"reset\":true}"),
}

/** Caller owns the transaction; absent episodes have no record to reset. */
internal suspend fun NeutrodyneDatabase.captureEpisodeIntents(
    ids: List<Long>,
    vararg intents: EpisodeIntent,
) {
    val state = syncStateDao().get() ?: return
    if (!state.enabled || state.applying) return

    val syncIds = mutableMapOf<Long, String>()
    for (id in ids.distinct()) {
        val episode = episodeDao().byId(id) ?: continue
        val podcastSyncId =
            syncIds[episode.podcastId] ?: podcastDao().byId(episode.podcastId)!!.syncId.also {
                syncIds[episode.podcastId] = it
            }
        for (intent in intents) {
            syncOutboxDao().captureIntent("episode", podcastSyncId + episode.identityKey, intent.field, intent.literal)
        }
    }
}
