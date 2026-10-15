// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.repo

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.CredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.model.ChangeOrigin
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import dev.zacsweers.metro.Inject

/** Lossless database merge (02), shared with the feed-move path without a repository DI cycle. */
@Inject
internal class PodcastMerger(
    private val db: NeutrodyneDatabase,
    private val coordinator: CredentialCommitCoordinator,
    private val clock: Clock,
) {
    @Suppress("UNUSED_PARAMETER") // Sync capture/applying hooks arrive with MS2, as for unsubscribe.
    suspend fun merge(loserId: Long, winnerId: Long, origin: ChangeOrigin) {
        require(loserId != winnerId) { "Cannot merge a podcast into itself" }

        // Last-reference deletion must exclude credential puts before acquiring the Room writer.
        coordinator.withCredentialCommit {
            db.withWriteTransaction {
                mergeInTransaction(loserId, winnerId)
            }
        }
    }

    /** Revalidate both primary and alias ownership after taking the coordinator and writer. */
    suspend fun mergeIntoKeyOwner(
        loserId: Long,
        key: String,
    ): Long? =
        coordinator.withCredentialCommit {
            db.withWriteTransaction {
                val podcasts = db.podcastDao()
                if (podcasts.byId(loserId) == null) return@withWriteTransaction null
                val owner = podcasts.byFeedKey(key)?.id ?: podcasts.aliasOwner(key)
                if (owner == null || owner == loserId) return@withWriteTransaction null

                mergeInTransaction(loserId, owner)
                owner
            }
        }

    private suspend fun mergeInTransaction(
        loserId: Long,
        winnerId: Long,
    ) {
        val podcasts = db.podcastDao()
        val loser = requireNotNull(podcasts.byId(loserId)) { "Losing podcast does not exist" }
        requireNotNull(podcasts.byId(winnerId)) { "Winning podcast does not exist" }
        val dao = db.podcastMergeDao()
        val now = clock.now()

        // Re-read under the writer: an ingest may have changed either feed while we waited.
        val winningEpisodes = db.ingestDao().existing(winnerId).sortedBy { it.id }
        val byKey = winningEpisodes.associateBy { it.identityKey }
        val byUrl =
            winningEpisodes
                .mapNotNull { row ->
                    row.enclosureUrl?.let(UrlNormalizer::forIdentity)?.let { it to row }
                }.groupBy({ it.first }, { it.second })
        val losingEpisodes = db.ingestDao().existing(loserId)

        dao.copyMemberships(loserId, winnerId)
        dao.moveAliases(loserId, winnerId)
        val urls = setOfNotNull(loser.feedKey, UrlNormalizer.forIdentity(loser.feedUrl))
        for (url in urls) dao.addMergeAlias(loserId, winnerId, url, now)
        dao.removeFeedKeyAliases(loserId, winnerId)
        dao.copySettings(loserId, winnerId)
        dao.copyMissingTitle(loserId, winnerId)

        for (episode in losingEpisodes) {
            val winner =
                byKey[episode.identityKey] ?: episode.enclosureUrl
                    ?.let(UrlNormalizer::forIdentity)
                    ?.let { byUrl[it]?.firstOrNull() }
            if (winner == null) {
                dao.reparentEpisode(episode.id, winnerId)
                continue
            }

            dao.copyState(episode.id, winner.id)
            dao.unionState(episode.id, winner.id)
            dao.clearPlayedStart(winner.id)
            dao.copyPosition(episode.id, winner.id)
            dao.mergePosition(episode.id, winner.id)
            dao.resetPlayedPosition(winner.id)
            dao.ensureProgressState(winner.id)
            dao.deriveProgressStart(winner.id)
            dao.moveQueue(episode.id, winner.id)
            dao.moveDownload(episode.id, winner.id)
            dao.moveCurrentEpisode(episode.id, winner.id)
        }

        dao.moveContext(loserId, winnerId)
        dao.moveImportItems(loserId, winnerId)
        dao.copyProvenance(loserId, winnerId)
        dao.unionAmbiguity(loserId, winnerId)
        podcasts.deleteCascade(loserId, now)
    }
}
