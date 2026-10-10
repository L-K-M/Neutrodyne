// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update
import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.EpisodeType
import ch.lkmc.neutrodyne.core.model.OwnerType

/**
 * The primitives 03's diff algorithm needs inside one `withWriteTransaction` per feed (02
 * Ingestion support).
 */
@Dao
abstract class IngestDao(
    private val db: NeutrodyneDatabase,
) {
    /** 03 builds its identity/enclosure/guid maps from these rows. */
    @Query(
        "SELECT id, identityKey, guid, enclosureUrl, title, pubDate, contentHash, inFeed" +
            " FROM episode WHERE podcastId = :podcastId",
    )
    abstract suspend fun existing(podcastId: Long): List<ExistingEpisodeKey>

    /** ABORT on a duplicate `(podcastId, identityKey)`; rows in descending `feedOrder` (02). */
    @Insert
    abstract suspend fun insertEpisodes(rows: List<EpisodeEntity>): List<Long>

    /**
     * Rewrites every feed column of one changed row except `id`, `podcastId`, `identityKey`,
     * `firstSeenAt`, `isNew` and `inFeed` (`setInFeed` flips that flag). The `COALESCE` columns
     * keep the stored value when the parsed value is null (02 Ingestion support). Room cannot
     * expand a projection's properties into `@Query` bind variables, so the row's fields are spread
     * onto the generated statement explicitly.
     */
    suspend fun updateFeedFields(row: EpisodeFeedUpdate) =
        updateFeedFieldsExpanded(
            guid = row.guid,
            title = row.title,
            pubDate = row.pubDate,
            rawPubDate = row.rawPubDate,
            sortDate = row.sortDate,
            feedOrder = row.feedOrder,
            lastSeenAt = row.lastSeenAt,
            enclosureUrl = row.enclosureUrl,
            enclosureType = row.enclosureType,
            enclosureLength = row.enclosureLength,
            externalMediaId = row.externalMediaId,
            season = row.season,
            seasonName = row.seasonName,
            episodeNumber = row.episodeNumber,
            episodeDisplay = row.episodeDisplay,
            episodeType = row.episodeType,
            explicit = row.explicit,
            link = row.link,
            contentHash = row.contentHash,
            snippet = row.snippet,
            durationMs = row.durationMs,
            imageUrl = row.imageUrl,
            artworkKey = row.artworkKey,
            chaptersUrl = row.chaptersUrl,
            chaptersType = row.chaptersType,
            availability = row.availability,
            isShort = row.isShort,
            isVideo = row.isVideo,
            id = row.id,
        )

    @Query(
        "UPDATE episode SET guid = :guid, title = :title, pubDate = :pubDate, rawPubDate = :rawPubDate," +
            " sortDate = :sortDate, feedOrder = :feedOrder, lastSeenAt = :lastSeenAt," +
            " enclosureUrl = :enclosureUrl, enclosureType = :enclosureType, enclosureLength = :enclosureLength," +
            " externalMediaId = :externalMediaId, season = :season, seasonName = :seasonName," +
            " episodeNumber = :episodeNumber, episodeDisplay = :episodeDisplay, episodeType = :episodeType," +
            " explicit = :explicit, link = :link, contentHash = :contentHash, snippet = :snippet," +
            " durationMs = COALESCE(:durationMs, durationMs)," +
            " imageUrl = COALESCE(:imageUrl, imageUrl), artworkKey = COALESCE(:artworkKey, artworkKey)," +
            " chaptersUrl = COALESCE(:chaptersUrl, chaptersUrl)," +
            " chaptersType = COALESCE(:chaptersType, chaptersType)," +
            " availability = COALESCE(:availability, availability)," +
            " isShort = COALESCE(:isShort, isShort), isVideo = COALESCE(:isVideo, isVideo)" +
            " WHERE id = :id",
    )
    protected abstract suspend fun updateFeedFieldsExpanded(
        guid: String?,
        title: String,
        pubDate: Long?,
        rawPubDate: String?,
        sortDate: Long,
        feedOrder: Int,
        lastSeenAt: Long,
        enclosureUrl: String?,
        enclosureType: String?,
        enclosureLength: Long?,
        externalMediaId: String?,
        season: Int?,
        seasonName: String?,
        episodeNumber: String?,
        episodeDisplay: String?,
        episodeType: EpisodeType?,
        explicit: Boolean?,
        link: String?,
        contentHash: Long,
        snippet: String?,
        durationMs: Long?,
        imageUrl: String?,
        artworkKey: String?,
        chaptersUrl: String?,
        chaptersType: String?,
        availability: Availability?,
        isShort: Boolean?,
        isVideo: Boolean?,
        id: Long,
    )

    /** Fallback-match re-key (02 Identity keys): preserves the row id and all user-state rows. */
    @Query("UPDATE episode SET identityKey = :key, guid = :guid WHERE id = :id")
    abstract suspend fun rekey(
        id: Long,
        key: String,
        guid: String?,
    )

    /** `inFeed` flips for rows inside the parsed window; chunked at 500 bound variables (02). */
    suspend fun setInFeed(
        ids: List<Long>,
        inFeed: Boolean,
    ) {
        ids.chunked(BIND_CHUNK).forEach { setInFeedChunk(it, inFeed) }
    }

    @Query("UPDATE episode SET inFeed = :inFeed WHERE id IN (:ids)")
    protected abstract suspend fun setInFeedChunk(
        ids: List<Long>,
        inFeed: Boolean,
    )

    /**
     * Bumps `lastSeenAt` only for rows older than a day — the 90-day retention clock needs day
     * granularity, and a large feed is not rewritten on every refresh (02).
     */
    @Query(
        "UPDATE episode SET lastSeenAt = :now WHERE podcastId = :podcastId AND inFeed = 1" +
            " AND lastSeenAt < :now - $DAY_MS",
    )
    abstract suspend fun touchSeen(
        podcastId: Long,
        now: Long,
    )

    /**
     * Delete-and-insert per child table of a changed episode (02): description blob (already
     * encoded by `EpisodeDescriptionCodec` on `Default`), transcripts, alternate enclosures,
     * PSC chapters, and the episode's `person`/`funding` rows (`ownerType = 'EPISODE'`).
     */
    suspend fun replaceChildren(
        episodeId: Long,
        description: ByteArray?,
        transcripts: List<EpisodeTranscriptEntity>,
        altEnclosures: List<EpisodeAltEnclosureEntity>,
        persons: List<PersonEntity>,
        funding: List<FundingEntity>,
        pscChapters: List<ChapterEntity>,
    ) {
        db.withWriteTransaction {
            deleteDescription(episodeId)
            if (description != null) insertDescription(EpisodeDescriptionEntity(episodeId, description))
            deleteTranscripts(episodeId)
            insertTranscripts(transcripts)
            deleteAltEnclosures(episodeId)
            insertAltEnclosures(altEnclosures)
            deleteChaptersOfSource(episodeId, PSC)
            insertChapters(pscChapters)
            deleteChildrenOf(OwnerType.EPISODE.name, episodeId)
            insertPersons(persons)
            insertFunding(funding)
        }
    }

    /**
     * The podcast-level variant of [replaceChildren] (02's "persons/funding by `(ownerType,
     * ownerId)`"): channel-level `itunes:author`/`podcast:person`/`funding` rows are replaced with
     * the parsed set in the feed's transaction.
     */
    suspend fun replacePodcastChildren(
        podcastId: Long,
        persons: List<PersonEntity>,
        funding: List<FundingEntity>,
    ) {
        db.withWriteTransaction {
            deleteChildrenOf(OwnerType.PODCAST.name, podcastId)
            insertPersons(persons)
            insertFunding(funding)
        }
    }

    /** The ingest-side `podcast` write: metadata, validators and scheduling columns only (02). */
    @Update(entity = PodcastEntity::class)
    abstract suspend fun applyFeedMetadata(row: PodcastFeedMetadata)

    /** The `YOUTUBE_CHANNEL` variant (04): omits the YouTube-page columns 04 writes. */
    @Update(entity = PodcastEntity::class)
    abstract suspend fun applyYouTubeFeedMetadata(row: YouTubeFeedMetadata)

    @Query("DELETE FROM episode_description WHERE episodeId = :episodeId")
    protected abstract suspend fun deleteDescription(episodeId: Long)

    @Insert
    protected abstract suspend fun insertDescription(row: EpisodeDescriptionEntity)

    @Query("DELETE FROM episode_transcript WHERE episodeId = :episodeId")
    protected abstract suspend fun deleteTranscripts(episodeId: Long)

    // REPLACE because the parser emits one row per <podcast:transcript> element and real feeds
    // repeat a URL across type/language variants: the (episodeId, url) primary key would abort
    // the whole child-table replace on the duplicate. Last element wins, document order.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertTranscripts(rows: List<EpisodeTranscriptEntity>)

    @Query("DELETE FROM episode_alt_enclosure WHERE episodeId = :episodeId")
    protected abstract suspend fun deleteAltEnclosures(episodeId: Long)

    @Insert
    protected abstract suspend fun insertAltEnclosures(rows: List<EpisodeAltEnclosureEntity>)

    @Query("DELETE FROM chapter WHERE episodeId = :episodeId AND source = :source")
    protected abstract suspend fun deleteChaptersOfSource(
        episodeId: Long,
        source: String,
    )

    @Insert
    protected abstract suspend fun insertChapters(rows: List<ChapterEntity>)

    @Query("DELETE FROM person WHERE ownerType = :ownerType AND ownerId = :ownerId")
    protected abstract suspend fun deletePersonsOf(
        ownerType: String,
        ownerId: Long,
    )

    @Query("DELETE FROM funding WHERE ownerType = :ownerType AND ownerId = :ownerId")
    protected abstract suspend fun deleteFundingOf(
        ownerType: String,
        ownerId: Long,
    )

    @Insert
    protected abstract suspend fun insertPersons(rows: List<PersonEntity>)

    @Insert
    protected abstract suspend fun insertFunding(rows: List<FundingEntity>)

    private suspend fun deleteChildrenOf(
        ownerType: String,
        ownerId: Long,
    ) {
        deletePersonsOf(ownerType, ownerId)
        deleteFundingOf(ownerType, ownerId)
    }

    internal companion object {
        /** 02 Transactions and threading: `IN (:ids)` lists are chunked at 500 bound variables. */
        const val BIND_CHUNK = 500

        const val DAY_MS = 86_400_000L

        /** `ChapterSource.PSC.name` — a literal because the column is converted to TEXT. */
        const val PSC = "PSC"
    }
}
