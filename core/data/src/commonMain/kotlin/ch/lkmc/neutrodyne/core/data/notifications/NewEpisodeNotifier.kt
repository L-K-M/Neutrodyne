// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.notifications

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.EffectiveSettingsResolver
import ch.lkmc.neutrodyne.core.domain.NewEpisodePoster
import ch.lkmc.neutrodyne.core.model.NewEpisodeLine
import ch.lkmc.neutrodyne.core.model.NewEpisodeNotice
import ch.lkmc.neutrodyne.core.model.NewEpisodes
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/** Common run-level selection; platform posters own presentation and active-notice merging. */
@Inject
@SingleIn(AppScope::class)
internal class NewEpisodeNotifier(
    private val db: NeutrodyneDatabase,
    private val resolver: EffectiveSettingsResolver,
    private val poster: NewEpisodePoster,
) {
    suspend fun post(batch: List<NewEpisodes>) {
        val ids = batch.filterNot { it.initialFetch }.flatMap { it.episodeIds }.toSet()
        if (ids.isEmpty()) return

        val rows = db.episodeDao().notificationRows(ids)
        if (rows.isEmpty()) return

        // Resolve the whole run once, never once per episode or podcast.
        val settings = resolver.notifications(rows.map { it.podcastId }.toSet())
        val selected =
            rows.groupBy { it.episodeId }.values.mapNotNull { memberships ->
                val episode = memberships.first()
                if (settings[episode.podcastId]?.value != true) return@mapNotNull null

                // SQL orders memberships by group orderKey and ID, independent of arrival order.
                memberships.firstOrNull { it.groupNotify == true }
                    ?: episode.copy(groupId = null, groupUuid = null, groupName = null, groupOrderKey = null)
            }
        val notices =
            selected
                .groupBy { it.groupUuid }
                .values
                .sortedWith(
                    compareBy(
                        { it.first().groupUuid == null },
                        { it.first().groupOrderKey },
                        { it.first().groupId },
                    ),
                ).map { episodes ->
                    NewEpisodeNotice(
                        groupUuid = episodes.first().groupUuid,
                        groupName = episodes.first().groupName,
                        episodes =
                            episodes.map {
                                NewEpisodeLine(
                                    episodeId = it.episodeId,
                                    podcastId = it.podcastId,
                                    podcastTitle = it.podcastTitle,
                                    episodeTitle = it.episodeTitle,
                                    artworkKey = it.artworkKey,
                                )
                            },
                    )
                }
        if (notices.isEmpty()) return

        try {
            poster.post(notices)
        } catch (error: Throwable) {
            // Posting is best effort, including a platform's own cancellation or permission failure.
            Log.w(TAG, error) { "new-episode posting failed" }
        }
    }

    private companion object {
        const val TAG = "NewEpisodeNotifier"
    }
}
