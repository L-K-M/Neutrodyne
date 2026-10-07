// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.data.ingest.IngestContext
import ch.lkmc.neutrodyne.core.data.ingest.IngestMode
import ch.lkmc.neutrodyne.core.database.ChapterEntity
import ch.lkmc.neutrodyne.core.model.ChapterSource
import ch.lkmc.neutrodyne.core.model.FeedErrorKind
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.episodeStateEntity
import ch.lkmc.neutrodyne.feeds.model.ParsedFeed
import ch.lkmc.neutrodyne.feeds.model.WarningCode
import ch.lkmc.neutrodyne.feeds.parse.FeedParser
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PLAN M1 acceptance 3/6 — the diff algorithm of 03 "Ingestion and diff" against an in-memory
 * database: accepted items, both match passes, `isNew` and the dump guard, column rules on
 * update, `inFeed` flips, warnings and transaction integrity.
 */
class IngestDiffTest {
    private val clock = TestClock()
    private val db = newDb(clock)
    private val ingestor = newIngestor(db, clock)

    private suspend fun podcastId(
        feedUrl: String = "https://example.com/feed.xml",
        subscribedAt: Long = NOW,
        initialFetch: Boolean = false,
        status: PodcastStatus = PodcastStatus.ACTIVE,
        latestEpisodeAt: Long? = null,
    ): Long =
        seedPodcast(
            db,
            feedUrl = feedUrl,
            feedKey = feedUrl,
            subscribedAt = subscribedAt,
            initialFetch = initialFetch,
            status = status,
            latestEpisodeAt = latestEpisodeAt,
        )

    private suspend fun ingest(
        id: Long,
        parsed: ParsedFeed,
        mode: IngestMode = IngestMode.REFRESH,
        partial: Boolean = false,
        meta: ch.lkmc.neutrodyne.core.data.ingest.FetchMeta = fetchMeta(),
        absenceFloor: Long? = null,
    ) = ingestor.ingest(
        dueFeedOf(db, id),
        parsed,
        IngestContext(mode = mode, partial = partial, fetch = meta, absenceFloor = absenceFloor),
    )

    // --- Accepted items and document keys -------------------------------------------------------

    @Test
    fun initialIngestInsertsEpisodesAndWritesMetadata() =
        runTest {
            val id = podcastId()
            val feed =
                parsedFeed(
                    title = "My Show",
                    items =
                        listOf(
                            parsedEpisode(0, guid = "a", pubDate = NOW - DAY),
                            parsedEpisode(1, guid = "b", pubDate = NOW - 2 * DAY),
                        ),
                )

            val result =
                ingest(id, feed, mode = IngestMode.INITIAL, meta = fetchMeta(etag = "e1", sha256Hex = "f".repeat(64)))

            assertEquals(2, result.inserted.size)
            assertEquals(2, result.accepted)
            assertEquals(emptyList(), result.newIds)
            val a = db.episodeDao().byId(result.inserted[0])!!
            assertEquals("g:a", a.identityKey)
            assertTrue(a.inFeed)
            assertFalse(a.isNew)

            val stored = db.podcastDao().byId(id)!!
            assertEquals("My Show", stored.title)
            assertEquals("e1", stored.etag)
            assertEquals("f".repeat(64), stored.contentSha256)
            assertEquals(FeedParser.VERSION, stored.parserVersion)
            assertTrue(stored.lastParseOk)
            assertEquals(NOW, stored.lastSuccessAt)
            assertTrue((stored.nextRefreshAt ?: 0) > NOW)
            assertEquals(NOW - DAY, stored.latestEpisodeAt)
        }

    @Test
    fun pendingPodcastIngestActivatesAndClearsInitialFetch() =
        runTest {
            val id =
                podcastId(
                    status = PodcastStatus.PENDING_FIRST_FETCH,
                    initialFetch = true,
                )
            val result =
                ingest(id, parsedFeed(items = listOf(parsedEpisode(0, guid = "a"))), mode = IngestMode.INITIAL)

            assertTrue(result.firstIngest)
            val stored = db.podcastDao().byId(id)!!
            assertEquals(PodcastStatus.ACTIVE, stored.status)
            assertFalse(stored.initialFetch)
        }

    @Test
    fun duplicateGuidsInOneDocumentGetFallbackKeys() =
        runTest {
            val id = podcastId()
            val feed =
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(0, guid = "dup", enclosureUrl = "https://cdn.example.com/a.mp3"),
                            parsedEpisode(1, guid = "dup", enclosureUrl = "https://cdn.example.com/b.mp3"),
                            parsedEpisode(
                                2,
                                guid = "dup",
                                enclosureUrl = null,
                                title = "T2",
                                pubDate = NOW,
                                externalMediaId = "m-x",
                            ),
                        ),
                )

            val result = ingest(id, feed, mode = IngestMode.INITIAL)

            assertEquals(3, result.inserted.size)
            val keys = db.ingestDao().existing(id).map { it.identityKey }
            assertEquals(3, keys.distinct().size)
            assertTrue(keys.any { it == "g:dup" })
            assertTrue(result.warnings.any { it.code == WarningCode.DUPLICATE_GUID })
        }

    @Test
    fun identicalItemsCollapseBeforeFallbackKeys() =
        runTest {
            val id = podcastId()
            // The same item twice (same GUID, same content): the repeat is dropped outright, not
            // given a fallback key (03 in-document dedupe; S2).
            val same =
                parsedEpisode(
                    0,
                    guid = "d",
                    enclosureUrl = "https://cdn.example.com/s.mp3",
                    title = "Same",
                    pubDate = NOW - DAY,
                )
            val result =
                ingest(
                    id,
                    parsedFeed(items = listOf(same, same.copy(feedOrder = 1))),
                    mode = IngestMode.INITIAL,
                )

            assertEquals(1, result.accepted)
            assertEquals(1, result.inserted.size)
            assertEquals(1, db.podcastDao().episodeCount(id))
        }

    @Test
    fun reusedGuidKeepsUserStateOnTheEnclosureMatch() =
        runTest {
            val id = podcastId()
            ingest(
                id,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(
                                0,
                                guid = "g",
                                enclosureUrl = "https://cdn.example.com/a.mp3",
                            ),
                        ),
                ),
                mode = IngestMode.INITIAL,
            )
            val stored = db.episodeDao().byIdentityKey(id, "g:g")!!
            db.episodeStateDao().upsert(episodeStateEntity(stored.id, playedAt = NOW - 1_000))

            // v2: two items reuse "g"; only the second still points at the stored enclosure.
            // Doc order alone would hand the stored row — and its user state — to the first,
            // distinct episode (S1); the enclosure contest keeps it with the real match.
            val result =
                ingest(
                    id,
                    parsedFeed(
                        items =
                            listOf(
                                parsedEpisode(
                                    0,
                                    guid = "g",
                                    enclosureUrl = "https://cdn.example.com/b.mp3",
                                ),
                                parsedEpisode(
                                    1,
                                    guid = "g",
                                    enclosureUrl = "https://cdn.example.com/a.mp3",
                                ),
                            ),
                    ),
                )

            assertEquals(1, result.inserted.size)
            val kept = db.episodeDao().byId(stored.id)!!
            assertEquals("g:g", kept.identityKey)
            assertEquals("https://cdn.example.com/a.mp3", kept.enclosureUrl)
            assertEquals(NOW - 1_000, db.episodeStateDao().byEpisode(stored.id)!!.playedAt)
            val fresh = db.episodeDao().byId(result.inserted.single())!!
            assertEquals("https://cdn.example.com/b.mp3", fresh.enclosureUrl)
            db.episodeStateDao().upsert(episodeStateEntity(fresh.id, playedAt = NOW - 2_000))

            // v3: the sibling returns alone. Stored state already proves "g" is reused (two rows
            // carry it), so the blind "g:" claim is skipped — a wrong claim would move A's row,
            // user state and all, to B (r2 F1). B instead matches its own fallback-keyed row and
            // A's row only leaves the feed.
            val v3 =
                ingest(
                    id,
                    parsedFeed(
                        items =
                            listOf(
                                parsedEpisode(
                                    0,
                                    guid = "g",
                                    enclosureUrl = "https://cdn.example.com/b.mp3",
                                ),
                            ),
                    ),
                )

            assertTrue(v3.inserted.isEmpty())
            val aRow = db.episodeDao().byId(stored.id)!!
            assertEquals("g:g", aRow.identityKey)
            assertEquals("https://cdn.example.com/a.mp3", aRow.enclosureUrl)
            assertFalse(aRow.inFeed)
            assertEquals(NOW - 1_000, db.episodeStateDao().byEpisode(stored.id)!!.playedAt)
            val bRow = db.episodeDao().byId(fresh.id)!!
            assertEquals("https://cdn.example.com/b.mp3", bRow.enclosureUrl)
            assertTrue(bRow.inFeed)
            assertEquals(NOW - 2_000, db.episodeStateDao().byEpisode(fresh.id)!!.playedAt)
        }

    // --- isNew and the back-catalogue guard -----------------------------------------------------

    @Test
    fun initialIngestNeverMarksNew() =
        runTest {
            val id = podcastId()
            val feed =
                parsedFeed(
                    items = listOf(parsedEpisode(0, guid = "a", pubDate = NOW)),
                )
            ingest(id, feed, mode = IngestMode.INITIAL)
            assertFalse(db.episodeDao().byIdentityKey(id, "g:a")!!.isNew)
        }

    @Test
    fun refreshMarksRecentItemsNewAndSkipsOld() =
        runTest {
            val id = podcastId(subscribedAt = NOW - 30 * DAY)
            val feed =
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(0, guid = "new", pubDate = NOW - DAY),
                            // Older than subscribedAt - 7 days: a re-published back-catalogue item.
                            parsedEpisode(1, guid = "old", pubDate = NOW - 38 * DAY),
                        ),
                )

            val result = ingest(id, feed)

            val newRow = db.episodeDao().byIdentityKey(id, "g:new")!!
            val oldRow = db.episodeDao().byIdentityKey(id, "g:old")!!
            assertTrue(newRow.isNew)
            assertFalse(oldRow.isNew)
            assertEquals(listOf(newRow.id), result.newIds)
        }

    @Test
    fun outOfRangePubDatesAreTreatedAsUndatedForSortAndNewness() =
        runTest {
            val id = podcastId(subscribedAt = NOW - 30 * DAY)
            // 1973 is below the 1990 floor; now+400d is past the now+365d ceiling — both count
            // as undated under `pubDateValid` (03 sortDate and clock): sortDate = firstSeenAt
            // and the newness check uses `now`, like an item with no date at all.
            val result =
                ingest(
                    id,
                    parsedFeed(
                        items =
                            listOf(
                                parsedEpisode(0, guid = "pre", pubDate = 100_000_000_000L),
                                parsedEpisode(1, guid = "post", pubDate = NOW + 400 * DAY),
                            ),
                    ),
                )

            val pre = db.episodeDao().byIdentityKey(id, "g:pre")!!
            val post = db.episodeDao().byIdentityKey(id, "g:post")!!
            assertEquals(pre.firstSeenAt, pre.sortDate)
            assertEquals(post.firstSeenAt, post.sortDate)
            assertTrue(pre.isNew)
            assertTrue(post.isNew)
            assertEquals(setOf(pre.id, post.id), result.newIds.toSet())
            // The raw pubDate stays on the row — only sort/newness treats it as undated.
            assertEquals(100_000_000_000L, pre.pubDate)
        }

    @Test
    fun dumpOfNewItemsKeepsAtMostThreeNew() =
        runTest {
            val id = podcastId(subscribedAt = NOW - 30 * DAY)
            val items =
                (0 until 25).map { i ->
                    parsedEpisode(i, guid = "g$i", pubDate = NOW - i * 60 * 60 * 1_000L)
                }

            val result = ingest(id, parsedFeed(items = items))

            assertEquals(3, result.newIds.size)
            assertTrue(result.warnings.any { it.code == WarningCode.BACK_CATALOGUE_DUMP })
            // The newest three by (sortDate, feedOrder): feed order 0, 1, 2 published most recently.
            val stored = (0 until 25).map { db.episodeDao().byIdentityKey(id, "g:g$it")!! }
            assertEquals(listOf(true, true, true) + List(22) { false }, stored.map { it.isNew })
        }

    // --- Matching: pass 1 and pass 2 ------------------------------------------------------------

    @Test
    fun rewrittenGuidsMatchByEnclosureAndKeepState() =
        runTest {
            val id = podcastId()
            ingest(
                id,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(
                                0,
                                guid = "g1",
                                enclosureUrl = "https://cdn.example.com/a.mp3",
                                pubDate =
                                    NOW - DAY,
                            ),
                            parsedEpisode(
                                1,
                                guid = "g2",
                                enclosureUrl = "https://cdn.example.com/b.mp3",
                                pubDate =
                                    NOW - 2 * DAY,
                            ),
                        ),
                ),
                mode = IngestMode.INITIAL,
            )
            val aBefore = db.episodeDao().byIdentityKey(id, "g:g1")!!
            db.episodeStateDao().upsert(episodeStateEntity(aBefore.id, playedAt = NOW - 1_000))

            // v2: the publisher rewrote every GUID; enclosures stayed.
            val result =
                ingest(
                    id,
                    parsedFeed(
                        items =
                            listOf(
                                parsedEpisode(
                                    0,
                                    guid = "x1",
                                    enclosureUrl = "https://cdn.example.com/a.mp3",
                                    pubDate =
                                        NOW - DAY,
                                ),
                                parsedEpisode(
                                    1,
                                    guid = "x2",
                                    enclosureUrl = "https://cdn.example.com/b.mp3",
                                    pubDate =
                                        NOW - 2 * DAY,
                                ),
                            ),
                    ),
                )

            assertEquals(2, result.rekeyed)
            assertEquals(emptyList(), result.inserted)
            val aAfter = db.episodeDao().byIdentityKey(id, "g:x1")!!
            assertEquals(aBefore.id, aAfter.id)
            assertEquals("x1", aAfter.guid)
            assertEquals(NOW - 1_000, db.episodeStateDao().byEpisode(aBefore.id)!!.playedAt)
        }

    @Test
    fun enclosureQueryRotationMatchesQueryLessAndRekeys() =
        runTest {
            val id = podcastId()
            ingest(
                id,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(
                                0,
                                enclosureUrl = "https://cdn.example.com/ep.mp3?token=old",
                                title = "Solo",
                                pubDate = NOW - DAY,
                            ),
                        ),
                ),
                mode = IngestMode.INITIAL,
            )
            val before = db.ingestDao().existing(id).single()

            val result =
                ingest(
                    id,
                    parsedFeed(
                        items =
                            listOf(
                                parsedEpisode(
                                    0,
                                    enclosureUrl = "https://cdn.example.com/ep.mp3?token=new",
                                    title = "Solo",
                                    pubDate = NOW - DAY,
                                ),
                            ),
                    ),
                )

            assertEquals(1, result.rekeyed)
            val after = db.ingestDao().existing(id).single()
            assertEquals(before.id, after.id)
            assertNotEquals(before.identityKey, after.identityKey)
            assertTrue(after.identityKey.startsWith("u:"))
        }

    @Test
    fun enclosurePrefixRotationMatchesByTitleAndDay() =
        runTest {
            val id = podcastId()
            val pubDate = NOW - 2 * DAY
            ingest(
                id,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(
                                0,
                                enclosureUrl = "https://old-cdn.example.com/a/ep.mp3",
                                title = "Fixed title",
                                pubDate = pubDate,
                                durationMs = 1_000,
                            ),
                        ),
                ),
                mode = IngestMode.INITIAL,
            )
            val before = db.ingestDao().existing(id).single()

            val result =
                ingest(
                    id,
                    parsedFeed(
                        items =
                            listOf(
                                parsedEpisode(
                                    0,
                                    enclosureUrl = "https://new-cdn.example.com/z/ep.mp3",
                                    title = "Fixed title",
                                    pubDate = pubDate,
                                    durationMs = 1_000,
                                ),
                            ),
                    ),
                )

            assertEquals(1, result.rekeyed)
            val after = db.ingestDao().existing(id).single()
            assertEquals(before.id, after.id)
            assertEquals("https://new-cdn.example.com/z/ep.mp3", after.enclosureUrl)
        }

    // --- Column rules on update ------------------------------------------------------------------

    @Test
    fun titleEditRewritesFeedColumnsAndChildren() =
        runTest {
            val id = podcastId()
            ingest(
                id,
                parsedFeed(items = listOf(parsedEpisode(0, guid = "a", title = "Old", descriptionHtml = "d1"))),
                mode = IngestMode.INITIAL,
            )
            val before = db.episodeDao().byIdentityKey(id, "g:a")!!

            val result =
                ingest(
                    id,
                    parsedFeed(items = listOf(parsedEpisode(0, guid = "a", title = "New", descriptionHtml = "d2"))),
                )

            assertEquals(1, result.updated)
            val after = db.episodeDao().byId(before.id)!!
            assertEquals("New", after.title)
            assertNotEquals(before.contentHash, after.contentHash)
        }

    @Test
    fun absentFieldsPreserveStoredColumns() =
        runTest {
            val id = podcastId()
            ingest(
                id,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(
                                0,
                                guid = "a",
                                title = "Same",
                                durationMs = 3_600_000,
                                descriptionHtml = "v1",
                            ).copy(artwork = listOf(artwork("https://img.example.com/ep.png"))),
                        ),
                ),
                mode = IngestMode.INITIAL,
            )
            val before = db.episodeDao().byIdentityKey(id, "g:a")!!
            assertEquals(3_600_000, before.durationMs)
            assertEquals("https://img.example.com/ep.png", before.imageUrl)

            // Same item but the doc dropped duration/artwork and changed the description.
            val result =
                ingest(
                    id,
                    parsedFeed(
                        items =
                            listOf(
                                parsedEpisode(0, guid = "a", title = "Same", durationMs = null, descriptionHtml = "v2"),
                            ),
                    ),
                )

            assertEquals(1, result.updated)
            val after = db.episodeDao().byId(before.id)!!
            assertEquals(3_600_000, after.durationMs)
            assertEquals("https://img.example.com/ep.png", after.imageUrl)
        }

    @Test
    fun futurePubDateClampsSortDateToFirstSeenPlusDay() =
        runTest {
            val id = podcastId()
            val feed = parsedFeed(items = listOf(parsedEpisode(0, guid = "a", pubDate = NOW + 10 * DAY)))
            ingest(id, feed, mode = IngestMode.INITIAL)
            val row = db.episodeDao().byIdentityKey(id, "g:a")!!
            assertEquals(row.firstSeenAt + DAY, row.sortDate)
        }

    @Test
    fun serverDateCorrectsFirstSeenAt() =
        runTest {
            val id = podcastId()
            val serverNow = NOW - 40 * DAY
            val feed = parsedFeed(items = listOf(parsedEpisode(0, guid = "a", pubDate = null)))
            ingest(id, feed, mode = IngestMode.INITIAL, meta = fetchMeta(serverDateMs = serverNow))
            val row = db.episodeDao().byIdentityKey(id, "g:a")!!
            assertEquals(serverNow, row.firstSeenAt)
        }

    @Test
    fun changedChaptersUrlDeletesFetchedJsonChapters() =
        runTest {
            val id = podcastId()
            ingest(
                id,
                parsedFeed(items = listOf(parsedEpisode(0, guid = "a", chaptersUrl = "https://x.example.com/c1.json"))),
                mode = IngestMode.INITIAL,
            )
            val episodeId = db.episodeDao().byIdentityKey(id, "g:a")!!.id
            db
                .chapterDao()
                .replace(
                    episodeId,
                    ChapterSource.PODCASTING20_JSON,
                    listOf(
                        ChapterEntity(
                            episodeId = episodeId,
                            source = ChapterSource.PODCASTING20_JSON,
                            ordinal = 0,
                            startMs = 0,
                        ),
                    ),
                )
            assertEquals(1, db.chapterDao().ofSource(episodeId, ChapterSource.PODCASTING20_JSON).size)

            ingest(
                id,
                parsedFeed(items = listOf(parsedEpisode(0, guid = "a", chaptersUrl = "https://x.example.com/c2.json"))),
            )

            assertEquals(emptyList(), db.chapterDao().ofSource(episodeId, ChapterSource.PODCASTING20_JSON))
        }

    // --- inFeed flips ---------------------------------------------------------------------------

    @Test
    fun removedItemsFlipOutAndReappearingRestores() =
        runTest {
            val id = podcastId()
            val three =
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(0, guid = "a", pubDate = NOW - DAY),
                            parsedEpisode(1, guid = "b", pubDate = NOW - 2 * DAY),
                            parsedEpisode(2, guid = "c", pubDate = NOW - 3 * DAY),
                        ),
                )
            ingest(id, three, mode = IngestMode.INITIAL)

            ingest(id, parsedFeed(items = listOf(parsedEpisode(0, guid = "a"), parsedEpisode(1, guid = "b"))))
            assertFalse(db.episodeDao().byIdentityKey(id, "g:c")!!.inFeed)

            ingest(id, three)
            assertTrue(db.episodeDao().byIdentityKey(id, "g:c")!!.inFeed)
        }

    @Test
    fun partialDocumentFlipsAbsentRowsOnlyInsideWindow() =
        runTest {
            val id = podcastId()
            ingest(
                id,
                parsedFeed(
                    items =
                        (0..12).map {
                            parsedEpisode(it, guid = "d$it", pubDate = NOW - it * DAY)
                        },
                ),
                mode = IngestMode.INITIAL,
            )

            // A partial page-1 document covering days 7..9: stored rows newer than the window
            // floor that are absent flip out; rows older than the window are outside its
            // coverage and stay (03 step 8's partial rule).
            ingest(
                id,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(0, guid = "d7", pubDate = NOW - 7 * DAY),
                            parsedEpisode(1, guid = "d9", pubDate = NOW - 9 * DAY),
                        ),
                ),
                partial = true,
            )

            assertFalse(db.episodeDao().byIdentityKey(id, "g:d5")!!.inFeed)
            assertFalse(db.episodeDao().byIdentityKey(id, "g:d8")!!.inFeed)
            assertTrue(db.episodeDao().byIdentityKey(id, "g:d11")!!.inFeed)
            assertTrue(db.episodeDao().byIdentityKey(id, "g:d7")!!.inFeed)
        }

    // --- Empty and unsupported feeds --------------------------------------------------------------

    @Test
    fun emptyChannelLeavesEveryStoredRowInFeed() =
        runTest {
            val id = podcastId()
            ingest(id, parsedFeed(items = listOf(parsedEpisode(0, guid = "a"))), mode = IngestMode.INITIAL)

            ingest(id, parsedFeed(title = "Still the show", items = emptyList()))

            assertTrue(db.episodeDao().byIdentityKey(id, "g:a")!!.inFeed)
        }

    @Test
    fun itemsWithoutMediaAreRejectedWithNoMedia() =
        runTest {
            val id = podcastId()
            val before = db.podcastDao().byId(id)!!
            val feed =
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(0, guid = "a", enclosureUrl = null, externalMediaId = null),
                        ),
                )

            val result = ingest(id, feed)

            assertEquals(FeedErrorKind.NO_MEDIA, result.emptyKind)
            assertEquals(0, result.accepted)
            assertEquals(before, db.podcastDao().byId(id))
        }

    @Test
    fun listMediumFeedReportsUnsupportedListFeed() =
        runTest {
            val id = podcastId()
            val result = ingest(id, parsedFeed(items = emptyList(), medium = "podcastL"))
            assertEquals(FeedErrorKind.UNSUPPORTED_LIST_FEED, result.emptyKind)
        }

    // --- Integrity -------------------------------------------------------------------------------

    @Test
    fun assignedKeyCollisionResolvesWithoutConflict() =
        runTest {
            val id = podcastId()
            // v1: row B (u:encX), row C (g:gc with the same enclosure URL), row D (t: key of T+day).
            val day = NOW - DAY
            ingest(
                id,
                parsedFeed(
                    items =
                        listOf(
                            parsedEpisode(
                                0,
                                enclosureUrl = "https://cdn.example.com/x.mp3",
                                title = "B",
                                pubDate = day,
                            ),
                            parsedEpisode(
                                1,
                                guid = "gc",
                                enclosureUrl = "https://cdn.example.com/x.mp3",
                                title = "C",
                                pubDate = day,
                            ),
                            parsedEpisode(2, enclosureUrl = null, externalMediaId = "m1", title = "T", pubDate = day),
                        ),
                ),
                mode = IngestMode.INITIAL,
            )
            val cBefore = db.episodeDao().byIdentityKey(id, "g:gc")!!
            val dBefore = db.ingestDao().existing(id).single { it.enclosureUrl == null }

            // v2: two items share enclosure x.mp3. The second's assigned key is its t: fallback —
            // exactly row D's key, so it claims D in pass 1 (S3: exact-match the assigned key;
            // every assigned key is reserved, so no pass-2 rekey can collide and nothing aborts
            // on UNIQUE(podcastId, identityKey)).
            val result =
                ingest(
                    id,
                    parsedFeed(
                        items =
                            listOf(
                                parsedEpisode(
                                    0,
                                    enclosureUrl = "https://cdn.example.com/x.mp3",
                                    title = "B2",
                                    pubDate = day,
                                ),
                                parsedEpisode(
                                    1,
                                    enclosureUrl = "https://cdn.example.com/x.mp3",
                                    title = "T",
                                    pubDate = day,
                                ),
                            ),
                    ),
                )

            assertEquals(0, result.inserted.size)
            assertEquals(2, result.updated)
            val d = db.episodeDao().byId(dBefore.id)!!
            assertEquals("https://cdn.example.com/x.mp3", d.enclosureUrl)
            assertEquals("T", d.title)
            // The unclaimed row C is absent from the complete document (03 step 8).
            val c = db.episodeDao().byId(cBefore.id)!!
            assertEquals("g:gc", c.identityKey)
            assertFalse(c.inFeed)
            assertEquals(3, db.podcastDao().episodeCount(id))
        }

    @Test
    fun ingestInTransactionCommitsInsideTheCallersTransaction() =
        runTest {
            val id = podcastId()
            // The subscribe path: ingestInTransaction inside a caller's write transaction.
            val result =
                db.withWriteTransaction {
                    db
                        .podcastDao()
                        .insertAlias(
                            ch.lkmc.neutrodyne.core.database.PodcastUrlAliasEntity(
                                url = "https://alias.example.com/feed",
                                podcastId = id,
                                reason = ch.lkmc.neutrodyne.core.model.AliasReason.SUBSCRIBE_INPUT,
                                addedAt = NOW,
                            ),
                        )
                    ingestor.ingestInTransaction(
                        dueFeedOf(db, id),
                        parsedFeed(items = listOf(parsedEpisode(0, guid = "a"))),
                        IngestContext(mode = IngestMode.INITIAL, partial = false, fetch = fetchMeta()),
                    )
                }
            assertEquals(1, result.inserted.size)
            assertEquals(1, db.podcastDao().aliases(id).size)
            assertNotNull(db.episodeDao().byIdentityKey(id, "g:a"))
        }

    private fun artwork(url: String) =
        ch.lkmc.neutrodyne.feeds.model.ArtworkCandidate(
            url = url,
            source = ch.lkmc.neutrodyne.feeds.model.ArtworkSource.ITUNES_IMAGE,
        )

    private companion object {
        const val NOW = TestClock.DEFAULT_NOW
        const val DAY = 86_400_000L
    }
}
