// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.ingest

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.artwork.ArtworkKeys
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.data.refresh.RefreshPolicy
import ch.lkmc.neutrodyne.core.database.DueFeed
import ch.lkmc.neutrodyne.core.database.EpisodeDescriptionCodec
import ch.lkmc.neutrodyne.core.database.ExistingEpisodeKey
import ch.lkmc.neutrodyne.core.database.FundingEntity
import ch.lkmc.neutrodyne.core.database.IngestDao
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PersonEntity
import ch.lkmc.neutrodyne.core.database.PodcastEntity
import ch.lkmc.neutrodyne.core.database.PodcastFeedMetadata
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.FeedErrorKind
import ch.lkmc.neutrodyne.core.model.GuidKnowledge
import ch.lkmc.neutrodyne.core.model.OwnerType
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.ShowType
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.feeds.html.ShowNotesSanitizer
import ch.lkmc.neutrodyne.feeds.identity.EpisodeContentHash
import ch.lkmc.neutrodyne.feeds.identity.EpisodeKeys
import ch.lkmc.neutrodyne.feeds.identity.PodcastGuid
import ch.lkmc.neutrodyne.feeds.identity.TitleMatch
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.feeds.model.ParseWarning
import ch.lkmc.neutrodyne.feeds.model.ParsedEpisode
import ch.lkmc.neutrodyne.feeds.model.ParsedFeed
import ch.lkmc.neutrodyne.feeds.model.WarningCode
import ch.lkmc.neutrodyne.feeds.parse.FeedParser
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.max

/**
 * The ingestion/diff algorithm of 03 for one podcast and one parsed document. All episode work of
 * a refresh commit runs in a single Room write transaction ([ingest]); [ingestInTransaction] is the
 * same body for callers already inside one (the subscribe commit — 03 Subscribe). Only 02's
 * feed-owned columns are ever written: user state is not in any write list here.
 *
 * `NO_MEDIA` (items but none acceptable) and `UNSUPPORTED_LIST_FEED` (empty `medium*L`) write
 * nothing: the engine records the kind plus success scheduling instead (03 Accepted items).
 */
@Inject
internal class FeedIngestor(
    private val db: NeutrodyneDatabase,
    private val sanitizer: ShowNotesSanitizer,
    private val settings: SettingsRepository,
    private val clock: Clock,
    @Dispatcher(NeutrodyneDispatchers.Default) private val defaultDispatcher: CoroutineDispatcher,
) {
    /**
     * CPU-heavy keying/matching runs on the default dispatcher; the resulting write list lands in
     * one `withWriteTransaction` (03 Diff algorithm).
     */
    suspend fun ingest(
        podcast: DueFeed,
        parsed: ParsedFeed,
        ctx: IngestContext,
    ): IngestResult {
        val prepared = withContext(defaultDispatcher) { prepare(parsed, ctx) }
        return db.withWriteTransaction { ingestPrepared(podcast, parsed, ctx, prepared) }
    }

    /**
     * The same ingest, meant to be called inside the caller's `withWriteTransaction` (the
     * subscribe commit — 03 Subscribe). Preparation still runs before the write work.
     */
    suspend fun ingestInTransaction(
        podcast: DueFeed,
        parsed: ParsedFeed,
        ctx: IngestContext,
    ): IngestResult = ingestPrepared(podcast, parsed, ctx, prepare(parsed, ctx))

    /**
     * The diff's write phase. [prepared] carries everything the transaction needs so nothing
     * heavy (normalisation, hashing, snippets) happens while the write lock is held.
     */
    private suspend fun ingestPrepared(
        podcast: DueFeed,
        parsed: ParsedFeed,
        ctx: IngestContext,
        prepared: PreparedFeed,
    ): IngestResult {
        val ingestDao = db.ingestDao()
        val podcastDao = db.podcastDao()
        val stored =
            podcastDao.byId(podcast.id)
                ?: return IngestResult.empty(prepared.warnings, vanished = true)

        // Accepted-items table of 03: blog and list feeds write nothing at all — the engine
        // records their error kind and the success scheduling through the batched fetch state.
        if (ctx.mode != IngestMode.OLDER_PAGE && parsed.items.isNotEmpty() && prepared.items.isEmpty()) {
            return IngestResult.empty(prepared.warnings, FeedErrorKind.NO_MEDIA)
        }
        if (
            ctx.mode != IngestMode.OLDER_PAGE && parsed.items.isEmpty() &&
            parsed.medium?.endsWith(LIST_MEDIUM_SUFFIX, ignoreCase = true) == true
        ) {
            return IngestResult.empty(prepared.warnings, FeedErrorKind.UNSUPPORTED_LIST_FEED)
        }

        val existing = ingestDao.existing(stored.id)
        val byKey = existing.associateBy { it.identityKey }
        val storedKeys = byKey.keys
        val taken = mutableSetOf<Long>()
        var rekeyed = 0

        // D98 GUID provenance (02 episode_guid_provenance): the ambiguity evidence is exactly
        // the snapshot reuse test — a GUID carried by two stored rows, by one row under a
        // non-`g:` key, or shared by two distinct accepted items — and it is recorded inside
        // this transaction before any matching runs. Recorded ambiguity is sticky across GUID
        // rotations, rekeys, retention and restarts: once shared, a GUID never regains
        // primary-key authority even when its carriers rotate away and the snapshot evidence
        // is gone. A covered podcast (02 podcast.guidCoverageSince) additionally records
        // KNOWN_INDEPENDENT for GUIDs it sole-observes; row absence never promotes anything —
        // unknown GUIDs stay unknown on conflicting evidence.
        val guidRowCounts =
            existing.mapNotNull { EpisodeKeys.canonicalGuid(it.guid) }.groupingBy { it }.eachCount()
        val ambiguousGuids =
            existing
                .asSequence()
                .mapNotNull { row ->
                    EpisodeKeys.canonicalGuid(row.guid)?.takeIf {
                        guidRowCounts[it]!! > 1 || row.identityKey != "g:$it"
                    }
                }.toMutableSet()
        ambiguousGuids +=
            prepared.items
                .mapNotNull { EpisodeKeys.canonicalGuid(it.episode.guid) }
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        val knowledge = ingestDao.guidKnowledge(stored.id)
        // KNOWN_INDEPENDENT requires the strict introduction rule: coverage is on, the document
        // is complete (a paging window can never prove sole carriage), the GUID appears on
        // exactly one accepted item, and no ambiguity evidence exists. Stored-only observation
        // never promotes — erased history stays unknown (D98).
        val independentGuids =
            if (stored.guidCoverageSince != null && !ctx.partial && ctx.mode != IngestMode.OLDER_PAGE) {
                prepared.items
                    .mapNotNull { EpisodeKeys.canonicalGuid(it.episode.guid) }
                    .groupingBy { it }
                    .eachCount()
                    .filterValues { it == 1 }
                    .keys - ambiguousGuids
            } else {
                emptySet()
            }
        ingestDao.recordGuidKnowledge(stored.id, ambiguousGuids, independentGuids)
        ambiguousGuids += knowledge.filterValues { it == GuidKnowledge.KNOWN_AMBIGUOUS }.keys

        // Pass 1 (03 step 4): each item claims rows in claim-key order — its assigned document
        // key first, then its (older-version) candidates. When a repeated GUID puts two items on
        // one stored row, the item whose enclosure matches the row keeps it and the loser is
        // retried on its next claim key — user state never moves between distinct episodes.
        // D98 authority: an ambiguous GUID never claims by any key — the sibling's `g:` row, its
        // own re-offered fallback key, or a rotated-away `g:` slot are all decided by pass 2's
        // global evidence instead; and a GUID without KNOWN_INDEPENDENT may not take a `g:` row
        // while its enclosure is provably owned by a different row (the unknown rule).
        val claimedBy = HashMap<Long, PreparedItem>()
        val pending = ArrayDeque(prepared.items)
        while (pending.isNotEmpty()) {
            val item = pending.removeFirst()
            if (item.matchedTo != null) continue
            val itemGuid = EpisodeKeys.canonicalGuid(item.episode.guid)
            if (itemGuid != null && itemGuid in ambiguousGuids) continue
            val contested = itemGuid != null && knowledge[itemGuid] != GuidKnowledge.KNOWN_INDEPENDENT
            for (key in item.claimKeys) {
                val row = byKey[key] ?: continue
                if (
                    contested && item.enclosureIdentity != null &&
                    existing.any { it.id != row.id && normEnc(it) == item.enclosureIdentity }
                ) {
                    // Erased or absent history cannot arbitrate between this GUID's stored owner
                    // and the enclosure's owner: leave both rows and their state untouched; the
                    // item takes the insert-or-drop path (D98, 03 deviation 17).
                    item.unresolved = true
                    break
                }
                val holder = claimedBy[row.id]
                if (holder == null) {
                    if (claimRow(ingestDao, storedKeys, item, row)) rekeyed++
                    claimedBy[row.id] = item
                    taken += row.id
                    break
                }
                val itemWins =
                    item.enclosureIdentity != null && item.enclosureIdentity == normEnc(row) &&
                        !(holder.enclosureIdentity != null && holder.enclosureIdentity == normEnc(row))
                if (itemWins) {
                    holder.matchedTo = null
                    pending.addLast(holder)
                    if (claimRow(ingestDao, storedKeys, item, row)) rekeyed++
                    claimedBy[row.id] = item
                    taken += row.id
                    break
                }
            }
        }

        // Pass 2 (03 step 5, deviations 14/16): the fallback relations run as global strength
        // tiers — each tier finishes for every unmatched item before the next starts, a match
        // inside a tier is made only when it is unique on both sides, and the tiers repeat
        // until a full pass claims nothing — so a weak relation can never take a row a
        // stronger one would claim (r4 F2), a row is reused only on evidence that identifies
        // the same episode (r5 F1), and a later claim clears an earlier ambiguity (r5 F2).
        // Eligible rows are every row pass 1 left unclaimed, including a `g:` row whose claim
        // the reuse guard rejected: reserving it would strand exactly the episodes this pass
        // recovers (r3 F1).
        val fallbacks = Pass2Index(existing, taken)
        for ((item, row) in fallbacks.matches(prepared.items.filter { !it.unresolved })) {
            if (claimRow(ingestDao, storedKeys, item, row)) rekeyed++
            taken += row.id
        }

        // An unmatched item whose assigned key a stored row holds (lost to a better enclosure
        // match above) re-derives a free fallback; with none it is dropped as a duplicate.
        val liveDocKeys = prepared.items.mapTo(HashSet()) { it.docKey }
        for (item in prepared.items) {
            if (item.matchedTo != null || item.dropped || item.docKey !in storedKeys) continue
            val fresh = item.fallbackKeys.firstOrNull { it !in storedKeys && it !in liveDocKeys }
            if (fresh == null) {
                item.dropped = true
                prepared.warnings +=
                    ParseWarning(
                        WarningCode.DUPLICATE_ITEM,
                        item.index,
                        item.episode.guid
                            .orEmpty()
                            .shorten(),
                    )
            } else {
                item.docKey = fresh
                liveDocKeys += fresh
            }
        }

        var updated = 0
        var flippedOut = 0
        val matchedItems = prepared.items.filter { it.matchedTo != null }
        val insertedItems = prepared.items.filter { it.matchedTo == null && !it.dropped }

        if (prepared.items.isNotEmpty()) {
            // Step 6: changed matched rows rewrite feed columns and children; out-of-feed rows
            // that re-appear flip back to inFeed = 1.
            val restored = mutableListOf<Long>()
            for (item in matchedItems) {
                val row = item.matchedTo ?: continue
                if (row.contentHash != item.contentHash) {
                    ingestDao.updateFeedFields(item.feedUpdate(prepared.now))
                    ingestDao.replaceChildren(
                        episodeId = row.id,
                        description = item.descriptionBytes,
                        transcripts = item.transcripts(row.id),
                        altEnclosures = item.altEnclosures(row.id),
                        persons = item.persons(row.id),
                        funding = item.funding(row.id),
                        pscChapters = item.pscChapters(row.id),
                    )
                    // A changed non-null chapters URL drops the fetched JSON chapter blob.
                    val chaptersUrl = item.episode.chaptersUrl
                    if (chaptersUrl != null && row.chaptersUrl != chaptersUrl) {
                        ingestDao.deleteJsonChapters(row.id)
                    }
                    updated++
                }
                if (!row.inFeed) restored += row.id
            }
            if (restored.isNotEmpty()) ingestDao.setInFeed(restored, true)

            // Step 7: unmatched items insert in descending feedOrder — the document's first item
            // gets the highest id. isNew only for non-initial refreshes inside the 7-day window.
            val newFloor =
                max(stored.latestEpisodeAt ?: Long.MIN_VALUE, stored.subscribedAt) - NEW_WINDOW_MS
            for (item in insertedItems) {
                item.isNew =
                    ctx.mode == IngestMode.REFRESH && !stored.initialFetch &&
                    (item.pubDateValid ?: prepared.now) >= newFloor
            }
            val rowsByDescFeedOrder = insertedItems.sortedByDescending { it.episode.feedOrder }
            val ids =
                ingestDao.insertEpisodes(
                    rowsByDescFeedOrder.map { it.insertRow(stored.id, prepared.firstSeenAt, prepared.now) },
                )
            for ((index, item) in rowsByDescFeedOrder.withIndex()) {
                val id = ids[index]
                item.insertedId = id
                ingestDao.replaceChildren(
                    episodeId = id,
                    description = item.descriptionBytes,
                    transcripts = item.transcripts(id),
                    altEnclosures = item.altEnclosures(id),
                    persons = item.persons(id),
                    funding = item.funding(id),
                    pscChapters = item.pscChapters(id),
                )
            }

            // Back-catalogue guard: >20 new rows keep only the newest 3 by (sortDate, feedOrder).
            val markedNew = insertedItems.filter { it.isNew }
            if (markedNew.size > BACK_CATALOGUE_LIMIT) {
                val keepIds =
                    markedNew
                        .sortedWith(
                            compareByDescending<PreparedItem> { it.sortDate(prepared.firstSeenAt) }
                                .thenByDescending { it.episode.feedOrder },
                        ).take(BACK_CATALOGUE_KEEP)
                        .mapNotNullTo(HashSet()) { it.insertedId }
                ingestDao.clearIsNew(markedNew.mapNotNull { it.insertedId } - keepIds)
                for (item in markedNew) {
                    if (item.insertedId !in keepIds) item.isNew = false
                }
                prepared.warnings +=
                    ParseWarning(WarningCode.BACK_CATALOGUE_DUMP, null, "${markedNew.size} candidates")
            }

            // Step 8: absent rows only flip for complete documents, or inside a partial window.
            if (ctx.mode != IngestMode.OLDER_PAGE) {
                val floor =
                    ctx.absenceFloor ?: if (ctx.partial) {
                        val docSortDates =
                            matchedItems.map { it.sortDate(it.matchedTo?.firstSeenAt ?: prepared.firstSeenAt) } +
                                insertedItems.map { it.sortDate(prepared.firstSeenAt) }
                        docSortDates.minOrNull() ?: Long.MAX_VALUE
                    } else {
                        Long.MIN_VALUE
                    }
                val absent = existing.filter { it.id !in taken && it.sortDate >= floor }
                if (absent.isNotEmpty()) {
                    ingestDao.setInFeed(absent.map { it.id }, false)
                    flippedOut = absent.size
                }
            }

            // Step 9: the 90-day retention clock needs day granularity (03 step 9).
            ingestDao.touchSeen(stored.id, prepared.now)
        }

        // Step 10: feed-level columns, validators and scheduling in the same transaction.
        // A paging ingest changes no metadata except the paging columns (03 RFC 5005 paging).
        if (ctx.mode == IngestMode.OLDER_PAGE) {
            val (pagingNextUrl, pagingComplete) = pagingState(parsed, ctx.mode, stored)
            ingestDao.applyPaging(stored.id, pagingNextUrl, pagingComplete)
            return IngestResult(
                inserted = insertedItems.mapNotNull { it.insertedId },
                newIds = emptyList(),
                updated = updated,
                accepted = prepared.items.size,
                flippedOut = flippedOut,
                rekeyed = rekeyed,
                firstIngest = false,
                artworkChanged = false,
                warnings = prepared.warnings,
            )
        }

        val realGuid = parsed.podcastGuid?.let(PodcastGuid::parse)
        val artworkUrl = parsed.artwork.firstOrNull()?.url
        val artworkKey = artworkUrl?.let(ArtworkKeys::forUrl) ?: ArtworkKeys.monogram(stored.feedKey)
        val artworkChanged = stored.artworkKey != artworkKey
        val (pagingNextUrl, pagingComplete) = pagingState(parsed, ctx.mode, stored)
        val pendingNewFeedUrl = pendingNewFeedUrl(parsed, stored, podcastDao.aliases(stored.id))
        val firstIngest = stored.initialFetch
        val latestEpisodeAt = ingestDao.maxSortDate(stored.id)
        val intervalMinutes =
            RefreshPolicy.effectiveIntervalMinutes(settings.get(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES))
        val (guidValue, guidDerived) =
            when {
                realGuid != null -> {
                    realGuid to false
                }

                !stored.podcastGuidDerived && stored.podcastGuid != null -> {
                    stored.podcastGuid to false
                }

                else -> {
                    (stored.podcastGuid ?: PodcastGuid.derive(stored.feedUrl)) to true
                }
            }
        ingestDao.applyFeedMetadata(
            PodcastFeedMetadata(
                id = stored.id,
                podcastGuid = guidValue,
                podcastGuidDerived = guidDerived,
                title = parsed.title?.takeUnless { it.isBlank() } ?: stored.title,
                author = parsed.author,
                link = parsed.link,
                language = parsed.language,
                explicit = parsed.explicit,
                showType = showTypeOf(parsed.showType),
                medium = parsed.medium,
                locked = parsed.locked,
                complete = parsed.complete,
                artworkUrl = artworkUrl,
                artworkKey = artworkKey,
                bannerUrl = parsed.bannerUrl,
                status = PodcastStatus.ACTIVE,
                initialFetch = false,
                latestEpisodeAt = latestEpisodeAt,
                etag = ctx.fetch.etag,
                lastModified = ctx.fetch.lastModified,
                contentSha256 = ctx.fetch.sha256Hex,
                parserVersion = FeedParser.VERSION,
                lastParseOk = true,
                lastAttemptAt = prepared.now,
                lastSuccessAt = prepared.now,
                // `lastFullFetchAt` tracks unconditional 200s only (03 full-fetch rule).
                lastFullFetchAt = if (ctx.fetch.unconditional) prepared.now else stored.lastFullFetchAt,
                nextRefreshAt =
                    RefreshPolicy.successNextRefreshAt(
                        prepared.now,
                        intervalMinutes,
                        parsed.complete,
                        latestEpisodeAt,
                        parsed.ttlMinutes,
                        ctx.fetch.maxAgeSec,
                    ),
                failureCount = 0,
                lastErrorKind = null,
                lastErrorDetail = null,
                gone = false,
                needsCredentials = false,
                ttlMinutes = parsed.ttlMinutes,
                updateFrequencyRrule = parsed.updateFrequencyRrule,
                pendingNewFeedUrl = pendingNewFeedUrl,
                pagingNextUrl = pagingNextUrl,
                pagingComplete = pagingComplete,
                hubUrl = parsed.hubUrl,
                usesPodping = parsed.usesPodping,
                descriptionHtml = parsed.descriptionHtml,
                categoriesJson = categoriesJson(parsed.categories),
            ),
        )
        ingestDao.replacePodcastChildren(
            podcastId = stored.id,
            persons =
                parsed.persons.map {
                    PersonEntity(
                        ownerType = OwnerType.PODCAST,
                        ownerId = stored.id,
                        name = it.name,
                        role = it.role,
                        grp = it.group,
                        imageUrl = it.img,
                        href = it.href,
                    )
                },
            funding =
                parsed.funding.map {
                    FundingEntity(
                        ownerType = OwnerType.PODCAST,
                        ownerId = stored.id,
                        url = it.url,
                        label = it.title,
                    )
                },
        )

        return IngestResult(
            inserted = insertedItems.mapNotNull { it.insertedId },
            newIds = insertedItems.filter { it.isNew }.mapNotNull { it.insertedId },
            updated = updated,
            accepted = prepared.items.size,
            flippedOut = flippedOut,
            rekeyed = rekeyed,
            firstIngest = firstIngest,
            artworkChanged = artworkChanged,
            warnings = prepared.warnings,
        )
    }

    /**
     * One pass-1 claim (03 step 4): [item] takes [row] and the identities align — the row's own
     * key when it equals [docKey], an upgrade of [docKey] to the primary the row already carries,
     * an in-place rekey to [docKey] when the assigned key is free, or adoption of the row's own
     * key when [docKey] would collide with a different stored row. Returns whether a rekey wrote.
     */
    private suspend fun claimRow(
        ingestDao: IngestDao,
        storedKeys: Set<String>,
        item: PreparedItem,
        row: ExistingEpisodeKey,
    ): Boolean {
        var rekeyed = false
        // D98: the current parsed GUID (including null) persists on every claim, ahead of the
        // content-hash gate — a sibling whose GUID rotated or disappeared must not keep stale
        // carriage, and provenance evidence must reflect the row's real GUID. A guid-only write
        // never counts as a rekey: `rekeyed` records identity-key moves only.
        val syncGuid = row.guid != item.episode.guid
        when {
            // The stored row already carries this document's key: nothing moves.
            row.identityKey == item.docKey -> {
                if (syncGuid) ingestDao.rekey(row.id, row.identityKey, item.episode.guid)
            }

            row.identityKey == item.primaryKey -> {
                item.docKey = item.primaryKey
                if (syncGuid) ingestDao.rekey(row.id, row.identityKey, item.episode.guid)
            }

            item.docKey !in storedKeys -> {
                // An older key version matched: the row's identity is rewritten in place.
                ingestDao.rekey(row.id, item.docKey, item.episode.guid)
                rekeyed = true
            }

            else -> {
                item.docKey = row.identityKey
                if (syncGuid) ingestDao.rekey(row.id, row.identityKey, item.episode.guid)
            }
        }
        item.matchedTo = row
        return rekeyed
    }

    private fun normEnc(row: ExistingEpisodeKey): String? = row.enclosureUrl?.let(UrlNormalizer::forIdentity)

    /**
     * The diff's CPU half: accepted-item filter, in-document key choice and every resolved column.
     * Runs off the write transaction (03 Diff algorithm step 1).
     */
    private fun prepare(
        parsed: ParsedFeed,
        ctx: IngestContext,
    ): PreparedFeed {
        val now = clock.now()
        val firstSeenAt = serverCorrectedNow(now, ctx.fetch.serverDateMs)
        val artworkIdentity =
            parsed.artwork
                .firstOrNull()
                ?.url
                ?.let(UrlNormalizer::forIdentity)
        val warnings = parsed.warnings.toMutableList()
        val usedKeys = mutableSetOf<String>()
        val items = mutableListOf<PreparedItem>()
        val seenIdenticals = HashMap<String, MutableSet<Long>>()

        for ((index, e) in parsed.items.withIndex()) {
            if (e.primaryEnclosure == null && e.externalMediaId == null) {
                warnings += ParseWarning(WarningCode.NO_MEDIA_ITEM, index, e.title.orEmpty().shorten())
                continue
            }
            val primaryKey = EpisodeKeys.primary(e)
            val contentHash = EpisodeContentHash.of(e)
            // Identical repeats collapse before fallback keys are assigned (03 step 2); a
            // different item that repeats the primary gets a fallback in `chooseKey` below.
            if (!seenIdenticals.getOrPut(primaryKey) { mutableSetOf() }.add(contentHash)) continue
            val docKey = chooseKey(e, primaryKey, usedKeys, warnings, index) ?: continue
            val imageUrl =
                e.artwork.firstOrNull()?.url?.takeUnless {
                    UrlNormalizer.forIdentity(it) == artworkIdentity
                }
            val titleNorm = e.title?.let(TitleMatch::normalise)?.takeIf(String::isNotEmpty)
            val pubDayUtc = e.pubDate?.div(PreparedItem.DAY_MS)
            items +=
                PreparedItem(
                    episode = e,
                    index = index,
                    primaryKey = primaryKey,
                    docKey = docKey,
                    claimKeys =
                        (listOf(docKey) + EpisodeKeys.candidates(e)).distinct(),
                    fallbackKeys = EpisodeKeys.fallbacks(e),
                    enclosureIdentity = e.primaryEnclosure?.url?.let(UrlNormalizer::forIdentity),
                    enclosureNoQuery = e.primaryEnclosure?.url?.let(UrlNormalizer::forIdentityNoQuery),
                    titleNorm = titleNorm,
                    pubDayUtc = pubDayUtc,
                    titleDayKey =
                        if (titleNorm != null && pubDayUtc != null) "$titleNorm|$pubDayUtc" else null,
                    contentHash = contentHash,
                    title = resolvedTitle(e),
                    imageUrl = imageUrl,
                    artworkKey = imageUrl?.let(ArtworkKeys::forUrl),
                    descriptionBytes = e.descriptionHtml?.let(EpisodeDescriptionCodec::encode),
                    snippet = e.descriptionHtml?.let { sanitizer.snippet(it, e.descriptionIsHtml) },
                    hint = e.externalMediaId?.let { ctx.rowHints[it] },
                    pubDateValid =
                        e.pubDate?.takeIf {
                            it in PreparedItem.PUB_DATE_MIN_MS..(now + PreparedItem.PUB_DATE_MAX_OFFSET_MS)
                        },
                )
        }
        return PreparedFeed(items = items, warnings = warnings, now = now, firstSeenAt = firstSeenAt)
    }

    /** In-document key choice (03 step 2–3): primary wins, else the first free fallback. */
    private fun chooseKey(
        e: ParsedEpisode,
        primary: String,
        usedKeys: MutableSet<String>,
        warnings: MutableList<ParseWarning>,
        index: Int,
    ): String? {
        if (usedKeys.add(primary)) return primary
        val fallback = EpisodeKeys.fallbacks(e).firstOrNull { it !in usedKeys }
        if (fallback != null) {
            usedKeys += fallback
            if (primary.startsWith("g:")) {
                warnings += ParseWarning(WarningCode.DUPLICATE_GUID, index, e.guid.orEmpty().shorten())
            }
            return fallback
        }
        warnings += ParseWarning(WarningCode.DUPLICATE_ITEM, index, e.guid.orEmpty().shorten())
        return null
    }

    /** A `Date` header >24 h off the local clock wins `firstSeenAt` (03 sortDate and clock). */
    private fun serverCorrectedNow(
        now: Long,
        serverDateMs: Long?,
    ): Long = if (serverDateMs != null && abs(now - serverDateMs) > PreparedItem.DAY_MS) serverDateMs else now

    /**
     * `itunes:new-feed-url` (03 Feed moves rule 1): stored on every ingest but ignored when it
     * points at the feed itself, is not http(s), or equals an alias the podcast moved away from.
     */
    private fun pendingNewFeedUrl(
        parsed: ParsedFeed,
        stored: PodcastEntity,
        aliases: List<ch.lkmc.neutrodyne.core.database.PodcastUrlAliasEntity>,
    ): String? {
        val raw = parsed.newFeedUrl ?: return null
        val identity = UrlNormalizer.forIdentity(raw) ?: return null
        if (identity == stored.feedKey) return null
        return if (aliases.none { it.url == identity }) raw else null
    }

    /**
     * The paging columns of `applyFeedMetadata` (03 RFC 5005 paging): page-1's "unknown" state
     * resolves to "older pages exist, not wanted" or "complete"; an `OLDER_PAGE` ingest advances
     * `pagingNextUrl` to the page's own link (or completes when none exists).
     */
    private fun pagingState(
        parsed: ParsedFeed,
        mode: IngestMode,
        stored: PodcastEntity,
    ): Pair<String?, Boolean> {
        val link = parsed.paging.next ?: parsed.paging.prevArchive
        return when {
            mode == IngestMode.OLDER_PAGE -> link to (link == null)
            stored.pagingNextUrl == null && !stored.pagingComplete -> link to true
            else -> stored.pagingNextUrl to stored.pagingComplete
        }
    }

    private fun showTypeOf(raw: String?): ShowType? =
        when (raw) {
            "serial" -> ShowType.SERIAL
            "episodic" -> ShowType.EPISODIC
            else -> null
        }

    private fun categoriesJson(categories: List<List<String>>): String? =
        categories.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it) }

    private fun String.shorten(): String = take(WARNING_DETAIL_CHARS)

    private companion object {
        /** `isNew`'s seven-day window behind the waterline (03 isNew and the dump guard). */
        const val NEW_WINDOW_MS = 7L * PreparedItem.DAY_MS

        /** Back-catalogue guard: more than this many "new" rows keeps only the newest three. */
        const val BACK_CATALOGUE_LIMIT = 20
        const val BACK_CATALOGUE_KEEP = 3

        /** Warning `detail` previews keep titles short. */
        const val WARNING_DETAIL_CHARS = 80

        /** `podcast:medium` values ending in `L` list items of another feed (03 Accepted items). */
        const val LIST_MEDIUM_SUFFIX = "L"
    }
}

/** The write phase's working set (prepared items plus the mutable ingest warnings). */
internal class PreparedFeed(
    val items: List<PreparedItem>,
    val warnings: MutableList<ParseWarning>,
    val now: Long,
    val firstSeenAt: Long,
)
