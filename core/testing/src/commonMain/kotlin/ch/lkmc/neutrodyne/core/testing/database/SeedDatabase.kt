// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing.database

import androidx.room3.useWriterConnection
import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.database.DescriptionFormat
import ch.lkmc.neutrodyne.core.database.DownloadEntity
import ch.lkmc.neutrodyne.core.database.EpisodeDescriptionCodec
import ch.lkmc.neutrodyne.core.database.EpisodeEntity
import ch.lkmc.neutrodyne.core.database.EpisodeRowProjection
import ch.lkmc.neutrodyne.core.database.EpisodeStateEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastEntity
import ch.lkmc.neutrodyne.core.database.PodcastGroupEntity
import ch.lkmc.neutrodyne.core.database.PodcastGroupMemberEntity
import ch.lkmc.neutrodyne.core.database.SyncClockEntity
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.EpisodeType
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.ShowType
import ch.lkmc.neutrodyne.core.model.SourceKind
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.WaitReason
import ch.lkmc.neutrodyne.core.model.YouTubeVariantBits
import ch.lkmc.neutrodyne.core.testing.TestClock
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlin.uuid.Uuid

/**
 * The seeded scale library of 02 Testing (`SeedDatabase`): every value derives from [seed], so
 * the same spec produces byte-identical rows on the JVM and on Android. [install] writes through
 * the production DAOs inside one transaction and returns the [Seeded] snapshot tests iterate —
 * for `QueryPlanTest`, `FeedQueryTimingTest` and the database-size measurement.
 *
 * The shape exists to exercise `FeedQueryBuilder`: overlapping group memberships plus ungrouped
 * podcasts, `includeInAll` exclusions, YouTube channels with and without the Shorts bit, equal
 * `sortDate` runs (per-podcast clusters, a shared pool and one "release day" block longer than a
 * page), played/in-progress/downloaded rows and every `availability` value — hidden ones and
 * ones `VISIBLE` keeps.
 */
class SeedDatabase(
    val seed: Int = 42,
    val podcasts: Int = 300,
    val episodes: Int = 50_000,
    val groups: Int = 20,
    val membershipsPerPodcast: IntRange = 0..3,
    val playedFraction: Double = 0.6,
    val favourites: Int = 200,
    val downloads: Int = 300,
    val inProgress: Int = 50,
    val youtubeChannels: Int = 30,
    val linked: Boolean = false,
) {
    /**
     * Generates every row from [seed], writes it in one transaction through the production DAOs
     * (descriptions through one reused prepared statement — the codec-encoded bytes are the same
     * ones ingest writes), then runs `PRAGMA optimize` so the planner sees the statistics a
     * long-lived database accumulates (02 Indices).
     */
    suspend fun install(db: NeutrodyneDatabase): Seeded {
        val rnd = Random(seed)
        val youTube = youTubeOrdinals(rnd)
        val podcastDrafts = List(podcasts) { i -> podcastDraft(i, youTube[i], rnd) }
        val groupDrafts = List(groups) { i -> groupDraft(i, rnd) }
        val memberSpecs = memberSpecs(rnd)
        val episodeDrafts = episodeDrafts(rnd, youTube.keys)
        val sideRows = sideRows(rnd, episodeDrafts, youTube.keys)

        val seeded =
            db.withWriteTransaction {
                writeAll(db, podcastDrafts, groupDrafts, memberSpecs, episodeDrafts, sideRows)
            }
        db.useWriterConnection { conn -> conn.usePrepared("PRAGMA optimize") { it.step() } }
        return seeded
    }

    /** ~80 episodes share one `sortDate` — a run longer than any plausible page size. */
    private val eventStride: Int
        get() = max(1, episodes / 80)

    private suspend fun writeAll(
        db: NeutrodyneDatabase,
        podcastDrafts: List<PodcastEntity>,
        groupDrafts: List<PodcastGroupEntity>,
        memberSpecs: List<MemberSpec>,
        episodeDrafts: List<List<EpisodeEntity>>,
        sideRows: SideRows,
    ): Seeded {
        val podcastIds = podcastDrafts.map { db.podcastDao().insertPodcast(it) }
        val groupIds = groupDrafts.map { db.groupDao().insert(it) }
        val writtenPodcasts = podcastDrafts.mapIndexed { i, row -> row.copy(id = podcastIds[i]) }
        val writtenGroups = groupDrafts.mapIndexed { i, row -> row.copy(id = groupIds[i]) }

        val members =
            memberSpecs.map { spec ->
                PodcastGroupMemberEntity(
                    groupId = groupIds[spec.groupIndex],
                    podcastId = podcastIds[spec.podcastIndex],
                    orderKey = spec.orderKey,
                    addedAt = spec.addedAt,
                )
            }
        members.forEach { db.groupDao().insertMember(it) }

        val writtenEpisodes = ArrayList<EpisodeEntity>(episodeDrafts.sumOf { it.size })
        episodeDrafts.forEachIndexed { i, drafts ->
            if (drafts.isNotEmpty()) {
                val rows = drafts.map { it.copy(podcastId = podcastIds[i]) }
                val ids = db.ingestDao().insertEpisodes(rows)
                ids.forEachIndexed { j, id -> writtenEpisodes += rows[j].copy(id = id) }
            }
        }

        val states = LinkedHashMap<Long, EpisodeStateEntity>(sideRows.states.size)
        for ((ordinal, row) in sideRows.states) {
            val entity = row.copy(episodeId = writtenEpisodes[ordinal].id)
            db.episodeStateDao().upsert(entity)
            states[entity.episodeId] = entity
        }

        val writtenDownloads = LinkedHashMap<Long, DownloadEntity>(sideRows.downloads.size)
        for ((ordinal, row) in sideRows.downloads) {
            val entity = row.copy(episodeId = writtenEpisodes[ordinal].id)
            db.downloadDao().insert(entity)
            writtenDownloads[entity.episodeId] = entity
        }

        db.useWriterConnection { conn ->
            conn.usePrepared("INSERT INTO episode_description (episodeId, html) VALUES (?, ?)") { stmt ->
                for ((ordinal, blob) in sideRows.descriptions) {
                    stmt.bindLong(1, writtenEpisodes[ordinal].id)
                    stmt.bindBlob(2, blob)
                    stmt.step()
                    stmt.reset()
                }
            }
        }

        if (linked) {
            writeSyncRows(db, writtenPodcasts, writtenGroups, members, writtenEpisodes, states.keys)
        }

        return Seeded(this, writtenPodcasts, writtenGroups, members, writtenEpisodes, states, writtenDownloads)
    }

    // --- Generation: pure value construction, all randomness off the one seeded [rnd] ----------

    /** Podcast index → its ordinal among YouTube channels; deterministic draw order. */
    private fun youTubeOrdinals(rnd: Random): Map<Int, Int> =
        (0 until podcasts)
            .shuffled(rnd)
            .take(min(youtubeChannels, podcasts))
            .sorted()
            .mapIndexed { ordinal, podcast -> podcast to ordinal }
            .toMap()

    private fun podcastDraft(
        i: Int,
        ytOrdinal: Int?,
        rnd: Random,
    ): PodcastEntity {
        val isYouTube = ytOrdinal != null
        val subscribedAt = BASE_NOW - rnd.nextLong(1, HISTORY_MS)
        val feedUrl = "https://seed.example.com/feeds/$i/feed.xml"
        return PodcastEntity(
            syncId = uuid(rnd),
            sourceType = if (isYouTube) SourceType.YOUTUBE_CHANNEL else SourceType.RSS,
            feedUrl = feedUrl,
            feedKey = feedUrl,
            youtubeChannelId = if (isYouTube) "UCSEED${i.toString().padStart(20, '0')}" else null,
            youtubeVariants =
                when {
                    !isYouTube -> YouTubeVariantBits.LONG_FORM

                    // The first two channels pin the edges: long-form only hides every Short,
                    // the Shorts bit shows them (02 `VISIBLE`).
                    ytOrdinal == 0 -> YouTubeVariantBits.LONG_FORM

                    ytOrdinal == 1 -> YouTubeVariantBits.LONG_FORM or YouTubeVariantBits.SHORTS

                    else -> VARIANT_SETS[rnd.nextInt(VARIANT_SETS.size)]
                },
            channelMetadataAt = if (isYouTube) BASE_NOW - rnd.nextLong(0, DAY_MS) else null,
            podcastGuid = "urn:seed:podcast:$i",
            title = prose(rnd, TITLE_MIN, TITLE_MAX),
            author = "Seed host $i",
            link = "https://seed.example.com/shows/$i",
            language = "en",
            showType =
                if (isYouTube) {
                    null
                } else if (i % 4 == 0) {
                    ShowType.SERIAL
                } else {
                    ShowType.EPISODIC
                },
            medium = if (isYouTube) "video" else "podcast",
            artworkUrl = "https://seed.example.com/shows/$i/cover.jpg",
            artworkKey = "u-${i.padded(8, HEX)}",
            customTitle = if (i % 9 == 0) "Pinned name $i" else null,
            includeInAll = i % EXCLUDED_EVERY != 1,
            episodeOrder = if (i % 5 == 0) FeedOrder.OLDEST_FIRST else null,
            status = PodcastStatus.ACTIVE,
            initialFetch = false,
            subscribedAt = subscribedAt,
            latestEpisodeAt = BASE_NOW - rnd.nextLong(0, DAY_MS),
            etag = "\"seed-etag-$i\"",
            lastModified = "Sat, 04 Oct 2025 00:00:00 GMT",
            lastSuccessAt = BASE_NOW - rnd.nextLong(0, 3 * DAY_MS),
            nextRefreshAt = BASE_NOW + rnd.nextLong(0, DAY_MS),
            needsCredentials = i % 23 == 0,
            ttlMinutes = if (i % 3 == 0) 30 + i % 120 else null,
            descriptionHtml = if (i % 2 == 0) "<p>${prose(rnd, 100, 400)}</p>" else null,
            guidCoverageSince = subscribedAt,
        )
    }

    private fun groupDraft(
        i: Int,
        rnd: Random,
    ): PodcastGroupEntity {
        val created = BASE_NOW - rnd.nextLong(1, HISTORY_MS)
        return PodcastGroupEntity(
            uuid = uuid(rnd),
            name = "Seed group ${i + 1}",
            nameKey = "seed group ${i + 1}",
            orderKey = "g${i.padded(6)}",
            colorArgb = if (i % 3 == 0) (0xFF000000L or (i * 7919L)).toInt() else null,
            feedOrder = if (i % 3 == 0) FeedOrder.OLDEST_FIRST else FeedOrder.NEWEST_FIRST,
            hideOlderThanDays = HIDE_OLDER_DAYS[i % HIDE_OLDER_DAYS.size],
            showAsTab = i % 4 != 0,
            createdAt = created,
            updatedAt = created + rnd.nextLong(0, 30 * DAY_MS),
        )
    }

    private class MemberSpec(
        val groupIndex: Int,
        val podcastIndex: Int,
        val orderKey: String,
        val addedAt: Long,
    )

    /**
     * `(groupIndex, podcastIndex)` pairs: a `membershipsPerPodcast` draw per podcast, plus the
     * structural guarantees the tests rely on — podcast 0 sits in two groups, podcast 2 stays
     * ungrouped, and the last group is kept empty (the empty-group feed edge).
     */
    private fun memberSpecs(rnd: Random): List<MemberSpec> {
        if (groups <= 0 || podcasts <= 0) return emptyList()
        val active = if (groups >= 2) groups - 1 else 1
        val pairs = LinkedHashSet<Pair<Int, Int>>()
        pairs += 0 to 0
        if (active >= 2) pairs += 1 to 0

        val forcedUngrouped = if (podcasts >= 3) 2 else -1
        for (podcast in 0 until podcasts) {
            if (podcast == forcedUngrouped) continue
            val count = min(membershipsPerPodcast.random(rnd), active)
            (0 until active).shuffled(rnd).take(count).forEach { pairs += it to podcast }
        }

        val sequence = IntArray(groups)
        return pairs.map { (group, podcast) ->
            MemberSpec(group, podcast, "m${(sequence[group]++).padded(6)}", BASE_NOW - rnd.nextLong(0, HISTORY_MS))
        }
    }

    /** Skewed per-podcast episode counts summing to exactly [episodes] (largest remainder). */
    private fun episodeCounts(rnd: Random): IntArray {
        if (podcasts <= 0 || episodes <= 0) return IntArray(max(0, podcasts))
        val weights = DoubleArray(podcasts) { 0.15 + 4.0 * rnd.nextDouble() * rnd.nextDouble() }
        val totalWeight = weights.sum()
        val counts = IntArray(podcasts) { (weights[it] * episodes / totalWeight).toInt() }
        var remainder = episodes - counts.sum()
        val order =
            counts.indices.sortedWith(
                compareByDescending<Int> { weights[it] * episodes / totalWeight - counts[it] }
                    .thenBy { it },
            )
        for (k in order) {
            if (remainder-- <= 0) break
            counts[k]++
        }
        return counts
    }

    private fun episodeDrafts(
        rnd: Random,
        youTube: Set<Int>,
    ): List<List<EpisodeEntity>> {
        val counts = episodeCounts(rnd)
        var ordinal = 0
        return List(podcasts) { p ->
            val count = counts[p]
            val isYouTube = p in youTube
            // Equal-sortDate runs: `clusterLength` consecutive episodes share a date inside one
            // podcast; the shared pool scatters cross-podcast runs, and every `eventStride`-th
            // episode lands on one release day — a run longer than a page.
            val clusterLength = 3 + rnd.nextInt(28)
            val clusters = max(1, (count + clusterLength - 1) / clusterLength)
            val step = max(1L, HISTORY_MS / clusters)
            val offset = rnd.nextLong(0, 14 * DAY_MS)
            List(count) { j ->
                val o = ordinal++
                val sortDate =
                    when {
                        o % eventStride == 0 -> BASE_NOW - EVENT_AGE_MS
                        rnd.nextDouble() < POOL_RATE -> BASE_NOW - POOL_BASE_MS - rnd.nextInt(POOL_SIZE) * POOL_STEP_MS
                        else -> BASE_NOW - offset - (j / clusterLength) * step
                    }
                episodeDraft(p, j, o, isYouTube, sortDate, rnd)
            }
        }
    }

    private fun episodeDraft(
        p: Int,
        j: Int,
        o: Int,
        isYouTube: Boolean,
        sortDate: Long,
        rnd: Random,
    ): EpisodeEntity {
        val isShort = isYouTube && j % SHORT_EVERY == 0
        val isVideo = isShort || (if (isYouTube) j % 10 != 0 else o % 13 == 0)
        val enclosure = !isYouTube && o % 23 != 0
        return EpisodeEntity(
            podcastId = 0,
            identityKey = "seed-$p-$j",
            guid = "urn:seed:episode:$p:$j",
            title = prose(rnd, TITLE_MIN, TITLE_MAX),
            pubDate = if (o % 17 == 0) null else sortDate,
            sortDate = sortDate,
            feedOrder = -j,
            firstSeenAt = min(BASE_NOW, sortDate + (o % 5) * HOUR_MS),
            lastSeenAt = min(BASE_NOW, sortDate + (o % 5) * HOUR_MS + (o % 4) * DAY_MS),
            inFeed = o % 97 != 1,
            isNew = o % 211 == 0,
            enclosureUrl = if (enclosure) enclosureUrl(p, j, o) else null,
            enclosureType = if (enclosure) (if (isVideo) "video/mp4" else "audio/mpeg") else null,
            enclosureLength = if (enclosure) 8_000_000L + (o % 90) * 1_000_000L else null,
            externalMediaId = if (isYouTube) "ytv${p.padded(2)}$j" else null,
            isVideo = isVideo,
            durationMs = if (o % 9 == 0) null else 300_000L + (o % 120) * 60_000L,
            season = if (!isYouTube && o % 5 == 0) 1 + o % 4 else null,
            episodeNumber = if (!isYouTube && o % 5 == 0) "${j + 1}" else null,
            episodeDisplay = if (o % 5 == 0) "E${j + 1}" else null,
            episodeType =
                when (o % 11) {
                    0 -> null
                    1 -> EpisodeType.TRAILER
                    2 -> EpisodeType.BONUS
                    else -> EpisodeType.FULL
                },
            explicit = o % 6 == 0,
            imageUrl = if (isYouTube || o % 10 == 0) "https://img.seed.example.com/e$p-$j.jpg" else null,
            artworkKey = if (isYouTube || o % 10 == 0) "e-${o.padded(6, HEX)}-${j.padded(4, HEX)}" else null,
            link = "https://seed.example.com/shows/$p/episodes/$j",
            chaptersUrl = if (o % 23 == 0) "https://seed.example.com/chapters/$p/$j.json" else null,
            contentHash = rnd.nextLong(),
            availability =
                if (o % SPECIAL_EVERY ==
                    3
                ) {
                    SPECIALS[(o / SPECIAL_EVERY) % SPECIALS.size]
                } else {
                    Availability.AVAILABLE
                },
            isShort = isShort,
            snippet = if (o % 3 != 0) prose(rnd, 60, SNIPPET_MAX) else null,
        )
    }

    /** 02's 90–180-char enclosure URLs; deterministic length from the ordinal. */
    private fun enclosureUrl(
        p: Int,
        j: Int,
        o: Int,
    ): String {
        val base = "https://cdn${p % 7}.seed.example.com/shows/$p/episodes/$j/track.mp3"
        val target = ENCLOSURE_URL_MIN + o % (ENCLOSURE_URL_MAX - ENCLOSURE_URL_MIN + 1)
        if (base.length >= target) return base
        return "$base?sig=" + "x".repeat(target - base.length - 5)
    }

    private class SideRows(
        val states: Map<Int, EpisodeStateEntity>,
        val downloads: Map<Int, DownloadEntity>,
        val descriptions: Map<Int, ByteArray>,
    )

    /**
     * The non-`episode` rows keyed by the episode's flat ordinal (insertion order = id order):
     * `episode_state` for played/in-progress/favourite/measured rows, `download` rows in a mixed
     * state vocabulary and `episode_description` blobs of the spec's 0.2–6 KB. One shuffled draw
     * feeds disjoint slices so in-progress lands only on unplayed rows.
     */
    private fun sideRows(
        rnd: Random,
        episodeDrafts: List<List<EpisodeEntity>>,
        youTube: Set<Int>,
    ): SideRows {
        val total = episodeDrafts.sumOf { it.size }
        val played = BooleanArray(total) { rnd.nextDouble() < playedFraction }
        val shuffled = (0 until total).shuffled(rnd)

        var cursor = 0
        val inProgressIdx = HashSet<Int>()
        while (inProgressIdx.size < inProgress && cursor < shuffled.size) {
            val i = shuffled[cursor++]
            if (!played[i]) inProgressIdx += i
        }
        val favouriteIdx = HashSet<Int>()
        while (favouriteIdx.size < favourites && cursor < shuffled.size) favouriteIdx += shuffled[cursor++]
        val downloadIdx = HashSet<Int>()
        while (downloadIdx.size < downloads && cursor < shuffled.size) downloadIdx += shuffled[cursor++]
        // The slices share one draw: an oversized quota would silently starve the later ones.
        check(inProgressIdx.size == inProgress && favouriteIdx.size == favourites && downloadIdx.size == downloads) {
            "seed quotas exceed the episode pool: in-progress ${inProgressIdx.size}/$inProgress," +
                " favourites ${favouriteIdx.size}/$favourites, downloads ${downloadIdx.size}/$downloads"
        }

        val states = LinkedHashMap<Int, EpisodeStateEntity>()
        val downloadRows = LinkedHashMap<Int, DownloadEntity>()
        val descriptions = LinkedHashMap<Int, ByteArray>()
        var ordinal = 0
        episodeDrafts.forEachIndexed { p, rows ->
            val isYouTube = p in youTube
            for (episode in rows) {
                val o = ordinal++
                val isPlayed = played[o]
                val isInProgress = o in inProgressIdx
                val isFavourite = o in favouriteIdx
                val hasMeasured = o % 17 == 0
                if (isPlayed || isInProgress || isFavourite || hasMeasured) {
                    val playedAt =
                        if (isPlayed) min(BASE_NOW, episode.sortDate + 2 * DAY_MS + (o % 24) * HOUR_MS) else null
                    states[o] =
                        EpisodeStateEntity(
                            episodeId = 0,
                            startedAt = if (isInProgress) min(BASE_NOW, episode.sortDate + HOUR_MS) else null,
                            playedAt = playedAt,
                            playCount = if (isPlayed) 1 + o % 2 else 0,
                            lastPlayedAt = playedAt,
                            isFavorite = isFavourite,
                            measuredDurationMs = if (hasMeasured) 900_000L + (o % 80) * 60_000L else null,
                            updatedAt = BASE_NOW - (o % 30) * HOUR_MS,
                        )
                }
                if (o in downloadIdx) {
                    val k = downloadRows.size
                    val state = DOWNLOAD_STATES[k % DOWNLOAD_STATES.size]
                    val completed = state == DownloadState.COMPLETED
                    downloadRows[o] =
                        DownloadEntity(
                            episodeId = 0,
                            lane = if (k % 3 == 0) DownloadLane.AUTO else DownloadLane.MANUAL,
                            state = state,
                            waitReason = WaitReason.NONE,
                            priority = if (k % 3 == 0) 0 else 100,
                            requestedAt = min(BASE_NOW, episode.sortDate + (k % 72) * HOUR_MS),
                            sourceKind = if (isYouTube) SourceKind.YOUTUBE else SourceKind.RSS_ENCLOSURE,
                            sourceRef = episode.enclosureUrl ?: episode.externalMediaId ?: "seed-$o",
                            rootId = "int",
                            totalBytes = if (completed) episode.enclosureLength else null,
                            downloadedBytes = if (completed) episode.enclosureLength ?: 0L else 0L,
                            relativePath = if (completed) "seed/dl/$o.mp3" else null,
                            finalUri = if (completed) "file:///seed/dl/$o" else null,
                            completedAt = if (completed) min(BASE_NOW, episode.sortDate + 3 * DAY_MS) else null,
                            allowMetered = k % 4 == 0,
                        )
                }
                if (o % DESCRIBE_EVERY == 0) {
                    descriptions[o] =
                        EpisodeDescriptionCodec.encode(
                            prose(rnd, DESCRIPTION_MIN, DESCRIPTION_MAX),
                            if (o % 8 == 0) DescriptionFormat.PLAIN_TEXT else DescriptionFormat.HTML,
                        )
                }
            }
        }
        return SideRows(states, downloadRows, descriptions)
    }

    /**
     * `linked = true` (02 Testing): a linked `sync_state` plus `sync_clock` rows for every podcast,
     * group and member and every episode carrying user state — the rids follow 10's conventions
     * (podcast `syncId`, group `uuid`, member `uuid + syncId`, episode `syncId + identityKey`).
     */
    private suspend fun writeSyncRows(
        db: NeutrodyneDatabase,
        podcasts: List<PodcastEntity>,
        groups: List<PodcastGroupEntity>,
        members: List<PodcastGroupMemberEntity>,
        episodes: List<EpisodeEntity>,
        stateEpisodeIds: Set<Long>,
    ) {
        val nodeId = "5eed5eed5eed5eed"
        db.syncStateDao().upsert(
            SyncStateEntity(
                enabled = true,
                serverUrl = "https://sync.seed.example.com",
                accountId = "seed-account",
                deviceId = "seed-device",
                cursor = "seed-cursor",
                hlc = BASE_NOW shl 16,
                nodeId = nodeId,
                protocol = 1,
                linkedAt = BASE_NOW - DAY_MS,
                lastSyncAt = BASE_NOW,
            ),
        )
        val groupUuids = groups.associate { it.id to it.uuid }
        val podcastSyncIds = podcasts.associate { it.id to it.syncId }
        val clocks = db.syncClockDao()
        podcasts.forEach { clocks.upsert(SyncClockEntity("podcast", it.syncId, CLOCK_PAYLOAD)) }
        groups.forEach { clocks.upsert(SyncClockEntity("group", it.uuid, CLOCK_PAYLOAD)) }
        members.forEach {
            clocks.upsert(
                SyncClockEntity(
                    "member",
                    groupUuids.getValue(it.groupId) + podcastSyncIds.getValue(it.podcastId),
                    CLOCK_PAYLOAD,
                ),
            )
        }
        episodes.forEach {
            if (it.id in stateEpisodeIds) {
                clocks.upsert(
                    SyncClockEntity("episode", podcastSyncIds.getValue(it.podcastId) + it.identityKey, CLOCK_PAYLOAD),
                )
            }
        }
    }

    private fun uuid(rnd: Random): String = Uuid.fromLongs(rnd.nextLong(), rnd.nextLong()).toString()

    /** Locale-free zero padding: `String.format` would render the default locale's digits. */
    private fun Int.padded(
        width: Int,
        radix: Int = 10,
    ): String = toString(radix).padStart(width, '0')

    /** `target` characters of seeded prose — deterministic length and content. */
    private fun prose(
        rnd: Random,
        minLength: Int,
        maxLength: Int,
    ): String {
        val target = minLength + rnd.nextInt(maxLength - minLength + 1)
        val sb = StringBuilder()
        while (sb.length < target) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(WORDS[rnd.nextInt(WORDS.size)])
        }
        return sb.substring(0, min(target, sb.length))
    }

    private companion object {
        /** The fixed anchor instant — the same value `TestDb` clocks report. */
        const val BASE_NOW = TestClock.DEFAULT_NOW

        const val DAY_MS = 86_400_000L

        const val HEX = 16
        const val HOUR_MS = 3_600_000L

        /** Episodes spread over ~800 days of feed history. */
        const val HISTORY_MS = 800 * DAY_MS

        /** ~1/7 of podcasts leave `includeInAll` — and index 1 is always one of them. */
        const val EXCLUDED_EVERY = 7

        // Equal-sortDate structure: per-podcast clusters, a shared pool and the release-day block.
        const val POOL_RATE = 0.12
        const val POOL_SIZE = 64
        const val POOL_BASE_MS = 10 * DAY_MS
        const val POOL_STEP_MS = 6 * DAY_MS
        const val EVENT_AGE_MS = 7 * DAY_MS

        /** Every ~1/47th episode cycles through the non-`AVAILABLE` vocabulary. */
        const val SPECIAL_EVERY = 47

        const val SHORT_EVERY = 6
        const val DESCRIBE_EVERY = 4
        const val TITLE_MIN = 40
        const val TITLE_MAX = 90
        const val SNIPPET_MAX = 190
        const val DESCRIPTION_MIN = 200
        const val DESCRIPTION_MAX = 6_000
        const val ENCLOSURE_URL_MIN = 90
        const val ENCLOSURE_URL_MAX = 180

        val SPECIALS =
            listOf(
                Availability.UPCOMING,
                Availability.LIVE,
                Availability.MEMBERS_ONLY,
                Availability.UNAVAILABLE,
                Availability.AGE_RESTRICTED,
                Availability.REGION_BLOCKED,
                Availability.PRIVATE,
                Availability.KIDS_ONLY,
            )

        val VARIANT_SETS =
            intArrayOf(
                YouTubeVariantBits.LONG_FORM,
                YouTubeVariantBits.LONG_FORM or YouTubeVariantBits.SHORTS,
                YouTubeVariantBits.LONG_FORM or YouTubeVariantBits.SHORTS or YouTubeVariantBits.LIVE,
                YouTubeVariantBits.LONG_FORM or YouTubeVariantBits.LIVE,
            )

        val HIDE_OLDER_DAYS = listOf(null, 1, 7, 30, 90, 365)

        val DOWNLOAD_STATES =
            listOf(
                DownloadState.COMPLETED,
                DownloadState.COMPLETED,
                DownloadState.COMPLETED,
                DownloadState.COMPLETED,
                DownloadState.COMPLETED,
                DownloadState.QUEUED,
                DownloadState.PAUSED,
                DownloadState.DOWNLOADING,
                DownloadState.FAILED,
                DownloadState.MISSING,
            )

        /** A few opaque `clocks`; with its `rid`, a row lands near 02's ≈ 150-byte N5 estimate. */
        val CLOCK_PAYLOAD =
            "{\"n\":\"5eed5eed5eed5eed\",\"t\":1760400000000,\"f\":{\"subscribed\":[1760400000000,0],\"title\":[1760400000000,0]}}"

        val WORDS =
            listOf(
                "the",
                "a",
                "of",
                "and",
                "episode",
                "podcast",
                "show",
                "week",
                "story",
                "interview",
                "news",
                "update",
                "special",
                "bonus",
                "review",
                "deep",
                "dive",
                "morning",
                "daily",
                "hourly",
                "weekly",
                "final",
                "first",
                "second",
                "part",
                "guest",
                "host",
                "panel",
                "debate",
                "report",
                "field",
                "notes",
                "live",
                "tape",
                "archive",
                "season",
                "finale",
                "premiere",
                "behind",
                "scenes",
                "extra",
                "cut",
                "extended",
                "short",
                "brief",
                "long",
                "edition",
                "music",
                "tech",
                "science",
                "history",
                "culture",
                "sports",
                "politics",
                "economy",
                "world",
                "local",
                "today",
                "tomorrow",
                "yesterday",
                "guide",
                "explained",
            )
    }
}

/**
 * The rows [SeedDatabase.install] wrote, with generated primary keys — the seed model tests
 * compute expectations over. [feedRows] is the Kotlin reference for `FeedQueryBuilder.page`:
 * the same source, visibility, filter and ordering semantics evaluated over the entities,
 * used by `QueryPlanTest` row-exactness checks (02 Feed pages; PLAN M2 AC10).
 */
class Seeded(
    val spec: SeedDatabase,
    val podcasts: List<PodcastEntity>,
    val groups: List<PodcastGroupEntity>,
    val members: List<PodcastGroupMemberEntity>,
    val episodes: List<EpisodeEntity>,
    val states: Map<Long, EpisodeStateEntity>,
    val downloads: Map<Long, DownloadEntity>,
) {
    private val podcastById = podcasts.associateBy { it.id }
    private val memberPodcastIds = members.groupBy({ it.groupId }, { it.podcastId }).mapValues { it.value.toSet() }
    private val groupedPodcastIds = members.mapTo(HashSet()) { it.podcastId }

    /**
     * Concrete `FeedSource`s covering every kind: All and Ungrouped, the most- and least-populated
     * groups and the empty group, the biggest podcast, a Shorts-enabled YouTube channel and a
     * podcast excluded from All.
     */
    fun matrixSources(): List<FeedSource> {
        val memberCount = members.groupingBy { it.groupId }.eachCount()
        val episodeCount = episodes.groupingBy { it.podcastId }.eachCount()
        val populated = groups.filter { memberCount.getOrDefault(it.id, 0) > 0 }
        return buildList {
            add(FeedSource.All)
            add(FeedSource.Ungrouped)
            populated.maxByOrNull { memberCount.getValue(it.id) }?.let { add(FeedSource.Group(it.id)) }
            populated.minByOrNull { memberCount.getValue(it.id) }?.let { add(FeedSource.Group(it.id)) }
            groups.firstOrNull { memberCount.getOrDefault(it.id, 0) == 0 }?.let { add(FeedSource.Group(it.id)) }
            podcasts.maxByOrNull { episodeCount.getOrDefault(it.id, 0) }?.let { add(FeedSource.Podcast(it.id)) }
            podcasts
                .firstOrNull {
                    it.sourceType == SourceType.YOUTUBE_CHANNEL && it.youtubeVariants and YouTubeVariantBits.SHORTS != 0
                }?.let { add(FeedSource.Podcast(it.id)) }
            podcasts.firstOrNull { !it.includeInAll }?.let { add(FeedSource.Podcast(it.id)) }
        }.distinct()
    }

    /**
     * `FeedFilters.minSortDate` values for the matrix: `null` plus two bounds that bisect the
     * seeded dates — the quartile values are real `sortDate`s, so `>=` cuts inside tie runs.
     */
    fun minSortDateValues(): List<Long?> {
        if (episodes.isEmpty()) return listOf(null)
        val dates = episodes.mapTo(HashSet()) { it.sortDate }.sorted()
        return listOf(null, dates[dates.size / 4], dates[dates.size * 3 / 4])
    }

    /**
     * The expected `FeedDao.page` rows for [source] filtered by [f] in [order] — the query's
     * semantics evaluated over the entities: `VISIBLE` (Shorts need the channel's Shorts bit;
     * `UPCOMING`/`LIVE`/`MEMBERS_ONLY` are hidden), the source predicate, the filter predicates
     * and the `(sortDate, id)` order.
     */
    fun feedRows(
        source: FeedSource,
        f: FeedFilters,
        order: FeedOrder,
    ): List<EpisodeRowProjection> {
        val memberIds = if (source is FeedSource.Group) memberPodcastIds[source.groupId].orEmpty() else emptySet()
        val minSortDate = f.minSortDate
        val rows =
            episodes.filter { e ->
                val p = podcastById.getValue(e.podcastId)
                if (e.isShort && p.youtubeVariants and YouTubeVariantBits.SHORTS == 0) return@filter false
                if (e.availability in HIDDEN_AVAILABILITY) return@filter false
                when (source) {
                    FeedSource.All -> if (!p.includeInAll) return@filter false
                    FeedSource.Ungrouped -> if (e.podcastId in groupedPodcastIds) return@filter false
                    is FeedSource.Group -> if (e.podcastId !in memberIds) return@filter false
                    is FeedSource.Podcast -> if (e.podcastId != source.podcastId) return@filter false
                }
                if (minSortDate != null && e.sortDate < minSortDate) return@filter false
                val state = states[e.id]
                if (f.unplayedOnly && state?.playedAt != null) return@filter false
                if (f.inProgressOnly && (state == null || state.startedAt == null || state.playedAt != null)) {
                    return@filter false
                }
                if (f.downloadedOnly && downloads[e.id]?.state != DownloadState.COMPLETED) return@filter false
                when (f.media) {
                    MediaFilter.ALL -> Unit
                    MediaFilter.AUDIO -> if (e.isVideo) return@filter false
                    MediaFilter.VIDEO -> if (!e.isVideo) return@filter false
                }
                true
            }
        val sorted =
            rows.sortedWith(
                if (order == FeedOrder.NEWEST_FIRST) {
                    compareByDescending<EpisodeEntity> { it.sortDate }.thenByDescending { it.id }
                } else {
                    compareBy<EpisodeEntity> { it.sortDate }.thenBy { it.id }
                },
            )
        return sorted.map { rowOf(it) }
    }

    /** `ROW_COLUMNS` as a projection (02 Feed pages) — the LEFT JOINs evaluated in Kotlin. */
    private fun rowOf(e: EpisodeEntity): EpisodeRowProjection {
        val p = podcastById.getValue(e.podcastId)
        val s = states[e.id]
        val d = downloads[e.id]
        return EpisodeRowProjection(
            id = e.id,
            podcastId = e.podcastId,
            title = e.title,
            podcastTitle = p.customTitle ?: p.title,
            sortDate = e.sortDate,
            pubDate = e.pubDate,
            durationMs = s?.measuredDurationMs ?: e.durationMs,
            isVideo = e.isVideo,
            isShort = e.isShort,
            availability = e.availability,
            episodeType = e.episodeType,
            episodeDisplay = e.episodeDisplay,
            sourceType = p.sourceType,
            externalMediaId = e.externalMediaId,
            isNew = e.isNew,
            firstSeenAt = e.firstSeenAt,
            artworkKey = e.artworkKey ?: p.artworkKey,
            artworkUrl = e.imageUrl ?: p.artworkUrl,
            artworkVersion = 0,
            artworkAvgArgb = null,
            podcastArtworkKey = p.artworkKey,
            podcastArtworkUrl = p.artworkUrl,
            podcastArtworkVersion = 0,
            podcastArtworkAvgArgb = null,
            playedAt = s?.playedAt,
            startedAt = s?.startedAt,
            isFavorite = s?.isFavorite == true,
            downloadState = d?.state,
        )
    }

    private companion object {
        /** `VISIBLE`'s hidden availabilities (02 Key queries; all other values stay visible). */
        val HIDDEN_AVAILABILITY =
            setOf(Availability.UPCOMING, Availability.LIVE, Availability.MEMBERS_ONLY)
    }
}
