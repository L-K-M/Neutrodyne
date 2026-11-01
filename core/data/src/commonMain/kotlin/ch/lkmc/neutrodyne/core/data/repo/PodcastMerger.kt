// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.repo

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.database.DownloadEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.CredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.domain.DownloadLifecycle
import ch.lkmc.neutrodyne.core.model.ChangeOrigin
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Lossless database merge (02), shared with the feed-move path without a repository DI cycle. */
@Inject
internal class PodcastMerger(
    private val db: NeutrodyneDatabase,
    private val coordinator: CredentialCommitCoordinator,
    private val clock: Clock,
    private val downloads: Provider<DownloadLifecycle>,
) {
    @Suppress("UNUSED_PARAMETER") // Sync capture/applying hooks arrive with MS2, as for unsubscribe.
    suspend fun merge(loserId: Long, winnerId: Long, origin: ChangeOrigin) {
        require(loserId != winnerId) { "Cannot merge a podcast into itself" }

        // Last-reference deletion must exclude credential puts before acquiring the Room writer.
        coordinator.withCredentialCommit {
            val pairs = episodePairs(loserId, winnerId)
            withPreparedDownloads(pairs) { prepared ->
                db.withWriteTransaction { mergeInTransaction(loserId, winnerId, prepared) }
            }
        }
    }

    /** Revalidate both primary and alias ownership after taking the coordinator and writer. */
    suspend fun mergeIntoKeyOwner(
        loserId: Long,
        key: String,
    ): Long? =
        coordinator.withCredentialCommit {
            val initialOwner = db.podcastDao().byFeedKey(key)?.id ?: db.podcastDao().aliasOwner(key)
            if (initialOwner == null || initialOwner == loserId) return@withCredentialCommit null
            val pairs = episodePairs(loserId, initialOwner)
            withPreparedDownloads(pairs) { prepared ->
                db.withWriteTransaction {
                    val podcasts = db.podcastDao()
                    if (podcasts.byId(loserId) == null) return@withWriteTransaction null
                    val owner = podcasts.byFeedKey(key)?.id ?: podcasts.aliasOwner(key)
                    if (owner == null || owner == loserId) return@withWriteTransaction null

                    check(owner == initialOwner) { "Feed owner changed during download cleanup; retry merge" }
                    mergeInTransaction(loserId, owner, prepared)
                    owner
                }
            }
        }

    private suspend fun <T> withPreparedDownloads(
        pairs: Set<Pair<Long, Long>>,
        write: suspend (Map<Pair<Long, Long>, DownloadEntity?>) -> T,
    ): T {
        try {
            val prepared =
                pairs.associateWith { (loser, winner) ->
                    downloads().beforeEpisodeMerge(loser, winner)
                    db.downloadDao().byEpisode(loser)
                }
            return write(prepared)
        } finally {
            withContext(NonCancellable) {
                val ids = pairs.flatMap { listOf(it.first, it.second) }.distinct()
                suspendRunCatching { downloads().afterEpisodeMerges(ids) }
                    .onFailure { Log.w("PodcastMerge", it) { "Download ownership refresh deferred to reconciliation" } }
            }
        }
    }

    private suspend fun episodePairs(
        loserId: Long,
        winnerId: Long,
    ): Set<Pair<Long, Long>> {
        val winners = db.ingestDao().existing(winnerId).sortedBy { it.id }
        val byKey = winners.associateBy { it.identityKey }
        val byUrl =
            winners
                .mapNotNull { row ->
                    row.enclosureUrl?.let(UrlNormalizer::forIdentity)?.let { it to row }
                }.groupBy({ it.first }, { it.second })
        return db
            .ingestDao()
            .existing(loserId)
            .mapNotNull { episode ->
                val winner =
                    byKey[episode.identityKey] ?: episode.enclosureUrl
                        ?.let(UrlNormalizer::forIdentity)
                        ?.let { byUrl[it]?.firstOrNull() }
                winner?.let { episode.id to it.id }
            }.toSet()
    }

    private suspend fun mergeInTransaction(
        loserId: Long,
        winnerId: Long,
        prepared: Map<Pair<Long, Long>, DownloadEntity?>,
    ) {
        val pairs = episodePairs(loserId, winnerId)
        check(prepared.keys.containsAll(pairs)) {
            "Episodes changed during download cleanup; retry merge"
        }
        for (pair in pairs) {
            check(db.downloadDao().byEpisode(pair.first) == prepared[pair]) {
                "Downloads changed during merge cleanup; retry merge"
            }
        }
        val podcasts = db.podcastDao()
        val loser = requireNotNull(podcasts.byId(loserId)) { "Losing podcast does not exist" }
        requireNotNull(podcasts.byId(winnerId)) { "Winning podcast does not exist" }
        val dao = db.podcastMergeDao()
        val now = clock.now()

        val matches = pairs.toMap()
        val losingEpisodes = db.ingestDao().existing(loserId)
        val currentBefore = db.playSessionDao().get()?.currentEpisodeId

        dao.copyMemberships(loserId, winnerId)
        dao.moveAliases(loserId, winnerId)
        val urls = setOfNotNull(loser.feedKey, UrlNormalizer.forIdentity(loser.feedUrl))
        for (url in urls) dao.addMergeAlias(loserId, winnerId, url, now)
        dao.removeFeedKeyAliases(loserId, winnerId)
        dao.copySettings(loserId, winnerId)
        dao.copyMissingTitle(loserId, winnerId)

        for (episode in losingEpisodes) {
            val winner = matches[episode.id]
            if (winner == null) {
                dao.reparentEpisode(episode.id, winnerId)
                continue
            }

            dao.copyState(episode.id, winner)
            dao.unionState(episode.id, winner)
            dao.clearPlayedStart(winner)
            dao.copyPosition(episode.id, winner)
            dao.mergePosition(episode.id, winner)
            dao.resetPlayedPosition(winner)
            dao.ensureProgressState(winner)
            dao.deriveProgressStart(winner)
            dao.moveQueue(episode.id, winner)
            dao.moveDownload(episode.id, winner)
            dao.moveCurrentEpisode(episode.id, winner)
        }

        dao.moveContext(loserId, winnerId)
        dao.moveImportItems(loserId, winnerId)
        dao.copyProvenance(loserId, winnerId)
        dao.unionAmbiguity(loserId, winnerId)
        podcasts.deleteCascade(loserId, now)
        val session = db.playSessionDao().get()
        if (session != null && session.currentEpisodeId != currentBefore) {
            // A moved current must load paused, after index ownership is published on commit.
            db.playSessionDao().write(session.copy(generation = session.generation + 1, updatedAt = now))
        }
    }
}
