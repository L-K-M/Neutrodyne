// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.backup

import androidx.room3.useWriterConnection
import ch.lkmc.neutrodyne.core.data.execSql
import ch.lkmc.neutrodyne.core.database.DescriptionFormat
import ch.lkmc.neutrodyne.core.database.EpisodeDescriptionCodec
import ch.lkmc.neutrodyne.core.database.EpisodeEntity
import ch.lkmc.neutrodyne.core.database.EpisodePositionEntity
import ch.lkmc.neutrodyne.core.database.EpisodeStateEntity
import ch.lkmc.neutrodyne.core.database.PodcastUrlAliasEntity
import ch.lkmc.neutrodyne.core.model.AliasReason
import ch.lkmc.neutrodyne.core.model.GuidKnowledge
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.feeds.identity.EpisodeKeys
import ch.lkmc.neutrodyne.feeds.identity.KeyInput
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * 02 Restore matching + 05 Merge and Replace rules over the bundled driver (05's
 * `RestoreMatchingTest`): the podcast ladder (feedKey → aliases → real GUID), the episode
 * ladder (version-aware key → enclosure → GUID, unique evidence only), `syncId` adoption,
 * stubs, and the merge/replace state writes with archive-preserved timestamps. The
 * `RestoreMerger` orchestration that calls these primitives lands in B10/B11.
 */
class RestoreMatchingTest {
    private val db = TestDb.inMemory()
    private val episodes = EpisodeMatcher(db)
    private val podcasts = PodcastMatcher(db)
    private val writes = RestoreWrites(db)

    @AfterTest fun close() = db.close()

    // --- Podcast ladder (02 steps 1–2) ---------------------------------------------------------

    @Test
    fun podcastFeedKeyMatch() =
        runTest {
            val id = db.podcastDao().insertPodcast(podcastEntity(feedKey = "https://a/feed"))
            assertEquals(id, podcasts.match("https://a/feed", emptyList(), null))
            assertNull(podcasts.match("https://a/other", emptyList(), null))
        }

    @Test
    fun podcastBackupAliasHitsLocalFeedKey() =
        runTest {
            // The backup knew the URL as an alias; locally it became the feed's current URL.
            val id = db.podcastDao().insertPodcast(podcastEntity(feedKey = "https://a/new"))
            assertEquals(id, podcasts.match("https://a/old", listOf("https://a/new"), null))
        }

    @Test
    fun podcastBackupKeyHitsLocalAlias() =
        runTest {
            // The local feed moved away; the backup's key survives only as a local alias.
            val id = db.podcastDao().insertPodcast(podcastEntity(feedKey = "https://a/new"))
            db.podcastDao().insertAlias(PodcastUrlAliasEntity("https://a/old", id, AliasReason.REDIRECT, 1L))
            assertEquals(id, podcasts.match("https://a/old", emptyList(), null))
        }

    @Test
    fun podcastLiveFeedKeyBeatsAliasEvidence() =
        runTest {
            // Rank 1 (backup alias = local feedKey) wins over rank 2 (backup key = local alias).
            val aliasHolder = db.podcastDao().insertPodcast(podcastEntity(feedKey = "https://a/live"))
            db.podcastDao().insertAlias(
                PodcastUrlAliasEntity("https://a/old", aliasHolder, AliasReason.REDIRECT, 1L),
            )
            val liveHolder = db.podcastDao().insertPodcast(podcastEntity(feedKey = "https://b/live"))
            assertEquals(
                liveHolder,
                podcasts.match("https://a/old", listOf("https://b/live"), null),
            )
        }

    @Test
    fun podcastRealGuidMatchDerivedIgnored() =
        runTest {
            val real = db.podcastDao().insertPodcast(podcastEntity { copy(podcastGuid = "urn:real") })
            db.podcastDao().insertPodcast(
                podcastEntity { copy(podcastGuid = "urn:derived", podcastGuidDerived = true) },
            )
            assertEquals(real, podcasts.match("https://other/feed", emptyList(), "urn:real"))
            assertNull(podcasts.match("https://other/feed", emptyList(), "urn:derived"))
        }

    @Test
    fun podcastUrlEvidenceBeatsGuid() =
        runTest {
            val byKey = db.podcastDao().insertPodcast(podcastEntity(feedKey = "https://k/feed"))
            db.podcastDao().insertPodcast(
                podcastEntity(feedKey = "https://g/feed") { copy(podcastGuid = "urn:g") },
            )
            // Feed key, alias and real GUID all point somewhere — the strongest URL step wins.
            assertEquals(byKey, podcasts.match("https://k/feed", listOf("https://g/feed"), "urn:g"))

            // And with no direct feedKey hit, alias evidence still beats the GUID step.
            val byAlias = db.podcastDao().insertPodcast(podcastEntity(feedKey = "https://x/feed"))
            db.podcastDao().insertAlias(
                PodcastUrlAliasEntity("https://k/old", byAlias, AliasReason.REDIRECT, 1L),
            )
            assertEquals(byAlias, podcasts.match("https://none", listOf("https://k/old"), "urn:g"))
        }

    @Test
    fun podcastMatchChunksAliasEvidenceAtBindLimit() =
        runTest {
            // A backup's alias list is unbounded: the `IN (:keys)` selects chunk at
            // BIND_CHUNK (02 Transactions and threading) or the 600+ keys bound twice
            // would pass SQLite's variable limit. The hits sit past the first chunk so
            // a single unchunked query also fails here.
            val byFeedKey = db.podcastDao().insertPodcast(podcastEntity(feedKey = "https://hit/feed"))
            val byAlias = db.podcastDao().insertPodcast(podcastEntity(feedKey = "https://other/feed"))
            db.podcastDao().insertAlias(
                PodcastUrlAliasEntity("https://hit/alias", byAlias, AliasReason.REDIRECT, 1L),
            )
            val low = db.podcastDao().insertPodcast(podcastEntity(feedKey = "https://hit/low"))
            val high = db.podcastDao().insertPodcast(podcastEntity(feedKey = "https://hit/high"))
            val padding = (1..600).map { "https://none/$it" }

            // Rank 1 (backup alias = local feedKey) in the second chunk beats rank 2 in the first.
            assertEquals(
                byFeedKey,
                podcasts.match(
                    "https://miss",
                    listOf("https://hit/alias") + padding + "https://hit/feed",
                    null,
                ),
            )
            // Alias evidence alone resolves from the second chunk.
            assertEquals(byAlias, podcasts.match("https://miss", padding + "https://hit/alias", null))
            // Same-rank hits spanning chunks take the lowest id (low was inserted
            // before high, whose hit lands in the first chunk), not the first chunk's hit.
            assertEquals(
                low,
                podcasts.match("https://miss", listOf("https://hit/high") + padding + "https://hit/low", null),
            )
        }

    @Test
    fun groupMatchUuidThenNameKey() =
        runTest {
            val byUuid = db.groupDao().insert(groupEntity(uuid = "u-1", name = "Tech", orderKey = "a"))
            val byName = db.groupDao().insert(groupEntity(uuid = "u-2", name = "News", orderKey = "b"))

            assertEquals(byUuid, podcasts.matchGroup("u-1", "news"))
            assertEquals(byName, podcasts.matchGroup("u-9", "news"))
            assertNull(podcasts.matchGroup(null, "none"))
        }

    @Test
    fun syncIdAdoptedWhenFreeRegeneratedOnCollision() =
        runTest {
            val taken = Uuid.random().toString()
            db.podcastDao().insertPodcast(podcastEntity(syncId = taken))

            val free = Uuid.random().toString()
            assertEquals(free, podcasts.syncIdForInsert(free))

            val regenerated = podcasts.syncIdForInsert(taken)
            assertNotEquals(taken, regenerated)
            assertFalse(db.backupDao().syncIdInUse(regenerated))

            assertNotEquals(taken, podcasts.syncIdForInsert(null))
        }

    // --- Episode ladder (02: key → enclosure → guid, unique evidence only) ----------------------

    @Test
    fun episodeKeyMatchAndOrder() =
        runTest {
            val p = insertPodcast()
            val byKey = insertEpisode(p, "g:keyed")
            // The same row also carries enclosure/guid, but the key step claims it first.
            val index = episodes.index(p, setOf(1))
            assertEquals(
                byKey,
                index.match(
                    EpisodeMatchHints(identityKey = "g:keyed", enclosureUrl = "https://cdn/other.mp3", guid = "other"),
                ),
            )
            assertNull(index.match(EpisodeMatchHints(identityKey = "g:none")))
        }

    @Test
    fun episodeForeignStoredKeyMatchesOlderLine() =
        runTest {
            val p = insertPodcast()
            // A `2`-version-keyed stub kept verbatim by an earlier restore (02's foreign-key rule).
            val foreign = insertEpisode(p, "2g:shared") { copy(guid = "shared") }
            val index = episodes.index(p, setOf(1))

            // A kv=1 line's v1 key recomputes over the row's own columns and claims it.
            assertEquals(foreign, index.match(EpisodeMatchHints(identityKey = "g:shared", kv = 1)))
        }

    @Test
    fun episodeNewerLineVersionSkipsKeyStep() =
        runTest {
            val p = insertPodcast()
            val row = insertEpisode(p, "g:x") { copy(guid = "x", enclosureUrl = "https://cdn/x.mp3") }
            val index = episodes.index(p, setOf(2))

            // kv = 2 > EpisodeKeys.VERSION: key matching is skipped even though `k` equals the
            // stored key — a version this build cannot compute proves nothing by string equality.
            assertNull(index.match(EpisodeMatchHints(identityKey = "g:x", kv = 2)))
            // Enclosure and guid evidence still claim the row.
            assertEquals(
                row,
                index.match(EpisodeMatchHints(identityKey = "g:x", kv = 2, enclosureUrl = "https://cdn/x.mp3")),
            )
            assertEquals(row, index.match(EpisodeMatchHints(identityKey = "g:x", kv = 2, guid = "x")))
        }

    @Test
    fun episodeEnclosureMatchNormalisedAndUnique() =
        runTest {
            val p = insertPodcast()
            val sole = insertEpisode(p, "u-stored") { copy(enclosureUrl = "https://CDN.example/ep.mp3") }
            insertEpisode(p, "dup-1") { copy(enclosureUrl = "https://cdn.example/dup.mp3") }
            insertEpisode(p, "dup-2") { copy(enclosureUrl = "https://cdn.example/dup.mp3") }
            val index = episodes.index(p, setOf(1))

            // `UrlNormalizer.forIdentity` folds host case; tracking params differ but the identity
            // survives on the unique row.
            assertEquals(
                sole,
                index.match(EpisodeMatchHints(identityKey = "k", enclosureUrl = "https://cdn.EXAMPLE/ep.mp3")),
            )
            // Two rows share the normalised enclosure — no claim (D98's duplicate-over-N1 rule).
            assertNull(index.match(EpisodeMatchHints(identityKey = "k", enclosureUrl = "https://cdn.example/dup.mp3")))
        }

    @Test
    fun episodeGuidMatchAmbiguousNeverProves() =
        runTest {
            val p = insertPodcast()
            val sole = insertEpisode(p, "g1") { copy(guid = "sole") }
            insertEpisode(p, "g2") { copy(guid = "dup") }
            insertEpisode(p, "g3") { copy(guid = "dup") }
            val index = episodes.index(p, setOf(1))

            assertEquals(sole, index.match(EpisodeMatchHints(identityKey = "k", guid = "sole")))
            // A GUID carried by two stored rows can never pick one — unmatched, so the line stubs.
            assertNull(index.match(EpisodeMatchHints(identityKey = "k", guid = "dup")))
        }

    @Test
    fun episodeIndexSpansDescriptionChunks() =
        runTest {
            val p = insertPodcast()
            // 700 foreign-version `h:` stubs (no guid/enclosure/pubDate/link — the fixture
            // defaults): a kv=1 line recomputes their v1 key, which needs the decoded
            // description head — `descriptionBlobs` runs its 500-bind chunks twice.
            val headIds =
                (1..700).map { i ->
                    db.episodeDao().insertStub(
                        episodeEntity(podcastId = p, identityKey = "2h:$i", title = "head-$i"),
                    )
                }
            headIds.forEachIndexed { i, id ->
                db.ingestDao().replaceChildren(
                    id,
                    EpisodeDescriptionCodec.encode("desc-${i + 1}", DescriptionFormat.HTML),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                )
            }
            // 600 ordinary v1 candidates — 1,300 total.
            (1..600).forEach { i ->
                insertEpisode(p, "g:big-$i") { copy(guid = "big-$i") }
            }

            val index = episodes.index(p, setOf(1))

            // The v1 `h:` key of row 650 recomputes from title + decoded head — the match spans
            // the second descriptionBlobs chunk (ids 501–700).
            val key =
                EpisodeKeys.keyFor(
                    KeyInput(title = "head-650", descriptionHead = "desc-650"),
                    EpisodeKeys.VERSION,
                )
            assertEquals(headIds[649], index.match(EpisodeMatchHints(identityKey = key, kv = 1)))

            val wrongKey =
                EpisodeKeys.keyFor(
                    KeyInput(title = "head-1", descriptionHead = "desc-999"),
                    EpisodeKeys.VERSION,
                )
            assertNull(index.match(EpisodeMatchHints(identityKey = wrongKey, kv = 1)))

            // Ordinary v1 rows still match by stored key without a recomputation or a head.
            assertNotNull(index.match(EpisodeMatchHints(identityKey = "g:big-42", kv = 1)))
        }

    @Test
    fun episodeForeignVersionKeyCollapseBlocksClaim() =
        runTest {
            val p = insertPodcast()
            // Two v2-keyed rows recompute to the same v1 key — non-unique evidence
            // claims nothing (02's collapse rule), exactly like a shared enclosure or
            // GUID, and the line falls through to a stub.
            insertEpisode(p, "2t:a") { copy(title = "Same", pubDate = 1000) }
            insertEpisode(p, "2t:b") { copy(title = "Same", pubDate = 1000) }
            val unique = insertEpisode(p, "2t:c") { copy(title = "Unique", pubDate = 2000) }
            val index = episodes.index(p, setOf(1))

            val sharedKey =
                EpisodeKeys.keyFor(KeyInput(title = "Same", pubDate = 1000), EpisodeKeys.VERSION)
            assertNull(index.match(EpisodeMatchHints(identityKey = sharedKey, kv = 1)))

            val uniqueKey =
                EpisodeKeys.keyFor(KeyInput(title = "Unique", pubDate = 2000), EpisodeKeys.VERSION)
            assertEquals(unique, index.match(EpisodeMatchHints(identityKey = uniqueKey, kv = 1)))
        }

    @Test
    fun restoreProvenanceWritesOnlyKnownAmbiguous() =
        runTest {
            val p = insertPodcast()
            // An OBSERVED row exists; the backup's ambiguity set promotes it (sticky), and the
            // call shape has no `independent` set — a restore can never mint KNOWN_INDEPENDENT.
            db.ingestDao().recordGuidKnowledge(p, emptySet(), emptySet(), setOf("seen"))
            writes.recordAmbiguousGuids(p, setOf("amb", "seen"))

            val knowledge = db.ingestDao().guidKnowledge(p)
            assertEquals(GuidKnowledge.KNOWN_AMBIGUOUS, knowledge["amb"])
            assertEquals(GuidKnowledge.KNOWN_AMBIGUOUS, knowledge["seen"])
            assertFalse(knowledge.values.any { it == GuidKnowledge.KNOWN_INDEPENDENT })
        }

    // --- Stubs (02's INSERT OR IGNORE) ----------------------------------------------------------

    @Test
    fun insertStubWritesDocFieldsAndIgnoredInsertResolves() =
        runTest {
            val p = insertPodcast()
            val now = 2_000_000L
            val fields =
                RestoreStubFields(
                    identityKey = "g:stub",
                    guid = "stub-guid",
                    title = "Stub",
                    pubDate = now + 10L * 86_400_000L,
                    enclosureUrl = "https://cdn/s.mp3",
                    enclosureType = "audio/mpeg",
                    durationMs = 60_000L,
                    externalMediaId = "yt-1",
                    link = "https://p/s",
                )

            val id = writes.insertStub(p, fields, now)!!
            val row = db.episodeDao().byId(id)!!
            assertFalse(row.inFeed)
            assertFalse(row.isNew)
            assertEquals(0, row.contentHash)
            // sortDate = MIN(pubDate, now + 24 h): the far-future date clamps to the ceiling.
            assertEquals(now + 86_400_000L, row.sortDate)
            assertEquals(now, row.firstSeenAt)
            assertEquals(now, row.lastSeenAt)
            assertEquals("g:stub", row.identityKey)
            assertEquals("stub-guid", row.guid)
            assertEquals("https://cdn/s.mp3", row.enclosureUrl)
            assertEquals("audio/mpeg", row.enclosureType)
            assertEquals(60_000L, row.durationMs)
            assertEquals("yt-1", row.externalMediaId)
            assertEquals("https://p/s", row.link)

            // A same-key re-insert is ignored and resolves to the existing row (treated as matched).
            assertEquals(id, writes.insertStub(p, fields.copy(title = "Dup"), now))
            assertEquals("Stub", db.episodeDao().byId(id)!!.title)
        }

    @Test
    fun insertStubSortDateUsesPubDateWhenNotFuture() =
        runTest {
            val p = insertPodcast()
            val now = 2_000_000L
            val id =
                writes.insertStub(
                    p,
                    RestoreStubFields(identityKey = "g:s2", title = "S2", pubDate = now - 10_000L),
                    now,
                )!!
            assertEquals(now - 10_000L, db.episodeDao().byId(id)!!.sortDate)
        }

    // --- Merge writes (05 Merge column) ----------------------------------------------------------

    @Test
    fun mergeStatePlayedOrMaxFieldsAndTimestamp() =
        runTest {
            val p = insertPodcast()
            val e = insertEpisode(p, "g:e")
            db.episodeStateDao().upsert(
                EpisodeStateEntity(
                    episodeId = e,
                    playedAt = 200,
                    playCount = 1,
                    lastPlayedAt = 190,
                    isFavorite = true,
                    downloadDismissedAt = 100,
                    measuredDurationMs = 50,
                    updatedAt = 500,
                ),
            )

            writes.mergeState(
                e,
                RestoreStateValues(
                    playedAt = 300,
                    playCount = 2,
                    startedAt = 150,
                    lastPlayedAt = 250,
                    isFavorite = false,
                    downloadDismissedAt = 80,
                    measuredDurationMs = 99,
                    ts = 700,
                ),
            )

            val s = db.episodeStateDao().byEpisode(e)!!
            assertEquals(300L, s.playedAt) // max
            assertEquals(2, s.playCount) // max
            assertEquals(250L, s.lastPlayedAt) // max
            assertTrue(s.isFavorite) // OR: local true survives backup false
            assertEquals(100L, s.downloadDismissedAt) // tombstone: max
            assertEquals(50L, s.measuredDurationMs) // local non-null wins
            assertNull(s.startedAt) // merged 150, then cleared — the result is played
            assertEquals(700L, s.updatedAt) // a backup field won → MAX(500, ts)
        }

    @Test
    fun mergeStateStartedAtEarliestKeptWhenUnplayed() =
        runTest {
            val p = insertPodcast()
            val e = insertEpisode(p, "g:e2")
            db.episodeStateDao().upsert(
                EpisodeStateEntity(episodeId = e, startedAt = 400, updatedAt = 500),
            )

            writes.mergeState(
                e,
                RestoreStateValues(
                    playedAt = null,
                    playCount = 0,
                    startedAt = 150,
                    lastPlayedAt = null,
                    isFavorite = false,
                    downloadDismissedAt = null,
                    measuredDurationMs = null,
                    ts = 300,
                ),
            )

            val s = db.episodeStateDao().byEpisode(e)!!
            assertEquals(150L, s.startedAt) // earliest non-null
            assertEquals(500L, s.updatedAt) // field changed; updatedAt = MAX(local, ts)
        }

    @Test
    fun mergeStateLocalWinsKeepsLocalUpdatedAt() =
        runTest {
            val p = insertPodcast()
            val e = insertEpisode(p, "g:e3")
            db.episodeStateDao().upsert(
                EpisodeStateEntity(
                    episodeId = e,
                    playedAt = 500,
                    playCount = 3,
                    isFavorite = true,
                    updatedAt = 400,
                ),
            )

            // Every backup field loses or is equal — the line's newer `ts` is not stamped.
            writes.mergeState(
                e,
                RestoreStateValues(
                    playedAt = 100,
                    playCount = 1,
                    startedAt = null,
                    lastPlayedAt = null,
                    isFavorite = false,
                    downloadDismissedAt = null,
                    measuredDurationMs = null,
                    ts = 900,
                ),
            )

            val s = db.episodeStateDao().byEpisode(e)!!
            assertEquals(500L, s.playedAt)
            assertEquals(3, s.playCount)
            assertTrue(s.isFavorite)
            assertEquals(400L, s.updatedAt)
        }

    @Test
    fun mergeStateCreatesAbsentRowStampedTs() =
        runTest {
            val p = insertPodcast()
            val e = insertEpisode(p, "g:e4")

            writes.mergeState(
                e,
                RestoreStateValues(
                    playedAt = 300,
                    playCount = 2,
                    startedAt = null,
                    lastPlayedAt = 250,
                    isFavorite = true,
                    downloadDismissedAt = null,
                    measuredDurationMs = 42,
                    ts = 700,
                ),
            )

            val s = db.episodeStateDao().byEpisode(e)!!
            assertEquals(300L, s.playedAt)
            assertEquals(2, s.playCount)
            assertTrue(s.isFavorite)
            assertEquals(42L, s.measuredDurationMs) // local null → backup's
            assertEquals(700L, s.updatedAt) // a backup-created row stamps ts, never now
        }

    @Test
    fun mergeStateTs0WritesNothing() =
        runTest {
            val p = insertPodcast()
            val e = insertEpisode(p, "g:e5")

            writes.mergeState(
                e,
                RestoreStateValues(
                    playedAt = 100,
                    playCount = 1,
                    startedAt = null,
                    lastPlayedAt = null,
                    isFavorite = true,
                    downloadDismissedAt = null,
                    measuredDurationMs = null,
                    ts = 0,
                ),
            )
            assertNull(db.episodeStateDao().byEpisode(e))
        }

    @Test
    fun mergePositionNewerWinsOlderKeeps() =
        runTest {
            val p = insertPodcast()

            val newer = insertEpisode(p, "g:n")
            db.positionDao().upsert(
                EpisodePositionEntity(
                    newer,
                    positionMs = 100,
                    durationMs = 900,
                    positionSource = PositionSource.STREAM,
                    updatedAt = 500,
                ),
            )
            writes.mergePosition(newer, RestorePositionValues(200, 950, PositionSource.DOWNLOAD, 700))
            val newerPos = db.positionDao().byEpisode(newer)!!
            assertEquals(200L, newerPos.positionMs)
            assertEquals(950L, newerPos.durationMs)
            assertEquals(PositionSource.DOWNLOAD, newerPos.positionSource)
            assertEquals(700L, newerPos.updatedAt)

            val older = insertEpisode(p, "g:o")
            db.positionDao().upsert(
                EpisodePositionEntity(
                    older,
                    positionMs = 100,
                    durationMs = 900,
                    positionSource = PositionSource.STREAM,
                    updatedAt = 500,
                ),
            )
            writes.mergePosition(older, RestorePositionValues(200, null, PositionSource.DOWNLOAD, 300))
            val olderPos = db.positionDao().byEpisode(older)!!
            assertEquals(100L, olderPos.positionMs)
            assertEquals(500L, olderPos.updatedAt)
        }

    @Test
    fun mergePositionZeroNeverOverwritesAndAbsentAdopts() =
        runTest {
            val p = insertPodcast()

            val kept = insertEpisode(p, "g:z")
            db.positionDao().upsert(
                EpisodePositionEntity(
                    kept,
                    positionMs = 100,
                    durationMs = null,
                    positionSource = PositionSource.STREAM,
                    updatedAt = 500,
                ),
            )
            writes.mergePosition(kept, RestorePositionValues(0, null, PositionSource.STREAM, 900))
            val keptPos = db.positionDao().byEpisode(kept)!!
            assertEquals(100L, keptPos.positionMs)
            assertEquals(500L, keptPos.updatedAt)

            val absent = insertEpisode(p, "g:absent")
            writes.mergePosition(absent, RestorePositionValues(200, 123, PositionSource.DOWNLOAD, 700))
            val adopted = db.positionDao().byEpisode(absent)!!
            assertEquals(200L, adopted.positionMs)
            assertEquals(700L, adopted.updatedAt)
        }

    @Test
    fun mergePositionTieKeepsLocal() =
        runTest {
            val p = insertPodcast()
            val e = insertEpisode(p, "g:tie")
            db.positionDao().upsert(
                EpisodePositionEntity(
                    e,
                    positionMs = 100,
                    durationMs = 900,
                    positionSource = PositionSource.STREAM,
                    updatedAt = 500,
                ),
            )

            // posAt == local updatedAt: the strict `updatedAt < :posAt` guard keeps local.
            writes.mergePosition(e, RestorePositionValues(200, 950, PositionSource.DOWNLOAD, 500))
            val pos = db.positionDao().byEpisode(e)!!
            assertEquals(100L, pos.positionMs)
            assertEquals(900L, pos.durationMs)
            assertEquals(PositionSource.STREAM, pos.positionSource)
            assertEquals(500L, pos.updatedAt)
        }

    @Test
    fun mergePositionZeroLineStillRunsPostRules() =
        runTest {
            val p = insertPodcast()
            val e = insertEpisode(p, "g:pz")
            // Local progress the backup's played mark post-dates: `mergeState` wins the
            // played OR, then the pos-0 line must still run the post rules — a pos-0
            // line writes no position (N1), but the played-reset and proxy derivations
            // evaluate the merged result. The stored position (500 < playedAt 900)
            // resets to 0 stamped playedAt (05 mark-played semantics).
            db.positionDao().upsert(
                EpisodePositionEntity(
                    e,
                    positionMs = 5000,
                    durationMs = null,
                    positionSource = PositionSource.STREAM,
                    updatedAt = 500,
                ),
            )
            writes.mergeState(
                e,
                RestoreStateValues(
                    playedAt = 900,
                    playCount = 1,
                    startedAt = null,
                    lastPlayedAt = null,
                    isFavorite = false,
                    downloadDismissedAt = null,
                    measuredDurationMs = null,
                    ts = 900,
                ),
            )
            writes.mergePosition(e, RestorePositionValues(0, null, PositionSource.STREAM, 850))

            val pos = db.positionDao().byEpisode(e)!!
            assertEquals(0L, pos.positionMs)
            assertEquals(900L, pos.updatedAt)
        }

    @Test
    fun mergePositionPlayedResetsStampedPlayedAt() =
        runTest {
            val p = insertPodcast()
            val e = insertEpisode(p, "g:p1")
            db.episodeStateDao().upsert(EpisodeStateEntity(e, playedAt = 900, updatedAt = 900))

            // played && playedAt > posAt → the position resets to 0, stamped playedAt (06 semantics).
            writes.mergePosition(e, RestorePositionValues(200, null, PositionSource.STREAM, 700))
            val pos = db.positionDao().byEpisode(e)!!
            assertEquals(0L, pos.positionMs)
            assertEquals(900L, pos.updatedAt)
        }

    @Test
    fun mergePositionDerivesInProgressProxyStampedPosAt() =
        runTest {
            val p = insertPodcast()

            // ts = 0 + pos > 0 on an unplayed episode: the ensured proxy row is stamped posAt.
            val noRow = insertEpisode(p, "g:p2")
            writes.mergePosition(noRow, RestorePositionValues(200, null, PositionSource.STREAM, 700))
            val proxy = db.episodeStateDao().byEpisode(noRow)!!
            assertEquals(700L, proxy.updatedAt)
            assertEquals(700L, proxy.startedAt)
            assertNull(proxy.playedAt)

            // An existing row keeps its updatedAt; startedAt fills only when null.
            val existing = insertEpisode(p, "g:p3")
            db.episodeStateDao().upsert(EpisodeStateEntity(existing, isFavorite = true, updatedAt = 100))
            writes.mergePosition(existing, RestorePositionValues(200, null, PositionSource.STREAM, 700))
            val kept = db.episodeStateDao().byEpisode(existing)!!
            assertEquals(700L, kept.startedAt)
            assertEquals(100L, kept.updatedAt)

            val earlier = insertEpisode(p, "g:p4")
            db.episodeStateDao().upsert(EpisodeStateEntity(earlier, startedAt = 50, updatedAt = 100))
            writes.mergePosition(earlier, RestorePositionValues(200, null, PositionSource.STREAM, 700))
            assertEquals(50L, db.episodeStateDao().byEpisode(earlier)!!.startedAt)
        }

    // --- Replace writes (05 Replace column) ------------------------------------------------------

    @Test
    fun replaceWritesBackupVerbatim() =
        runTest {
            val p = insertPodcast()
            val e = insertEpisode(p, "g:r")
            db.episodeStateDao().upsert(
                EpisodeStateEntity(e, playedAt = 999, playCount = 9, isFavorite = true, updatedAt = 999),
            )
            db.positionDao().upsert(
                EpisodePositionEntity(
                    e,
                    positionMs = 999,
                    durationMs = null,
                    positionSource = PositionSource.STREAM,
                    updatedAt = 999,
                ),
            )

            // The Replace pre-step (05 step 2) deletes the matched podcasts' local rows first.
            db.backupDao().deleteStateOfPodcast(p)
            db.backupDao().deletePositionsOfPodcast(p)
            assertNull(db.episodeStateDao().byEpisode(e))
            assertNull(db.positionDao().byEpisode(e))

            writes.replaceState(
                e,
                RestoreStateValues(
                    playedAt = 100,
                    playCount = 1,
                    startedAt = 40,
                    lastPlayedAt = 90,
                    isFavorite = false,
                    downloadDismissedAt = null,
                    measuredDurationMs = null,
                    ts = 300,
                ),
            )
            writes.replacePosition(e, RestorePositionValues(50, null, PositionSource.STREAM, 200))

            val s = db.episodeStateDao().byEpisode(e)!!
            assertEquals(100L, s.playedAt)
            assertEquals(1, s.playCount)
            assertFalse(s.isFavorite)
            // The backup's startedAt = 40 wrote, then the played row's marker cleared.
            assertNull(s.startedAt)
            assertEquals(300L, s.updatedAt) // ts verbatim, no max
            val pos = db.positionDao().byEpisode(e)!!
            assertEquals(50L, pos.positionMs)
            assertEquals(200L, pos.updatedAt)
        }

    @Test
    fun replaceTs0WritesNoStateRow() =
        runTest {
            val p = insertPodcast()
            val e = insertEpisode(p, "g:r2")
            writes.replaceState(
                e,
                RestoreStateValues(
                    playedAt = 100,
                    playCount = 1,
                    startedAt = null,
                    lastPlayedAt = null,
                    isFavorite = true,
                    downloadDismissedAt = null,
                    measuredDurationMs = null,
                    ts = 0,
                ),
            )
            assertNull(db.episodeStateDao().byEpisode(e))
        }

    @Test
    fun replacePositionStillDerivesProxyForUnplayed() =
        runTest {
            val p = insertPodcast()
            val e = insertEpisode(p, "g:r3")
            writes.replacePosition(e, RestorePositionValues(200, null, PositionSource.STREAM, 700))
            val proxy = db.episodeStateDao().byEpisode(e)!!
            assertEquals(700L, proxy.startedAt)
            assertEquals(700L, proxy.updatedAt)
        }

    // --- Up next (05) ----------------------------------------------------------------------------

    @Test
    fun queueInsertKeepsArchiveOrderAndSkipsDuplicates() =
        runTest {
            val p = insertPodcast()
            val e1 = insertEpisode(p, "g:q1")
            val e2 = insertEpisode(p, "g:q2")
            val e3 = insertEpisode(p, "g:q3")

            assertTrue(writes.insertQueueEntry(e1, "a0", 100))
            assertTrue(writes.insertQueueEntry(e2, "a1", 101))
            assertTrue(writes.insertQueueEntry(e3, "a2", 102))
            // Already queued → ignored, entry unchanged.
            assertFalse(writes.insertQueueEntry(e2, "a3", 103))

            val entries = db.queueDao().entries()
            assertEquals(listOf(e1, e2, e3), entries.map { it.episodeId })
            assertEquals(listOf(100L, 101L, 102L), entries.map { it.addedAt })
            assertEquals(setOf(e1, e2, e3), db.queueDao().queuedEpisodeIds().toSet())
        }

    // --- Replace pre-step clears (05 UP_NEXT and the session context) ----------------------------

    @Test
    fun queueClearRemovesEveryEntry() =
        runTest {
            val p = insertPodcast()
            val e1 = insertEpisode(p, "g:c1")
            val e2 = insertEpisode(p, "g:c2")
            writes.insertQueueEntry(e1, "a0", 100)
            writes.insertQueueEntry(e2, "a1", 101)

            assertEquals(2, db.queueDao().clear())
            assertTrue(db.queueDao().entries().isEmpty())
            assertNull(db.queueDao().lastOrderKey())
        }

    @Test
    fun sessionClearContextKeepsCurrentEpisodeAndBumpsGeneration() =
        runTest {
            val p = insertPodcast()
            val e = insertEpisode(p, "g:s")
            db.playSessionDao().ensureNow(10)
            db.useWriterConnection {
                it.execSql(
                    "UPDATE play_session SET currentEpisodeId = $e, contextType = 'GROUP'," +
                        " contextId = 7, contextAnchorEpisodeId = $e, contextAnchorSortDate = 42" +
                        " WHERE id = 0",
                )
            }

            // The scoped clear (05 Replace pre-step): the context fields null out and
            // the generation bumps so a session write elsewhere is never clobbered;
            // the playing episode is not context and stays.
            assertEquals(1, db.playSessionDao().clearContext(500))
            val session = db.playSessionDao().get()!!
            assertNull(session.contextType)
            assertNull(session.contextId)
            assertNull(session.contextAnchorEpisodeId)
            assertNull(session.contextAnchorSortDate)
            assertEquals(e, session.currentEpisodeId)
            assertEquals(1L, session.generation)
            assertEquals(500L, session.updatedAt)
        }

    private suspend fun insertPodcast(): Long = db.podcastDao().insertPodcast(podcastEntity())

    private suspend fun insertEpisode(
        podcastId: Long,
        identityKey: String,
        block: EpisodeEntity.() -> EpisodeEntity = { this },
    ): Long = db.episodeDao().insertStub(episodeEntity(podcastId = podcastId, identityKey = identityKey, block = block))
}
