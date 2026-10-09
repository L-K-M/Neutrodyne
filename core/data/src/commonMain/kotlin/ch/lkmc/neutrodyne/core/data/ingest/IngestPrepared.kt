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
import ch.lkmc.neutrodyne.feeds.identity.TitleMatch
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
    /** `UrlNormalizer.forIdentity(enclosure.url)` — pass-2 tier A's map key. */
    val enclosureIdentity: String?,
    /** `UrlNormalizer.forIdentityNoQuery(enclosure.url)` — pass-2 tier B's map key. */
    val enclosureNoQuery: String?,
    /** `TitleMatch.normalise(title)` or null when absent/blank — the title half of tiers B/C. */
    val titleNorm: String?,
    /** The item's UTC publication-day bucket — the day half of tiers B/C. */
    val pubDayUtc: Long?,
    /** `titleNorm + "|" + pubDayUtc` — pass-2 tier C's map key. */
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
 * The pass-2 matcher of 03 step 5 as *global strength tiers* (deviation 14) under deviation
 * 16's rule: a stored row is reused only on evidence that identifies the same episode, so a
 * duplicate is preferred to a state transfer whenever the evidence is weak. A tier finishes
 * for every unmatched item before the next starts, so a weak relation can never take a row a
 * stronger one would claim (r4 F2). Inside a tier a pair matches only when it is unique on
 * both sides — exactly one eligible row relates to the item and exactly one unmatched item
 * relates to that row; ambiguous pairs skip the tier and can still match on a later pass
 * (r4 F1, r5 F2).
 *
 * The whole A → B → C sequence *iterates*: a claim can clear an ambiguity an earlier pass
 * left behind, so after any productive pass the tiers run again over what remains, until a
 * full pass claims nothing.
 *
 * Eligible rows are every stored row pass 1 left unclaimed — the design's reservation of
 * "primary keys of document items" stays narrowed to *claimed* rows (deviation 12): an
 * unclaimed doc-keyed row can only be a `g:` claim the pass-1 guard rejected, and reserving it
 * would strand exactly the episodes this pass exists to recover.
 */
internal class Pass2Index(
    existing: List<ExistingEpisodeKey>,
    alreadyMatched: Set<Long>,
) {
    private val candidates = existing.filter { it.id !in alreadyMatched }
    private val titleNorms = candidates.associate { it.id to titleNormOf(it.title) }
    private val pubDays = candidates.associate { it.id to it.pubDate?.div(PreparedItem.DAY_MS) }

    private val byEnclosure = candidates.grouped { it.enclosureUrl?.let(UrlNormalizer::forIdentity) }
    private val byEnclosureNoQuery =
        candidates.grouped { it.enclosureUrl?.let(UrlNormalizer::forIdentityNoQuery) }
    private val byTitleDay =
        candidates.grouped { row ->
            titleNorms[row.id]?.let { norm -> pubDays[row.id]?.let { "$norm|$it" } }
        }

    /**
     * The (item, row) claims in claim order. Rows and items a tier claims leave the pool
     * before the next tier starts; each pass runs ENCLOSURE → ENCLOSURE_NO_QUERY → TITLE_DAY
     * over what remains, and the passes repeat until one claims nothing — every productive
     * pass takes at least one item, so the starting item count bounds the loop. Items still
     * unmatched afterwards insert.
     */
    fun matches(items: List<PreparedItem>): List<Pair<PreparedItem, ExistingEpisodeKey>> {
        val unmatched = items.filterTo(mutableSetOf()) { it.matchedTo == null && !it.dropped }
        val free = candidates.mapTo(HashSet()) { it.id }
        val claims = mutableListOf<Pair<PreparedItem, ExistingEpisodeKey>>()
        repeat(unmatched.size) {
            var claimed = false
            for (tier in FallbackTier.entries) {
                if (unmatched.isEmpty() || free.isEmpty()) break
                for ((item, row) in uniquePairs(unmatched, free, tier)) {
                    claims += item to row
                    unmatched -= item
                    free -= row.id
                    claimed = true
                }
            }
            if (!claimed) return claims
        }
        return claims
    }

    /**
     * One tier's claims: an item takes its row only when it relates to exactly one free row
     * and that row relates to exactly one unmatched item.
     */
    private fun uniquePairs(
        items: Set<PreparedItem>,
        free: Set<Long>,
        tier: FallbackTier,
    ): List<Pair<PreparedItem, ExistingEpisodeKey>> {
        val soleRow = HashMap<PreparedItem, ExistingEpisodeKey>()
        val suitors = HashMap<Long, Int>()
        for (item in items) {
            val related = relatedRows(item, tier, free)
            if (related.size == 1) soleRow[item] = related.single()
            for (row in related) suitors.merge(row.id, 1, Int::plus)
        }
        return items.mapNotNull { item ->
            val row = soleRow[item] ?: return@mapNotNull null
            if (suitors.getValue(row.id) == 1) item to row else null
        }
    }

    /** The item's rows under [tier]: the tier index's bucket, filtered to free and related. */
    private fun relatedRows(
        item: PreparedItem,
        tier: FallbackTier,
        free: Set<Long>,
    ): List<ExistingEpisodeKey> {
        val bucket =
            when (tier) {
                FallbackTier.ENCLOSURE -> item.enclosureIdentity?.let(byEnclosure::get)
                FallbackTier.ENCLOSURE_NO_QUERY -> item.enclosureNoQuery?.let(byEnclosureNoQuery::get)
                FallbackTier.TITLE_DAY -> item.titleDayKey?.let(byTitleDay::get)
            } ?: return emptyList()
        return bucket.filter { it.id in free && relationHolds(item, it, tier) }
    }

    /** The tier's pair predicate beyond its index key (the index keys are equal by lookup). */
    private fun relationHolds(
        item: PreparedItem,
        row: ExistingEpisodeKey,
        tier: FallbackTier,
    ): Boolean =
        when (tier) {
            FallbackTier.ENCLOSURE -> true
            FallbackTier.ENCLOSURE_NO_QUERY -> corroborates(item, row) && guardsPass(row, item)
            FallbackTier.TITLE_DAY -> guardsPass(row, item)
        }

    /**
     * Tier B's corroboration (r5 F1, deviation 16): the query-less URL plus *one* of title or
     * day still cannot pick an episode — a rolling feed's `/download?id=N` rows share the day
     * or a generic title across different episodes — so the pair needs an equal `TitleMatch`
     * *and* the same UTC publication day on top. A pure query-token rotation keeps both and
     * still matches.
     */
    private fun corroborates(
        item: PreparedItem,
        row: ExistingEpisodeKey,
    ): Boolean =
        item.titleNorm != null && item.titleNorm == titleNorms[row.id] &&
            item.pubDayUtc != null && item.pubDayUtc == pubDays[row.id]

    /** The guards of 03 step 5: known durations within 10 min, known MIME majors equal. */
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

    /** The pass-2 strength tiers of deviations 14/16, strongest first. */
    private enum class FallbackTier {
        /** Equal normalised enclosure URL (`UrlNormalizer.forIdentity`). */
        ENCLOSURE,

        /** Equal query-less URL (`forIdentityNoQuery`) + equal `TitleMatch` + same UTC day + the guards. */
        ENCLOSURE_NO_QUERY,

        /** Equal `TitleMatch` + same UTC day + the guards. */
        TITLE_DAY,
    }

    private companion object {
        const val DURATION_GUARD_MS = 10L * 60_000L
    }
}

/** `TitleMatch.normalise` or null when the title is missing or folds to empty. */
private fun titleNormOf(title: String?): String? = title?.let(TitleMatch::normalise)?.takeIf(String::isNotEmpty)

private fun List<ExistingEpisodeKey>.grouped(
    keyOf: (ExistingEpisodeKey) -> String?,
): Map<String, List<ExistingEpisodeKey>> =
    mapNotNull { row -> keyOf(row)?.let { it to row } }.groupBy({ it.first }, { it.second })
