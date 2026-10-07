// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.ingest

import ch.lkmc.neutrodyne.core.database.ChapterEntity
import ch.lkmc.neutrodyne.core.database.EpisodeAltEnclosureEntity
import ch.lkmc.neutrodyne.core.database.EpisodeEntity
import ch.lkmc.neutrodyne.core.database.EpisodeFeedUpdate
import ch.lkmc.neutrodyne.core.database.EpisodeTranscriptEntity
import ch.lkmc.neutrodyne.core.database.ExistingEpisodeKey
import ch.lkmc.neutrodyne.core.database.FundingEntity
import ch.lkmc.neutrodyne.core.database.PersonEntity
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.ChapterSource
import ch.lkmc.neutrodyne.core.model.EpisodeType
import ch.lkmc.neutrodyne.core.model.OwnerType
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.feeds.model.ParsedEpisode
import ch.lkmc.neutrodyne.feeds.parse.EnclosureTypes
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.min

/**
 * The off-transaction projection of one accepted item (03 Diff algorithm): identity keys, pass-2
 * match aids and every resolved column, so the write transaction only maps values onto entities.
 * `matchedTo`/`insertedId` are filled in by the diff passes.
 */
internal class PreparedItem(
    val episode: ParsedEpisode,
    /** The item's index in the document — warning records carry it. */
    val index: Int,
    /** `EpisodeKeys.primary(episode)` — also the docPrimary set entry. */
    val primaryKey: String,
    /**
     * The key this document actually assigns (primary, else a fallback for a dup'd primary).
     * Mutable: a contested row keeps its own stored key, and an unmatched item whose assigned
     * key a stored row already holds re-derives a free fallback before insert (03 step 4).
     */
    var docKey: String,
    /** [docKey] first, then `EpisodeKeys.candidates(episode)` — pass 1's claim keys in order. */
    val claimKeys: List<String>,
    /** `EpisodeKeys.fallbacks(episode)` — the re-derive pool when [docKey] collides at insert. */
    val fallbackKeys: List<String>,
    /** `UrlNormalizer.forIdentity(enclosure.url)` — pass-2 enclosure map key. */
    val enclosureIdentity: String?,
    /** `UrlNormalizer.forIdentityNoQuery(enclosure.url)` — pass-2 query-less map key. */
    val enclosureNoQuery: String?,
    /** `TitleMatch.normalise(title) + "|" + utcDay(pubDate)` — pass-2 title-day key. */
    val titleDayKey: String?,
    val contentHash: Long,
    /** The non-empty stored title (03 Accepted items: date, then file name, then `…`). */
    val title: String,
    /** Null when it would duplicate the podcast cover (03 Episode artwork). */
    val imageUrl: String?,
    val artworkKey: String?,
    val descriptionBytes: ByteArray?,
    val snippet: String?,
    /** 04's per-`externalMediaId` override (null for RSS items). */
    val hint: RowHint?,
    /**
     * `pubDateValid` (03 sortDate and clock): `pubDate` clamped to
     * `1990-01-01 .. now + 365 days` — invalid and future dates sort/newness-date as undated.
     */
    val pubDateValid: Long?,
) {
    var matchedTo: ExistingEpisodeKey? = null
    var insertedId: Long? = null
    var isNew: Boolean = false

    /** A still-unmatched item with no free fallback left — skipped by the insert phase. */
    var dropped: Boolean = false

    private val parsedIsVideo: Boolean get() = EnclosureTypes.isVideo(episode.primaryEnclosure?.effectiveType)

    /** `sortDate = min(pubDateValid ?: firstSeenAt, firstSeenAt + 24 h)` (03 sortDate and clock). */
    fun sortDate(firstSeenAt: Long): Long = min(pubDateValid ?: firstSeenAt, firstSeenAt + DAY_MS)

    /** The insert row (03 step 7); `isNew`/`firstSeenAt` come from the transaction phase. */
    fun insertRow(
        podcastId: Long,
        firstSeenAt: Long,
        now: Long,
    ): EpisodeEntity =
        EpisodeEntity(
            podcastId = podcastId,
            identityKey = docKey,
            guid = episode.guid,
            title = title,
            pubDate = episode.pubDate,
            rawPubDate = episode.rawPubDate,
            sortDate = sortDate(firstSeenAt),
            feedOrder = episode.feedOrder,
            firstSeenAt = firstSeenAt,
            lastSeenAt = now,
            inFeed = true,
            isNew = isNew,
            enclosureUrl = episode.primaryEnclosure?.url,
            enclosureType = episode.primaryEnclosure?.effectiveType,
            enclosureLength = episode.primaryEnclosure?.length,
            externalMediaId = episode.externalMediaId,
            isVideo = hint?.isVideo ?: parsedIsVideo,
            durationMs = episode.durationMs,
            season = episode.season,
            seasonName = episode.seasonName,
            episodeNumber = episode.episodeNumber,
            episodeDisplay = episode.episodeDisplay,
            episodeType = episodeTypeEnum(),
            explicit = episode.explicit,
            imageUrl = imageUrl,
            artworkKey = artworkKey,
            link = episode.link,
            chaptersUrl = episode.chaptersUrl,
            chaptersType = episode.chaptersType,
            contentHash = contentHash,
            availability = hint?.availability ?: Availability.AVAILABLE,
            isShort = hint?.isShort ?: false,
            snippet = snippet,
        )

    /**
     * The `updateFeedFields` row of a changed match (03 step 6): `sortDate` recomputed against the
     * stored `firstSeenAt`, null-preserving columns left null so their `COALESCE` keeps the value.
     */
    fun feedUpdate(now: Long): EpisodeFeedUpdate {
        val row = requireNotNull(matchedTo) { "feedUpdate without a matched row" }
        return EpisodeFeedUpdate(
            id = row.id,
            guid = episode.guid,
            title = title,
            pubDate = episode.pubDate,
            rawPubDate = episode.rawPubDate,
            sortDate = sortDate(row.firstSeenAt),
            feedOrder = episode.feedOrder,
            lastSeenAt = now,
            enclosureUrl = episode.primaryEnclosure?.url,
            enclosureType = episode.primaryEnclosure?.effectiveType,
            enclosureLength = episode.primaryEnclosure?.length,
            externalMediaId = episode.externalMediaId,
            season = episode.season,
            seasonName = episode.seasonName,
            episodeNumber = episode.episodeNumber,
            episodeDisplay = episode.episodeDisplay,
            episodeType = episodeTypeEnum(),
            explicit = episode.explicit,
            link = episode.link,
            contentHash = contentHash,
            snippet = snippet,
            durationMs = episode.durationMs,
            imageUrl = imageUrl,
            artworkKey = artworkKey,
            chaptersUrl = episode.chaptersUrl,
            chaptersType = episode.chaptersType,
            availability = hint?.availability,
            isShort = hint?.isShort,
            // 04 owns isVideo for externalMediaId rows; RSS rows take the parsed value.
            isVideo = if (episode.externalMediaId != null) hint?.isVideo else parsedIsVideo,
        )
    }

    fun transcripts(episodeId: Long): List<EpisodeTranscriptEntity> =
        episode.transcripts.map {
            EpisodeTranscriptEntity(
                episodeId = episodeId,
                url = it.url,
                type = it.type.orEmpty(),
                language = it.language,
                rel = it.rel,
            )
        }

    fun altEnclosures(episodeId: Long): List<EpisodeAltEnclosureEntity> =
        episode.alternateEnclosures.mapIndexed { index, a ->
            EpisodeAltEnclosureEntity(
                episodeId = episodeId,
                ordinal = index,
                type = a.type.orEmpty(),
                length = a.length,
                bitrate = a.bitrate,
                height = a.height?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
                lang = a.lang,
                title = a.title,
                rel = a.rel,
                codecs = a.codecs,
                isDefault = a.isDefault,
                integrityType = a.integrity.firstOrNull()?.type,
                integrityValue = a.integrity.firstOrNull()?.value,
                sourcesJson = Json.encodeToString(a.sources),
            )
        }

    fun persons(episodeId: Long): List<PersonEntity> =
        episode.persons.orEmpty().map {
            PersonEntity(
                ownerType = OwnerType.EPISODE,
                ownerId = episodeId,
                name = it.name,
                role = it.role,
                grp = it.group,
                imageUrl = it.img,
                href = it.href,
            )
        }

    fun funding(episodeId: Long): List<FundingEntity> =
        episode.funding.map {
            FundingEntity(
                ownerType = OwnerType.EPISODE,
                ownerId = episodeId,
                url = it.url,
                label = it.title,
            )
        }

    /** Podlove Simple Chapters, ordered by `start` (03 PSC). */
    fun pscChapters(episodeId: Long): List<ChapterEntity> =
        episode.inlineChapters.sortedBy { it.startMs }.mapIndexed { index, c ->
            ChapterEntity(
                episodeId = episodeId,
                source = ChapterSource.PSC,
                ordinal = index,
                startMs = c.startMs,
                title = c.title,
                imageUrl = c.image,
                linkUrl = c.href,
            )
        }

    private fun episodeTypeEnum(): EpisodeType? =
        when (episode.episodeType) {
            "full" -> EpisodeType.FULL
            "trailer" -> EpisodeType.TRAILER
            "bonus" -> EpisodeType.BONUS
            else -> null
        }

    companion object {
        const val DAY_MS = 86_400_000L

        /** `pubDateValid`'s lower bound: 1990-01-01T00:00:00Z (03 sortDate and clock). */
        const val PUB_DATE_MIN_MS = 631_152_000_000L

        /** `pubDateValid`'s upper bound offset: one year past the ingest's `now`. */
        const val PUB_DATE_MAX_OFFSET_MS = 365L * DAY_MS
    }
}

/**
 * The pass-2 lookup structure of 03 step 5: stored rows whose `identityKey` no document key
 * claims — every *assigned* document key is reserved, primary or fallback — indexed by normalised
 * enclosure URL, query-less enclosure URL and title-day. Match order is the spec's: enclosure
 * URL → query-less URL → title+day with guards.
 */
internal class Pass2Index(
    existing: List<ExistingEpisodeKey>,
    docKeys: Set<String>,
    alreadyMatched: Set<Long>,
) {
    private val candidates =
        existing.filter { it.id !in alreadyMatched && it.identityKey !in docKeys }

    private val byEnclosure = candidates.grouped { it.enclosureUrl?.let(UrlNormalizer::forIdentity) }
    private val byEnclosureNoQuery =
        candidates.grouped { it.enclosureUrl?.let(UrlNormalizer::forIdentityNoQuery) }
    private val byTitleDay = candidates.grouped { rowTitleDayKey(it) }

    fun match(
        item: PreparedItem,
        taken: Set<Long>,
    ): ExistingEpisodeKey? {
        item.enclosureIdentity?.let { key ->
            byEnclosure[key]?.firstOrNull { it.id !in taken }?.let { return it }
        }
        item.enclosureNoQuery?.let { key ->
            byEnclosureNoQuery[key]?.firstOrNull { it.id !in taken }?.let { return it }
        }
        val dayKey = item.titleDayKey ?: return null
        return byTitleDay[dayKey]?.firstOrNull { it.id !in taken && guardsPass(it, item) }
    }

    /** The title-day guards (03 step 5): known durations within 10 min, known MIME majors equal. */
    private fun guardsPass(
        row: ExistingEpisodeKey,
        item: PreparedItem,
    ): Boolean {
        val storedDuration = row.durationMs
        val itemDuration = item.episode.durationMs
        if (storedDuration != null && itemDuration != null &&
            abs(storedDuration - itemDuration) > DURATION_GUARD_MS
        ) {
            return false
        }
        val storedMajor = row.enclosureType?.substringBefore('/')
        val itemMajor =
            item.episode.primaryEnclosure
                ?.effectiveType
                ?.substringBefore('/')
        return storedMajor == null || itemMajor == null || storedMajor == itemMajor
    }

    private fun rowTitleDayKey(row: ExistingEpisodeKey): String? {
        val day = row.pubDate ?: return null
        return titleDayKeyOf(row.title, day)
    }

    private companion object {
        const val DURATION_GUARD_MS = 10L * 60_000L
    }
}

internal fun titleDayKeyOf(
    title: String,
    pubDateMs: Long,
): String =
    ch.lkmc.neutrodyne.feeds.identity.TitleMatch
        .normalise(title) + "|" + pubDateMs / PreparedItem.DAY_MS

private fun List<ExistingEpisodeKey>.grouped(
    keyOf: (ExistingEpisodeKey) -> String?,
): Map<String, List<ExistingEpisodeKey>> =
    mapNotNull { row -> keyOf(row)?.let { it to row } }.groupBy({ it.first }, { it.second })
